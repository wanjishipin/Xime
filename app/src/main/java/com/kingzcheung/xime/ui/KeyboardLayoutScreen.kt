package com.kingzcheung.xime.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.kingzcheung.xime.keyboard.GestureAction

/**
 * 键盘布局调度屏幕 — 根据 [KeyboardLayoutState] 渲染对应的键盘布局。
 *
 * 职责单一：state → layout，不处理任何按键逻辑/状态转移。
 * 按键回调（[onKeyPress]）需由调用方封装好（含 [isShifted] 处理）。
 */
@Composable
fun KeyboardLayoutScreen(
    state: KeyboardLayoutState,
    onKeyPress: (String) -> Unit,
    isShifted: Boolean,
    isAsciiMode: Boolean,
    isLandscape: Boolean,
    schemaName: String,
    enterKeyText: String,
    isDarkTheme: Boolean,
    keyBackgroundColor: Color,
    keyTextColor: Color,
    specialKeyBackgroundColor: Color,
    keyboardBackgroundColor: Color,
    modifier: Modifier = Modifier,
    onKeyPressDown: ((String) -> Unit)? = null,
    // Chinese-only 参数
    onVoiceModeChange: ((Boolean) -> Unit)? = null,
    onCommitText: ((String) -> Unit)? = null,
    isSttEnabled: Boolean = true,
    isVoiceMode: Boolean = false,
    onCursorMove: ((Int) -> Unit)? = null,
    onGestureAction: ((GestureAction, String) -> Unit)? = null,
    currentSchemaId: String = "",
) {
    when (state) {
        is KeyboardLayoutState.Chinese -> {
            KeyboardLayout(
                onKeyPress = onKeyPress,
                isShifted = isShifted,
                isLandscape = isLandscape,
                schemaName = schemaName,
                enterKeyText = enterKeyText,
                currentSchemaId = currentSchemaId,
                isDarkTheme = isDarkTheme,
                keyBackgroundColor = keyBackgroundColor,
                keyTextColor = keyTextColor,
                specialKeyBackgroundColor = specialKeyBackgroundColor,
                keyboardBackgroundColor = keyboardBackgroundColor,
                modifier = modifier,
                onVoiceModeChange = onVoiceModeChange,
                onCommitText = onCommitText,
                isSttEnabled = isSttEnabled,
                isVoiceMode = isVoiceMode,
                onKeyPressDown = onKeyPressDown,
                onCursorMove = onCursorMove,
                onGestureAction = onGestureAction,
            )
        }

        is KeyboardLayoutState.English -> {
            EnglishKeyboardLayout(
                onKeyPress = onKeyPress,
                isShifted = isShifted,
                isLandscape = isLandscape,
                enterKeyText = enterKeyText,
                isDarkTheme = isDarkTheme,
                keyBackgroundColor = keyBackgroundColor,
                keyTextColor = keyTextColor,
                specialKeyBackgroundColor = specialKeyBackgroundColor,
                keyboardBackgroundColor = keyboardBackgroundColor,
                modifier = modifier,
                onKeyPressDown = onKeyPressDown,
            )
        }

        is KeyboardLayoutState.Number -> {
            NumberKeyboardLayout(
                onKeyPress = onKeyPress,
                keyBackgroundColor = keyBackgroundColor,
                keyTextColor = keyTextColor,
                specialKeyBackgroundColor = specialKeyBackgroundColor,
                keyboardBackgroundColor = keyboardBackgroundColor,
                modifier = modifier,
                onKeyPressDown = onKeyPressDown,
            )
        }

        is KeyboardLayoutState.Symbol -> {
            // 符号键盘已改为路由，此处不应到达
        }
    }
}
