package com.kingzcheung.xime.settings

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
    fun currentSchemaUsesDefaultThenMigratesLegacy() {
        // 引擎未初始化（测试环境）且无历史数据时使用默认方案
        assertEquals("wubi86", SettingsPreferences.getCurrentSchema(context))
        // 模拟旧版本 SharedPreferences 数据：一次性迁移读取
        context.getSharedPreferences("kime_settings", Context.MODE_PRIVATE)
            .edit()
            .putString("current_schema", "wubi98")
            .commit()
        assertEquals("wubi98", SettingsPreferences.getCurrentSchema(context))
    }

    @Test
    fun darkModePersistsIntegerSetting() {
        assertEquals(2, SettingsPreferences.getDarkMode(context))

        SettingsPreferences.setDarkMode(context, 0)

        assertEquals(0, SettingsPreferences.getDarkMode(context))
    }

    @Test
    fun darkModeValues() {
        SettingsPreferences.setDarkMode(context, 0)
        assertEquals(0, SettingsPreferences.getDarkMode(context))
        
        SettingsPreferences.setDarkMode(context, 1)
        assertEquals(1, SettingsPreferences.getDarkMode(context))
        
        SettingsPreferences.setDarkMode(context, 2)
        assertEquals(2, SettingsPreferences.getDarkMode(context))
    }

    @Test
    fun soundAndVibrationDefaultsAndUpdatesWork() {
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
    fun soundVolumeBoundaryValues() {
        SettingsPreferences.setSoundVolume(context, 0)
        assertEquals(0, SettingsPreferences.getSoundVolume(context))
        
        SettingsPreferences.setSoundVolume(context, 100)
        assertEquals(100, SettingsPreferences.getSoundVolume(context))
    }

    @Test
    fun vibrationIntensityBoundaryValues() {
        SettingsPreferences.setVibrationIntensity(context, 0)
        assertEquals(0, SettingsPreferences.getVibrationIntensity(context))
        
        SettingsPreferences.setVibrationIntensity(context, 100)
        assertEquals(100, SettingsPreferences.getVibrationIntensity(context))
    }

    @Test
    fun hapticModeDefaultsToFollowingSystem() {
        assertEquals("following_system", SettingsPreferences.getHapticMode(context))
    }

    @Test
    fun hapticModePersistsValues() {
        SettingsPreferences.setHapticMode(context, "enabled")
        assertEquals("enabled", SettingsPreferences.getHapticMode(context))

        SettingsPreferences.setHapticMode(context, "disabled")
        assertEquals("disabled", SettingsPreferences.getHapticMode(context))

        SettingsPreferences.setHapticMode(context, "following_system")
        assertEquals("following_system", SettingsPreferences.getHapticMode(context))
    }

    @Test
    fun hapticOnKeyUpDefaultsToFalse() {
        assertFalse(SettingsPreferences.isHapticOnKeyUp(context))
    }

    @Test
    fun vibrationDurationAndAmplitudeDefaultsToZero() {
        assertEquals(0, SettingsPreferences.getVibrationPressDuration(context))
        assertEquals(0, SettingsPreferences.getVibrationLongPressDuration(context))
        assertEquals(0, SettingsPreferences.getVibrationPressAmplitude(context))
        assertEquals(0, SettingsPreferences.getVibrationLongPressAmplitude(context))
    }

    @Test
    fun vibrationDurationAndAmplitudePersist() {
        SettingsPreferences.setVibrationPressDuration(context, 30)
        assertEquals(30, SettingsPreferences.getVibrationPressDuration(context))

        SettingsPreferences.setVibrationLongPressDuration(context, 50)
        assertEquals(50, SettingsPreferences.getVibrationLongPressDuration(context))

        SettingsPreferences.setVibrationPressAmplitude(context, 128)
        assertEquals(128, SettingsPreferences.getVibrationPressAmplitude(context))

        SettingsPreferences.setVibrationLongPressAmplitude(context, 200)
        assertEquals(200, SettingsPreferences.getVibrationLongPressAmplitude(context))
    }

    @Test
    fun keyboardThemeAndToolbarButtonsPersist() {
        assertEquals("lavender_purple", SettingsPreferences.getKeyboardTheme(context))
        assertTrue(SettingsPreferences.getToolbarButtons(context).isEmpty())

        SettingsPreferences.setKeyboardTheme(context, "sunset")
        SettingsPreferences.setToolbarButtons(context, listOf("symbols", "clipboard"))

        assertEquals("sunset", SettingsPreferences.getKeyboardTheme(context))
        assertEquals(listOf("symbols", "clipboard"), SettingsPreferences.getToolbarButtons(context))
    }

    @Test
    fun multipleThemeChanges() {
        SettingsPreferences.setKeyboardTheme(context, "ocean_blue")
        assertEquals("ocean_blue", SettingsPreferences.getKeyboardTheme(context))
        
        SettingsPreferences.setKeyboardTheme(context, "lavender_purple")
        assertEquals("lavender_purple", SettingsPreferences.getKeyboardTheme(context))
        
        SettingsPreferences.setKeyboardTheme(context, "sunset")
        assertEquals("sunset", SettingsPreferences.getKeyboardTheme(context))
    }

    @Test
    fun pluginEnabledStateIsIsolatedByPluginId() {
        val predictionPlugin = "prediction-onnx"
        val emojiPlugin = "meme-bunny"

        assertFalse(SettingsPreferences.isPluginEnabled(context, predictionPlugin))
        assertFalse(SettingsPreferences.isPluginEnabled(context, emojiPlugin))

        SettingsPreferences.setPluginEnabled(context, predictionPlugin, true)

        assertTrue(SettingsPreferences.isPluginEnabled(context, predictionPlugin))
        assertFalse(SettingsPreferences.isPluginEnabled(context, emojiPlugin))

        SettingsPreferences.setPluginEnabled(context, predictionPlugin, false)
        assertFalse(SettingsPreferences.isPluginEnabled(context, predictionPlugin))
    }
    
    @Test
    fun multiplePluginsCanBeEnabledIndependently() {
        val plugins = listOf("plugin1", "plugin2", "plugin3")
        
        for (plugin in plugins) {
            SettingsPreferences.setPluginEnabled(context, plugin, true)
        }
        
        for (plugin in plugins) {
            assertTrue("Plugin $plugin should be enabled", 
                SettingsPreferences.isPluginEnabled(context, plugin))
        }
        
        SettingsPreferences.setPluginEnabled(context, "plugin2", false)
        
        assertTrue("plugin1 should still be enabled", 
            SettingsPreferences.isPluginEnabled(context, "plugin1"))
        assertFalse("plugin2 should be disabled", 
            SettingsPreferences.isPluginEnabled(context, "plugin2"))
        assertTrue("plugin3 should still be enabled", 
            SettingsPreferences.isPluginEnabled(context, "plugin3"))
    }
    
    @Test
    fun smartPredictionSettings() {
        assertFalse("Smart prediction should be disabled by default", 
            SettingsPreferences.isSmartPredictionEnabled(context))
        
        SettingsPreferences.setSmartPredictionEnabled(context, true)
        assertTrue("Smart prediction should be enabled", 
            SettingsPreferences.isSmartPredictionEnabled(context))
        
        SettingsPreferences.setSmartPredictionEnabled(context, false)
        assertFalse("Smart prediction should be disabled", 
            SettingsPreferences.isSmartPredictionEnabled(context))
    }
    
    @Test
    fun predictionModelRepoSettings() {
        val defaultRepo = "https://www.modelscope.cn/models/bikeand/predictive-text-small"
        assertEquals(defaultRepo, SettingsPreferences.getPredictionModelRepo(context))
        
        val customRepo = "https://custom.model.repo/model"
        SettingsPreferences.setPredictionModelRepo(context, customRepo)
        assertEquals(customRepo, SettingsPreferences.getPredictionModelRepo(context))
    }
    
    @Test
    fun sttSettings() {
        assertFalse("STT should be disabled by default", 
            SettingsPreferences.isSttEnabled(context))
        
        SettingsPreferences.setSttEnabled(context, true)
        assertTrue("STT should be enabled", 
            SettingsPreferences.isSttEnabled(context))
    }
    
    @Test
    fun schemaLegacyMigration() {
        val schemas = listOf("wubi86", "wubi98", "wubi_pinyin")

        // 方案状态以 librime user.yaml 为准；引擎未初始化时回退读取旧 SharedPreferences 数据
        for (schema in schemas) {
            context.getSharedPreferences("kime_settings", Context.MODE_PRIVATE)
                .edit()
                .putString("current_schema", schema)
                .commit()
            assertEquals("Current schema should be $schema",
                schema, SettingsPreferences.getCurrentSchema(context))
        }
    }
    
    @Test
    fun toolbarButtonsPersist() {
        SettingsPreferences.setToolbarButtons(context, listOf("symbols", "clipboard"))
        assertEquals(listOf("symbols", "clipboard"), SettingsPreferences.getToolbarButtons(context))
        
        SettingsPreferences.setToolbarButtons(context, emptyList())
        assertTrue(SettingsPreferences.getToolbarButtons(context).isEmpty())
        
        SettingsPreferences.setToolbarButtons(context, listOf("symbols"))
        assertEquals(listOf("symbols"), SettingsPreferences.getToolbarButtons(context))
    }
    
    @Test
    fun clearingPreferencesResetsToDefaults() {
        SettingsPreferences.setDarkMode(context, 2)
        SettingsPreferences.setSoundEnabled(context, false)
        
        context.getSharedPreferences("kime_settings", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
        
        assertEquals("wubi86", SettingsPreferences.getCurrentSchema(context))
        assertEquals(0, SettingsPreferences.getDarkMode(context))
        assertTrue(SettingsPreferences.isSoundEnabled(context))
    }
}
