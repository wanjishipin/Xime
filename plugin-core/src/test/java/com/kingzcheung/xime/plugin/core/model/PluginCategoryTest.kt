package com.kingzcheung.xime.plugin.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PluginCategoryTest {

    @Test
    fun `fromId maps emoji`() {
        assertEquals(PluginCategory.EMOJI, PluginCategory.fromId("emoji"))
    }

    @Test
    fun `fromId maps speech to ASR`() {
        assertEquals(PluginCategory.ASR, PluginCategory.fromId("speech"))
    }

    @Test
    fun `fromId maps prediction`() {
        assertEquals(PluginCategory.PREDICTION, PluginCategory.fromId("prediction"))
    }

    @Test
    fun `fromId maps tool`() {
        assertEquals(PluginCategory.TOOL, PluginCategory.fromId("tool"))
        assertEquals(PluginCategory.TOOL, PluginCategory.fromId("  Tool "))
    }

    @Test
    fun `fromId is case-insensitive and trims`() {
        assertEquals(PluginCategory.EMOJI, PluginCategory.fromId("  Emoji "))
    }

    @Test
    fun `fromId returns UNKNOWN for null blank and unknown`() {
        assertEquals(PluginCategory.UNKNOWN, PluginCategory.fromId(null))
        assertEquals(PluginCategory.UNKNOWN, PluginCategory.fromId(""))
        assertEquals(PluginCategory.UNKNOWN, PluginCategory.fromId("  "))
        assertEquals(PluginCategory.UNKNOWN, PluginCategory.fromId("clipboard"))
    }

    @Test
    fun `activation model per category`() {
        assertEquals(Activation.MULTI, PluginCategory.EMOJI.activation)
        assertEquals(Activation.SINGLE, PluginCategory.ASR.activation)
        assertEquals(Activation.MULTI, PluginCategory.PREDICTION.activation)
        assertEquals(Activation.SINGLE, PluginCategory.CLIPBOARD_SYNC.activation)
        assertEquals(Activation.MULTI, PluginCategory.TOOL.activation)
        assertEquals(Activation.NONE, PluginCategory.UNKNOWN.activation)
    }

    @Test
    fun `every entry has non-blank id and label`() {
        PluginCategory.entries.forEach { category ->
            assertTrue("id should not be blank", category.id.isNotBlank())
            assertTrue("label should not be blank", category.label.isNotBlank())
        }
    }
}
