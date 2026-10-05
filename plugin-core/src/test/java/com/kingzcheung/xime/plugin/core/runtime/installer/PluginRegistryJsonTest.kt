package com.kingzcheung.xime.plugin.core.runtime.installer

import com.kingzcheung.xime.plugin.core.api.ToolResult
import com.kingzcheung.xime.plugin.core.model.PluginCapabilities
import com.kingzcheung.xime.plugin.core.model.PluginInfo
import com.kingzcheung.xime.plugin.core.model.PluginSource
import com.kingzcheung.xime.plugin.core.model.PluginToolbarButton
import com.kingzcheung.xime.plugin.core.model.TrustLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PluginRegistryJsonTest {

    private fun sampleInfo() = PluginInfo(
        id = "com.example.demo",
        name = "示例插件",
        iconResId = 0,
        description = "注册表往返测试",
        versionCode = 0L,
        versionName = "1.2.3",
        path = "/data/user/0/app/files/plugins/com.example.demo/main.js",
        type = "tool",
        enabled = false,
        installTime = 1_700_000_000_000L,
        source = PluginSource.FILE,
        minHostVersion = "2.8.0",
        maxHostVersion = "3.0.0",
        trustLevel = TrustLevel.THIRD_PARTY,
        entryScript = "main.js",
        declaredHosts = listOf("api.openai.com", "example.com"),
        allowCustomHosts = true,
        toolbarButtons = listOf(
            PluginToolbarButton(id = "demo:open", label = "打开", icon = "icons/a.png", action = "open_panel")
        ),
        manifestIcon = "示",
        capabilities = PluginCapabilities(
            emoji = PluginCapabilities.EmojiCapabilities(supportsSearch = true, columns = 5, itemHeightDp = 40),
            speech = PluginCapabilities.SpeechCapabilities(
                inputMode = "streaming",
                supportsPartialResults = true,
                requiresNetwork = false
            ),
            tool = PluginCapabilities.ToolCapabilities(display = ToolResult.PASSIVE),
            clipboardSync = PluginCapabilities.ClipboardSyncCapabilities(
                protocols = listOf("webdav"),
                attachments = true
            ),
            backup = PluginCapabilities.BackupCapabilities(protocols = listOf("webdav")),
            events = listOf("input_changed", "text_committed"),
            candidateTransform = true,
            quickSendRead = true,
            clipboardRead = true
        )
    )

    @Test
    fun `注册表编解码全字段往返保真`() {
        val original = sampleInfo()
        val decoded = decodeRegistryJson(encodeRegistryJson(listOf(original)))

        assertEquals(1, decoded.size)
        assertEquals(original, decoded.single())
    }

    @Test
    fun `多个插件编解码保持顺序`() {
        val a = sampleInfo().copy(id = "a", name = "A")
        val b = sampleInfo().copy(id = "b", name = "B")
        val decoded = decodeRegistryJson(encodeRegistryJson(listOf(a, b)))
        assertEquals(listOf("a", "b"), decoded.map { it.id })
    }

    @Test
    fun `capabilities 的 JSON key 与 manifest 契约一致`() {
        val text = encodeRegistryJson(listOf(sampleInfo()))
        assertTrue("clipboard_sync 应为 snake_case", text.contains("\"clipboard_sync\""))
        assertTrue("candidate_transform 应为 snake_case", text.contains("\"candidate_transform\": true"))
        assertTrue("quick_send_read 应为 snake_case", text.contains("\"quick_send_read\": true"))
        assertTrue("clipboard_read 应为 snake_case", text.contains("\"clipboard_read\": true"))
        assertTrue("attachments 应随 clipboard_sync 一起持久化", text.contains("\"attachments\": true"))
    }

    @Test
    fun `未声明 attachments 的旧注册表读回为 false`() {
        val legacy = """
            {"version":1,"plugins":[{"id":"old","name":"旧插件","type":"clipboard_sync",
              "path":"/tmp/old/main.js","versionName":"1.0.0",
              "capabilities":{"clipboard_sync":{"protocols":["webdav"]}}}]}
        """.trimIndent()
        val decoded = decodeRegistryJson(legacy)
        val cs = decoded.single().capabilities?.clipboardSync
        assertEquals(listOf("webdav"), cs?.protocols)
        assertEquals("缺省应为 false（宿主导入为文本-only）", false, cs?.attachments)
    }

    @Test
    fun `null 字段不写入存储`() {
        val info = sampleInfo().copy(minHostVersion = null, maxHostVersion = null, manifestIcon = null)
        val text = encodeRegistryJson(listOf(info))
        assertFalse(text.contains("minHostVersion"))
        assertFalse(text.contains("manifestIcon"))
    }

    @Test
    fun `旧契约 tool display select 兼容为 PASSIVE`() {
        val content = """
            {
              "version": 1,
              "plugins": [
                {
                  "id": "a",
                  "path": "/x/main.js",
                  "type": "tool",
                  "capabilities": { "tool": { "display": "select" } }
                }
              ]
            }
        """.trimIndent()

        val decoded = decodeRegistryJson(content).single()
        assertEquals(ToolResult.PASSIVE, decoded.capabilities?.tool?.display)
    }

    @Test
    fun `未知字段被忽略`() {
        val content = """
            {
              "version": 2,
              "futureTop": true,
              "plugins": [
                { "id": "a", "path": "/x/main.js", "futureField": 123 }
              ]
            }
        """.trimIndent()

        val decoded = decodeRegistryJson(content).single()
        assertEquals("a", decoded.id)
        assertEquals("unknown", decoded.type)
    }

    @Test
    fun `非法 source 回退 SYSTEM`() {
        val content = """{ "version": 1, "plugins": [ { "id": "a", "path": "/x", "source": "HACK" } ] }"""
        val decoded = decodeRegistryJson(content).single()
        assertEquals(PluginSource.SYSTEM, decoded.source)
        assertEquals(TrustLevel.TRUSTED, decoded.trustLevel)
    }

    @Test
    fun `缺省字段使用默认值`() {
        val content = """{ "version": 1, "plugins": [ { "id": "mini", "path": "/x/main.js" } ] }"""
        val decoded = decodeRegistryJson(content).single()
        assertEquals("", decoded.name)
        assertEquals("", decoded.versionName)
        assertEquals("unknown", decoded.type)
        assertTrue(decoded.enabled)
        assertEquals(emptyList<String>(), decoded.declaredHosts)
        assertEquals(emptyList<PluginToolbarButton>(), decoded.toolbarButtons)
    }

    @Test
    fun `capabilities 缺省时为 null`() {
        val content = """{ "version": 1, "plugins": [ { "id": "a", "path": "/x" } ] }"""
        val decoded = decodeRegistryJson(content).single()
        assertEquals(null, decoded.capabilities)
    }

    @Test
    fun `注册表旧条目无 platforms 时归一化为 android`() {
        val content = """{ "version": 1, "plugins": [ { "id": "a", "path": "/x/main.js" } ] }"""
        val decoded = decodeRegistryJson(content).single()
        assertEquals(listOf(PluginInfo.PLATFORM_ANDROID), decoded.platforms)
        assertTrue(decoded.supportsPlatform(PluginInfo.PLATFORM_ANDROID))
    }

    @Test
    fun `非目标平台插件不通过 android 门禁`() {
        val info = sampleInfo().copy(platforms = listOf("ios"))
        assertFalse(info.supportsPlatform(PluginInfo.PLATFORM_ANDROID))
        assertTrue(info.supportsPlatform("ios"))
    }
}