package com.kingzcheung.xime.plugin.core.api

import android.content.Context
import com.kingzcheung.xime.plugin.core.config.IPluginConfigurable
import com.kingzcheung.xime.plugin.core.config.UiNode
import com.kingzcheung.xime.plugin.core.config.UiNodeType
import com.kingzcheung.xime.plugin.core.model.PluginCapabilities
import com.kingzcheung.xime.plugin.core.model.PluginContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeechCapabilitiesTest {

    @Test
    fun `speech capabilities have correct defaults`() {
        val caps = PluginCapabilities.SpeechCapabilities()

        assertEquals("streaming", caps.inputMode)
        assertTrue(caps.supportsPartialResults)
        assertTrue(caps.requiresNetwork)
    }

    @Test
    fun `speech capabilities can declare BATCH mode`() {
        val caps = PluginCapabilities.SpeechCapabilities(inputMode = "batch", supportsPartialResults = false)

        assertEquals("batch", caps.inputMode)
        assertFalse(caps.supportsPartialResults)
    }
}

class AsrPluginDefaultImplTest {

    private open class FakeAsrPlugin : AsrPlugin {
        override fun getCapabilities(): PluginCapabilities.SpeechCapabilities =
            PluginCapabilities.SpeechCapabilities()
        override fun isConfigured(): Boolean = true
        override fun createBackend(context: Context): AsrPluginBackend = FakeBackend()

        override fun onLoad(context: PluginContext) {}
        override fun onUnload() {}
    }

    private class FakeBackend : AsrPluginBackend {
        override val isRunning: Boolean = false
        override fun setListener(listener: AsrPluginListener) {}
        override fun initialize(): Boolean = true
        override fun start(): Boolean = true
        override fun processAudioChunk(pcm: ByteArray) {}
        override fun stop() {}
        override fun cancel() {}
        override fun release() {}
    }

    @Test
    fun `plugin extends IPluginEntryClass and IPluginConfigurable`() {
        val plugin: IPluginEntryClass = FakeAsrPlugin()
        val configurable: IPluginConfigurable = FakeAsrPlugin()
        assertTrue(plugin is IPluginConfigurable)
        assertTrue(configurable is IPluginEntryClass)
    }

    @Test
    fun `listener default callbacks are no-ops`() {
        val listener = object : AsrPluginListener {
            override fun onFinal(text: String) {}
        }
        listener.onPartial("partial")
        listener.onError("error")
        listener.onStateChanged(AsrPluginState.LISTENING)
    }

    @Test
    fun `schema can be overridden with SECRET apiKey field`() {
        val plugin = object : FakeAsrPlugin() {
            override fun getSettingsSchema(): List<UiNode> =
                listOf(
                    UiNode(
                        key = "apiKey",
                        label = "API Key",
                        type = UiNodeType.SECRET,
                    )
                )
        }

        val field = plugin.getSettingsSchema().first()
        assertEquals("apiKey", field.key)
        assertEquals(UiNodeType.SECRET, field.type)
        assertTrue(plugin.getSettingsSchema().isNotEmpty())
    }

    @Test
    fun `plugin state values are distinct`() {
        val states = AsrPluginState.entries.map { it.name }.toSet()
        assertEquals(4, states.size)
    }
}