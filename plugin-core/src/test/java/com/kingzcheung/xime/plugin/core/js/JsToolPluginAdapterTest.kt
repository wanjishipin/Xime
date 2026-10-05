package com.kingzcheung.xime.plugin.core.js

import android.app.Application
import com.kingzcheung.xime.plugin.core.config.PluginConfigStore
import com.kingzcheung.xime.plugin.core.model.PluginContext
import com.kingzcheung.xime.plugin.core.model.PluginInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * 验证 tool 类型插件 JS 契约（panel.state/onInput/onAction/onItemClick，v3）
 * 宿主侧解析结果正确。
 */
class JsToolPluginAdapterTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private class InMemoryConfigStore : PluginConfigStore {
        private val map = HashMap<String, String>()
        override fun get(key: String): String? = map[key]
        override fun set(key: String, value: String) { map[key] = value }
        override fun remove(key: String) { map.remove(key) }
        override fun keys(): Set<String> = map.keys.toSet()
    }

    private fun writeScript(content: String): File {
        val dir = tempFolder.newFolder("tool-plugin", System.nanoTime().toString())
        File(dir, "main.js").writeText(content)
        return dir
    }

    private fun createAdapter(dir: File, pluginId: String = "com.test.tool"): JsToolPluginAdapter {
        val runtime = JsScriptRuntime(
            pluginId = pluginId,
            pluginDir = dir,
            entryScript = "main.js",
            configStore = InMemoryConfigStore(),
        )
        val info = PluginInfo(id = pluginId, name = "测试工具", description = "测试", iconResId = 0, versionCode = 1, versionName = "1.0", path = File(dir, "main.js").absolutePath, type = "tool")
        val pluginContext = PluginContext(application = Application(), pluginInfo = info, configStore = InMemoryConfigStore())
        val adapter = JsToolPluginAdapter(runtime, pluginContext)
        adapter.onLoad(pluginContext)
        return adapter
    }

    @Test
    fun `getPanelState 解析输入框与候选`() {
        val dir = writeScript(
            """
            globalThis.plugin = {
              panel: {
                state: function(input) {
                  return {
                    inputText: "预填:" + input.inputText,
                    items: [
                      { id: "1", text: "候选一" },
                      { id: "2", text: "候选二" },
                    ],
                  };
                }
              }
            }
            """.trimIndent()
        )
        val adapter = createAdapter(dir)

        val state = adapter.getPanelState("hello")
        assertEquals("预填:hello", state.inputText)
        assertEquals(2, state.items.size)
        assertEquals("候选一", state.items[0].text)
        assertEquals("2", state.items[1].id)
    }

    @Test
    fun `getPanelState 未实现时回退宿主上下文`() {
        val dir = writeScript(
            """
            globalThis.plugin = {
              onLoad: function() {}
            }
            """.trimIndent()
        )
        val adapter = createAdapter(dir)
        val state = adapter.getPanelState("x")
        // 协议失败/未实现时回退宿主上下文（面板输入框仍有预填可用）
        assertEquals("x", state.inputText)
        assertTrue(state.items.isEmpty())
    }

    @Test
    fun `getPanelState inputText 缺省回退上下文 空串表示明确空输入框`() {
        // 纯函数契约（引擎无关）：与宿主 PluginCapabilities.validateForType 同样可离线验证
        fun resolve(raw: Any?): String = JsToolPluginAdapter.inputTextFromState(raw, "ctx")
        // 缺省（未返回 inputText 字段 → null）或非字符串 = 沿用宿主上下文
        assertEquals("ctx", resolve(null))
        assertEquals("ctx", resolve(42))
        // 空串 = 插件明确要求空输入框（如翻译插件拒绝剪贴板预填），宿主不得再用上下文兜底
        assertEquals("", resolve(""))
        // 正常回显
        assertEquals("预填", resolve("预填"))
    }

    @Test
    fun `getPanelState 非法 items 元素按协议丢弃`() {
        val dir = writeScript(
            """
            globalThis.plugin = {
              panel: {
                state: function(input) {
                  return {
                    items: [
                      { id: "1", text: "合规项" },
                      { id: "", text: "空 id" },
                      { text: "缺 id" },
                      { id: "3", text: "" },
                      "非对象元素",
                    ],
                  };
                }
              }
            }
            """.trimIndent()
        )
        val adapter = createAdapter(dir)

        val state = adapter.getPanelState("x")
        assertEquals(1, state.items.size)
        assertEquals("合规项", state.items[0].text)
    }

    @Test
    fun `getPanelState items id 重复时丢弃重复项`() {
        val dir = writeScript(
            """
            globalThis.plugin = {
              panel: {
                state: function(input) {
                  return {
                    items: [
                      { id: "a", text: "第一" },
                      { id: "a", text: "重复 id" },
                      { id: "b", text: "第二" },
                    ],
                  };
                }
              }
            }
            """.trimIndent()
        )
        val adapter = createAdapter(dir)

        val state = adapter.getPanelState("x")
        assertEquals(2, state.items.size)
        // 重复 id 不会进入宿主 UI（InfoPanel 候选条目依赖 id 唯一）
        assertEquals(listOf("a", "b"), state.items.map { it.id })
    }

    @Test
    fun `tool 结果显示方式来自 manifest capabilities`() {
        val config = com.kingzcheung.xime.plugin.core.model.PluginCapabilities.ToolCapabilities(
            display = com.kingzcheung.xime.plugin.core.api.ToolResult.PASSIVE
        )
        assertEquals(com.kingzcheung.xime.plugin.core.api.ToolResult.PASSIVE, config.display)
    }

    @Test
    fun `面板事件回调桥接到 JS`() {
        val dir = writeScript(
            """
            globalThis.plugin = {
              lastKey: "",
              lastValue: "",
              lastAction: "",
              lastItem: "",
              _lastKey: function() { return this.lastKey; },
              _lastValue: function() { return this.lastValue; },
              _lastAction: function() { return this.lastAction; },
              _lastItem: function() { return this.lastItem; },
              panel: {
                onInput: function(e) { globalThis.plugin.lastKey = e.key; globalThis.plugin.lastValue = e.value; },
                onAction: async function(e) { globalThis.plugin.lastAction = e.actionId; },
                onItemClick: function(e) { globalThis.plugin.lastItem = e.itemId; }
              }
            }
            """.trimIndent()
        )
        val runtime = JsScriptRuntime(
            pluginId = "com.test.tool",
            pluginDir = dir,
            entryScript = "main.js",
            configStore = InMemoryConfigStore(),
        )
        assertTrue(runtime.load())
        val info = PluginInfo(id = "com.test.tool", name = "测试工具", description = "测试", iconResId = 0, versionCode = 1, versionName = "1.0", path = File(dir, "main.js").absolutePath, type = "tool")
        val adapter = JsToolPluginAdapter(runtime, PluginContext(application = Application(), pluginInfo = info, configStore = InMemoryConfigStore()))

        // key 空串 = 主输入框；非空 key（控件行字段）原样透传
        adapter.onPanelInput("", "你好")
        assertEquals("", runtime.call("_lastKey")?.toString())
        assertEquals("你好", runtime.call("_lastValue")?.toString())

        adapter.onPanelInput("sourceLang", "English")
        assertEquals("sourceLang", runtime.call("_lastKey")?.toString())
        assertEquals("English", runtime.call("_lastValue")?.toString())

        adapter.onPanelAction("generate")
        assertEquals("generate", runtime.call("_lastAction")?.toString())

        adapter.onPanelItemClick("7")
        assertEquals("7", runtime.call("_lastItem")?.toString())
    }

    @Test
    fun `getPanelState ui 节点解析为统一 UiNode 模型`() {
        val dir = writeScript(
            """
            globalThis.plugin = {
              panel: {
                state: function(input) {
                  return {
                    ui: [
                      { type: "section", label: "统计" },
                      { type: "text", value: "说明", style: "caption" },
                      { type: "metric", label: "字数", value: "100", unit: "字" },
                      { type: "divider" },
                      { type: "button", label: "清零", key: "reset" },
                    ],
                  };
                }
              }
            }
            """.trimIndent()
        )
        val adapter = createAdapter(dir)
        val state = adapter.getPanelState("x")
        val ui = state.ui!!
        assertEquals(5, ui.size)
        assertEquals(com.kingzcheung.xime.plugin.core.config.UiNodeType.SECTION, ui[0].type)
        assertEquals("统计", ui[0].label)
        assertEquals(com.kingzcheung.xime.plugin.core.config.UiNodeType.TEXT, ui[1].type)
        assertEquals("说明", ui[1].value)
        assertEquals("caption", ui[1].style)
        assertEquals(com.kingzcheung.xime.plugin.core.config.UiNodeType.METRIC, ui[2].type)
        assertEquals("字", ui[2].unit)
        assertEquals(com.kingzcheung.xime.plugin.core.config.UiNodeType.DIVIDER, ui[3].type)
        assertEquals(com.kingzcheung.xime.plugin.core.config.UiNodeType.BUTTON, ui[4].type)
        assertEquals("reset", ui[4].key)
        assertEquals("清零", ui[4].label)
    }

    @Test
    fun `getPanelState ui 旧字段与新字段兼容`() {
        // v0.2.0 存量写法（action/title/content/actionId）应被解析层兼容为 UiNode
        val dir = writeScript(
            """
            globalThis.plugin = {
              panel: {
                state: function(input) {
                  return {
                    ui: [
                      { type: "section", title: "旧标题" },
                      { type: "text", content: "旧文本", style: "caption" },
                      { type: "action", label: "旧按钮", actionId: "old_action" },
                    ],
                  };
                }
              }
            }
            """.trimIndent()
        )
        val adapter = createAdapter(dir)
        val state = adapter.getPanelState("x")
        val ui = state.ui!!
        assertEquals(3, ui.size)
        assertEquals(com.kingzcheung.xime.plugin.core.config.UiNodeType.SECTION, ui[0].type)
        assertEquals("旧标题", ui[0].label)
        assertEquals(com.kingzcheung.xime.plugin.core.config.UiNodeType.TEXT, ui[1].type)
        assertEquals("旧文本", ui[1].value)
        assertEquals(com.kingzcheung.xime.plugin.core.config.UiNodeType.BUTTON, ui[2].type)
        assertEquals("old_action", ui[2].key)
        assertEquals("旧按钮", ui[2].label)
    }

    @Test
    fun `getSettingsSchema 旧 button action 字段兼容为 key`() {
        val dir = writeScript(
            """
            globalThis.plugin = {
              settings: {
                schema: function() {
                  return [
                    { key: "apiKey", label: "API Key", type: "secret" },
                    { key: "testConnection", label: "测试连接", type: "button", action: "testConnection" },
                  ];
                }
              }
            }
            """.trimIndent()
        )
        val adapter = createAdapter(dir)
        val schema = adapter.getSettingsSchema()
        assertEquals(2, schema.size)
        val button = schema.first { it.key == "testConnection" }
        assertEquals(com.kingzcheung.xime.plugin.core.config.UiNodeType.BUTTON, button.type)
        // 旧字段 action → key 兼容（assets 预装旧 xipk 在新宿主上按钮可点）
        assertEquals("testConnection", button.key)
    }

    @Test
    fun `getPanelState 未知节点 type 降级为文本不崩溃`() {
        val dir = writeScript(
            """
            globalThis.plugin = {
              panel: {
                state: function(input) {
                  return {
                    ui: [
                      { type: "future_node", value: "未来节点" },
                      { type: "section", label: "正常" },
                    ],
                  };
                }
              }
            }
            """.trimIndent()
        )
        val adapter = createAdapter(dir)
        val state = adapter.getPanelState("x")
        // 未知 type 解析为 TEXT（降级），面板渲染为只读文本
        assertEquals(com.kingzcheung.xime.plugin.core.config.UiNodeType.TEXT, state.ui!![0].type)
        assertEquals("未来节点", state.ui!![0].value)
        assertEquals(2, state.ui!!.size)
    }
}