package com.kingzcheung.xime.service

import android.text.InputType
import android.view.inputmethod.EditorInfo
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * EditorInfoClassifier 分类判定：
 * - 受限输入框（密码/终端/NO_SUGGESTIONS）→ isRestrictedEditor
 * - 纯数字输入框（数字/电话/日期时间）→ isNumberEditor
 * - 密码类输入框（ascii 会话决策强制英文）→ isPasswordEditor
 */
class EditorInfoClassifierTest {

    private fun editorInfo(inputType: Int): EditorInfo = EditorInfo().apply { this.inputType = inputType }

    // ── isRestrictedEditor ──

    @Test
    fun `null输入框不算受限`() {
        assertFalse(EditorInfoClassifier.isRestrictedEditor(null))
    }

    @Test
    fun `普通文本框不受限`() {
        assertFalse(
            EditorInfoClassifier.isRestrictedEditor(
                editorInfo(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_AUTO_CORRECT)
            )
        )
    }

    @Test
    fun `文本密码框受限`() {
        assertTrue(
            EditorInfoClassifier.isRestrictedEditor(
                editorInfo(
                    InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                )
            )
        )
    }

    @Test
    fun `可见密码框受限`() {
        assertTrue(
            EditorInfoClassifier.isRestrictedEditor(
                editorInfo(
                    InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                )
            )
        )
    }

    @Test
    fun `网页密码框受限`() {
        assertTrue(
            EditorInfoClassifier.isRestrictedEditor(
                editorInfo(
                    InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD
                )
            )
        )
    }

    @Test
    fun `TYPE_NULL受限_终端场景`() {
        assertTrue(EditorInfoClassifier.isRestrictedEditor(editorInfo(InputType.TYPE_NULL)))
    }

    @Test
    fun `NO_SUGGESTIONS标记受限`() {
        assertTrue(
            EditorInfoClassifier.isRestrictedEditor(
                editorInfo(
                    InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                )
            )
        )
    }

    @Test
    fun `数字密码框受限`() {
        assertTrue(
            EditorInfoClassifier.isRestrictedEditor(
                editorInfo(
                    InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
                )
            )
        )
    }

    @Test
    fun `邮箱框不受限`() {
        assertFalse(
            EditorInfoClassifier.isRestrictedEditor(
                editorInfo(
                    InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
                )
            )
        )
    }

    // ── isSecretEditor ──

    @Test
    fun `null输入框不算秘密框`() {
        assertFalse(EditorInfoClassifier.isSecretEditor(null))
    }

    @Test
    fun `普通文本框不算秘密框`() {
        assertFalse(
            EditorInfoClassifier.isSecretEditor(
                editorInfo(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_AUTO_CORRECT)
            )
        )
    }

    @Test
    fun `NO_SUGGESTIONS不算秘密框_英文联想应放行`() {
        assertFalse(
            EditorInfoClassifier.isSecretEditor(
                editorInfo(
                    InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                )
            )
        )
    }

    @Test
    fun `TYPE_NULL算秘密框`() {
        assertTrue(EditorInfoClassifier.isSecretEditor(editorInfo(InputType.TYPE_NULL)))
    }

    @Test
    fun `文本密码框算秘密框`() {
        assertTrue(
            EditorInfoClassifier.isSecretEditor(
                editorInfo(
                    InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                )
            )
        )
    }

    @Test
    fun `可见密码框算秘密框`() {
        assertTrue(
            EditorInfoClassifier.isSecretEditor(
                editorInfo(
                    InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                )
            )
        )
    }

    @Test
    fun `网页密码框算秘密框`() {
        assertTrue(
            EditorInfoClassifier.isSecretEditor(
                editorInfo(
                    InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD
                )
            )
        )
    }

    @Test
    fun `数字密码框算秘密框`() {
        assertTrue(
            EditorInfoClassifier.isSecretEditor(
                editorInfo(
                    InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
                )
            )
        )
    }

    // ── isNumberEditor ──

    @Test
    fun `null输入框不算数字框`() {
        assertFalse(EditorInfoClassifier.isNumberEditor(null))
    }

    @Test
    fun `数字类输入框识别为数字框`() {
        assertTrue(
            EditorInfoClassifier.isNumberEditor(
                editorInfo(InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL)
            )
        )
        assertTrue(EditorInfoClassifier.isNumberEditor(editorInfo(InputType.TYPE_CLASS_NUMBER)))
    }

    @Test
    fun `电话类输入框识别为数字框`() {
        assertTrue(
            EditorInfoClassifier.isNumberEditor(
                editorInfo(InputType.TYPE_CLASS_PHONE)
            )
        )
    }

    @Test
    fun `日期时间类输入框识别为数字框`() {
        assertTrue(
            EditorInfoClassifier.isNumberEditor(
                editorInfo(InputType.TYPE_CLASS_DATETIME)
            )
        )
    }

    @Test
    fun `数字密码框不算数字框`() {
        assertFalse(
            EditorInfoClassifier.isNumberEditor(
                editorInfo(
                    InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
                )
            )
        )
    }

    @Test
    fun `普通文本框不算数字框`() {
        assertFalse(EditorInfoClassifier.isNumberEditor(editorInfo(InputType.TYPE_CLASS_TEXT)))
    }

    // ── isPasswordEditor：ascii 会话决策（密码框强制英文）──

    @Test
    fun `null输入框不算密码框`() {
        assertFalse(EditorInfoClassifier.isPasswordEditor(null))
    }

    @Test
    fun `文本密码框识别为密码框`() {
        assertTrue(
            EditorInfoClassifier.isPasswordEditor(
                editorInfo(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
            )
        )
    }

    @Test
    fun `可见密码框识别为密码框`() {
        assertTrue(
            EditorInfoClassifier.isPasswordEditor(
                editorInfo(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD)
            )
        )
    }

    @Test
    fun `网页密码框识别为密码框`() {
        assertTrue(
            EditorInfoClassifier.isPasswordEditor(
                editorInfo(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD)
            )
        )
    }

    @Test
    fun `数字密码框识别为密码框`() {
        assertTrue(
            EditorInfoClassifier.isPasswordEditor(
                editorInfo(InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD)
            )
        )
    }

    @Test
    fun `TYPE_NULL不算密码框_终端场景保留中文输入`() {
        assertFalse(EditorInfoClassifier.isPasswordEditor(editorInfo(InputType.TYPE_NULL)))
    }

    @Test
    fun `普通文本与邮箱框不算密码框`() {
        assertFalse(EditorInfoClassifier.isPasswordEditor(editorInfo(InputType.TYPE_CLASS_TEXT)))
        assertFalse(
            EditorInfoClassifier.isPasswordEditor(
                editorInfo(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS)
            )
        )
    }
}
