package com.kingzcheung.xime.ui.keyboard

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kingzcheung.xime.keyboard.GestureAction
import com.kingzcheung.xime.handwriting.HandwritingCandidate
import com.kingzcheung.xime.rime.T9InputController
import com.kingzcheung.xime.service.CandidateState
import com.kingzcheung.xime.settings.KeysConfigHelper
import com.kingzcheung.xime.ui.theme.KeyboardThemes
import com.kingzcheung.xime.ui.theme.resolveSolidColor
import com.kingzcheung.xime.util.FileLogger
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
    isHandwritingLookup: Boolean = false,
    onHandwritingCandidates: ((List<HandwritingCandidate>) -> Unit)? = null,
    onHandwritingButtonFeedback: ((String) -> Unit)? = null,
    handwritingClearSignal: Int = 0,
    onHandwritingLookupExit: (() -> Unit)? = null,
    t9Controller: T9InputController? = null,
    candidateState: State<CandidateState> = remember { mutableStateOf(CandidateState()) },
) {
    var lastLoggedKb by remember { mutableStateOf<KeyboardLayoutState?>(null) }
    if (keyboardState != lastLoggedKb) {
        lastLoggedKb = keyboardState
        FileLogger.d("XimeKeyboard", "render keyboard: $keyboardState, ui ascii=${uiState.isAsciiMode}")
    }
    val kbColors = KeysConfigHelper.getKeyboardColors()
    val longToColor: (Long) -> Color = { if (it > 0xFFFFFF) Color(it) else Color(0xFF000000 or it) }
    val themeScheme = KeyboardThemes.getThemeById(uiState.themeId)
    val themeBgColor =
        themeScheme.keyboardBackground?.let { resolveSolidColor(it, uiState.isDarkTheme) }
    val keyboardBgColor = themeBgColor ?: KeyboardThemes.getKeyboardBackgroundColor(
        uiState.themeId,
        uiState.isDarkTheme
    )
    val themeSpecialKeyColor =
        KeyboardThemes.getSpecialKeyColor(uiState.themeId, uiState.isDarkTheme)
    val keyBgColor = KeyboardThemes.getKeyBgColorOverride(uiState.themeId, uiState.isDarkTheme)
        ?: if (uiState.isDarkTheme) longToColor(kbColors.keyBgColorDark) else longToColor(kbColors.keyBgColor)
    val keyTextColor = KeyboardThemes.getKeyTextColorOverride(uiState.themeId, uiState.isDarkTheme)
        ?: if (uiState.isDarkTheme) longToColor(kbColors.keyTextColorDark) else longToColor(kbColors.keyTextColor)
    val specialKeyBgColor =
        if (uiState.isDarkTheme) kbColors.specialKeyBgColorDark?.let { longToColor(it) }
            ?: themeSpecialKeyColor else kbColors.specialKeyBgColor?.let { longToColor(it) }
            ?: themeSpecialKeyColor
    val specialKeyTextColor = KeyboardThemes.getSpecialKeyTextColorForBackground(
        specialKeyBgColor, keyTextColor
    )
    val kbShadow = KeysConfigHelper.getKeyboardShadow()
    val kbKey = KeysConfigHelper.getKeyboardKeyConfig()
    val accentColor = KeyboardThemes.getAccentColor(uiState.themeId, uiState.isDarkTheme)


    val onGestureAction: (GestureAction, String) -> Unit = { action, value ->
        when (action) {
            // 面板切换映射的唯一实现（三处 UI 手势入口共用，含 clipboard）
            GestureAction.SWITCH_ROUTE -> switchRouteOverlay(value)?.let { viewModel.showOverlay(it) }

            GestureAction.TOGGLE_ASCII -> {
                FileLogger.i("XimeKeyboard", "earth key toggle_ascii tapped, ui ascii=${uiState.isAsciiMode}")
                viewModel.resetShift()
                callbacks.onKeyPress("ime_switch", uiState.isAsciiMode)
            }

            GestureAction.TOGGLE_SHIFT -> {
                viewModel.toggleShift()
            }

            else -> callbacks.onGestureAction?.invoke(action, value) ?: Unit
        }
    }

// 系统手势（如三指截图）截走触摸流时，Compose 手势检测器不会被 ACTION_CANCEL 取消，
    // 键内部的长按/按下协程会继续执行并继续上报，导致气泡/按下状态残留。
    // 这里用 key() 让整个活动键盘在 cancel（epoch 变化）时 remount：
    // 所有键的 remember 状态重置、所有进行中手势协程被 Compose 取消，彻底清干净。
    key(keyboardState) {
        key(uiState.swipeCancelEpoch) {
            when (keyboardState) {
            is KeyboardLayoutState.Chinese -> {
                if (isHandwritingLookup) {
                    HandwritingLookupKeyboard(
                        keyTextColor = keyTextColor,
                        specialKeyBgColor = specialKeyBgColor,
                        specialKeyTextColor = specialKeyTextColor,
                        keyboardBgColor = keyboardBgColor,
                        shadowEnabled = kbShadow.enabled,
                        shadowElevation = kbShadow.elevation.dp,
                        shadowShapeRadius = kbShadow.shapeRadius.dp,
                        onKeyPress = onKeyPress,
                        onButtonFeedback = onHandwritingButtonFeedback,
                        onCandidates = onHandwritingCandidates,
                        onExit = { onHandwritingLookupExit?.invoke() },
                        clearSignal = handwritingClearSignal,
                        uiState = uiState,
                        modifier = modifier,
                    )
                } else {
                    KeyboardLayout(
                        onKeyPress = onKeyPress,
                        viewModel = viewModel,
                        callbacks = callbacks,
                        uiState = uiState,
                        isAsciiMode = false,
                        modifier = modifier,
                    )
                }
            }

            is KeyboardLayoutState.English -> {
                if (isHandwritingLookup) {
                    HandwritingLookupKeyboard(
                        keyTextColor = keyTextColor,
                        specialKeyBgColor = specialKeyBgColor,
                        specialKeyTextColor = specialKeyTextColor,
                        keyboardBgColor = keyboardBgColor,
                        shadowEnabled = kbShadow.enabled,
                        shadowElevation = kbShadow.elevation.dp,
                        shadowShapeRadius = kbShadow.shapeRadius.dp,
                        onKeyPress = onKeyPress,
                        onButtonFeedback = onHandwritingButtonFeedback,
                        onCandidates = onHandwritingCandidates,
                        onExit = { onHandwritingLookupExit?.invoke() },
                        clearSignal = handwritingClearSignal,
                        uiState = uiState,
                        modifier = modifier,
                    )
                } else {
                    KeyboardLayout(
                        onKeyPress = onKeyPress,
                        viewModel = viewModel,
                        callbacks = callbacks,
                        uiState = uiState,
                        isAsciiMode = true,
                        modifier = modifier,
                    )
                }
            }

            is KeyboardLayoutState.Number -> {
                // 从全键盘（?123 在左下角）进入数字键盘时，返回键放到左下角与进入位置对齐；
                // 九键/笔画/手写进入时保持「符号键在最左下角」的九键习惯
                val lastMainLayout by viewModel.lastMainLayout.collectAsStateWithLifecycle()
                NumberKeyboardLayout(
                    onKeyPress = onKeyPress,
                    keyBackgroundColor = keyBgColor,
                    keyTextColor = keyTextColor,
                    specialKeyBackgroundColor = specialKeyBgColor,
                    bubbleBackgroundColor = themeSpecialKeyColor,
                    keyboardBackgroundColor = keyboardBgColor,
                    shadowEnabled = kbShadow.enabled,
                    shadowElevation = kbShadow.elevation.dp,
                    shadowShapeRadius = kbShadow.shapeRadius.dp,
                    keyCornerRadius = kbKey.cornerRadius.dp,
                    keySpacingX = kbKey.spacingFor("number").first?.dp,
                    keySpacingY = kbKey.spacingFor("number").second?.dp,
                    modifier = modifier,
                    onKeyPressDown = callbacks.onKeyPressDown,
                    isFloatingMode = uiState.isFloatingMode,
                    specialKeyTextColor = specialKeyTextColor,
                    backKeyOnLeft = lastMainLayout is KeyboardLayoutState.Chinese ||
                        lastMainLayout is KeyboardLayoutState.English,
                )
            }

            is KeyboardLayoutState.CommonSymbol -> {
                CommonSymbolKeyboardLayout(
                    onKeyPress = onKeyPress,
                    isAsciiMode = uiState.isAsciiMode,
                    keyBackgroundColor = keyBgColor,
                    keyTextColor = keyTextColor,
                    specialKeyBackgroundColor = specialKeyBgColor,
                    bubbleBackgroundColor = themeSpecialKeyColor,
                    keyboardBackgroundColor = keyboardBgColor,
                    shadowEnabled = kbShadow.enabled,
                    shadowElevation = kbShadow.elevation.dp,
                    shadowShapeRadius = kbShadow.shapeRadius.dp,
                    keyCornerRadius = kbKey.cornerRadius.dp,
                    keySpacingX = kbKey.spacingFor("symbol").first?.dp,
                    keySpacingY = kbKey.spacingFor("symbol").second?.dp,
                    modifier = modifier,
                    onKeyPressDown = callbacks.onKeyPressDown,
                    isFloatingMode = uiState.isFloatingMode,
                    specialKeyTextColor = specialKeyTextColor,
                )
            }

            is KeyboardLayoutState.Stroke -> {
                StrokeKeyboardLayout(
                    onKeyPress = onKeyPress,
                    keyBackgroundColor = keyBgColor,
                    keyTextColor = keyTextColor,
                    specialKeyBackgroundColor = specialKeyBgColor,
                    bubbleBackgroundColor = themeSpecialKeyColor,
                    keyboardBackgroundColor = keyboardBgColor,
                    shadowEnabled = kbShadow.enabled,
                    shadowElevation = kbShadow.elevation.dp,
                    shadowShapeRadius = kbShadow.shapeRadius.dp,
                    keyCornerRadius = kbKey.cornerRadius.dp,
                    keySpacingX = kbKey.spacingFor("stroke").first?.dp,
                    keySpacingY = kbKey.spacingFor("stroke").second?.dp,
                    modifier = modifier,
                    onKeyPressDown = callbacks.onKeyPressDown,
                    isFloatingMode = uiState.isFloatingMode,
                    specialKeyTextColor = specialKeyTextColor,
                    onGestureAction = onGestureAction,
                )
            }

            is KeyboardLayoutState.T9Pinyin -> {
                if (isHandwritingLookup) {
                    HandwritingLookupKeyboard(
                        keyTextColor = keyTextColor,
                        specialKeyBgColor = specialKeyBgColor,
                        specialKeyTextColor = specialKeyTextColor,
                        keyboardBgColor = keyboardBgColor,
                        shadowEnabled = kbShadow.enabled,
                        shadowElevation = kbShadow.elevation.dp,
                        shadowShapeRadius = kbShadow.shapeRadius.dp,
                        onKeyPress = onKeyPress,
                        onButtonFeedback = onHandwritingButtonFeedback,
                        onCandidates = onHandwritingCandidates,
                        onExit = { onHandwritingLookupExit?.invoke() },
                        clearSignal = handwritingClearSignal,
                        uiState = uiState,
                        modifier = modifier,
                    )
                } else if (t9Controller != null) {
                    T9KeyboardLayout(
                        onKeyPress = onKeyPress,
                        callbacks = callbacks,
                        uiState = uiState,
                        t9Controller = t9Controller,
                        candidateState = candidateState,
                        keyBackgroundColor = keyBgColor,
                        keyTextColor = keyTextColor,
                        specialKeyBackgroundColor = specialKeyBgColor,
                        bubbleBackgroundColor = themeSpecialKeyColor,
                        accentColor = accentColor,
                        keyboardBackgroundColor = keyboardBgColor,
                        shadowEnabled = kbShadow.enabled,
                        shadowElevation = kbShadow.elevation.dp,
                        shadowShapeRadius = kbShadow.shapeRadius.dp,
                        keyCornerRadius = kbKey.cornerRadius.dp,
                        keySpacingX = kbKey.spacingFor("t9").first?.dp,
                        keySpacingY = kbKey.spacingFor("t9").second?.dp,
                        modifier = modifier,
                        onKeyPressDown = callbacks.onKeyPressDown,
                        isFloatingMode = uiState.isFloatingMode,
                        specialKeyTextColor = specialKeyTextColor,
                        onGestureAction = onGestureAction,
                    )
                }
            }

            is KeyboardLayoutState.Symbol -> {
                // 符号键盘已改为路由，此处不应到达
            }
        }
        }
    }
}
