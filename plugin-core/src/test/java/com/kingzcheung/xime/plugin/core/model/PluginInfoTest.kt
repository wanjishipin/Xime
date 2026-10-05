package com.kingzcheung.xime.plugin.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PluginInfoTest {
    
    @Test
    fun `PluginInfo should have correct default values`() {
        val pluginInfo = PluginInfo(
            id = "test_plugin",
            name = "Test Plugin",
            iconResId = 0,
            versionCode = 1,
            versionName = "1.0.0",
            path = "/path/to/plugin",
            description = "A test plugin"
        )
        
        assertEquals("test_plugin", pluginInfo.id)
        assertEquals("Test Plugin", pluginInfo.name)
        assertEquals("1.0.0", pluginInfo.versionName)
        assertEquals("unknown", pluginInfo.type)
        assertTrue("Should be enabled by default", pluginInfo.enabled)
    }
    
    @Test
    fun `PluginInfo version property should return versionName`() {
        val pluginInfo = PluginInfo(
            id = "test",
            name = "Test",
            iconResId = 0,
            versionCode = 2,
            versionName = "2.0.0",
            path = "",
            description = ""
        )
        
        assertEquals("2.0.0", pluginInfo.version)
    }
    
    @Test
    fun `PluginInfo can be disabled`() {
        val pluginInfo = PluginInfo(
            id = "test",
            name = "Test",
            iconResId = 0,
            versionCode = 1,
            versionName = "1.0",
            path = "",
            description = "",
            enabled = false
        )
        
        assertFalse("Plugin can be disabled", pluginInfo.enabled)
    }
    
    @Test
    fun `PluginInfo can have different types`() {
        val emojiPlugin = PluginInfo(
            id = "emoji_plugin",
            name = "Emoji",
            iconResId = 0,
            versionCode = 1,
            versionName = "1.0",
            path = "",
            description = "",
            type = "emoji"
        )
        
        assertEquals("emoji", emojiPlugin.type)
    }

    @Test
    fun `PluginInfo category is derived from type`() {
        assertEquals(
            PluginCategory.EMOJI,
            PluginInfo(id = "e", name = "E", iconResId = 0, versionCode = 1, versionName = "1", path = "", description = "", type = "emoji").category
        )
        assertEquals(
            PluginCategory.ASR,
            PluginInfo(id = "a", name = "A", iconResId = 0, versionCode = 1, versionName = "1", path = "", description = "", type = "speech").category
        )
        assertEquals(
            PluginCategory.PREDICTION,
            PluginInfo(id = "p", name = "P", iconResId = 0, versionCode = 1, versionName = "1", path = "", description = "", type = "prediction").category
        )
        assertEquals(
            PluginCategory.UNKNOWN,
            PluginInfo(id = "u", name = "U", iconResId = 0, versionCode = 1, versionName = "1", path = "", description = "").category
        )
    }
    
    @Test
    fun `PluginInfo copy should preserve values`() {
        val original = PluginInfo(
            id = "original",
            name = "Original",
            iconResId = 123,
            versionCode = 100,
            versionName = "10.0",
            path = "/original/path",
            description = "Original plugin",
            type = "emoji",
            enabled = true
        )
        
        val copied = original.copy(enabled = false)
        
        assertEquals("original", copied.id)
        assertEquals("Original", copied.name)
        assertEquals(123, copied.iconResId)
        assertFalse("Copied should be disabled", copied.enabled)
        assertEquals("emoji", copied.type)
    }
    
    @Test
    fun `PluginInfo installTime can be set`() {
        val customTime = 1234567890L
        val pluginInfo = PluginInfo(
            id = "test",
            name = "Test",
            iconResId = 0,
            versionCode = 1,
            versionName = "1.0",
            path = "",
            description = "",
            installTime = customTime
        )
        
        assertEquals(customTime, pluginInfo.installTime)
    }
    
    @Test
    fun `PluginInfo can have entryScript`() {
        val pluginInfo = PluginInfo(
            id = "js_plugin",
            name = "QuickJS",
            iconResId = 0,
            versionCode = 0,
            versionName = "1.0",
            path = "/data/plugins/js_plugin/main.js",
            description = "",
            entryScript = "main.js"
        )

        assertEquals("main.js", pluginInfo.entryScript)
    }

    @Test
    fun `PluginInfo toolbarButtons default to empty list`() {
        val pluginInfo = PluginInfo(
            id = "test",
            name = "Test",
            iconResId = 0,
            versionCode = 1,
            versionName = "1.0",
            path = "",
            description = ""
        )
        assertEquals("toolbarButtons 缺省应为空", emptyList<PluginToolbarButton>(), pluginInfo.toolbarButtons)
    }

    @Test
    fun `PluginInfo keeps toolbar buttons and category of tool type`() {
        val buttons = listOf(
            PluginToolbarButton(id = "ai_reply", label = "AI 回复", icon = "ai_reply.png"),
            PluginToolbarButton(id = "ai_write", label = "AI 帮写")
        )
        val pluginInfo = PluginInfo(
            id = "test",
            name = "Test",
            iconResId = 0,
            versionCode = 1,
            versionName = "1.0",
            path = "",
            description = "",
            type = "tool",
            toolbarButtons = buttons
        )
        assertEquals(buttons, pluginInfo.toolbarButtons)
        assertEquals(PluginCategory.TOOL, pluginInfo.category)
    }

    @Test
    fun `PluginToolbarButton defaults`() {
        val button = PluginToolbarButton(id = "ai_reply")
        assertEquals("", button.label)
        assertEquals(null, button.icon)
        assertEquals("open_panel", button.action)
    }
}
