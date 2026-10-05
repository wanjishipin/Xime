package com.kingzcheung.xime.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 验证 keyboard.* 配置的字段级一路 fallback 合并：
 * xime.custom.yaml → xime.yaml → 代码默认值。
 */
class KeyboardConfigFallbackTest {

    // ── fonts ──

    @Test
    fun `fonts 段整体为 null 时不抛异常并返回空`() {
        val partial = KeysConfigHelper.parseKeyboardFontsYamlText(
            "keyboard:\n  fonts:\n    # key_font: \"x\"\n    # comment_font: \"y\"\n"
        )
        assertNull(partial)
    }

    @Test
    fun `fonts 只配置部分字段时仅显式字段非 null`() {
        val partial = KeysConfigHelper.parseKeyboardFontsYamlText(
            "keyboard:\n  fonts:\n    comment_font: \"rime/fonts/a.ttf\"\n"
        )
        assertNull(partial?.keyFont)
        assertNull(partial?.keyLabelFont)
        assertNull(partial?.candidateFont)
        assertEquals("rime/fonts/a.ttf", partial?.commentFont)
    }

    @Test
    fun `mergeFontConfigs custom 覆盖 builtIn`() {
        val builtIn = KeyboardFontPartial(keyFont = "builtin.ttf", commentFont = "bc.ttf")
        val custom = KeyboardFontPartial(commentFont = "custom.ttf")
        val merged = KeysConfigHelper.mergeFontConfigs(custom, builtIn)
        assertEquals("builtin.ttf", merged.keyFont)
        assertEquals("custom.ttf", merged.commentFont)
    }

    @Test
    fun `mergeFontConfigs custom 空字符串不覆盖 builtIn`() {
        val builtIn = KeyboardFontPartial(keyFont = "builtin.ttf")
        val custom = KeyboardFontPartial(keyFont = "")
        val merged = KeysConfigHelper.mergeFontConfigs(custom, builtIn)
        assertEquals("builtin.ttf", merged.keyFont)
    }

    @Test
    fun `mergeFontConfigs 双层都未配置时回落代码默认空字符串`() {
        val merged = KeysConfigHelper.mergeFontConfigs(null, null)
        assertEquals("", merged.keyFont)
        assertEquals("", merged.commentFont)
    }

    @Test
    fun `mergeFontConfigs blank 字符串视为未配置`() {
        val builtIn = KeyboardFontPartial(keyLabelFont = " ")
        val merged = KeysConfigHelper.mergeFontConfigs(null, builtIn)
        assertEquals("", merged.keyLabelFont)
    }

    // ── tiered 通用辅助 ──

    @Test
    fun `tiered 取第一个非 null`() {
        assertEquals(3, tiered(custom = null, builtIn = null, default = 3))
        assertEquals(2, tiered(custom = null, builtIn = 2, default = 3))
        assertEquals(1, tiered(custom = 1, builtIn = 2, default = 3))
    }

    // ── colors ──

    @Test
    fun `colors 只配置一部分时按字段 fallback`() {
        val builtIn = KeysConfigHelper.parseKeyboardColorsYamlText(
            "keyboard:\n  colors:\n    key_bg_color: \"0x111111\"\n    key_text_color: \"0x222222\"\n"
        )
        val custom = KeysConfigHelper.parseKeyboardColorsYamlText(
            "keyboard:\n  colors:\n    key_bg_color: \"0x333333\"\n"
        )
        val merged = KeysConfigHelper.mergeColorsConfigs(custom, builtIn)
        assertEquals(0x333333, merged.keyBgColor)
        assertEquals(0x222222, merged.keyTextColor)
        // 双层都未配置的颜色回落代码默认值
        assertEquals(0x8AB4F8, merged.candidateTextColorDark)
    }

    @Test
    fun `colors 段整体为 null 时返回 null 而非崩溃`() {
        val partial = KeysConfigHelper.parseKeyboardColorsYamlText(
            "keyboard:\n  colors:\n    # key_bg_color: \"0x111111\"\n"
        )
        assertNull(partial)
    }

    @Test
    fun `colors 非法值按未配置处理`() {
        val partial = KeysConfigHelper.parseKeyboardColorsYamlText(
            "keyboard:\n  colors:\n    key_bg_color: \"not-a-color\"\n"
        )
        assertNull(partial?.keyBgColor)
    }

    // ── shadow ──

    @Test
    fun `shadow 只配置一部分时按字段 fallback`() {
        val builtIn = KeysConfigHelper.parseKeyboardShadowYamlText(
            "keyboard:\n  shadow:\n    enabled: true\n    elevation: 4\n    shape_radius: 12\n"
        )
        val custom = KeysConfigHelper.parseKeyboardShadowYamlText(
            "keyboard:\n  shadow:\n    elevation: 6\n"
        )
        val merged = KeysConfigHelper.mergeShadowConfigs(custom, builtIn)
        assertTrue(merged.enabled)
        assertEquals(6, merged.elevation)
        assertEquals(12, merged.shapeRadius)
    }

    @Test
    fun `shadow 未配置时回落代码默认值`() {
        val merged = KeysConfigHelper.mergeShadowConfigs(null, null)
        assertTrue(merged.enabled)
        assertEquals(1, merged.elevation)
        assertEquals(8, merged.shapeRadius)
    }

    // ── key ──

    @Test
    fun `parseKeyboardKeyYamlText 未配置时返回默认 cornerRadius`() {
        val cfg = KeysConfigHelper.parseKeyboardKeyYamlText("keyboard:\n  key:\n    spacing_x: 3\n")!!
        assertEquals(8, cfg.cornerRadius)
        assertEquals(3f, cfg.spacingX!!, 0.001f)
    }

    @Test
    fun `key 间距覆盖字段级 fallback`() {
        val builtIn = KeyboardKeyPartial(
            spacingOverrides = mapOf(
                "t9" to KeyboardSpacingConfig(spacingX = 2f, spacingY = 4f)
            )
        )
        val custom = KeyboardKeyPartial(
            spacingOverrides = mapOf(
                "t9" to KeyboardSpacingConfig(spacingX = 3f, spacingY = null)
            )
        )
        val merged = KeysConfigHelper.mergeKeyConfigs(custom, builtIn)
        val t9 = merged.spacingOverrides["t9"]!!
        assertEquals(3f, t9.spacingX!!, 0.001f)
        assertEquals(4f, t9.spacingY!!, 0.001f)
    }
}