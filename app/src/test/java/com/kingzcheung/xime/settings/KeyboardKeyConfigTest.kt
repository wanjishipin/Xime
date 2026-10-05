package com.kingzcheung.xime.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class KeyboardKeyConfigTest {

    private fun parse(yamlFragment: String): KeyboardKeyConfig? {
        val fullYaml = "keyboard:\n  " + yamlFragment.replace("\n", "\n  ")
        return KeysConfigHelper.parseKeyboardKeyYamlText(fullYaml)
    }

    @Test
    fun `未配置时返回默认值`() {
        val cfg = parse("key:\n  corner_radius: 8")!!
        assertEquals(8, cfg.cornerRadius)
        assertNull(cfg.spacingX)
        assertNull(cfg.spacingY)
    }

    @Test
    fun `解析 spacing_x 和 spacing_y`() {
        val cfg = parse("key:\n  spacing_x: 3\n  spacing_y: 5.5")!!
        assertEquals(3f, cfg.spacingX!!, 0.001f)
        assertEquals(5.5f, cfg.spacingY!!, 0.001f)
        assertEquals(8, cfg.cornerRadius)
    }

    @Test
    fun `spacing 与 corner_radius 同时配置`() {
        val cfg = parse("key:\n  corner_radius: 10\n  spacing_x: 1.5\n  spacing_y: 2.25")!!
        assertEquals(10, cfg.cornerRadius)
        assertEquals(1.5f, cfg.spacingX!!, 0.001f)
        assertEquals(2.25f, cfg.spacingY!!, 0.001f)
    }

    @Test
    fun `非法数值回退为空`() {
        val cfg = parse("key:\n  spacing_x: abc\n  spacing_y: -1")!!
        assertNull(cfg.spacingX)
        assertEquals(-1f, cfg.spacingY!!, 0.001f)
    }

    @Test
    fun `无 keyboard 段时返回 null`() {
        assertNull(KeysConfigHelper.parseKeyboardKeyYamlText("style:\n  font_size: 14"))
    }

    @Test
    fun `键盘级间距覆盖优先于全局`() {
        val cfg = parse("key:\n  spacing_x: 3\n  spacing_y: 4.25\n  t9:\n    spacing_x: 1\n    spacing_y: 2")!!
        val (t9x, t9y) = cfg.spacingFor("t9")
        assertEquals(1f, t9x!!, 0.001f)
        assertEquals(2f, t9y!!, 0.001f)
        val (qwertyX, qwertyY) = cfg.spacingFor("qwerty")
        assertEquals(3f, qwertyX!!, 0.001f)
        assertEquals(4.25f, qwertyY!!, 0.001f)
    }

    @Test
    fun `未覆盖的键盘回退到全局间距`() {
        val cfg = parse("key:\n  spacing_x: 2\n  spacing_y: 4.25\n  stroke:\n    spacing_y: 3")!!
        val (strokeX, strokeY) = cfg.spacingFor("stroke")
        assertEquals(2f, strokeX!!, 0.001f)
        assertEquals(3f, strokeY!!, 0.001f)
        val (numberX, numberY) = cfg.spacingFor("number")
        assertEquals(2f, numberX!!, 0.001f)
        assertEquals(4.25f, numberY!!, 0.001f)
    }

    @Test
    fun `键盘覆盖支持内联对象格式`() {
        val cfg = parse("key:\n  spacing_x: 2\n  t9: { spacing_x: 1, spacing_y: 2 }")!!
        val (t9x, t9y) = cfg.spacingFor("t9")
        assertEquals(1f, t9x!!, 0.001f)
        assertEquals(2f, t9y!!, 0.001f)
        val (qwertyX, _) = cfg.spacingFor("qwerty")
        assertEquals(2f, qwertyX!!, 0.001f)
    }
}