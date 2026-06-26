package com.kingzcheung.xime.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.kingzcheung.xime.settings.SettingsPreferences
import com.kingzcheung.xime.ui.theme.KeyboardColorScheme
import com.kingzcheung.xime.ui.theme.KeyboardThemes
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class ThemeUiState(
    val darkMode: Int = 0,
    val colorTheme: String = "lavender_purple",
    val colorThemes: List<KeyboardColorScheme> = KeyboardThemes.themes,
    val isGlassEffectEnabled: Boolean = false,
)

class ThemeSettingsViewModel(application: Application) : AndroidViewModel(application) {
    private val context = application.applicationContext
    
    private val _uiState = MutableStateFlow(ThemeUiState(
        darkMode = SettingsPreferences.getDarkMode(context),
        colorTheme = SettingsPreferences.getKeyboardTheme(context),
        isGlassEffectEnabled = SettingsPreferences.isGlassEffectEnabled(context),
    ))
    val uiState: StateFlow<ThemeUiState> = _uiState.asStateFlow()
    
    fun setDarkMode(mode: Int) {
        SettingsPreferences.setDarkMode(context, mode)
        _uiState.update { it.copy(darkMode = mode) }
    }
    
    fun setColorTheme(themeId: String) {
        SettingsPreferences.setKeyboardTheme(context, themeId)
        _uiState.update { it.copy(colorTheme = themeId) }
    }

    fun setGlassEffectEnabled(enabled: Boolean) {
        SettingsPreferences.setGlassEffectEnabled(context, enabled)
        _uiState.update { it.copy(isGlassEffectEnabled = enabled) }
    }
}