package com.kingzcheung.xime.plugin.core.js

import com.kingzcheung.xime.plugin.core.config.PluginConfigStore
import com.kingzcheung.xime.plugin.core.js.crypto.CryptoHostApi
import com.kingzcheung.xime.plugin.core.js.sdk.JsHostApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class JsTypingStatsTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private class InMemoryConfigStore : PluginConfigStore {
        private val map = java.util.concurrent.ConcurrentHashMap<String, String>()
        override fun get(key: String): String? = map[key]
        override fun set(key: String, value: String) { map[key] = value }
        override fun remove(key: String) { map.remove(key) }
        override fun keys(): Set<String> = map.keys.toSet()
    }

    private class MockCrypto : CryptoHostApi {
        override fun sha256(data: ByteArray): ByteArray = ByteArray(32)
        override fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray = ByteArray(32)
        override fun hmacSha1(key: ByteArray, data: ByteArray): ByteArray = ByteArray(20)
        override fun hex(data: ByteArray): String = ""
        override fun base64(data: ByteArray): String = ""
        override fun utcTime(format: String): String = "20260828"
        override fun epochSeconds(): Long = 1767225600
    }

    private class DebugHostApi(private val store: PluginConfigStore) : JsHostApi {
        override val sdkVersion = "0.1.0"
        override fun log(message: String) { System.out.println("JS_LOG: $message") }
        override fun logError(message: String) { System.err.println("JS_ERROR: $message") }
        override fun configGet(key: String) = store.get(key)
        override fun configSet(key: String, value: String) { store.set(key, value) }
        override fun configRemove(key: String) { store.remove(key) }
        override fun configKeys(): Set<String> = store.keys()
        override fun resourcePath(name: String) = null
        override fun resourceList(dir: String) = emptyList<String>()
        override fun uuid() = "uuid"
    }

    /** 载入真实插件产物（xipm build 输出），测试与发布同源。 */
    private fun writePlugin(): File {
        val dir = tmp.newFolder()
        pluginSourceFile().copyTo(File(dir, "main.js"))
        return dir
    }

    private fun pluginSourceFile(): File {
        var dir: File? = File(System.getProperty("user.dir") ?: ".")
        while (dir != null) {
            val candidate = File(dir, "build/plugin-js/typing-stats/main.js")
            if (candidate.isFile) return candidate
            dir = dir.parentFile
        }
        throw AssertionError(
            "找不到 build/plugin-js/typing-stats/main.js，" +
                "请先运行：cd tools/xime-plugin && cargo run -- build ../../plugins/typing-stats --out ../../build/plugin-js"
        )
    }

    private fun newRuntime(store: PluginConfigStore): JsScriptRuntime {
        val runtime = JsScriptRuntime(
            "js-typing-stats",
            writePlugin(),
            "main.js",
            store,
            hostApi = DebugHostApi(store),
            cryptoHostApi = MockCrypto()
        )
        runtime.initEvents(setOf(PluginEvent.TYPE_INPUT_CHANGED, PluginEvent.TYPE_TEXT_COMMITTED))
        assertTrue("main.js 应能加载", runtime.load())
        return runtime
    }

    private fun dispatchCommitted(runtime: JsScriptRuntime, text: String, totalChars: Long, totalCommits: Long) {
        assertTrue(
            runtime.dispatchEvent(
                PluginEvent(
                    PluginEvent.TYPE_TEXT_COMMITTED,
                    mapOf(
                        PluginEvent.FIELD_COMMITTED_TEXT to text,
                        PluginEvent.FIELD_SESSION_TOTAL_CHARS to totalChars,
                        PluginEvent.FIELD_SESSION_TOTAL_COMMITS to totalCommits,
                    )
                )
            )
        )
    }

    private fun panelUi(runtime: JsScriptRuntime): List<*>? {
        val state = runtime.call("panel.state", mapOf("inputText" to "")) as? Map<*, *>
        return state?.get("ui") as? List<*>
    }

    private fun awaitPanelUi(runtime: JsScriptRuntime, contains: String): Map<*, *> {
        val deadline = System.currentTimeMillis() + 5000
        while (System.currentTimeMillis() < deadline) {
            val ui = panelUi(runtime)
            if (ui != null) {
                for (node in ui) {
                    val m = node as? Map<*, *> ?: continue
                    if (m.values.any { it?.toString()?.contains(contains) == true }) return m
                }
            }
            Thread.sleep(50)
        }
        throw AssertionError("面板 ui 未出现含 '$contains' 的节点")
    }

    /** 等待出现 value 匹配的 metric 节点（今日/累计共享同一计数时取其一即可）。 */
    private fun awaitMetricValue(runtime: JsScriptRuntime, value: String) {
        val deadline = System.currentTimeMillis() + 5000
        while (System.currentTimeMillis() < deadline) {
            val ui = panelUi(runtime)
            if (ui != null) {
                for (node in ui) {
                    val m = node as? Map<*, *> ?: continue
                    if (m["type"]?.toString() == "metric" && m["value"]?.toString() == value) return
                }
            }
            Thread.sleep(50)
        }
        throw AssertionError("面板 ui 未出现 metric value=$value")
    }

    /**
     * 等待 text_committed 快照被插件消费（persist 无条件写 last_seen_chars，可作消费信号）。
     * conflated 通道连发会覆盖未消费事件：背靠背 dispatch 时首条可能在消费前被合并丢弃，
     * last_seen 基线缺失会让后续差值断言永不成立（CI 负载下偶发超时）。
     */
    private fun awaitConsumed(store: PluginConfigStore, lastSeenChars: String) {
        val deadline = System.currentTimeMillis() + 5000
        while (System.currentTimeMillis() < deadline) {
            if (store.get("last_seen_chars") == lastSeenChars) return
            Thread.sleep(50)
        }
        throw AssertionError("快照未被消费: last_seen_chars != $lastSeenChars")
    }

    @Test
    fun `首次事件不回溯历史，之后按差值累计`() {
        val store = InMemoryConfigStore()
        val runtime = newRuntime(store)
        try {
            dispatchCommitted(runtime, "你好", totalChars = 100, totalCommits = 10)
            awaitConsumed(store, "100")
            dispatchCommitted(runtime, "世界你好", totalChars = 125, totalCommits = 11)

            awaitMetricValue(runtime, "25")
            assertEquals("25", store.get("total_chars"))
            assertEquals(
                "25",
                Regex("\"20260828\":(\\d+)").find(store.get("daily")!!)?.groupValues?.get(1)
            )
        } finally {
            runtime.close()
        }
    }

    @Test
    fun `reset action 清零统计`() {
        val store = InMemoryConfigStore()
        val runtime = newRuntime(store)
        try {
            dispatchCommitted(runtime, "你好", totalChars = 30, totalCommits = 1)
            awaitConsumed(store, "30")
            dispatchCommitted(runtime, "你好世界", totalChars = 34, totalCommits = 2)
            awaitMetricValue(runtime, "4")

            runtime.call("panel.onAction", mapOf("actionId" to "reset"))
            awaitMetricValue(runtime, "0")
            assertEquals("0", store.get("total_chars"))
        } finally {
            runtime.close()
        }
    }

    @Test
    fun `宿主重启后 session 归零，负差值翻转为增量`() {
        val store = InMemoryConfigStore()
        val runtime = newRuntime(store)
        try {
            dispatchCommitted(runtime, "a", totalChars = 100, totalCommits = 1)
            awaitConsumed(store, "100")
            dispatchCommitted(runtime, "b", totalChars = 110, totalCommits = 2)
            awaitMetricValue(runtime, "10")

            dispatchCommitted(runtime, "c", totalChars = 5, totalCommits = 1)
            awaitMetricValue(runtime, "15")
            assertEquals("15", store.get("total_chars"))
        } finally {
            runtime.close()
        }
    }

    @Test
    fun `input_changed 只进面板不落盘`() {
        val store = InMemoryConfigStore()
        val runtime = newRuntime(store)
        try {
            assertTrue(
                runtime.dispatchEvent(
                    PluginEvent(PluginEvent.TYPE_INPUT_CHANGED, mapOf(PluginEvent.FIELD_INPUT_TEXT to "niha"))
                )
            )
            awaitPanelUi(runtime, "正在输入: niha")
            assertTrue(store.get("total_chars") == null || store.get("total_chars") == "0")
        } finally {
            runtime.close()
        }
    }

    @Test
    fun `称号随累计字数进阶并显示升级提示`() {
        val store = InMemoryConfigStore()
        val runtime = newRuntime(store)
        try {
            dispatchCommitted(runtime, "你好", totalChars = 100, totalCommits = 1)
            awaitConsumed(store, "100")
            awaitPanelUi(runtime, "🌱 新手")
            awaitPanelUi(runtime, "✨ 距「入门学徒」还差 1000 字")

            dispatchCommitted(runtime, "再打", totalChars = 1100, totalCommits = 2)
            awaitPanelUi(runtime, "🥉 入门学徒")
        } finally {
            runtime.close()
        }
    }

    @Test
    fun `重载后 last_seen 持久化，快照回退不重复累计`() {
        val store = InMemoryConfigStore()
        var runtime = newRuntime(store)
        try {
            dispatchCommitted(runtime, "你好世界", totalChars = 50, totalCommits = 1)
            awaitConsumed(store, "50")
            dispatchCommitted(runtime, "，再见", totalChars = 65, totalCommits = 2)
            awaitMetricValue(runtime, "15")
        } finally {
            runtime.close()
        }

        runtime = newRuntime(store)
        try {
            dispatchCommitted(runtime, "x", totalChars = 70, totalCommits = 3)
            awaitMetricValue(runtime, "20")
            assertEquals("20", store.get("total_chars"))
            assertEquals("70", store.get("last_seen_chars"))
        } finally {
            runtime.close()
        }
    }
}