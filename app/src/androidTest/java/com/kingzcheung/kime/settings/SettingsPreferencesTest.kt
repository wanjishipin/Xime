package com.kingzcheung.kime.settings

import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class SettingsPreferencesTest {

    private lateinit var context: Context

    @Before
    fun setup() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        context.getSharedPreferences("kime_settings", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
    }

    @Test
    fun `current schema uses default then persists value`() {
        assertEquals("wubi86", SettingsPreferences.getCurrentSchema(context))

        SettingsPreferences.setCurrentSchema(context, "wubi98")

        assertEquals("wubi98", SettingsPreferences.getCurrentSchema(context))
    }

    @Test
    fun `dark mode persists integer setting`() {
        assertEquals(0, SettingsPreferences.getDarkMode(context))

        SettingsPreferences.setDarkMode(context, 2)

        assertEquals(2, SettingsPreferences.getDarkMode(context))
    }

    @Test
    fun `sound and vibration defaults and updates work`() {
        assertTrue(SettingsPreferences.isSoundEnabled(context))
        assertEquals(50, SettingsPreferences.getSoundVolume(context))
        assertTrue(SettingsPreferences.isVibrationEnabled(context))
        assertEquals(50, SettingsPreferences.getVibrationIntensity(context))

        SettingsPreferences.setSoundEnabled(context, false)
        SettingsPreferences.setSoundVolume(context, 72)
        SettingsPreferences.setVibrationEnabled(context, false)
        SettingsPreferences.setVibrationIntensity(context, 66)

        assertFalse(SettingsPreferences.isSoundEnabled(context))
        assertEquals(72, SettingsPreferences.getSoundVolume(context))
        assertFalse(SettingsPreferences.isVibrationEnabled(context))
        assertEquals(66, SettingsPreferences.getVibrationIntensity(context))
    }

    @Test
    fun `keyboard theme and bottom buttons persist`() {
        assertEquals("lavender_purple", SettingsPreferences.getKeyboardTheme(context))
        assertFalse(SettingsPreferences.showBottomButtons(context))

        SettingsPreferences.setKeyboardTheme(context, "sunset")
        SettingsPreferences.setShowBottomButtons(context, true)

        assertEquals("sunset", SettingsPreferences.getKeyboardTheme(context))
        assertTrue(SettingsPreferences.showBottomButtons(context))
    }

    @Test
    fun `plugin enabled state is isolated by plugin id`() {
        val predictionPlugin = "prediction-onnx"
        val emojiPlugin = "emoji-sticker"

        assertFalse(SettingsPreferences.isPluginEnabled(context, predictionPlugin))
        assertFalse(SettingsPreferences.isPluginEnabled(context, emojiPlugin))

        SettingsPreferences.setPluginEnabled(context, predictionPlugin, true)

        assertTrue(SettingsPreferences.isPluginEnabled(context, predictionPlugin))
        assertFalse(SettingsPreferences.isPluginEnabled(context, emojiPlugin))

        SettingsPreferences.setPluginEnabled(context, predictionPlugin, false)
        assertFalse(SettingsPreferences.isPluginEnabled(context, predictionPlugin))
    }
}
