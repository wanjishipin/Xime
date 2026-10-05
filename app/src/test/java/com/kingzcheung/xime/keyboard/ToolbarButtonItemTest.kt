package com.kingzcheung.xime.keyboard

import com.kingzcheung.xime.plugin.core.api.PluginIcon
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class ToolbarButtonItemTest {

    private val pluginItems = listOf(
        ToolbarButtonItem.Plugin(
            id = "com.test.ai_reply",
            label = "AI 回复",
            icon = PluginIcon(text = "AI"),
            pluginId = "com.test",
            action = "open_panel",
        ),
        ToolbarButtonItem.Plugin(
            id = "com.test.ai_write",
            label = "AI 帮写",
            icon = null,
            pluginId = "com.test",
            action = "open_panel",
        ),
    )

    @Test
    fun `内置 id 命中内置按钮`() {
        val item = resolveToolbarButtonItem("emoji", pluginItems)
        assertNotNull(item)
        assertEquals(ToolbarButton.EMOJI, (item as ToolbarButtonItem.Builtin).button)
    }

    @Test
    fun `插件 id 命中插件按钮`() {
        val item = resolveToolbarButtonItem("com.test.ai_reply", pluginItems)
        assertNotNull(item)
        val plugin = item as ToolbarButtonItem.Plugin
        assertEquals("com.test", plugin.pluginId)
        assertEquals("AI 回复", plugin.label)
        assertEquals("open_panel", plugin.action)
    }

    @Test
    fun `内置查不到且无插件匹配时返回 null`() {
        assertNull(resolveToolbarButtonItem("unknown_id", pluginItems))
    }

    @Test
    fun `插件禁用卸载后残留 id 匹配不到插件返回 null`() {
        assertNull(resolveToolbarButtonItem("com.test.ai_write", emptyList()))
    }

    @Test
    fun `内置 id 优先于同名插件 id`() {
        val colliding = listOf(
            ToolbarButtonItem.Plugin(
                id = "emoji",
                label = "插件表情",
                icon = null,
                pluginId = "com.test",
                action = "open_panel",
            )
        )
        val item = resolveToolbarButtonItem("emoji", colliding)
        assertEquals(ToolbarButton.EMOJI, (item as ToolbarButtonItem.Builtin).button)
    }
}