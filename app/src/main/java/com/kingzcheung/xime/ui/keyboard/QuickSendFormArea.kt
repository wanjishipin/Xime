package com.kingzcheung.xime.ui.keyboard

import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.kingzcheung.xime.service.QuickSendFormCodeEditTextHolder
import com.kingzcheung.xime.service.QuickSendFormEditTextHolder

private val QUICK_SEND_FORM_HEIGHT = 200

@Composable
fun QuickSendFormArea(
    backgroundColor: Color,
    textColor: Color,
    accentColor: Color,
    isFocused: Boolean,
    initialText: String = "",
    initialCode: String = "",
    cardBgColor: Color,
    editingItemId: Long? = null,
    onClose: (text: String, code: String) -> Unit,
    onFocusChange: (Boolean) -> Unit,
    onCodeFocusChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val closeButtonBg = androidx.compose.ui.graphics.lerp(
        MaterialTheme.colorScheme.surface,
        MaterialTheme.colorScheme.primary,
        0.35f
    )
    val title = if (editingItemId != null) "编辑快捷发送" else "添加快捷发送"

    CandidateBarOverlayPanel(
        heightDp = QUICK_SEND_FORM_HEIGHT,
        backgroundColor = backgroundColor,
        cardBgColor = cardBgColor,
        closeButtonBg = closeButtonBg,
        closeButtonColor = accentColor,
        title = title,
        titleColor = textColor,
        modifier = modifier
            .onGloballyPositioned {
                android.util.Log.d("QuickSendForm", "formArea bounds=${it.boundsInWindow()} size=${it.size}")
            }
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    android.util.Log.d("QuickSendForm", "formTouch pos=${down.position}")
                }
            },
        onCloseClick = {
            android.util.Log.d("QuickSendForm", "closeClick: holder=${QuickSendFormEditTextHolder.editText != null}")
            val et = QuickSendFormEditTextHolder.editText
            val codeEt = QuickSendFormCodeEditTextHolder.editText
            onClose(et?.text?.toString() ?: "", codeEt?.text?.toString() ?: "")
        },
    ) {
        androidx.compose.foundation.layout.Column(modifier = Modifier.fillMaxSize()) {
            AndroidView(
                factory = { context ->
                    android.widget.EditText(context).apply {
                        setBackgroundColor(android.graphics.Color.TRANSPARENT)
                        setTextColor(textColor.hashCode())
                        setHintTextColor((textColor.copy(alpha = 0.4f)).hashCode())
                        hint = "输入快捷发送内容"
                        textSize = 16f
                        isSingleLine = false
                        gravity = Gravity.TOP or Gravity.START
                        setPadding(12, 8, 12, 8)

                        setImeActionLabel("确定", EditorInfo.IME_ACTION_DONE)
                        imeOptions = EditorInfo.IME_FLAG_NO_ENTER_ACTION or
                            EditorInfo.IME_ACTION_DONE

                        onFocusChangeListener = View.OnFocusChangeListener { _, hasFocus ->
                            onFocusChange(hasFocus)
                        }
                        setOnClickListener {
                            onFocusChange(true)
                        }
                        QuickSendFormEditTextHolder.editText = this
                        if (isFocused) {
                            post { requestFocus() }
                        }
                    }
                },
                update = { editText ->
                    if (initialText.isNotEmpty() && !editText.text.toString().equals(initialText)) {
                        editText.setText(initialText)
                        editText.setSelection(initialText.length)
                    }
                },
                modifier = Modifier
                    .fillMaxSize()
                    .weight(1f),
            )
            AndroidView(
                factory = { context ->
                    android.widget.EditText(context).apply {
                        setBackgroundColor(android.graphics.Color.TRANSPARENT)
                        setTextColor(textColor.hashCode())
                        setHintTextColor((textColor.copy(alpha = 0.4f)).hashCode())
                        hint = "触发编码（选填，如 dh）"
                        textSize = 14f
                        isSingleLine = true
                        gravity = Gravity.START or Gravity.CENTER_VERTICAL
                        setPadding(12, 4, 12, 4)

                        setImeActionLabel("确定", EditorInfo.IME_ACTION_DONE)
                        imeOptions = EditorInfo.IME_FLAG_NO_ENTER_ACTION or
                            EditorInfo.IME_ACTION_DONE

                        // 焦点回调 + 点击抢焦点：宿主按键输入按焦点路由（文本框 vs 编码框）
                        onFocusChangeListener = View.OnFocusChangeListener { _, hasFocus ->
                            if (hasFocus) onCodeFocusChange(true) else onCodeFocusChange(false)
                        }
                        setOnClickListener {
                            requestFocus()
                            onCodeFocusChange(true)
                        }
                        QuickSendFormCodeEditTextHolder.editText = this
                    }
                },
                update = { editText ->
                    if (initialCode.isNotEmpty() && !editText.text.toString().equals(initialCode)) {
                        editText.setText(initialCode)
                        editText.setSelection(initialCode.length)
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
