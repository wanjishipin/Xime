package com.kingzcheung.xime.ui.theme

import android.content.Context
import android.os.Build
import androidx.compose.ui.graphics.Color

/**
 * Material You 动态配色（Android 12+）：从系统壁纸调色板（system_accent/neutral 资源）
 * 构建键盘主题，随用户壁纸变化。
 *
 * framework 资源编号与 Material tonal tone 反向对应：编号 n ≈ tone (1000 - n) / 100 的近似档位，
 * 即 system_neutral1_10 近白（tone 99）、system_neutral1_900 近黑（tone 10）。
 * 档位选择对齐 material3 dynamicLight/DarkColorScheme 的浅深色规范。
 */
object DynamicThemes {
    /** 内置动态配色方案的 id（xime.yaml color_schemes 中的条目）。 */
    const val THEME_ID = "dynamic"

    /** 动态配色仅在 Android 12+ 可用。 */
    fun isSupported(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

    /**
     * 读取当前系统取色的主强调色（浅/深），用于与缓存中的动态主题对比，
     * 判断壁纸取色是否发生了变化。不支持时返回 null。
     */
    fun currentAccentColors(context: Context): Pair<Color, Color>? {
        if (!isSupported()) return null
        val p = SystemPalette(context)
        return p.accent1(600) to p.accent1(200)
    }

    /**
     * 构建动态配色键盘主题（颜色全部来自壁纸调色板）。
     * 不支持（< Android 12）或系统资源缺失时返回 null，调用方跳过该方案。
     */
    fun create(context: Context, id: String = THEME_ID, name: String = "动态配色"): KeyboardColorScheme? {
        if (!isSupported()) return null
        val p = SystemPalette(context)
        val accentLight = p.accent1(600)
        val accentDark = p.accent1(200)
        return KeyboardColorScheme(
            id = id,
            name = name,
            specialKeyLight = p.accent1(100),
            // 700 而非 600：深色下功能键大面积铺色，取更暗一档降低饱和度刺激（与 primaryContainerDark 同档）
            specialKeyDark = p.accent1(700),
            accentLight = accentLight,
            accentDark = accentDark,
            primaryLight = accentLight,
            primaryDark = accentDark,
            primaryContainerLight = p.accent1(100),
            primaryContainerDark = p.accent1(700),
            surfaceLight = p.neutral1(10),
            surfaceDark = p.neutral1(900),
            keyboardBgLight = p.neutral1(10),
            keyboardBgDark = p.neutral1(900),
            keyBgLight = p.neutral1(0),
            keyBgDark = p.neutral1(800),
            candidateBarBgLight = p.neutral1(10),
            candidateBarBgDark = p.neutral1(900),
            keyTextColorLight = p.neutral1(900),
            keyTextColorDark = p.neutral1(100),
            candidateTextColorLight = p.accent1(700),
            candidateTextColorDark = accentDark,
            candidateSelectedTextColorLight = accentLight,
            candidateSelectedTextColorDark = accentDark,
            dividerColorLight = p.neutral2(100),
            dividerColorDark = p.neutral2(700),
            isDynamic = true,
        )
    }
}

/** framework 系统调色板资源读取器，按名缓存解析结果。 */
private class SystemPalette(context: Context) {
    private val appContext = context.applicationContext
    private val cache = HashMap<String, Color>()

    /** 资源缺失时的兜底色（薰衣草紫系），保证动态主题在任何设备上不崩溃。 */
    private fun resolve(resource: String, fallback: Color): Color {
        cache[resource]?.let { return it }
        val color = try {
            val id = appContext.resources.getIdentifier(resource, "color", "android")
            if (id != 0) Color(appContext.getColor(id)) else null
        } catch (_: Throwable) {
            null
        }
        return if (color != null) {
            cache[resource] = color
            color
        } else {
            fallback
        }
    }

    fun accent1(resource: Int): Color =
        resolve("system_accent1_$resource", Color(0xFF8F73E2))

    fun neutral1(resource: Int): Color =
        resolve("system_neutral1_$resource", Color(0xFF1C1B1F))

    fun neutral2(resource: Int): Color =
        resolve("system_neutral2_$resource", Color(0xFF3C4043))
}
