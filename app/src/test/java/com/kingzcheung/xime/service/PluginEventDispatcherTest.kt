package com.kingzcheung.xime.service

import android.text.InputType
import android.view.inputmethod.EditorInfo
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.mockito.junit.MockitoJUnitRunner

/**
 * PluginEventDispatcher 上屏计数（text_committed payload 基准）：
 * - 按 Unicode 代码点计数：emoji/生僻字（UTF-16 代理对）计 1 不计 2
 *   （用户反馈"复制 12 个字统计 13"的根源即 String.length 的代理对双计）
 * - 敏感输入框（密码类）不计；离开敏感框后恢复计数
 */
@RunWith(MockitoJUnitRunner::class)
class PluginEventDispatcherTest {

    private lateinit var dispatcher: PluginEventDispatcher

    @Before
    fun setup() {
        dispatcher = PluginEventDispatcher(mock(XimeInputMethodService::class.java))
    }

    private fun editorInfo(inputType: Int): EditorInfo = EditorInfo().apply { this.inputType = inputType }

    @Test
    fun `普通汉字按字数累计`() {
        dispatcher.onStartInput(editorInfo(InputType.TYPE_CLASS_TEXT))
        val text = "你好世界再见你好世界再见" // 12 个汉字
        assertEquals(12, text.length)
        dispatcher.onTextCommitted(text)
        assertEquals(12L, dispatcher.sessionCommittedCharsForTest)
    }

    @Test
    fun `emoji按一个字计数而非两个UTF16单元`() {
        dispatcher.onStartInput(editorInfo(InputType.TYPE_CLASS_TEXT))
        // 12 个用户感知字符，其中 1 个 emoji：String.length 为 13，代码点计数应为 12
        val text = "复制粘贴测试文本😊" // 8 汉字 + 1 emoji = 9
        assertEquals(10, text.length) // 验证 emoji 确实占 2 个 UTF-16 单元
        assertEquals(9, text.codePointCount(0, text.length))
        dispatcher.onTextCommitted(text)
        assertEquals(9L, dispatcher.sessionCommittedCharsForTest)
    }

    @Test
    fun `增补平面生僻字按一个字计数`() {
        dispatcher.onStartInput(editorInfo(InputType.TYPE_CLASS_TEXT))
        val text = "𠮷" // U+20BB7，String.length == 2
        assertEquals(2, text.length)
        dispatcher.onTextCommitted(text)
        assertEquals(1L, dispatcher.sessionCommittedCharsForTest)
    }

    @Test
    fun `多次提交跨emoji混合累计`() {
        dispatcher.onStartInput(editorInfo(InputType.TYPE_CLASS_TEXT))
        dispatcher.onTextCommitted("你好")
        dispatcher.onTextCommitted("👍") // 代理对
        dispatcher.onTextCommitted("abc")
        dispatcher.onTextCommitted("𠮷tera") // 𠮷=1, t/e/r/a=4
        assertEquals(2L + 1L + 3L + 5L, dispatcher.sessionCommittedCharsForTest)
    }

    @Test
    fun `敏感输入框不计数`() {
        val passwordType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        dispatcher.onStartInput(editorInfo(passwordType))
        dispatcher.onTextCommitted("secret")
        assertEquals(0L, dispatcher.sessionCommittedCharsForTest)
    }

    @Test
    fun `离开敏感输入框后恢复计数`() {
        val passwordType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        dispatcher.onStartInput(editorInfo(passwordType))
        dispatcher.onTextCommitted("secret")
        dispatcher.onStartInput(editorInfo(InputType.TYPE_CLASS_TEXT))
        dispatcher.onTextCommitted("你好")
        assertEquals(2L, dispatcher.sessionCommittedCharsForTest)
    }
}
