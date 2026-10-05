package com.kingzcheung.xime.plugin.core.js

import com.kingzcheung.xime.plugin.core.config.PluginConfigStore
import com.kingzcheung.xime.plugin.core.js.crypto.CryptoHostApi
import com.kingzcheung.xime.plugin.core.js.sdk.JsHostApi
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class JsTypingStatsPluginTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private class InMemoryConfigStore : PluginConfigStore {
        private val map = java.util.concurrent.ConcurrentHashMap<String, String>()
        override fun get(key: String): String? = map[key]
        override fun set(key: String, value: String) { map[key] = value }
        override fun remove(key: String) { map.remove(key) }
        override fun keys(): Set<String> = map.keys.toSet()
    }

    /** 固定时间源：今日 = 20260910（JS 无 io，插件经 host.crypto.utcTime 取日期）。 */
    private class FixedClockCrypto : CryptoHostApi {
        override fun utcTime(format: String): String =
            if (format == "YYYYMMDD") "20260910" else "20260910T120000Z"
        override fun epochSeconds(): Long = 0L
        override fun sha256(data: ByteArray): ByteArray = ByteArray(0)
        override fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray = ByteArray(0)
        override fun hmacSha1(key: ByteArray, data: ByteArray): ByteArray = ByteArray(0)
        override fun hex(data: ByteArray): String = ""
        override fun base64(data: ByteArray): String = ""
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
        throw AssertionError("找不到 build/plugin-js/typing-stats/main.js，请先运行 xipm build")
    }

    private fun newRuntime(store: InMemoryConfigStore): JsScriptRuntime {
        val runtime = JsScriptRuntime(
            "js-typing-stats-paste",
            writePlugin(),
            "main.js",
            store,
            hostApi = DebugHostApi(store),
            cryptoHostApi = FixedClockCrypto()
        )
        // 事件通道必须在 load 之前声明（与宿主 PluginLifecycleManager 同序）
        runtime.initEvents(setOf("input_changed", "text_committed"))
        assertTrue("main.js 应能加载", runtime.load())
        return runtime
    }

    private fun committedEvent(sessionChars: Long, isPaste: Boolean) = PluginEvent(
        PluginEvent.TYPE_TEXT_COMMITTED,
        mapOf(
            PluginEvent.FIELD_COMMITTED_TEXT to "文本",
            PluginEvent.FIELD_SESSION_TOTAL_CHARS to sessionChars,
            PluginEvent.FIELD_SESSION_TOTAL_COMMITS to 1L,
            PluginEvent.FIELD_IS_PASTE to isPaste,
        )
    )

    /** 事件经 conflated 通道异步消费：轮询 config 直到 last_seen 前移到期望值。 */
    private fun awaitLastSeen(store: InMemoryConfigStore, expected: Long, timeoutMs: Long = 3000) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (store.get("last_seen_chars") == expected.toString()) return
            Thread.sleep(20)
        }
        assertTrue("等待 last_seen_chars=$expected 超时，实际=${store.get("last_seen_chars")}", false)
    }

    @Test
    fun `打字累计而粘贴不计入且不破坏后续差值`() {
        val store = InMemoryConfigStore()
        val runtime = newRuntime(store)
        try {
            // 正常打字：session 推进 5 字（首次差值按 0，防插件重载重复累计——既有设计语义）
            assertTrue(runtime.dispatchEvent(committedEvent(5L, isPaste = false)))
            awaitLastSeen(store, 5L)
            assertTrue("首次差值应为 0: ${store.get("total_chars")}", store.get("total_chars") == "0")
            assertTrue("提交次数应为 1", store.get("total_commits") == "1")

            // 第二笔正常打字：差值 4 正常累计
            assertTrue(runtime.dispatchEvent(committedEvent(9L, isPaste = false)))
            awaitLastSeen(store, 9L)
            assertTrue("累计应为 4: ${store.get("total_chars")}", store.get("total_chars") == "4")
            assertTrue("提交次数应为 2", store.get("total_commits") == "2")
            assertTrue(
                "daily 应含今日 4 字: ${store.get("daily")}",
                store.get("daily")?.contains("20260910") == true && store.get("daily")!!.contains("4")
            )

            // 粘贴 12 字：不计字数/次数，但 last_seen 推进到 21（差值基准不被粘贴破坏）
            assertTrue(runtime.dispatchEvent(committedEvent(21L, isPaste = true)))
            awaitLastSeen(store, 21L)
            assertTrue("粘贴不应计入累计字数: ${store.get("total_chars")}", store.get("total_chars") == "4")
            assertTrue("粘贴不应计入提交次数: ${store.get("total_commits")}", store.get("total_commits") == "2")

            // 粘贴后继续打字 3 字：只累计 3，不把粘贴的 12 重复算入
            assertTrue(runtime.dispatchEvent(committedEvent(24L, isPaste = false)))
            awaitLastSeen(store, 24L)
            assertTrue("累计应为 7: ${store.get("total_chars")}", store.get("total_chars") == "7")
            assertTrue("提交次数应为 3", store.get("total_commits") == "3")
        } finally {
            runtime.close()
        }
    }

}
