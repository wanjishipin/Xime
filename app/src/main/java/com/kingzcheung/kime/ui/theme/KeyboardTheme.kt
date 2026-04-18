package com.kingzcheung.kime.ui.theme

import androidx.compose.ui.graphics.Color

data class KeyboardColorScheme(
    val id: String,
    val name: String,
    val specialKeyLight: Color,
    val specialKeyDark: Color,
    val accentLight: Color,
    val accentDark: Color,
    val primaryLight: Color = accentLight,
    val primaryDark: Color = accentDark,
    val primaryContainerLight: Color = specialKeyLight,
    val primaryContainerDark: Color = specialKeyDark,
    val surfaceLight: Color = Color.White,
    val surfaceDark: Color = Color(0xFF1C1B1F)
)

object KeyboardThemes {
    val themes = listOf(
        KeyboardColorScheme(
            id = "lavender_purple",
            name = "薰衣草紫",
            specialKeyLight = Color(0xFFE8DEF8),
            specialKeyDark = Color(0xFF6750A4),
            accentLight = Color(0xFF8F73E2),
            accentDark = Color(0xFFD0BCFF),
            primaryLight = Color(0xFF8F73E2),
            primaryDark = Color(0xFFD0BCFF),
            primaryContainerLight = Color(0xFFEADDFF),
            primaryContainerDark = Color(0xFF4F378B),
            surfaceLight = Color(0xFFFAF8FC),
            surfaceDark = Color(0xFF2B2930)
        ),
        KeyboardColorScheme(
            id = "ocean_blue",
            name = "海洋蔚蓝",
            specialKeyLight = Color(0xFFD3E3FD),
            specialKeyDark = Color(0xFF4A90D9),
            accentLight = Color(0xFF1A73E8),
            accentDark = Color(0xFF8AB4F8),
            primaryLight = Color(0xFF1A73E8),
            primaryDark = Color(0xFF8AB4F8),
            primaryContainerLight = Color(0xFFD3E3FD),
            primaryContainerDark = Color(0xFF4A90D9),
            surfaceLight = Color(0xFFF8F9FA),
            surfaceDark = Color(0xFF2D2D2D)
        ),
        KeyboardColorScheme(
            id = "forest_green",
            name = "森林翠绿",
            specialKeyLight = Color(0xFFC8E6C9),
            specialKeyDark = Color(0xFF4CAF50),
            accentLight = Color(0xFF2E7D32),
            accentDark = Color(0xFF81C784),
            primaryLight = Color(0xFF2E7D32),
            primaryDark = Color(0xFF81C784),
            primaryContainerLight = Color(0xFFC8E6C9),
            primaryContainerDark = Color(0xFF4CAF50),
            surfaceLight = Color(0xFFF5F9F5),
            surfaceDark = Color(0xFF2B2D2B)
        ),
        KeyboardColorScheme(
            id = "sunset_orange",
            name = "落日橙光",
            specialKeyLight = Color(0xFFFFE0B2),
            specialKeyDark = Color(0xFFFF9800),
            accentLight = Color(0xFFE65100),
            accentDark = Color(0xFFFFB74D),
            primaryLight = Color(0xFFE65100),
            primaryDark = Color(0xFFFFB74D),
            primaryContainerLight = Color(0xFFFFE0B2),
            primaryContainerDark = Color(0xFFFF9800),
            surfaceLight = Color(0xFFFFFAF5),
            surfaceDark = Color(0xFF2D2B29)
        ),
        KeyboardColorScheme(
            id = "coral_red",
            name = "珊瑚绯红",
            specialKeyLight = Color(0xFFFFCDD2),
            specialKeyDark = Color(0xFFE57373),
            accentLight = Color(0xFFC62828),
            accentDark = Color(0xFFEF9A9A),
            primaryLight = Color(0xFFC62828),
            primaryDark = Color(0xFFEF9A9A),
            primaryContainerLight = Color(0xFFFFCDD2),
            primaryContainerDark = Color(0xFFE57373),
            surfaceLight = Color(0xFFFFF8F8),
            surfaceDark = Color(0xFF2D2929)
        ),
        KeyboardColorScheme(
            id = "slate_gray",
            name = "沉稳石墨",
            specialKeyLight = Color(0xFFE0E0E0),
            specialKeyDark = Color(0xFF616161),
            accentLight = Color(0xFF424242),
            accentDark = Color(0xFF9E9E9E),
            primaryLight = Color(0xFF424242),
            primaryDark = Color(0xFF9E9E9E),
            primaryContainerLight = Color(0xFFE0E0E0),
            primaryContainerDark = Color(0xFF616161),
            surfaceLight = Color(0xFFF5F5F5),
            surfaceDark = Color(0xFF2D2D2D)
        ),
        KeyboardColorScheme(
            id = "rose_pink",
            name = "浪漫玫瑰",
            specialKeyLight = Color(0xFFF8BBD9),
            specialKeyDark = Color(0xFFE91E63),
            accentLight = Color(0xFFAD1457),
            accentDark = Color(0xFFF48FB1),
            primaryLight = Color(0xFFAD1457),
            primaryDark = Color(0xFFF48FB1),
            primaryContainerLight = Color(0xFFF8BBD9),
            primaryContainerDark = Color(0xFFE91E63),
            surfaceLight = Color(0xFFFFF8FA),
            surfaceDark = Color(0xFF2D2B2C)
        ),
        KeyboardColorScheme(
            id = "teal_cyan",
            name = "青碧如水",
            specialKeyLight = Color(0xFFB2DFDB),
            specialKeyDark = Color(0xFF009688),
            accentLight = Color(0xFF00796B),
            accentDark = Color(0xFF80CBC4),
            primaryLight = Color(0xFF00796B),
            primaryDark = Color(0xFF80CBC4),
            primaryContainerLight = Color(0xFFB2DFDB),
            primaryContainerDark = Color(0xFF009688),
            surfaceLight = Color(0xFFF8FAF9),
            surfaceDark = Color(0xFF2B2D2D)
        )
    )
    
    fun getThemeById(id: String): KeyboardColorScheme {
        return themes.find { it.id == id } ?: themes[0]
    }
    
    fun getSpecialKeyColor(themeId: String, isDark: Boolean): Color {
        val theme = getThemeById(themeId)
        return if (isDark) theme.specialKeyDark else theme.specialKeyLight
    }
    
    fun getAccentColor(themeId: String, isDark: Boolean): Color {
        val theme = getThemeById(themeId)
        return if (isDark) theme.accentDark else theme.accentLight
    }
    
    fun getPrimaryColor(themeId: String, isDark: Boolean): Color {
        val theme = getThemeById(themeId)
        return if (isDark) theme.primaryDark else theme.primaryLight
    }
    
    fun getPrimaryContainerColor(themeId: String, isDark: Boolean): Color {
        val theme = getThemeById(themeId)
        return if (isDark) theme.primaryContainerDark else theme.primaryContainerLight
    }
    
    fun getSurfaceColor(themeId: String, isDark: Boolean): Color {
        val theme = getThemeById(themeId)
        return if (isDark) theme.surfaceDark else theme.surfaceLight
    }
}