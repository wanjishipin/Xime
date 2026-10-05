package com.kingzcheung.xime.plugin.core.js

import android.app.Application
import com.kingzcheung.xime.plugin.core.config.PluginConfigStore
import com.kingzcheung.xime.plugin.core.config.UiNodeType
import com.kingzcheung.xime.plugin.core.js.http.HttpHostApi
import com.kingzcheung.xime.plugin.core.js.http.HttpResponse
import com.kingzcheung.xime.plugin.core.js.http.SseHostApi
import com.kingzcheung.xime.plugin.core.js.http.SseHostListener
import com.kingzcheung.xime.plugin.core.model.PluginContext
import com.kingzcheung.xime.plugin.core.model.PluginInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * 验证 AI 插件端到端数据链路（JS 产物同源）：
 * - ai-reply：同步 host.http.request 生成候选列表 → getPanelState 解析
 * - ai-write：host.http.stream 流式累积（事件投递到 plugin.onSseData/onSseDone 回调槽）
 *   → getPanelState 实时文本 → closeStream 中断
 * - qwen-translate：SSE 流式翻译（原名 ai-translate）（POST + JSON body 透传）
 *
 * 脚本统一从 `xipm build` 产物 `build/plugin-js/<name>/main.js` 载入，
 * 与发布包同源（先构建再跑测试）。
 */
class JsAiPluginTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private class InMemoryConfigStore : PluginConfigStore {
        private val map = HashMap<String, String>()
        override fun get(key: String): String? = map[key]
        override fun set(key: String, value: String) { map[key] = value }
        override fun remove(key: String) { map.remove(key) }
        override fun keys(): Set<String> = map.keys.toSet()
    }

    private class MockHttpHostApi(var response: HttpResponse? = null) : HttpHostApi {
        var lastUrl: String? = null
        var lastTimeout: Int? = null
        override fun request(
            method: String,
            url: String,
            headers: Map<String, String>,
            body: ByteArray?,
            timeoutMillis: Int?
        ): HttpResponse? {
            lastUrl = url
            lastTimeout = timeoutMillis
            return response
        }
        override fun lastError(): String? = null
    }

    private class MockSseHostApi : SseHostApi {
        var listener: SseHostListener? = null
        var closedIds = mutableListOf<Int>()
        var returnId = 100
        var connectedMethod: String? = null
        var connectedBody: ByteArray? = null
        var connectedCount = 0
        override fun connect(
            url: String,
            headers: Map<String, String>,
            listener: SseHostListener,
            timeoutMillis: Int?,
            method: String,
            body: ByteArray?
        ): Int {
            connectedCount++
            connectedMethod = method
            connectedBody = body
            this.listener = listener
            return returnId
        }
        override fun close(sessionId: Int) { closedIds.add(sessionId) }
        override fun lastError(): String? = null
    }

    private fun awaitUntil(timeoutMs: Long = 5000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(50)
        }
        assertTrue("等待条件超时", condition())
    }

    /** 载入真实插件产物（xipm build 输出），测试与发布同源。 */
    private fun writePlugin(name: String): File {
        val dir = tempFolder.newFolder()
        pluginSourceFile(name).copyTo(File(dir, "main.js"))
        return dir
    }

    private fun pluginSourceFile(name: String): File {
        var dir: File? = File(System.getProperty("user.dir") ?: ".")
        while (dir != null) {
            val candidate = File(dir, "build/plugin-js/$name/main.js")
            if (candidate.isFile) return candidate
            dir = dir.parentFile
        }
        throw AssertionError(
            "找不到 build/plugin-js/$name/main.js，请先运行：" +
                "cd tools/xime-plugin && cargo run -- build ../../plugins/$name --out ../../build/plugin-js"
        )
    }

    private fun newAdapter(
        dir: File,
        pluginId: String,
        runtime: JsScriptRuntime,
        store: PluginConfigStore
    ): JsToolPluginAdapter {
        val info = PluginInfo(
            id = pluginId, name = "AI 插件", description = "测试",
            iconResId = 0, versionCode = 1, versionName = "0.1.0",
            path = File(dir, "main.js").absolutePath, type = "tool"
        )
        return JsToolPluginAdapter(runtime, PluginContext(application = Application(), pluginInfo = info, configStore = store))
    }

    private fun aiStore(): InMemoryConfigStore = InMemoryConfigStore().apply {
        set("apiKey", "test-key")
        set("baseUrl", "https://api.example.com/v1")
    }

    @Test
    fun `ai-reply 同步生成候选并解析到面板状态`() {
        val store = aiStore()
        store.set("model", "gpt-test")
        val mockHttp = MockHttpHostApi(
            HttpResponse(
                status = 200,
                body = """
                    {"choices":[{"message":{"content":"好的！\n好的呀！\n没问题！"}}]}
                """.trimIndent().toByteArray()
            )
        )
        val dir = writePlugin("ai-reply")
        val runtime = JsScriptRuntime(
            "com.kingzcheung.xime.plugin.ai_reply",
            dir,
            "main.js",
            store,
            httpHostApi = mockHttp
        )
        try {
            assertTrue("main.js 应能加载", runtime.load())
            val adapter = newAdapter(dir, "com.kingzcheung.xime.plugin.ai_reply", runtime, store)

            adapter.onPanelInput("", "明天有空吗")
            adapter.onPanelAction("generate")

            val state = adapter.getPanelState("明天有空吗")
            assertTrue("应生成 3 条候选", state.items.size == 3)
            assertEquals("好的！", state.items[0].text)
            assertEquals("好的呀！", state.items[1].text)
            assertEquals("没问题！", state.items[2].text)
            assertFalse("同步生成后不在加载中", state.loading)
            assertTrue("请求应带长超时", (mockHttp.lastTimeout ?: 0) >= 60000)
            assertTrue("请求 URL 指向 chat/completions", mockHttp.lastUrl?.contains("/chat/completions") == true)
        } finally {
            runtime.close()
        }
    }

    @Test
    fun `ai-reply 未配置 API Key 时生成失败`() {
        val store = InMemoryConfigStore()
        val dir = writePlugin("ai-reply")
        val runtime = JsScriptRuntime(
            "com.kingzcheung.xime.plugin.ai_reply", dir, "main.js", store,
            httpHostApi = MockHttpHostApi()
        )
        try {
            assertTrue(runtime.load())
            val adapter = newAdapter(dir, "com.kingzcheung.xime.plugin.ai_reply", runtime, store)

            adapter.onPanelInput("", "你好")
            adapter.onPanelAction("generate")
            assertTrue("未配置 Key 不应产生候选", adapter.getPanelState("你好").items.isEmpty())
        } finally {
            runtime.close()
        }
    }

    @Test
    fun `ai-reply 点选上屏后重置面板状态`() {
        val store = aiStore()
        val dir = writePlugin("ai-reply")
        val runtime = JsScriptRuntime(
            "com.kingzcheung.xime.plugin.ai_reply", dir, "main.js", store,
            httpHostApi = MockHttpHostApi(
                HttpResponse(
                    status = 200,
                    body = """{"choices":[{"message":{"content":"好的！\n好的呀！"}}]}""".toByteArray()
                )
            )
        )
        try {
            assertTrue(runtime.load())
            val adapter = newAdapter(dir, "com.kingzcheung.xime.plugin.ai_reply", runtime, store)

            adapter.onPanelInput("", "明天有空吗")
            adapter.onPanelAction("generate")
            assertTrue("生成后应有候选", adapter.getPanelState("明天有空吗").items.isNotEmpty())

            // 点选上屏：状态重置，下次打开面板回到初始态
            adapter.onPanelItemClick("1")
            val state = adapter.getPanelState("")
            assertTrue("上屏后候选应清空", state.items.isEmpty())
            assertTrue("重置后应回到初始态（含生成按钮）", state.ui?.any { it.type == UiNodeType.BUTTON } == true)
        } finally {
            runtime.close()
        }
    }

    @Test
    fun `ai-reply 上下文变化时旧候选自动失效`() {
        val store = aiStore()
        val dir = writePlugin("ai-reply")
        val runtime = JsScriptRuntime(
            "com.kingzcheung.xime.plugin.ai_reply", dir, "main.js", store,
            httpHostApi = MockHttpHostApi(
                HttpResponse(
                    status = 200,
                    body = """{"choices":[{"message":{"content":"好的！"}}]}""".toByteArray()
                )
            )
        )
        try {
            assertTrue(runtime.load())
            val adapter = newAdapter(dir, "com.kingzcheung.xime.plugin.ai_reply", runtime, store)

            adapter.onPanelInput("", "明天有空吗")
            adapter.onPanelAction("generate")
            assertTrue(adapter.getPanelState("明天有空吗").items.isNotEmpty())

            // 用户复制了新消息后重新打开面板（新上下文）→ 旧候选失效，回到初始态
            val state = adapter.getPanelState("周六怎么样")
            assertTrue("新上下文应清空旧候选", state.items.isEmpty())
            assertTrue("新上下文应回到初始态（含生成按钮）", state.ui?.any { it.type == UiNodeType.BUTTON } == true)
        } finally {
            runtime.close()
        }
    }

    @Test
    fun `ai-write 流式累积文本并支持中断`() {
        val store = aiStore()
        val sse = MockSseHostApi()
        val dir = writePlugin("ai-write")
        val runtime = JsScriptRuntime(
            "com.kingzcheung.xime.plugin.ai_write",
            dir,
            "main.js",
            store,
            httpHostApi = MockHttpHostApi(),
            sseHostApi = sse
        )
        try {
            assertTrue("main.js 应能加载", runtime.load())
            val adapter = newAdapter(dir, "com.kingzcheung.xime.plugin.ai_write", runtime, store)

            adapter.onPanelInput("", "帮我写一条好评")
            adapter.onPanelAction("generate")

            var state = adapter.getPanelState("帮我写一条好评")
            assertTrue("生成中 loading=true", state.loading)

            sse.listener?.onData("""{"choices":[{"delta":{"content":"这家"}}]}""")
            sse.listener?.onData("""{"choices":[{"delta":{"content":"店很好"}}]}""")
            sse.listener?.onData("""{"choices":[{"delta":{"content":"，值得推荐"}}]}""")

            awaitUntil { adapter.getPanelState("帮我写一条好评").items.singleOrNull()?.text == "这家店很好，值得推荐" }
            state = adapter.getPanelState("帮我写一条好评")
            assertEquals("流式增量累积", "这家店很好，值得推荐", state.items.single().text)
            assertTrue("仍在加载中", state.loading)

            // 生成中途可关停：closeStream 中断会话（此后宿主不再回调）
            adapter.onPanelAction("stop")
            awaitUntil { sse.closedIds == listOf(100) }
            assertEquals("closeStream 中断会话", listOf(100), sse.closedIds)
            state = adapter.getPanelState("帮我写一条好评")
            assertFalse("中断后不再加载", state.loading)

            // 正常流结束路径：重新生成 → 累积 → onDone 收尾
            adapter.onPanelAction("generate")
            sse.listener?.onData("""{"choices":[{"delta":{"content":"新"}}]}""")
            sse.listener?.onData("""{"choices":[{"delta":{"content":"结果"}}]}""")
            sse.listener?.onDone("新结果")
            awaitUntil { adapter.getPanelState("帮我写一条好评").items.singleOrNull()?.text == "新结果" }
            state = adapter.getPanelState("帮我写一条好评")
            assertFalse("onDone 后不再加载", state.loading)
            assertEquals("onDone 收尾文本", "新结果", state.items.single().text)
        } finally {
            runtime.close()
        }
    }

    @Test
    fun `qwen-translate 流式翻译生成译文`() {
        val store = aiStore()
        store.set("targetLang", "English")
        val sse = MockSseHostApi()
        val dir = writePlugin("qwen-translate")
        val runtime = JsScriptRuntime(
            "com.kingzcheung.xime.plugin.qwen_translate",
            dir,
            "main.js",
            store,
            sseHostApi = sse
        )
        try {
            assertTrue("main.js 应能加载", runtime.load())
            val adapter = newAdapter(dir, "com.kingzcheung.xime.plugin.qwen_translate", runtime, store)

            adapter.onPanelInput("", "你好世界")
            adapter.onPanelAction("generate")

            assertEquals("应发起 SSE 流式连接", 1, sse.connectedCount)
            assertEquals("SSE 应使用 POST", "POST", sse.connectedMethod)
            assertNotNull("SSE 应携带 JSON body", sse.connectedBody)

            var state = adapter.getPanelState("你好世界")
            assertTrue("生成中应 loading", state.loading)

            // 流式增量累积
            sse.listener?.onData("""{"choices":[{"delta":{"content":"Hello"}}]}""")
            state = adapter.getPanelState("你好世界")
            assertTrue("流式期间应 loading", state.loading)
            assertEquals("增量累积", "Hello", state.items.single().text)

            sse.listener?.onData("""{"choices":[{"delta":{"content":" world"}}]}""")
            sse.listener?.onDone("Hello world")
            awaitUntil { adapter.getPanelState("你好世界").items.singleOrNull()?.text == "Hello world" }

            state = adapter.getPanelState("你好世界")
            assertFalse("onDone 后不再加载", state.loading)
            assertEquals("译文解析", "Hello world", state.items.single().text)
        } finally {
            runtime.close()
        }
    }

    @Test
    fun `qwen-translate 控件行与互换语言`() {
        val store = aiStore()
        val dir = writePlugin("qwen-translate")
        val runtime = JsScriptRuntime(
            "com.kingzcheung.xime.plugin.qwen_translate", dir, "main.js", store
        )
        try {
            assertTrue("main.js 应能加载", runtime.load())
            val adapter = newAdapter(dir, "com.kingzcheung.xime.plugin.qwen_translate", runtime, store)

            val state = adapter.getPanelState("ctx")
            // 翻译插件接受宿主上下文预填（选中文本优先；空串 = 空框手输）
            assertEquals("ctx", state.inputText)
            // 控件行：源语言 select + 互换 button + 目标语言 select
            val ui = state.ui
            assertNotNull("direct 面板应声明控件行", ui)
            assertEquals(3, ui!!.size)
            assertEquals(UiNodeType.SELECT, ui[0].type)
            assertEquals("sourceLang", ui[0].key)
            assertEquals(UiNodeType.BUTTON, ui[1].type)
            assertEquals("swapLang", ui[1].key)
            assertEquals(UiNodeType.SELECT, ui[2].type)
            assertEquals("targetLang", ui[2].key)

            // 语言选择经 onInput 回流并持久化到配置（值为 qwen-mt 官方语言枚举）
            adapter.onPanelInput("sourceLang", "English")
            adapter.onPanelInput("targetLang", "Japanese")
            assertEquals("English", store.get("sourceLang"))
            assertEquals("Japanese", store.get("targetLang"))

            // 互换按钮后宿主重拉 state：源/目标对调
            adapter.onPanelAction("swapLang")
            val swapped = adapter.getPanelState("")
            assertEquals("Japanese", swapped.ui!![0].value)
            assertEquals("English", swapped.ui!![2].value)
        } finally {
            runtime.close()
        }
    }

    @Test
    fun `AI 插件均导出配置 schema（插件中心设置入口依据）`() {
        // ai-reply / ai-write 走通用 chat 模型 + prompt 模板
        for (name in listOf("ai-reply", "ai-write")) {
            val store = InMemoryConfigStore()
            val dir = writePlugin(name)
            val runtime = JsScriptRuntime(
                "com.kingzcheung.xime.plugin.$name", dir, "main.js", store
            )
            try {
                assertTrue("$name main.js 应能加载", runtime.load())
                val adapter = newAdapter(dir, "com.kingzcheung.xime.plugin.$name", runtime, store)
                val fields = adapter.getSettingsSchema()
                assertTrue("$name 应至少 4 个配置字段（apiKey/baseUrl/model/prompt）", fields.size >= 4)
                val keyToField = fields.associateBy { it.key }
                assertTrue("$name 应包含 apiKey", keyToField.containsKey("apiKey"))
                assertTrue("$name 应包含 baseUrl", keyToField.containsKey("baseUrl"))
                val prompt = keyToField["prompt"]
                assertNotNull("$name 应包含 prompt", prompt)
                assertEquals(
                    "$name prompt 应为 textarea 长文本编辑",
                    com.kingzcheung.xime.plugin.core.config.UiNodeType.TEXTAREA,
                    prompt!!.type
                )
            } finally {
                runtime.close()
            }
        }

        // qwen-translate 为 qwen-mt 专用（原文直传 + translation_options，无 prompt 模板字段）
        val store = InMemoryConfigStore()
        val dir = writePlugin("qwen-translate")
        val runtime = JsScriptRuntime(
            "com.kingzcheung.xime.plugin.qwen_translate", dir, "main.js", store
        )
        try {
            assertTrue("qwen-translate main.js 应能加载", runtime.load())
            val adapter = newAdapter(dir, "com.kingzcheung.xime.plugin.qwen_translate", runtime, store)
            val fields = adapter.getSettingsSchema()
            val keyToField = fields.associateBy { it.key }
            assertTrue("应包含 apiKey", keyToField.containsKey("apiKey"))
            assertTrue("应包含 baseUrl", keyToField.containsKey("baseUrl"))
            assertTrue("应包含 model", keyToField.containsKey("model"))
            assertFalse("qwen-mt 专用插件不应有 prompt 字段", keyToField.containsKey("prompt"))
        } finally {
            runtime.close()
        }
    }
}
