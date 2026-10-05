package com.kingzcheung.xime.plugin.core.js

import com.kingzcheung.xime.plugin.core.config.NoopPluginConfigStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * 验证常用语插件（quick-phrase）：宿主快捷发送数据（host.quickSend）匹配进候选栏。
 *
 * 覆盖：
 * - onLoad 拉取 host.quickSend.list() 缓存 → transformCandidates 内容命中跟随引擎候选并注入 comment
 * - 编码命中插到第一候选之后
 * - quick_send_changed 事件后缓存刷新（旧条目失效、新条目生效）
 * - 无缓存 / 空输入返回 NoResponse（不干预）
 *
 * 载入真实插件产物（xipm build 输出），测试与发布同源。
 */
class JsQuickPhraseTest {

    @get:Rule
    val tmp = TemporaryFolder()

    /** 可变数据的 QuickSend 宿主 mock（模拟 Room 数据变更）。 */
    private class FakeQuickSendApi : QuickSendHostApi {
        var items: List<QuickSendItem> = emptyList()
        override fun list(): List<QuickSendItem> = items
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
            val candidate = File(dir, "build/plugin-js/quick-phrase/main.js")
            if (candidate.isFile) return candidate
            dir = dir.parentFile
        }
        throw AssertionError(
            "找不到 build/plugin-js/quick-phrase/main.js，" +
                "请先运行：cd tools/xime-plugin && cargo run -- build ../../plugins/quick-phrase --out ../../build/plugin-js"
        )
    }

    private fun newRuntime(api: QuickSendHostApi): JsScriptRuntime {
        val runtime = JsScriptRuntime(
            "js-quick-phrase",
            writePlugin(),
            "main.js",
            NoopPluginConfigStore,
            quickSendHostApi = api,
        )
        runtime.initEvents(setOf(PluginEvent.TYPE_QUICK_SEND_CHANGED))
        assertTrue("main.js 应能加载", runtime.load())
        runtime.callOnLoad()
        return runtime
    }

    private fun transform(
        runtime: JsScriptRuntime,
        inputText: String,
        candidates: List<String>,
    ): CandidateTransformOutcome = runtime.transformCandidates(
        CandidateTransformRequest(
            inputText = inputText,
            preedit = inputText,
            candidates = candidates.map { CandidateTransformCandidate(it, "") },
            asciiMode = false,
        )
    )

    private fun awaitUntil(timeoutMs: Long = 5000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(50)
        }
        assertTrue("等待条件超时", condition())
    }

    @Test
    fun `onLoad 后内容命中跟随引擎候选并注入 comment`() {
        val api = FakeQuickSendApi().apply {
            items = listOf(
                QuickSendItem(id = 1, text = "电话号码：13512345678", code = "dh", timestamp = 1000, isPinned = false),
                QuickSendItem(id = 2, text = "电话备注：家里", code = "", timestamp = 900, isPinned = false),
            )
        }
        val runtime = newRuntime(api)
        try {
            val outcome = transform(runtime, "dianhua", listOf("电话", "的"))
            val items = (outcome as CandidateTransformOutcome.Success).items
            assertEquals("引擎候选 2 条 + 内容命中 2 条", 4, items.size)

            assertEquals(0, items[0].engineIndex)
            // 条目 1：text 以候选"电话"开头 → 紧跟其后，comment 为触发码
            assertEquals("电话号码：13512345678", items[1].text)
            assertEquals("dh", items[1].comment)
            // 条目 2：无 code → comment 为"快捷"
            assertEquals("电话备注：家里", items[2].text)
            assertEquals("快捷", items[2].comment)
            assertEquals(1, items[3].engineIndex)
        } finally {
            runtime.close()
        }
    }

    @Test
    fun `编码命中插到第一候选之后`() {
        val api = FakeQuickSendApi().apply {
            items = listOf(
                QuickSendItem(id = 1, text = "电话号码：13512345678", code = "dh", timestamp = 1000, isPinned = false),
            )
        }
        val runtime = newRuntime(api)
        try {
            // 输入编码以 code 开头（"dh"）：插到第一候选之后（第二候选位）
            val outcome = transform(runtime, "dh", listOf("的", "到"))
            val items = (outcome as CandidateTransformOutcome.Success).items
            assertEquals(3, items.size)
            assertEquals(0, items[0].engineIndex)
            assertEquals("电话号码：13512345678", items[1].text)
            assertEquals("dh", items[1].comment)
            assertEquals(1, items[2].engineIndex)
        } finally {
            runtime.close()
        }
    }

    @Test
    fun `quick_send_changed 事件后缓存刷新`() {
        val api = FakeQuickSendApi().apply {
            items = listOf(
                QuickSendItem(id = 1, text = "旧条目AAA", code = "aa", timestamp = 1000, isPinned = false),
            )
        }
        val runtime = newRuntime(api)
        try {
            val before = transform(runtime, "aa", listOf("的")) as CandidateTransformOutcome.Success
            assertEquals("旧条目AAA", before.items[1].text)

            // 宿主数据变更 → 投递事件 → 插件刷新缓存
            api.items = listOf(
                QuickSendItem(id = 2, text = "新条目BBB", code = "bb", timestamp = 2000, isPinned = true),
            )
            assertTrue(
                "应投递 quick_send_changed 事件",
                runtime.dispatchEvent(
                    PluginEvent(PluginEvent.TYPE_QUICK_SEND_CHANGED, mapOf(PluginEvent.FIELD_COUNT to 1))
                )
            )
            awaitUntil {
                val outcome = transform(runtime, "bb", listOf("的"))
                outcome is CandidateTransformOutcome.Success && outcome.items.any { it.text == "新条目BBB" }
            }

            // 旧缓存已被替换：旧条目不再命中
            val afterOld = transform(runtime, "aa", listOf("的")) as CandidateTransformOutcome.Success
            assertTrue("旧条目不应再命中", afterOld.items.none { it.text == "旧条目AAA" })
        } finally {
            runtime.close()
        }
    }

    @Test
    fun `无缓存或空输入时不干预`() {
        val empty = newRuntime(FakeQuickSendApi())
        try {
            assertEquals(CandidateTransformOutcome.NoResponse, transform(empty, "dh", listOf("的")))
        } finally {
            empty.close()
        }

        val api = FakeQuickSendApi().apply {
            items = listOf(
                QuickSendItem(id = 1, text = "电话号码：13512345678", code = "dh", timestamp = 1000, isPinned = false),
            )
        }
        val runtime = newRuntime(api)
        try {
            assertEquals(CandidateTransformOutcome.NoResponse, transform(runtime, "", listOf("电话")))
        } finally {
            runtime.close()
        }
    }
}
