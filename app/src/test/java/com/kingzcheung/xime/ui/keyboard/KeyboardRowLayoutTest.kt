package com.kingzcheung.xime.ui.keyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 键盘行规范化测试：rows 行数动态化（4 行标准 / 5 行含数字行），
 * 以及横屏纯字母布局的 shift/delete 注入。
 */
class KeyboardRowLayoutTest {

    private val controlRow = listOf("mode_change", "comma", "space", "earth", "enter")
    private val digitRow = listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "0")

    @Test
    fun `四行配置原样保留`() {
        val rows = normalizeQwertyRows(
            listOf(
                listOf("q", "w", "e", "r", "t", "y", "u", "i", "o", "p"),
                listOf("a", "s", "d", "f", "g", "h", "j", "k", "l"),
                listOf("shift", "z", "x", "c", "v", "b", "n", "m", "delete"),
                controlRow,
            )
        )
        assertEquals(4, rows.size)
        assertEquals(controlRow, rows[3])
    }

    @Test
    fun `五行配置（含数字行）原样保留`() {
        val rows = normalizeQwertyRows(
            listOf(
                digitRow,
                listOf("q", "w", "e", "r", "t", "y", "u", "i", "o", "p"),
                listOf("a", "s", "d", "f", "g", "h", "j", "k", "l"),
                listOf("shift", "z", "x", "c", "v", "b", "n", "m", "delete"),
                controlRow,
            )
        )
        assertEquals(5, rows.size)
        assertEquals(digitRow, rows[0])
        assertEquals(controlRow, rows[4])
    }

    @Test
    fun `不足四行时补出内置默认控制行`() {
        val rows = normalizeQwertyRows(
            listOf(
                listOf("q", "w", "e", "r", "t", "y", "u", "i", "o", "p"),
                listOf("a", "s", "d", "f", "g", "h", "j", "k", "l"),
                listOf("z", "x", "c", "v", "b", "n", "m"),
            )
        )
        assertEquals(4, rows.size)
        assertEquals(controlRow, rows[3])
    }

    @Test
    fun `超过最大行数的行被忽略`() {
        val six = (1..6).map { listOf("k$it") }
        assertEquals(MAX_QWERTY_ROWS, normalizeQwertyRows(six).size)
    }

    @Test
    fun `空配置回退内置默认行`() {
        val rows = normalizeQwertyRows(emptyList())
        assertEquals(4, rows.size)
    }

    @Test
    fun `横屏纯字母布局在最后一个字母行补 shift 与 delete`() {
        val rows = landscapeQwertyRows(
            listOf(
                listOf("q", "w", "e", "r", "t", "y", "u", "i", "o", "p"),
                listOf("a", "s", "d", "f", "g", "h", "j", "k", "l"),
                listOf("z", "x", "c", "v", "b", "n", "m"),
            )
        )
        assertEquals(4, rows.size)
        assertEquals("shift", rows[2].first())
        assertEquals("delete", rows[2].last())
    }

    @Test
    fun `横屏已含功能键的布局不注入`() {
        val source = listOf(
            digitRow,
            listOf("q", "w", "e", "r", "t", "y", "u", "i", "o", "p"),
            listOf("a", "s", "d", "f", "g", "h", "j", "k", "l"),
            listOf("shift", "z", "x", "c", "v", "b", "n", "m", "delete"),
            controlRow,
        )
        val rows = landscapeQwertyRows(source)
        assertEquals(source, rows)
        assertTrue(rows[0].first() == "1")
    }

    // ── 横向滑动接管（左右滑 vs 父层光标手势）──

    @Test
    fun `未配置左右滑的键不接管横向滑动`() {
        assertFalse(
            shouldSuppressCursorMove(
                hasHorizontalSwipe = false,
                dragOffsetX = 100f, dragOffsetY = 2f, horizontalThreshold = 30f,
            )
        )
    }

    @Test
    fun `配置左右滑且横向主导越过阈值时接管`() {
        assertTrue(
            shouldSuppressCursorMove(
                hasHorizontalSwipe = true,
                dragOffsetX = -40f, dragOffsetY = 5f, horizontalThreshold = 30f,
            )
        )
    }

    @Test
    fun `配置左右滑但纵向主导时不接管`() {
        assertFalse(
            shouldSuppressCursorMove(
                hasHorizontalSwipe = true,
                dragOffsetX = 40f, dragOffsetY = -80f, horizontalThreshold = 30f,
            )
        )
    }

    @Test
    fun `配置左右滑但未越过接管阈值时不接管`() {
        assertFalse(
            shouldSuppressCursorMove(
                hasHorizontalSwipe = true,
                dragOffsetX = 20f, dragOffsetY = 1f, horizontalThreshold = 30f,
            )
        )
    }
}