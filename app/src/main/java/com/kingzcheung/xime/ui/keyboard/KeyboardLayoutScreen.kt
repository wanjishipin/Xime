package com.kingzcheung.xime.ui.keyboard

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.kingzcheung.xime.keyboard.GestureAction
import com.kingzcheung.xime.keyboard.KeyboardRoute
import com.kingzcheung.xime.settings.KeysConfigHelper
import com.kingzcheung.xime.ui.theme.KeyboardThemes
import com.kingzcheung.xime.viewmodel.KeyboardUiState
import com.kingzcheung.xime.viewmodel.KeyboardViewModel

@Composable
fun KeyboardLayoutScreen(
    keyboardState: KeyboardLayoutState,
    uiState: KeyboardUiState,
    viewModel: KeyboardViewModel,
    callbacks: KeyboardCallbacks,
    onKeyPress: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val kbColors = KeysConfigHelper.getKeyboardColors()
    val longToColor: (Long) -> Color = { Color(0xFF000000 or it) }
    val keyboardBgColor = if (uiState.isDarkTheme) longToColor(kbColors.keyboardBgColorDark) else longToColor(kbColors.keyboardBgColor)
    val themeSpecialKeyColor = KeyboardThemes.getSpecialKeyColor(uiState.themeId, uiState.isDarkTheme)
    val keyBgColor = if (uiState.isDarkTheme) longToColor(kbColors.keyBgColorDark) else longToColor(kbColors.keyBgColor)
    val keyTextColor = if (uiState.isDarkTheme) longToColor(kbColors.keyTextColorDark) else longToColor(kbColors.keyTextColor)
    val specialKeyBgColor = if (uiState.isDarkTheme) kbColors.specialKeyBgColorDark?.let { longToColor(it) }
        ?: themeSpecialKeyColor else kbColors.specialKeyBgColor?.let { longToColor(it) } ?: themeSpecialKeyColor
    val kbShadow = KeysConfigHelper.getKeyboardShadow()

    val onGestureAction: (GestureAction, String) -> Unit = { action, value ->
        when (action) {
            GestureAction.SWITCH_ROUTE -> {
                val route = when (value) {
                    "emoji" -> KeyboardRoute.Emoji
                    "symbol" -> KeyboardRoute.Symbol
                    else -> null
                }
                if (route != null) viewModel.setRoute(route)
            }
            GestureAction.TOGGLE_ASCII -> {
                viewModel.resetShift()
                callbacks.onKeyPress("ime_switch", uiState.isAsciiMode)
            }
            else -> callbacks.onGestureAction?.invoke(action, value) ?: Unit
        }
    }

    when (keyboardState) {
        is KeyboardLayoutState.Chinese -> {
            KeyboardLayout(
                onKeyPress = onKeyPress,
                viewModel = viewModel,
                callbacks = callbacks,
                uiState = uiState,
                isAsciiMode = false,
                modifier = modifier,
            )
        }

        is KeyboardLayoutState.English -> {
            KeyboardLayout(
                onKeyPress = onKeyPress,
                viewModel = viewModel,
                callbacks = callbacks,
                uiState = uiState,
                isAsciiMode = true,
                modifier = modifier,
            )
        }

        is KeyboardLayoutState.Number -> {
            NumberKeyboardLayout(
                onKeyPress = onKeyPress,
                keyBackgroundColor = keyBgColor,
                keyTextColor = keyTextColor,
                specialKeyBackgroundColor = specialKeyBgColor,
                keyboardBackgroundColor = keyboardBgColor,
                shadowEnabled = kbShadow.enabled,
                shadowElevation = kbShadow.elevation.dp,
                shadowShapeRadius = kbShadow.shapeRadius.dp,
                modifier = modifier,
                onKeyPressDown = callbacks.onKeyPressDown,
                isFloatingMode = uiState.isFloatingMode,
            )
        }

        is KeyboardLayoutState.CommonSymbol -> {
            CommonSymbolKeyboardLayout(
                onKeyPress = onKeyPress,
                isAsciiMode = uiState.isAsciiMode,
                keyBackgroundColor = keyBgColor,
                keyTextColor = keyTextColor,
                specialKeyBackgroundColor = specialKeyBgColor,
                keyboardBackgroundColor = keyboardBgColor,
                shadowEnabled = kbShadow.enabled,
                shadowElevation = kbShadow.elevation.dp,
                shadowShapeRadius = kbShadow.shapeRadius.dp,
                modifier = modifier,
                onKeyPressDown = callbacks.onKeyPressDown,
                isFloatingMode = uiState.isFloatingMode,
            )
        }

        is KeyboardLayoutState.Stroke -> {
            StrokeKeyboardLayout(
                onKeyPress = onKeyPress,
                keyBackgroundColor = keyBgColor,
                keyTextColor = keyTextColor,
                specialKeyBackgroundColor = specialKeyBgColor,
                keyboardBackgroundColor = keyboardBgColor,
                shadowEnabled = kbShadow.enabled,
                shadowElevation = kbShadow.elevation.dp,
                shadowShapeRadius = kbShadow.shapeRadius.dp,
                modifier = modifier,
                onKeyPressDown = callbacks.onKeyPressDown,
                isFloatingMode = uiState.isFloatingMode,
            )
        }

        is KeyboardLayoutState.Symbol -> {
            // 符号键盘已改为路由，此处不应到达
        }
    }
}
