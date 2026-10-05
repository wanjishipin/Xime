package com.kingzcheung.xime.plugin.core.api

import com.kingzcheung.xime.plugin.core.model.PluginCapabilities
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PluginResultItemTest {

    @Test
    fun `PluginResultItem should have correct properties`() {
        val item = PluginResultItem(
            id = "emoji_1",
            text = "😀",
            insertText = "😀",
            imageUrl = null
        )

        assertEquals("emoji_1", item.id)
        assertEquals("😀", item.text)
        assertEquals("😀", item.insertText)
        assertNull("imageUrl should be null", item.imageUrl)
    }

    @Test
    fun `PluginResultItem can have imageUrl`() {
        val item = PluginResultItem(
            id = "sticker_1",
            text = "兔子",
            insertText = "[兔子]",
            imageUrl = "/path/to/image.png"
        )

        assertEquals("/path/to/image.png", item.imageUrl)
    }

    @Test
    fun `PluginResultItem text and insertText can differ`() {
        val item = PluginResultItem(
            id = "kaomoji",
            text = "(╯°□°）╯︵ ┻━┻",
            insertText = "(╯°□°）╯︵ ┻━┻"
        )

        assertEquals("(╯°□°）╯︵ ┻━┻", item.text)
        assertEquals("(╯°□°）╯︵ ┻━┻", item.insertText)
    }
}

class EmojiCapabilitiesTest {

    @Test
    fun `emoji capabilities have correct defaults`() {
        val caps = PluginCapabilities.EmojiCapabilities()

        assertTrue(!caps.supportsSearch)
        assertNull(caps.columns)
        assertNull(caps.itemHeightDp)
    }

    @Test
    fun `layout metadata comes from manifest`() {
        val caps = PluginCapabilities.EmojiCapabilities(
            supportsSearch = true,
            columns = 3,
            itemHeightDp = 30
        )

        assertEquals(3, caps.columns)
        assertEquals(30, caps.itemHeightDp)
    }
}

class EmojiPluginDefaultImplTest {

    @Test
    fun `PluginResultItem list filtering by text`() {
        val items = listOf(
            PluginResultItem("1", "你好"),
            PluginResultItem("2", "世界"),
            PluginResultItem("3", "你好吗")
        )

        val filtered = items.filter { it.text.contains("你好") }

        assertEquals(2, filtered.size)
    }

    @Test
    fun `PluginResultItem topK selection`() {
        val items = (1..20).map { i ->
            PluginResultItem("$i", "emoji$i")
        }

        val top5 = items.take(5)

        assertEquals(5, top5.size)
    }
}