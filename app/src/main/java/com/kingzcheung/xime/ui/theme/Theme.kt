package com.kingzcheung.xime.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import com.kingzcheung.xime.settings.SettingsPreferences

// ========== 统一主题系统 ==========

@Composable
fun XimeTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    themeId: String? = null,
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    val currentThemeId = themeId ?: SettingsPreferences.getKeyboardTheme(context)
    val theme = KeyboardThemes.getThemeById(currentThemeId)
    
    val lightScheme = lightColorScheme(
        primary = theme.primaryLight,
        onPrimary = Color.White,
        primaryContainer = theme.primaryContainerLight,
        onPrimaryContainer = Color(0xFF21005D),
        secondary = theme.primaryContainerLight,
        onSecondary = Color(0xFF21005D),
        tertiary = theme.primaryContainerLight,
        onTertiary = Color(0xFF21005D),
        background = Color.White,
        onBackground = Color(0xFF1C1B1F),
        surface = theme.surfaceLight,
        onSurface = Color(0xFF1C1B1F),
        surfaceVariant = theme.primaryContainerLight.copy(alpha = 0.5f),
        onSurfaceVariant = Color(0xFF49454F),
        outline = Color(0xFF79747E),
        outlineVariant = Color(0xFFCAC4D0)
    )
    
    val darkScheme = darkColorScheme(
        primary = theme.primaryDark,
        onPrimary = Color(0xFF381E72),
        primaryContainer = theme.primaryContainerDark,
        onPrimaryContainer = Color(0xFFEADDFF),
        secondary = theme.primaryContainerDark,
        onSecondary = Color(0xFFEADDFF),
        tertiary = theme.primaryContainerDark,
        onTertiary = Color(0xFFEADDFF),
        background = Color(0xFF1C1B1F),
        onBackground = Color(0xFFE6E1E5),
        surface = theme.surfaceDark,
        onSurface = Color(0xFFE6E1E5),
        surfaceVariant = theme.primaryContainerDark.copy(alpha = 0.5f),
        onSurfaceVariant = Color(0xFFCAC4D0),
        outline = Color(0xFF938F99),
        outlineVariant = Color(0xFF49454F)
    )

    CompositionLocalProvider(
        LocalDensity provides Density(
            density = LocalDensity.current.density,
            fontScale = 1.0f
        )
    ) {
        MaterialTheme(
            colorScheme = if (darkTheme) darkScheme else lightScheme,
            content = content
        )
    }
}