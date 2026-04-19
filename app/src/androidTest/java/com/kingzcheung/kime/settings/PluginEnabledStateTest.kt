package com.kingzcheung.kime.settings

import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class PluginEnabledStateTest {

    private lateinit var context: Context

    @Before
    fun setup() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        clearPluginPreferences()
    }

    private fun clearPluginPreferences() {
        context.getSharedPreferences("kime_settings", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
    }

    @Test
    fun `plugin enabled state defaults to false`() {
        val pluginId = "prediction-onnx"
        
        val isEnabled = SettingsPreferences.isPluginEnabled(context, pluginId)
        
        assertFalse(isEnabled)
    }

    @Test
    fun `plugin enabled state can be set to true`() {
        val pluginId = "prediction-onnx"
        
        SettingsPreferences.setPluginEnabled(context, pluginId, true)
        
        assertTrue(SettingsPreferences.isPluginEnabled(context, pluginId))
    }

    @Test
    fun `plugin enabled state can be toggled`() {
        val pluginId = "prediction-onnx"
        
        SettingsPreferences.setPluginEnabled(context, pluginId, true)
        assertTrue(SettingsPreferences.isPluginEnabled(context, pluginId))
        
        SettingsPreferences.setPluginEnabled(context, pluginId, false)
        assertFalse(SettingsPreferences.isPluginEnabled(context, pluginId))
    }

    @Test
    fun `different plugins have independent enabled states`() {
        val plugin1 = "prediction-onnx"
        val plugin2 = "emoji-sticker"
        
        SettingsPreferences.setPluginEnabled(context, plugin1, true)
        SettingsPreferences.setPluginEnabled(context, plugin2, false)
        
        assertTrue(SettingsPreferences.isPluginEnabled(context, plugin1))
        assertFalse(SettingsPreferences.isPluginEnabled(context, plugin2))
        
        SettingsPreferences.setPluginEnabled(context, plugin2, true)
        
        assertTrue(SettingsPreferences.isPluginEnabled(context, plugin1))
        assertTrue(SettingsPreferences.isPluginEnabled(context, plugin2))
    }

    @Test
    fun `plugin enabled state persists across clear`() {
        val pluginId = "prediction-onnx"
        
        SettingsPreferences.setPluginEnabled(context, pluginId, true)
        assertTrue(SettingsPreferences.isPluginEnabled(context, pluginId))
        
        context.getSharedPreferences("kime_settings", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
        
        assertFalse("State should be cleared", SettingsPreferences.isPluginEnabled(context, pluginId))
    }

    @Test
    fun `plugin enabled state keys are isolated`() {
        val plugin1 = "prediction-onnx"
        val plugin2 = "kaomoji"
        
        SettingsPreferences.setPluginEnabled(context, plugin1, true)
        
        val prefs = context.getSharedPreferences("kime_settings", Context.MODE_PRIVATE)
        
        assertTrue(prefs.getBoolean("plugin_enabled_prediction-onnx", false))
        assertFalse(prefs.getBoolean("plugin_enabled_kaomoji", true))
        assertFalse(prefs.getBoolean("plugin_enabled_unknown", true))
    }

    @Test
    fun `multiple plugins enabled states are tracked independently`() {
        val plugins = listOf("prediction-onnx", "emoji-sticker", "kaomoji")
        
        plugins.forEachIndexed { index, pluginId ->
            SettingsPreferences.setPluginEnabled(context, pluginId, index % 2 == 0)
        }
        
        assertTrue(SettingsPreferences.isPluginEnabled(context, "prediction-onnx"))
        assertFalse(SettingsPreferences.isPluginEnabled(context, "emoji-sticker"))
        assertTrue(SettingsPreferences.isPluginEnabled(context, "kaomoji"))
    }
}