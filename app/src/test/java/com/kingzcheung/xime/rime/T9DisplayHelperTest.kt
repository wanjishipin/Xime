package com.kingzcheung.xime.rime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T9 编码显示状态构建测试。
 *
 * 锁定核心语义：编码区永远不显示原始数字串——preedit 缺失时显示空
 * （字母保留由调用方负责），而非回退 inputText。
 */
class T9DisplayHelperTest {

    @Test
    fun `正常路径 - preedit 字母编码直接显示`() {
        val state = buildT9DisplayState(
            partialTexts = emptyList(),
            preeditText = "huange",
            inputText = "482643",
            candidates = listOf("换个"),
            comments = listOf("huan ge"),
        )
        assertEquals("huange", state.displayText)
        assertTrue(state.isComposing)
    }

    @Test
    fun `preedit 缺失 - 不回退显示原始数字 input`() {
        val state = buildT9DisplayState(
            partialTexts = emptyList(),
            preeditText = "",
            inputText = "4826435",
            candidates = listOf("广东了"),
            comments = listOf("guang dong le"),
        )
        assertEquals("", state.displayText)
        assertTrue("4826435".none { it in state.displayText })
        assertTrue(state.isComposing)
    }

    @Test
    fun `preedit 含未转换数字 - 视为无效编码不显示（切不出词时引擎回退原始输入）`() {
        val state = buildT9DisplayState(
            partialTexts = emptyList(),
            preeditText = "4826435",
            inputText = "4826435",
            candidates = emptyList(),
            comments = emptyList(),
        )
        assertEquals("", state.displayText)
        assertFalse(state.displayText.any { it.isDigit() })
        assertTrue(state.isComposing)
    }

    @Test
    fun `preedit 混合字母与数字 - 数字部分不显示`() {
        val state = buildT9DisplayState(
            partialTexts = emptyList(),
            preeditText = "huange5",
            inputText = "4826435",
            candidates = listOf("换个"),
            comments = listOf("huan ge"),
        )
        assertFalse(state.displayText.any { it.isDigit() })
    }

    @Test
    fun `preedit 与 input 均空 - 非合成态`() {
        val state = buildT9DisplayState(
            partialTexts = emptyList(),
            preeditText = "",
            inputText = "",
            candidates = emptyList(),
            comments = emptyList(),
        )
        assertEquals("", state.displayText)
        assertFalse(state.isComposing)
    }

    @Test
    fun `RightCommit 展示态 - partial 拼接显示已提交文本`() {
        val state = buildT9DisplayState(
            partialTexts = listOf("可恨"),
            preeditText = "",
            inputText = "3",
            candidates = emptyList(),
            comments = emptyList(),
        )
        assertEquals("可恨", state.displayText)
        assertTrue(state.isComposing)
    }

    @Test
    fun `partial 与 preedit 并存 - 合并显示`() {
        val state = buildT9DisplayState(
            partialTexts = listOf("可恨"),
            preeditText = "e",
            inputText = "3",
            candidates = listOf("恶"),
            comments = listOf("e"),
        )
        assertTrue(state.displayText.contains("可恨"))
        assertTrue(state.displayText.contains("e"))
        assertFalse(state.displayText.any { it.isDigit() })
    }
}
