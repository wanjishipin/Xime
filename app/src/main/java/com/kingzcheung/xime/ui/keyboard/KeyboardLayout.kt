package com.kingzcheung.xime.ui.keyboard

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Backspace
import androidx.compose.material.icons.filled.EmojiEmotions
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.twotone.EmojiEmotions
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import com.kingzcheung.xime.settings.SettingsPreferences
import com.kingzcheung.xime.settings.DisplayMode
import com.kingzcheung.xime.settings.KeysConfigHelper
import com.kingzcheung.xime.keyboard.GestureAction
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kingzcheung.xime.keyboard.KeyboardDimensions
import com.kingzcheung.xime.util.PermissionHelper
import com.kingzcheung.xime.util.CharInfo
import com.kingzcheung.xime.util.SubcharHelper
import com.kingzcheung.xime.viewmodel.KeyboardUiState
import com.kingzcheung.xime.viewmodel.KeyboardViewModel
import com.kingzcheung.xime.keyboard.KeyboardRoute
import com.kingzcheung.xime.ui.theme.KeyboardThemes
import androidx.compose.ui.platform.LocalConfiguration
import android.content.res.Configuration
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.TextUnit

@Composable
fun KeyboardLayout(
    onKeyPress: (String) -> Unit,
    viewModel: KeyboardViewModel,
    callbacks: KeyboardCallbacks,
    uiState: KeyboardUiState,
    modifier: Modifier = Modifier,
) {
    val isShifted by viewModel.isShifted.collectAsStateWithLifecycle()
    val isLandscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE

    val context = LocalContext.current
    val kbColors = KeysConfigHelper.getKeyboardColors()
    val longToColor: (Long) -> Color = { Color(0xFF000000 or it) }
    val keyboardBackgroundColor = if (uiState.isDarkTheme) longToColor(kbColors.keyboardBgColorDark) else longToColor(kbColors.keyboardBgColor)
    val themeSpecialKeyColor = KeyboardThemes.getSpecialKeyColor(uiState.themeId, uiState.isDarkTheme)
    val keyBackgroundColor = if (uiState.isDarkTheme) longToColor(kbColors.keyBgColorDark) else longToColor(kbColors.keyBgColor)
    val keyTextColor = if (uiState.isDarkTheme) longToColor(kbColors.keyTextColorDark) else longToColor(kbColors.keyTextColor)
    val specialKeyBackgroundColor = if (uiState.isDarkTheme) kbColors.specialKeyBgColorDark?.let { longToColor(it) }
        ?: themeSpecialKeyColor else kbColors.specialKeyBgColor?.let { longToColor(it) } ?: themeSpecialKeyColor
    val kbShadow = KeysConfigHelper.getKeyboardShadow()
    val shadowEnabled = kbShadow.enabled
    val shadowElevation = kbShadow.elevation.dp
    val shadowShapeRadius = kbShadow.shapeRadius.dp
    val schemaName = uiState.schemaName
    val enterKeyText = uiState.enterKeyText
    val isDarkTheme = uiState.isDarkTheme
    val isSttEnabled = uiState.isSttEnabled
    val isVoiceMode = uiState.isVoiceMode
    val onKeyPressDown = callbacks.onKeyPressDown
    val onVoiceModeChange = callbacks.onVoiceModeChange
    val onCommitText = callbacks.onCommitText
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
                callbacks.onKeyPress("ime_switch", uiState.isAsciiMode)
            }
            else -> callbacks.onGestureAction?.invoke(action, value) ?: Unit
        }
    }
    val suppressCursorMove = LocalSuppressCursorMove.current
    var swipeUpHintsEnabled by remember {
        mutableStateOf(
            SettingsPreferences.isSwipeUpHintsEnabled(
                context
            )
        )
    }
    var swipeDownHintsEnabled by remember {
        mutableStateOf(
            SettingsPreferences.isSwipeDownHintsEnabled(
                context
            )
        )
    }

    // 监听设置变化
    DisposableEffect(context) {
        val prefs = SettingsPreferences.getPrefsPublic(context)
        val listener =
            android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
                when (key) {
                    SettingsPreferences.KEY_SWIPE_UP_HINTS_ENABLED ->
                        swipeUpHintsEnabled = SettingsPreferences.isSwipeUpHintsEnabled(context)

                    SettingsPreferences.KEY_SWIPE_DOWN_HINTS_ENABLED ->
                        swipeDownHintsEnabled = SettingsPreferences.isSwipeDownHintsEnabled(context)
                }
            }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }

    LaunchedEffect(Unit) {
        SubcharHelper.init(context)
    }

    var swipeState by remember { mutableStateOf(SwipeState()) }
    var keyboardBounds by remember { mutableStateOf(Rect(0f, 0f, 0f, 0f)) }
    var lastKeyBounds by remember { mutableStateOf(Rect(0f, 0f, 0f, 0f)) }

    // 监听手势配置版本号，部署后强制刷新键帽显示
    val cfgVer by KeysConfigHelper.configVersion.collectAsState()

    fun processSwipeState(state: SwipeState, bounds: Rect) {
        val newState = if (state.isSwipeDown && state.swipeText != null) {
            state.copy(charInfos = SubcharHelper.parseSwipeDownText(state.swipeText))
        } else {
            state
        }
        swipeState = newState

        lastKeyBounds = Rect(
            left = bounds.left - keyboardBounds.left,
            top = bounds.top - keyboardBounds.top,
            right = bounds.right - keyboardBounds.left,
            bottom = bounds.bottom - keyboardBounds.top
        )
    }

    val bubbleData = rememberSwipeBubbleDrawData(
        swipeState = swipeState,
        keyBounds = lastKeyBounds,
        isDarkTheme = isDarkTheme,
        keyWidth = if (swipeState.isSwiping || swipeState.isPressed) lastKeyBounds.width else 0f,
        keyboardWidth = keyboardBounds.width
    )

    Box(
        modifier = modifier
            .background(keyboardBackgroundColor)
            .onGloballyPositioned { coordinates ->
                keyboardBounds = coordinates.boundsInRoot()
            }
            .drawWithContent {
                drawContent()
                bubbleData?.let { drawSwipeBubble(it) }
            }
    ) {
        if (isLandscape) {
            LandscapeKeyboardContent(
                onKeyPress = onKeyPress,
                viewModel = viewModel,
                callbacks = callbacks,
                uiState = uiState,
                swipeUpHintsEnabled = swipeUpHintsEnabled,
                swipeDownHintsEnabled = swipeDownHintsEnabled,
                onSwipeStateChange = { state, bounds -> processSwipeState(state, bounds) },
            )
        } else {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight()
                    .background(keyboardBackgroundColor)
                    .padding(vertical = 8.dp, horizontal = 4.dp)
            ) {

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    verticalArrangement = Arrangement.spacedBy(KeyboardDimensions.RowSpacing),
                ) {
                    // 第一行
                    if (isVoiceMode) {
                        Box(modifier = Modifier.weight(1f)) {
                            DummyKeyboardRow(
                                keysCount = 10,
                                keyBackgroundColor = keyBackgroundColor.copy(alpha = 0.5f),
                                keyboardBackgroundColor = keyboardBackgroundColor
                            )
                        }
                    } else {
                        Box(modifier = Modifier.weight(1f)) {
                            KeyboardRowWithConfig(
                                keys = listOf("q", "w", "e", "r", "t", "y", "u", "i", "o", "p"),
                                onKeyPress = onKeyPress,
                                config = KeyboardRowConfig(
                                    keyBackgroundColor = keyBackgroundColor,
                                    keyTextColor = keyTextColor,
                                    keyboardBackgroundColor = keyboardBackgroundColor,
                                ),
                                isShifted = isShifted,
                                onSwipeStateChange = { state, bounds ->
                                    processSwipeState(
                                        state,
                                        bounds
                                    )
                                },
                                onKeyPressDown = onKeyPressDown,
                                swipeDownHintsEnabled = swipeDownHintsEnabled,
                                swipeUpHintsEnabled = swipeUpHintsEnabled,
                                onCommitText = onCommitText,
                                configVersion = cfgVer,
                            )
                        }
                    }

                    // 第二行
                    if (isVoiceMode) {
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .padding(horizontal = 16.dp)
                        ) {
                            DummyKeyboardRow(
                                keysCount = 9,
                                keyBackgroundColor = keyBackgroundColor.copy(alpha = 0.5f),
                                keyboardBackgroundColor = keyboardBackgroundColor
                            )
                        }
                    } else {
                        Box(modifier = Modifier.weight(1f)) {
                            KeyboardRowWithConfig(
                                keys = listOf("a", "s", "d", "f", "g", "h", "j", "k", "l"),
                                onKeyPress = onKeyPress,
                                config = KeyboardRowConfig(
                                    keyBackgroundColor = keyBackgroundColor,
                                    keyTextColor = keyTextColor,
                                    keyboardBackgroundColor = keyboardBackgroundColor,
                                ),
                                isShifted = isShifted,
                                modifier = Modifier.padding(horizontal = 16.dp),
                                onSwipeStateChange = { state, bounds ->
                                    processSwipeState(
                                        state,
                                        bounds
                                    )
                                },
                                onKeyPressDown = onKeyPressDown,
                                swipeDownHintsEnabled = swipeDownHintsEnabled,
                                swipeUpHintsEnabled = swipeUpHintsEnabled,
                                onCommitText = onCommitText,
                                configVersion = cfgVer,
                            )
                        }
                    }

                    // 第三行
                    if (isVoiceMode) {
                        Box(modifier = Modifier.weight(1f)) {
                            DummyBottomRow(
                                keyBackgroundColor = keyBackgroundColor.copy(alpha = 0.5f),
                                specialKeyBackgroundColor = specialKeyBackgroundColor.copy(alpha = 0.5f),
                                keyboardBackgroundColor = keyboardBackgroundColor
                            )
                        }
                    } else {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f)
                                .fillMaxHeight()
                                .background(keyboardBackgroundColor),
                        ) {
                            Row3KeyButton(
                                icon = rememberVectorPainter(Icons.TwoTone.EmojiEmotions),
                                onKeyPress = onKeyPress,
                                onGestureAction = onGestureAction,
                                onKeyPressDown = onKeyPressDown,
                                backgroundColor = specialKeyBackgroundColor,
                                iconColor = keyTextColor,
                                modifier = Modifier
                                    .weight(1.4f)
                                    .fillMaxHeight(),
                                shadowEnabled = shadowEnabled,
                                shadowElevation = shadowElevation,
                                shadowShapeRadius = shadowShapeRadius,
                            )

                                Row(
                                    modifier = Modifier
                                        .weight(7f)
                                        .fillMaxHeight()
                                        .background(keyboardBackgroundColor),
                                ) {
                                val bottomKeys = listOf("z", "x", "c", "v", "b", "n", "m")
                                bottomKeys.forEach { key ->
                                    val rawSwipeUpText = KeysConfigHelper.getSwipeUpText(key)
                                    val swipeUpText =
                                        if (swipeUpHintsEnabled) rawSwipeUpText else null
                                    val swipeUpAction = KeysConfigHelper.getSwipeUpAction(key)
                                    val swipeUpDisplay = KeysConfigHelper.getSwipeUpDisplay(key)
                                    val swipeUpKeyLabel =
                                        if (swipeUpDisplay != DisplayMode.BUBBLE && swipeUpHintsEnabled) swipeUpText else null
                                    val swipeDownRaw =
                                        KeysConfigHelper.getKeyGesture(key)?.swipeDown
                                    val swipeDownLabel =
                                        swipeDownRaw?.label?.takeIf { it.isNotEmpty() }
                                    val swipeDownAction = swipeDownRaw?.action
                                    val swipeDownValue = swipeDownRaw?.value
                                    val swipeDownDisplay = swipeDownRaw?.display ?: DisplayMode.BOTH
                                    val swipeDownBubbleText = if (swipeDownDisplay != DisplayMode.KEY) swipeDownLabel else null
                                    val longPressConfig =
                                        KeysConfigHelper.getKeyGesture(key)?.longPress
                                    val longPressDisplay = longPressConfig?.display ?: "key"
                                    val longPressLabels = if (longPressDisplay == "bubble") {
                                        longPressConfig?.values?.map { it.label }
                                            ?.filter { it.isNotEmpty() }?.ifEmpty { null }
                                    } else null
                                    val longPressGestureMap = if (longPressDisplay == "bubble") {
                                        longPressConfig?.values?.associateBy { it.label }
                                    } else null

                                    val displayText = KeysConfigHelper.getKeyDisplayLabel(key)
                                    val commitValue = KeysConfigHelper.getKeyCommitValue(key)

                                    val onClick = remember(key, commitValue, onKeyPress) { { onKeyPress(commitValue) } }
                                    val onPress: (() -> Unit)? = remember(key, onKeyPressDown) { { onKeyPressDown?.invoke(key); Unit } }
                                    val onSwipeDown = if (swipeDownAction != null && swipeDownLabel != null) {
                                        remember(key, onKeyPress, onGestureAction, onCommitText, swipeDownAction, swipeDownValue, swipeDownLabel) {
                                            val label = swipeDownLabel
                                            { _: String ->
                                                if (swipeDownAction == GestureAction.COMMIT) {
                                                    (onCommitText ?: onKeyPress)(swipeDownValue?.ifEmpty { label } ?: label)
                                                } else {
                                                    onGestureAction?.invoke(
                                                        swipeDownAction,
                                                        swipeDownValue?.ifEmpty { label } ?: label)
                                                }
                                                Unit
                                            }
                                        }
                                    } else null
                                    val onSwipeStateChange = remember(key) { { state: SwipeState, bounds: Rect -> processSwipeState(state, bounds) } }
                                    val onLongPressSelect: ((String) -> Unit)? = remember(key, longPressGestureMap, onGestureAction, onCommitText, onKeyPress) { { selectedLabel: String ->
                                        val gesture = longPressGestureMap?.get(selectedLabel)
                                        if (gesture != null && gesture.action != GestureAction.COMMIT) {
                                            onGestureAction?.invoke(
                                                gesture.action!!,
                                                gesture.value.ifEmpty { selectedLabel })
                                        } else {
                                            (onCommitText ?: onKeyPress)(selectedLabel)
                                        }
                                        Unit
                                    } }

                                    SwipeableKeyButton(
                                        text = displayText,
                                        onClick = onClick,
                                        backgroundColor = keyBackgroundColor,
                                        textColor = keyTextColor,
                                        modifier = Modifier.weight(1f),
                                        swipeText = swipeUpText,
                                        swipeDownText = swipeDownBubbleText,
                                        swipeUpKeyLabel = swipeUpKeyLabel,
                                        swipeDownKeyLabel = if (swipeDownDisplay == DisplayMode.KEY || swipeDownDisplay == DisplayMode.BOTH) swipeDownLabel else null,
                                        onSwipe = if (swipeUpText != null && swipeUpAction != GestureAction.NONE) onKeyPress else null,
                                        onSwipeDown = onSwipeDown,
                                        onSwipeStateChange = onSwipeStateChange,
                                        onPress = onPress,
                                        onLongPressSelect = onLongPressSelect,
                                        longPressItems = longPressLabels,
                                        shadowEnabled = shadowEnabled,
                                        shadowElevation = shadowElevation,
                                        shadowShapeRadius = shadowShapeRadius,
                                    )
                                }
                            }

                            SwipeableIconKeyButton(
                                icon = rememberVectorPainter(Icons.AutoMirrored.Filled.Backspace),
                                onClick = { onKeyPress("delete") },
                                backgroundColor = specialKeyBackgroundColor,
                                iconColor = keyTextColor,
                                modifier = Modifier
                                    .weight(1.4f)
                                    .fillMaxHeight(),
                                swipeText = "清空",
                                onSwipe = { onKeyPress("clear_composition") },
                                onLongClick = { onKeyPress("delete") },
                                onPress = { onKeyPressDown?.invoke("delete") },
                                swipeUpLabel = "上滑清空",
                                swipeDownLabel = "下滑撤回",
                                onSwipeUp = { onKeyPress("clear_all") },
                                onSwipeDown = { onKeyPress("undo_clear") },
                                onSwipeLeft = { suppressCursorMove.value = true; onKeyPress("clear_composition") },
                                onSwipeStateChange = { state, bounds ->
                                    processSwipeState(
                                        state,
                                        bounds
                                    )
                                },
                                shadowEnabled = shadowEnabled,
                                shadowElevation = shadowElevation,
                                shadowShapeRadius = shadowShapeRadius,
                            )
                        }
                    }

                    // 第四行（控制行）- 包含空格键
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .background(keyboardBackgroundColor),
                    ) {
                        // 123 / 英中 键
                        if (isVoiceMode) {
                            DummyKeyButton(
                                backgroundColor = specialKeyBackgroundColor.copy(alpha = 0.5f),
                                modifier = Modifier.weight(1.2f)
                            )
                            DummyKeyButton(
                                backgroundColor = specialKeyBackgroundColor.copy(alpha = 0.5f),
                                modifier = Modifier.weight(0.8f)
                            )
                        } else {
                            KeyButton(
                                text = "?123",
                                onClick = { onKeyPress("mode_change") },
                                onLongClick = { onKeyPress("mode_change_symbol") },
                                backgroundColor = specialKeyBackgroundColor,
                                textColor = keyTextColor,
                                modifier = Modifier.weight(1.2f),
                                onPress = { onKeyPressDown?.invoke("mode_change") },
                                shadowEnabled = shadowEnabled,
                                shadowElevation = shadowElevation,
                                shadowShapeRadius = shadowShapeRadius,
                            )

                            val row4k2 = KeysConfigHelper.getKeyGesture("row4_key2")
                            val k2Tap = row4k2?.tap?.value?.takeIf { it.isNotEmpty() } ?: "，"
                            val k2Swipe = row4k2?.swipeUp?.value?.takeIf { it.isNotEmpty() } ?: "。"
                            SwipeableKeyButton(
                                text = k2Tap,
                                onClick = { onKeyPress(k2Tap) },
                                backgroundColor = keyBackgroundColor,
                                textColor = keyTextColor,
                                modifier = Modifier.weight(0.8f),
                                swipeText = k2Swipe,
                                onSwipe = { onKeyPress(it) },
                                onSwipeStateChange = { state, bounds ->
                                    processSwipeState(state, bounds)
                                },
                                onPress = { onKeyPressDown?.invoke(k2Swipe) },
                                shadowEnabled = shadowEnabled,
                                shadowElevation = shadowElevation,
                                shadowShapeRadius = shadowShapeRadius,
                            )
                        }

                        // 空格键 - 支持长按语音
                        val currentOnKeyPress by rememberUpdatedState(onKeyPress)
                        val currentOnKeyPressDown by rememberUpdatedState(onKeyPressDown)
                        val currentOnVoiceModeChange by rememberUpdatedState(onVoiceModeChange)
                        val scope = rememberCoroutineScope()
                        val spaceShadowModifier = remember(shadowEnabled, shadowElevation, shadowShapeRadius) {
                            if (shadowEnabled) Modifier.shadow(shadowElevation, RoundedCornerShape(shadowShapeRadius), ambientColor = Color(0x80000000), spotColor = Color(0x80000000))
                            else Modifier
                        }
                        Box(
                            modifier = Modifier
                                .weight(3f)
                                .fillMaxHeight()
                                .pointerInput(isSttEnabled) {
                                    awaitEachGesture {
                                        awaitFirstDown(requireUnconsumed = false)
                                        currentOnKeyPressDown?.invoke("space")

                                        var longPressTriggered = false
                                        val longPressJob = scope.launch {
                                            delay(400)
                                            longPressTriggered = true

                                            if (isSttEnabled) {
                                                if (!PermissionHelper.hasRecordAudioPermission(
                                                        context
                                                    )
                                                ) {
                                                    Toast.makeText(
                                                        context,
                                                        "需要麦克风权限才能使用语音输入",
                                                        Toast.LENGTH_SHORT
                                                    ).show()
                                                    PermissionHelper.requestRecordAudioPermission(
                                                        context
                                                    )
                                                } else {
                                                    currentOnVoiceModeChange?.invoke(true)
                                                }
                                            } else {
                                                while (true) {
                                                    currentOnKeyPress("space")
                                                    delay(80)
                                                }
                                            }
                                        }

                                        waitForUpOrCancellation()
                                        longPressJob.cancel()

                                        if (!longPressTriggered) {
                                            currentOnKeyPress("space")
                                        }
                                    }
                                }
                                .padding(horizontal = 2.dp, vertical = 3.dp)
                                .fillMaxWidth()
                                .fillMaxHeight()
                                .then(spaceShadowModifier)
                                .clip(RoundedCornerShape(shadowShapeRadius))
                                .background(keyBackgroundColor),
                            contentAlignment = Alignment.Center
                        ) {
                            if (isVoiceMode) {
                                Icon(
                                    imageVector = Icons.Default.Mic,
                                    contentDescription = "语音输入",
                                    tint = keyTextColor,
                                    modifier = Modifier.size(24.dp)
                                )
                            } else {
                                Text(
                                    text = schemaName,
                                    color = keyTextColor,
                                    fontSize = 14.sp,
                                    fontWeight = androidx.compose.ui.text.font.FontWeight.Normal,
                                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                                    maxLines = 1
                                )

                                if (isSttEnabled) {
                                    Icon(
                                        painter = painterResource(com.kingzcheung.xime.R.drawable.voice),
                                        contentDescription = "语音输入",
                                        tint = keyTextColor.copy(alpha = 0.3f),
                                        modifier = Modifier
                                            .size(18.dp)
                                            .align(Alignment.BottomStart)
                                            .padding(start = 6.dp, bottom = 2.dp)
                                    )
                                } else {
                                    Text(
                                        text = "空格",
                                        color = keyTextColor.copy(alpha = 0.3f),
                                        fontSize = 10.sp,
                                        fontWeight = androidx.compose.ui.text.font.FontWeight.Normal,
                                        textAlign = androidx.compose.ui.text.style.TextAlign.Start,
                                        maxLines = 1,
                                        modifier = Modifier
                                            .align(Alignment.BottomStart)
                                            .padding(start = 6.dp, bottom = 2.dp)
                                    )
                                }
                            }
                        }

                        // 逗号 / 回车 键
                        if (isVoiceMode) {
                            DummyKeyButton(
                                backgroundColor = keyBackgroundColor.copy(alpha = 0.5f),
                                modifier = Modifier.weight(0.8f)
                            )
                            DummyKeyButton(
                                backgroundColor = specialKeyBackgroundColor.copy(alpha = 0.5f),
                                modifier = Modifier.weight(1.2f)
                            )
                        } else {
                            val row4k4 = KeysConfigHelper.getKeyGesture("row4_key4")
                            val k4Action = row4k4?.tap?.action
                            val k4Value = row4k4?.tap?.value ?: "ime_switch"
                            val k4Label = row4k4?.tap?.label?.takeIf { it.isNotEmpty() } ?: "中"
                            KeyButton(
                                text = k4Label,
                                onClick = {
                                    if (k4Action != null && k4Action != GestureAction.COMMIT) {
                                        onGestureAction?.invoke(k4Action, k4Value)
                                    } else {
                                        onKeyPress(k4Value)
                                    }
                                },
                                backgroundColor = specialKeyBackgroundColor,
                                textColor = keyTextColor,
                                modifier = Modifier.weight(0.8f),
                                onPress = { onKeyPressDown?.invoke(k4Value) },
                                shadowEnabled = shadowEnabled,
                                shadowElevation = shadowElevation,
                                shadowShapeRadius = shadowShapeRadius,
                            )

                            KeyButton(
                                text = enterKeyText,
                                onClick = { onKeyPress("enter") },
                                backgroundColor = specialKeyBackgroundColor,
                                textColor = keyTextColor,
                                modifier = Modifier.weight(1.2f),
                                onPress = { onKeyPressDown?.invoke("enter") },
                                shadowEnabled = shadowEnabled,
                                shadowElevation = shadowElevation,
                                shadowShapeRadius = shadowShapeRadius,
                            )
                        }
                    }
                }

            }
        }

        // 语音模式中央麦克风图标
        if (isVoiceMode) {
            Box(
                modifier = Modifier.matchParentSize(),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Mic,
                    contentDescription = "语音输入",
                    tint = keyTextColor.copy(alpha = 0.6f),
                    modifier = Modifier.size(64.dp)
                )
            }
        }

    }
}

@Composable
private fun DummyKeyboardRow(
    keysCount: Int,
    keyBackgroundColor: Color,
    keyboardBackgroundColor: Color = Color.Transparent,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(keyboardBackgroundColor),
    ) {
        repeat(keysCount) {
            DummyKeyButton(
                backgroundColor = keyBackgroundColor,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun DummyBottomRow(
    keyBackgroundColor: Color,
    specialKeyBackgroundColor: Color,
    keyboardBackgroundColor: Color = Color.Transparent
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(keyboardBackgroundColor),
    ) {
        DummyKeyButton(
            backgroundColor = specialKeyBackgroundColor,
            modifier = Modifier.weight(1.2f)
        )
        Row(
            modifier = Modifier
                .weight(7f)
                .fillMaxHeight(),
        ) {
            repeat(7) {
                DummyKeyButton(
                    backgroundColor = keyBackgroundColor,
                    modifier = Modifier.weight(1f)
                )
            }
        }
        DummyKeyButton(
            backgroundColor = specialKeyBackgroundColor,
            modifier = Modifier.weight(1.2f)
        )
    }
}

@Composable
private fun DummyKeyButton(
    backgroundColor: Color,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxHeight()
            .clip(RoundedCornerShape(8.dp))
            .background(backgroundColor)
    )
}

@Composable
fun KeyboardRowWithConfig(
    keys: List<String>,
    onKeyPress: (String) -> Unit,
    config: KeyboardRowConfig,
    isShifted: Boolean,
    modifier: Modifier = Modifier,
    onSwipeStateChange: ((SwipeState, Rect) -> Unit)? = null,
    onKeyPressDown: ((String) -> Unit)? = null,
    swipeDownHintsEnabled: Boolean = true,
    swipeUpHintsEnabled: Boolean = true,
    onCommitText: ((String) -> Unit)? = null,
    onGestureAction: ((GestureAction, String) -> Unit)? = null,
    configVersion: Int = 0,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(config.keyboardBackgroundColor),
    ) {
        keys.forEach { key ->
            val rawSwipeUpText = KeysConfigHelper.getSwipeUpText(key)
            val swipeUpText = if (swipeUpHintsEnabled) rawSwipeUpText else null
            val swipeUpAction = KeysConfigHelper.getSwipeUpAction(key)
            val swipeUpDisplay = KeysConfigHelper.getSwipeUpDisplay(key)
            val swipeUpKeyLabel =
                if (swipeUpDisplay != DisplayMode.BUBBLE && swipeUpHintsEnabled) swipeUpText else null
            val swipeDownRaw = KeysConfigHelper.getKeyGesture(key)?.swipeDown
            val swipeDownLabel = swipeDownRaw?.label?.takeIf { it.isNotEmpty() }
            val swipeDownAction = swipeDownRaw?.action
            val swipeDownValue = swipeDownRaw?.value
            val swipeDownDisplay = swipeDownRaw?.display ?: DisplayMode.BOTH
            val swipeDownBubbleText =
                if (swipeDownDisplay != DisplayMode.KEY && swipeDownHintsEnabled) swipeDownLabel else null

            // 长按选项
            val longPressConfig = KeysConfigHelper.getKeyGesture(key)?.longPress
            val longPressDisplay = longPressConfig?.display ?: "key"
            val longPressLabels = if (longPressDisplay == "bubble") {
                longPressConfig?.values?.map { it.label }?.filter { it.isNotEmpty() }
                    ?.ifEmpty { null }
            } else null
            val longPressGestureMap = if (longPressDisplay == "bubble") {
                longPressConfig?.values?.associateBy { it.label }
            } else null

            // 键帽显示文本
            val displayText = KeysConfigHelper.getKeyDisplayLabel(key)
            val commitValue = KeysConfigHelper.getKeyCommitValue(key)

            val onClick = remember(key, commitValue, onKeyPress) { { onKeyPress(commitValue) } }
            val onPress: (() -> Unit)? = remember(key, onKeyPressDown) { { onKeyPressDown?.invoke(key); Unit } }
            val onSwipeDown: ((String) -> Unit)? = if (swipeDownAction != null && swipeDownHintsEnabled && swipeDownLabel != null) {
                remember(key, onKeyPress, onGestureAction, onCommitText, swipeDownAction, swipeDownValue, swipeDownLabel) {
                    val label = swipeDownLabel
                    { _: String ->
                        if (swipeDownAction == GestureAction.COMMIT) {
                            (onCommitText ?: onKeyPress)(swipeDownValue?.ifEmpty { label } ?: label)
                        } else {
                            onGestureAction?.invoke(
                                swipeDownAction,
                                swipeDownValue?.ifEmpty { label } ?: label)
                        }
                        Unit
                    }
                }
            } else null
            val onLongPressSelect: ((String) -> Unit)? = remember(key, longPressGestureMap, onGestureAction, onCommitText, onKeyPress) { { selectedLabel: String ->
                val gesture = longPressGestureMap?.get(selectedLabel)
                if (gesture != null && gesture.action != GestureAction.COMMIT) {
                    onGestureAction?.invoke(
                        gesture.action!!,
                        gesture.value.ifEmpty { selectedLabel })
                } else {
                    (onCommitText ?: onKeyPress)(selectedLabel)
                }
                Unit
            } }

            SwipeableKeyButton(
                text = displayText,
                onClick = onClick,
                backgroundColor = config.keyBackgroundColor,
                textColor = config.keyTextColor,
                modifier = Modifier.weight(1f),
                swipeText = swipeUpText,
                swipeDownText = swipeDownBubbleText,
                swipeUpKeyLabel = swipeUpKeyLabel,
                swipeDownKeyLabel = if ((swipeDownDisplay == DisplayMode.KEY || swipeDownDisplay == DisplayMode.BOTH) && swipeDownHintsEnabled) swipeDownLabel else null,
                onSwipe = if (swipeUpText != null && swipeUpAction != GestureAction.NONE) onKeyPress else null,
                onSwipeDown = onSwipeDown,
                onSwipeStateChange = onSwipeStateChange,
                onPress = onPress,
                onLongPressSelect = onLongPressSelect,
                longPressItems = longPressLabels,
                fontSize = config.fontSize,
                swipeFontSize = config.swipeFontSize,
                shadowEnabled = config.shadowEnabled,
                shadowElevation = config.shadowElevation,
                shadowShapeRadius = config.shadowShapeRadius,
            )
        }
    }
}

@Composable
private fun Row3KeyButton(
    icon: Painter,
    onKeyPress: (String) -> Unit,
    onGestureAction: ((GestureAction, String) -> Unit)?,
    onKeyPressDown: ((String) -> Unit)?,
    backgroundColor: Color,
    iconColor: Color,
    modifier: Modifier = Modifier,
    shadowEnabled: Boolean = true,
    shadowElevation: Dp = 1.dp,
    shadowShapeRadius: Dp = 8.dp,
) {
    val tap = KeysConfigHelper.getKeyGesture("row3_key1")?.tap
    val action = tap?.action
    val value = tap?.value?.takeIf { it.isNotEmpty() } ?: "emoji"
    IconKeyButton(
        icon = icon,
        onClick = {
            if (action != null && action != GestureAction.COMMIT) {
                onGestureAction?.invoke(action, value)
            } else {
                onKeyPress(value)
            }
        },
        backgroundColor = backgroundColor,
        iconColor = iconColor,
        modifier = modifier,
        onPress = { onKeyPressDown?.invoke(value) },
        shadowEnabled = shadowEnabled,
        shadowElevation = shadowElevation,
        shadowShapeRadius = shadowShapeRadius,
    )
}

/**
 * 横屏分体键盘内容 — 当 [KeyboardLayout.isLandscape] 为 true 时渲染。
 * 将键盘拆分为左右两个面板，紧贴屏幕左右边缘，中间留空方便双手持机拇指操作。
 */
@Composable
private fun LandscapeKeyboardContent(
    onKeyPress: (String) -> Unit,
    viewModel: KeyboardViewModel,
    callbacks: KeyboardCallbacks,
    uiState: KeyboardUiState,
    swipeUpHintsEnabled: Boolean,
    swipeDownHintsEnabled: Boolean,
    onSwipeStateChange: ((SwipeState, Rect) -> Unit)? = null,
) {
    val isShifted by viewModel.isShifted.collectAsStateWithLifecycle()
    val suppressCursorMove = LocalSuppressCursorMove.current
    val staggerStep = 10.dp
    val landscapeFontSize = 12.sp
    val landscapeSwipeFontSize = 7.sp

    val kbColors = KeysConfigHelper.getKeyboardColors()
    val longToColor: (Long) -> Color = { Color(0xFF000000 or it) }
    val keyboardBackgroundColor = if (uiState.isDarkTheme) longToColor(kbColors.keyboardBgColorDark) else longToColor(kbColors.keyboardBgColor)
    val themeSpecialKeyColor = KeyboardThemes.getSpecialKeyColor(uiState.themeId, uiState.isDarkTheme)
    val keyBackgroundColor = if (uiState.isDarkTheme) longToColor(kbColors.keyBgColorDark) else longToColor(kbColors.keyBgColor)
    val keyTextColor = if (uiState.isDarkTheme) longToColor(kbColors.keyTextColorDark) else longToColor(kbColors.keyTextColor)
    val specialKeyBackgroundColor = if (uiState.isDarkTheme) kbColors.specialKeyBgColorDark?.let { longToColor(it) }
        ?: themeSpecialKeyColor else kbColors.specialKeyBgColor?.let { longToColor(it) } ?: themeSpecialKeyColor
    val kbShadow = KeysConfigHelper.getKeyboardShadow()
    val shadowEnabled = kbShadow.enabled
    val shadowElevation = kbShadow.elevation.dp
    val shadowShapeRadius = kbShadow.shapeRadius.dp
    val schemaName = uiState.schemaName
    val enterKeyText = uiState.enterKeyText
    val onKeyPressDown = callbacks.onKeyPressDown
    val onCommitText = callbacks.onCommitText
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
                callbacks.onKeyPress("ime_switch", uiState.isAsciiMode)
            }
            else -> callbacks.onGestureAction?.invoke(action, value) ?: Unit
        }
    }

    Row(
        modifier = Modifier
            .fillMaxSize()
            .padding(vertical = 6.dp, horizontal = 50.dp)
    ) {
        // ========== 左面板 ==========
        Column(
            modifier = Modifier
                .fillMaxHeight()
                .weight(0.42f)
                .padding(start = 4.dp),
        ) {
            Box(modifier = Modifier.weight(1f)) {
                CompactKeyboardRowWithConfig(
                    keys = listOf("q", "w", "e", "r", "t"),
                    onKeyPress = onKeyPress,
                    config = KeyboardRowConfig(
                        keyBackgroundColor = keyBackgroundColor,
                        keyTextColor = keyTextColor,
                        keyboardBackgroundColor = keyboardBackgroundColor,
                        fontSize = landscapeFontSize,
                        swipeFontSize = landscapeSwipeFontSize,
                    ),
                    isShifted = isShifted,
                    onKeyPressDown = onKeyPressDown,
                    swipeDownHintsEnabled = swipeDownHintsEnabled,
                    swipeUpHintsEnabled = swipeUpHintsEnabled,
                    onCommitText = onCommitText,
                    onGestureAction = onGestureAction,
                    onSwipeStateChange = onSwipeStateChange,
                )
            }
            Box(
                modifier = Modifier
                    .weight(1f)
                    .padding(start = staggerStep)
            ) {
                CompactKeyboardRowWithConfig(
                    keys = listOf("a", "s", "d", "f", "g"),
                    onKeyPress = onKeyPress,
                    config = KeyboardRowConfig(
                        keyBackgroundColor = keyBackgroundColor,
                        keyTextColor = keyTextColor,
                        keyboardBackgroundColor = keyboardBackgroundColor,
                        fontSize = landscapeFontSize,
                        swipeFontSize = landscapeSwipeFontSize,
                    ),
                    isShifted = isShifted,
                    onKeyPressDown = onKeyPressDown,
                    swipeDownHintsEnabled = swipeDownHintsEnabled,
                    swipeUpHintsEnabled = swipeUpHintsEnabled,
                    onCommitText = onCommitText,
                    onGestureAction = onGestureAction,
                    onSwipeStateChange = onSwipeStateChange,
                )
            }
            Box(
                modifier = Modifier
                    .weight(1f)
                    .padding(start = staggerStep * 2)
            ) {
                CompactKeyboardRowWithConfig(
                    keys = listOf("z", "x", "c", "v"),
                    onKeyPress = onKeyPress,
                    config = KeyboardRowConfig(
                        keyBackgroundColor = keyBackgroundColor,
                        keyTextColor = keyTextColor,
                        keyboardBackgroundColor = keyboardBackgroundColor,
                        fontSize = landscapeFontSize,
                        swipeFontSize = landscapeSwipeFontSize,
                    ),
                    isShifted = isShifted,
                    onKeyPressDown = onKeyPressDown,
                    swipeDownHintsEnabled = swipeDownHintsEnabled,
                    swipeUpHintsEnabled = swipeUpHintsEnabled,
                    onCommitText = onCommitText,
                    onGestureAction = onGestureAction,
                    onSwipeStateChange = onSwipeStateChange,
                )
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
            ) {
                Row3KeyButton(
                    icon = rememberVectorPainter(Icons.Default.EmojiEmotions),
                    onKeyPress = onKeyPress,
                    onGestureAction = onGestureAction,
                    onKeyPressDown = onKeyPressDown,
                    backgroundColor = specialKeyBackgroundColor,
                    iconColor = keyTextColor,
                    modifier = Modifier.weight(1.2f),
                    shadowEnabled = shadowEnabled,
                    shadowElevation = shadowElevation,
                    shadowShapeRadius = shadowShapeRadius,
                )
                val row4k2 = KeysConfigHelper.getKeyGesture("row4_key2")
                val k2Tap = row4k2?.tap?.value?.takeIf { it.isNotEmpty() } ?: "，"
                val k2Swipe = row4k2?.swipeUp?.value?.takeIf { it.isNotEmpty() } ?: "。"
                CompactSwipeableKeyButton(
                    text = k2Tap,
                    onClick = { onKeyPress(k2Tap) },
                    backgroundColor = keyBackgroundColor,
                    textColor = keyTextColor,
                    modifier = Modifier.weight(0.8f),
                    swipeText = k2Swipe,
                    swipeFontSize = landscapeSwipeFontSize,
                    onSwipe = { onKeyPress(it) },
                    onPress = { onKeyPressDown?.invoke(k2Swipe) },
                    shadowEnabled = shadowEnabled,
                    shadowElevation = shadowElevation,
                    shadowShapeRadius = shadowShapeRadius,
                )
                SplitSpaceKey(
                    onClick = { onKeyPress("space") },
                    backgroundColor = keyBackgroundColor,
                    textColor = keyTextColor,
                    schemaName = schemaName,
                    modifier = Modifier.weight(3f),
                    onPress = { onKeyPressDown?.invoke("space") },
                    shadowEnabled = shadowEnabled,
                    shadowElevation = shadowElevation,
                    shadowShapeRadius = shadowShapeRadius,
                )
            }
        }

        // 中间留空
        Spacer(modifier = Modifier.weight(0.16f))

        // ========== 右面板 ==========
        Column(
            modifier = Modifier
                .fillMaxHeight()
                .weight(0.42f)
                .padding(end = 4.dp),
        ) {
            Box(modifier = Modifier.weight(1f)) {
                CompactKeyboardRowWithConfig(
                    keys = listOf("y", "u", "i", "o", "p"),
                    onKeyPress = onKeyPress,
                    config = KeyboardRowConfig(
                        keyBackgroundColor = keyBackgroundColor,
                        keyTextColor = keyTextColor,
                        keyboardBackgroundColor = keyboardBackgroundColor,
                        fontSize = landscapeFontSize,
                        swipeFontSize = landscapeSwipeFontSize,
                    ),
                    isShifted = isShifted,
                    onKeyPressDown = onKeyPressDown,
                    swipeDownHintsEnabled = swipeDownHintsEnabled,
                    swipeUpHintsEnabled = swipeUpHintsEnabled,
                    onCommitText = onCommitText,
                    onGestureAction = onGestureAction,
                    onSwipeStateChange = onSwipeStateChange,
                )
            }
            Box(
                modifier = Modifier
                    .weight(1f)
                    .padding(end = staggerStep)
            ) {
                CompactKeyboardRowWithConfig(
                    keys = listOf("g", "h", "j", "k", "l"),
                    onKeyPress = onKeyPress,
                    config = KeyboardRowConfig(
                        keyBackgroundColor = keyBackgroundColor,
                        keyTextColor = keyTextColor,
                        keyboardBackgroundColor = keyboardBackgroundColor,
                        fontSize = landscapeFontSize,
                        swipeFontSize = landscapeSwipeFontSize,
                    ),
                    isShifted = isShifted,
                    onKeyPressDown = onKeyPressDown,
                    swipeDownHintsEnabled = swipeDownHintsEnabled,
                    swipeUpHintsEnabled = swipeUpHintsEnabled,
                    onCommitText = onCommitText,
                    onGestureAction = onGestureAction,
                    onSwipeStateChange = onSwipeStateChange,
                )
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(end = staggerStep * 2),
            ) {
                Box(modifier = Modifier.weight(4f)) {
                    CompactKeyboardRowWithConfig(
                        keys = listOf("v", "b", "n", "m"),
                        onKeyPress = onKeyPress,
                        config = KeyboardRowConfig(
                            keyBackgroundColor = keyBackgroundColor,
                            keyTextColor = keyTextColor,
                            keyboardBackgroundColor = keyboardBackgroundColor,
                            fontSize = landscapeFontSize,
                            swipeFontSize = landscapeSwipeFontSize,
                        ),
                        isShifted = isShifted,
                        onKeyPressDown = onKeyPressDown,
                        swipeDownHintsEnabled = swipeDownHintsEnabled,
                        swipeUpHintsEnabled = swipeUpHintsEnabled,
                        onCommitText = onCommitText,
                        onGestureAction = onGestureAction,
                        onSwipeStateChange = onSwipeStateChange,
                    )
                }
                SwipeableIconKeyButton(
                    icon = rememberVectorPainter(Icons.AutoMirrored.Filled.Backspace),
                    onClick = { onKeyPress("delete") },
                    backgroundColor = specialKeyBackgroundColor,
                    iconColor = keyTextColor,
                    modifier = Modifier
                        .width(48.dp)
                        .fillMaxHeight(),
                    swipeText = "",
                    onSwipe = { onKeyPress("clear_composition") },
                    onLongClick = { onKeyPress("delete") },
                    onPress = { onKeyPressDown?.invoke("delete") },
                    swipeUpLabel = "上滑清空",
                    swipeDownLabel = "下滑撤回",
                    onSwipeUp = { onKeyPress("clear_all") },
                    onSwipeDown = { onKeyPress("undo_clear") },
                    onSwipeLeft = { suppressCursorMove.value = true; onKeyPress("clear_composition") },
                    onSwipeStateChange = onSwipeStateChange,
                    shadowEnabled = shadowEnabled,
                    shadowElevation = shadowElevation,
                    shadowShapeRadius = shadowShapeRadius,
                )
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
            ) {
                SplitSpaceKey(
                    onClick = { onKeyPress("space") },
                    backgroundColor = keyBackgroundColor,
                    textColor = keyTextColor,
                    schemaName = "",
                    modifier = Modifier.weight(2f),
                    onPress = { onKeyPressDown?.invoke("space") },
                    shadowEnabled = shadowEnabled,
                    shadowElevation = shadowElevation,
                    shadowShapeRadius = shadowShapeRadius,
                )
                KeyButton(
                    text = "?123",
                    onClick = { onKeyPress("mode_change") },
                    onLongClick = { onKeyPress("mode_change_symbol") },
                    backgroundColor = specialKeyBackgroundColor,
                    textColor = keyTextColor,
                    modifier = Modifier.weight(1.2f),
                    onPress = { onKeyPressDown?.invoke("mode_change") },
                    shadowEnabled = shadowEnabled,
                    shadowElevation = shadowElevation,
                    shadowShapeRadius = shadowShapeRadius,
                )
                val row4k4 = KeysConfigHelper.getKeyGesture("row4_key4")
                val k4Action = row4k4?.tap?.action
                val k4Value = row4k4?.tap?.value ?: "ime_switch"
                val k4Label = row4k4?.tap?.label?.takeIf { it.isNotEmpty() } ?: "中"
                KeyButton(
                    text = k4Label,
                    onClick = {
                        if (k4Action != null && k4Action != GestureAction.COMMIT) {
                            onGestureAction?.invoke(k4Action, k4Value)
                        } else {
                            onKeyPress(k4Value)
                        }
                    },
                    backgroundColor = specialKeyBackgroundColor,
                    textColor = keyTextColor,
                    modifier = Modifier.weight(0.8f),
                    onPress = { onKeyPressDown?.invoke(k4Value) },
                    shadowEnabled = shadowEnabled,
                    shadowElevation = shadowElevation,
                    shadowShapeRadius = shadowShapeRadius,
                )
                KeyButton(
                    text = enterKeyText,
                    onClick = { onKeyPress("enter") },
                    backgroundColor = specialKeyBackgroundColor,
                    textColor = keyTextColor,
                    modifier = Modifier.weight(1.2f),
                    onPress = { onKeyPressDown?.invoke("enter") },
                    shadowEnabled = shadowEnabled,
                    shadowElevation = shadowElevation,
                    shadowShapeRadius = shadowShapeRadius,
                )
            }
        }
    }
}

/**
 * 横屏紧凑版按键 — 主字符和上滑字符垂直堆叠居中
 */
@Composable
fun CompactSwipeableKeyButton(
    text: String,
    onClick: () -> Unit,
    backgroundColor: Color,
    textColor: Color,
    modifier: Modifier = Modifier,
    swipeText: String? = null,
    swipeDownText: String? = null,
    swipeUpKeyLabel: String? = null,
    swipeDownKeyLabel: String? = null,
    onSwipe: ((String) -> Unit)? = null,
    onSwipeDown: ((String) -> Unit)? = null,
    onPress: (() -> Unit)? = null,
    onLongPressSelect: ((String) -> Unit)? = null,
    longPressItems: List<String>? = null,
    fontSize: androidx.compose.ui.unit.TextUnit = androidx.compose.ui.unit.TextUnit.Unspecified,
    swipeFontSize: androidx.compose.ui.unit.TextUnit = 8.sp,
    onSwipeStateChange: ((SwipeState, Rect) -> Unit)? = null,
    shadowEnabled: Boolean = true,
    shadowElevation: Dp = 1.dp,
    shadowShapeRadius: Dp = 8.dp,
) {
    var isPressed by remember { mutableStateOf(false) }
    var dragOffsetY by remember { mutableStateOf(0f) }
    var hasTriggeredSwipeUp by remember { mutableStateOf(false) }
    var hasTriggeredSwipeDown by remember { mutableStateOf(false) }
    var isSwiping by remember { mutableStateOf(false) }
    var isSwipeDown by remember { mutableStateOf(false) }
    var buttonBounds by remember { mutableStateOf(Rect(0f, 0f, 0f, 0f)) }

    val currentText by rememberUpdatedState(text)
    val currentSwipeText by rememberUpdatedState(swipeText)
    val currentSwipeDownText by rememberUpdatedState(swipeDownText)
    val currentOnSwipe by rememberUpdatedState(onSwipe)
    val currentOnSwipeDown by rememberUpdatedState(onSwipeDown)
    val currentOnClick by rememberUpdatedState(onClick)
    val currentOnPress by rememberUpdatedState(onPress)
    val currentOnLongPressSelect by rememberUpdatedState(onLongPressSelect)
    val currentLongPressItems by rememberUpdatedState(longPressItems)
    val currentOnSwipeStateChange by rememberUpdatedState(onSwipeStateChange)
    val scope = rememberCoroutineScope()
    val view = LocalView.current
    val context = LocalContext.current
    val chaiPuaFontFamily = remember {
        FontFamily(Font("ChaiPUA-0.2.7-snow.ttf", context.assets))
    }

    val density = LocalDensity.current
    val swipeUpThreshold = with(density) { (-15).dp.toPx() }
    val swipeDownThreshold = with(density) { 15.dp.toPx() }
    val bubbleShowThresholdUp = swipeUpThreshold * 0.3f
    val bubbleShowThresholdDown = swipeDownThreshold * 0.3f

    val shadowShape = remember(shadowShapeRadius) { RoundedCornerShape(shadowShapeRadius) }
    val shadowModifier = remember(shadowEnabled, shadowElevation, shadowShapeRadius) {
        if (shadowEnabled) Modifier.shadow(shadowElevation, shadowShape) else Modifier
    }

    fun darkenColor(color: Color, factor: Float = 0.15f): Color {
        return Color(
            red = (color.red * (1 - factor)).coerceIn(0f, 1f),
            green = (color.green * (1 - factor)).coerceIn(0f, 1f),
            blue = (color.blue * (1 - factor)).coerceIn(0f, 1f),
            alpha = color.alpha
        )
    }

    Box(
        modifier = modifier
            .fillMaxHeight()
            .fillMaxWidth()
            .pointerInput(currentLongPressItems, currentOnLongPressSelect) {
                if (currentLongPressItems.isNullOrEmpty() || currentOnLongPressSelect == null) {
                    detectTapGestures(
                        onPress = {
                            isPressed = true
                            currentOnSwipeStateChange?.invoke(SwipeState(isPressed = true, pressedText = currentText), buttonBounds)
                            currentOnPress?.invoke()
                            tryAwaitRelease()
                            isPressed = false
                            currentOnSwipeStateChange?.invoke(SwipeState(), buttonBounds)
                        },
                        onTap = {
                            if (!hasTriggeredSwipeUp && !hasTriggeredSwipeDown) currentOnClick()
                        }
                    )
                } else {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        isPressed = true
                        var localLongPressTriggered = false
                        var selectedIdx = 0
                        val downX = down.position.x
                        val items = currentLongPressItems ?: return@awaitEachGesture

                        currentOnSwipeStateChange?.invoke(SwipeState(isPressed = true, pressedText = currentText), buttonBounds)
                        currentOnPress?.invoke()

                        val longPressJob = scope.launch {
                            delay(400L)
                            localLongPressTriggered = true
                            view.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
                            currentOnSwipeStateChange?.invoke(
                                SwipeState(
                                    isPressed = true,
                                    isLongPress = true,
                                    longPressItems = items,
                                    selectedLongPressIndex = 0
                                ),
                                buttonBounds
                            )
                        }

                        val cancelThresholdPx = with(density) { 5.dp.toPx() }
                        val downY = down.position.y
                        var swipeDetected = false

                        try {
                            var lastReportedIdx = -1
                            var completed = false
                            while (!completed) {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull() ?: break

                                if (change.isConsumed) continue

                                if (!localLongPressTriggered) {
                                    val deltaX = change.position.x - downX
                                    val deltaY = change.position.y - downY
                                    if (kotlin.math.abs(deltaX) > cancelThresholdPx || kotlin.math.abs(deltaY) > cancelThresholdPx) {
                                        swipeDetected = true
                                        longPressJob.cancel()
                                    }
                                }

                                if (localLongPressTriggered) {
                                    val deltaX = change.position.x - downX
                                    val itemWidth = buttonBounds.width / items.size
                                    selectedIdx = ((deltaX / itemWidth) + if (items.size > 1) 0.5f else 0f).toInt()
                                        .coerceIn(0, items.size - 1)

                                    if (selectedIdx != lastReportedIdx) {
                                        lastReportedIdx = selectedIdx
                                        currentOnSwipeStateChange?.invoke(
                                            SwipeState(
                                                isPressed = true,
                                                isLongPress = true,
                                                longPressItems = items,
                                                selectedLongPressIndex = selectedIdx
                                            ),
                                            buttonBounds
                                        )
                                    }
                                    change.consume()
                                }

                                if (event.type == androidx.compose.ui.input.pointer.PointerEventType.Release) {
                                    completed = true
                                    if (localLongPressTriggered) {
                                        val selected = items.getOrNull(selectedIdx)
                                        if (selected != null) {
                                            currentOnLongPressSelect?.invoke(selected)
                                        }
                                    } else if (!swipeDetected) {
                                        currentOnClick()
                                    }
                                }
                            }
                        } finally {
                            longPressJob.cancel()
                            isPressed = false
                            currentOnSwipeStateChange?.invoke(SwipeState(), buttonBounds)
                        }
                    }
                }
            }
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = {
                        isPressed = true
                        dragOffsetY = 0f
                        hasTriggeredSwipeUp = false
                        hasTriggeredSwipeDown = false
                        isSwiping = false
                        isSwipeDown = false
                        currentOnSwipeStateChange?.invoke(SwipeState(isPressed = true, pressedText = currentText), buttonBounds)
                    },
                    onDragEnd = {
                        if (!hasTriggeredSwipeUp && !hasTriggeredSwipeDown && dragOffsetY > swipeUpThreshold && dragOffsetY < swipeDownThreshold) {
                            onClick()
                        }
                        isPressed = false
                        dragOffsetY = 0f
                        hasTriggeredSwipeUp = false
                        hasTriggeredSwipeDown = false
                        isSwiping = false
                        isSwipeDown = false
                        currentOnSwipeStateChange?.invoke(SwipeState(), buttonBounds)
                    },
                    onDragCancel = {
                        isPressed = false
                        dragOffsetY = 0f
                        hasTriggeredSwipeUp = false
                        hasTriggeredSwipeDown = false
                        isSwiping = false
                        isSwipeDown = false
                        currentOnSwipeStateChange?.invoke(SwipeState(), buttonBounds)
                    },
                    onDrag = { _: androidx.compose.ui.input.pointer.PointerInputChange, dragAmount: Offset ->
                        dragOffsetY += dragAmount.y

                        val swipeTextValue = currentSwipeText
                        val swipeDownTextValue = currentSwipeDownText
                        val onSwipeAction = currentOnSwipe
                        val onSwipeDownAction = currentOnSwipeDown
                        val onSwipeStateChangeAction = currentOnSwipeStateChange

                        if (dragOffsetY < 0) {
                            val shouldShowBubble = swipeTextValue != null && dragOffsetY < bubbleShowThresholdUp
                            if (shouldShowBubble != isSwiping) {
                                isSwiping = shouldShowBubble
                                isSwipeDown = false
                                onSwipeStateChangeAction?.invoke(
                                    SwipeState(isSwiping = shouldShowBubble, swipeText = swipeTextValue, isSwipeDown = false),
                                    buttonBounds
                                )
                            }
                        } else if (dragOffsetY > 0) {
                            val shouldShowBubble = swipeDownTextValue != null && dragOffsetY > bubbleShowThresholdDown
                            if (shouldShowBubble != isSwipeDown) {
                                isSwipeDown = shouldShowBubble
                                isSwiping = shouldShowBubble
                                onSwipeStateChangeAction?.invoke(
                                    SwipeState(isSwiping = shouldShowBubble, swipeText = swipeDownTextValue, isSwipeDown = true),
                                    buttonBounds
                                )
                            }
                        }

                        if (dragOffsetY < 0 && !hasTriggeredSwipeUp && swipeTextValue != null && onSwipeAction != null) {
                            if (dragOffsetY < swipeUpThreshold) {
                                hasTriggeredSwipeUp = true
                                onSwipeAction(swipeTextValue)
                            }
                        } else if (dragOffsetY > 0 && !hasTriggeredSwipeDown && swipeDownTextValue != null && onSwipeDownAction != null) {
                            if (dragOffsetY > swipeDownThreshold) {
                                hasTriggeredSwipeDown = true
                                onSwipeDownAction(swipeDownTextValue)
                            }
                        }
                    }
                )
            }
            .onGloballyPositioned { coordinates ->
                buttonBounds = coordinates.boundsInRoot()
            }
            .padding(horizontal = 2.dp, vertical = 3.dp)
            .then(shadowModifier)
            .clip(shadowShape)
            .background(if (isPressed) darkenColor(backgroundColor) else backgroundColor),
        contentAlignment = Alignment.TopStart
    ) {


        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = text,
                color = textColor,
                fontSize = if (fontSize != androidx.compose.ui.unit.TextUnit.Unspecified) fontSize else 14.sp,
                fontWeight = FontWeight.Medium,
                textAlign = TextAlign.Center,
                maxLines = 1,
                lineHeight = TextUnit.Unspecified
            )
        }

        val keyLabel = swipeUpKeyLabel ?: swipeText
        if (keyLabel != null) {
            Text(
                text = keyLabel,
                color = textColor.copy(alpha = 0.5f),
                fontSize = swipeFontSize,
                fontWeight = FontWeight.Normal,
                textAlign = TextAlign.End,
                maxLines = 1,
                lineHeight = 8.sp,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 2.dp, end = 4.dp)
            )
        }
        if (swipeDownText != null) {
            Text(
                text = swipeDownText,
                color = textColor.copy(alpha = 0.5f),
                fontSize = swipeFontSize,
                fontWeight = FontWeight.Normal,
                fontFamily = chaiPuaFontFamily,
                textAlign = TextAlign.Start,
                maxLines = 1,
                lineHeight = 8.sp,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(start = 4.dp, bottom = 2.dp)
            )
        }
    }
}

/**
 * 横屏紧凑版键盘行 — 使用 [CompactSwipeableKeyButton] 替代 [SwipeableKeyButton]
 */
@Composable
fun CompactKeyboardRowWithConfig(
    keys: List<String>,
    onKeyPress: (String) -> Unit,
    config: KeyboardRowConfig,
    isShifted: Boolean,
    modifier: Modifier = Modifier,
    onKeyPressDown: ((String) -> Unit)? = null,
    swipeDownHintsEnabled: Boolean = true,
    swipeUpHintsEnabled: Boolean = true,
    onCommitText: ((String) -> Unit)? = null,
    onGestureAction: ((GestureAction, String) -> Unit)? = null,
    onSwipeStateChange: ((SwipeState, Rect) -> Unit)? = null,
    configVersion: Int = 0,
) {
    Row(
        modifier = modifier
            .fillMaxSize()
            .background(config.keyboardBackgroundColor),
    ) {
        keys.forEach { key ->
            val rawSwipeUpText = KeysConfigHelper.getSwipeUpText(key)
            val swipeUpText = if (swipeUpHintsEnabled) rawSwipeUpText else null
            val swipeUpAction = KeysConfigHelper.getSwipeUpAction(key)
            val swipeUpDisplay = KeysConfigHelper.getSwipeUpDisplay(key)
            val swipeUpKeyLabel =
                if (swipeUpDisplay != DisplayMode.BUBBLE && swipeUpHintsEnabled) swipeUpText else null
            val swipeDownRaw = KeysConfigHelper.getKeyGesture(key)?.swipeDown
            val swipeDownLabel = swipeDownRaw?.label?.takeIf { it.isNotEmpty() }
            val swipeDownAction = swipeDownRaw?.action
            val swipeDownValue = swipeDownRaw?.value
            val swipeDownDisplay = swipeDownRaw?.display ?: DisplayMode.BOTH
            val swipeDownBubbleText =
                if (swipeDownDisplay != DisplayMode.KEY && swipeDownHintsEnabled) swipeDownLabel else null

            val longPressConfig = KeysConfigHelper.getKeyGesture(key)?.longPress
            val longPressDisplay = longPressConfig?.display ?: "key"
            val longPressLabels = if (longPressDisplay == "bubble") {
                longPressConfig?.values?.map { it.label }?.filter { it.isNotEmpty() }
                    ?.ifEmpty { null }
            } else null
            val longPressGestureMap = if (longPressDisplay == "bubble") {
                longPressConfig?.values?.associateBy { it.label }
            } else null

            val commitValue = KeysConfigHelper.getKeyCommitValue(key)
            val compactOnClick = remember(key, commitValue, onKeyPress) { { onKeyPress(commitValue) } }
            val compactOnPress: (() -> Unit)? = remember(key, onKeyPressDown) { { onKeyPressDown?.invoke(key); Unit } }
            val compactOnSwipeDown: ((String) -> Unit)? = if (swipeDownAction != null && swipeDownHintsEnabled && swipeDownLabel != null) {
                remember(key, onKeyPress, onGestureAction, onCommitText, swipeDownAction, swipeDownValue, swipeDownLabel) {
                    val label = swipeDownLabel
                    { _: String ->
                        if (swipeDownAction == GestureAction.COMMIT) {
                            (onCommitText ?: onKeyPress)(swipeDownValue?.ifEmpty { label } ?: label)
                        } else {
                            onGestureAction?.invoke(
                                swipeDownAction,
                                swipeDownValue?.ifEmpty { label } ?: label!!)
                        }
                        Unit
                    }
                }
            } else null
            val compactOnLongPressSelect: ((String) -> Unit)? = remember(key, longPressGestureMap, onGestureAction, onCommitText, onKeyPress) { { selectedLabel: String ->
                val gesture = longPressGestureMap?.get(selectedLabel)
                if (gesture != null && gesture.action != GestureAction.COMMIT) {
                    onGestureAction?.invoke(
                        gesture.action!!,
                        gesture.value.ifEmpty { selectedLabel })
                } else {
                    (onCommitText ?: onKeyPress)(selectedLabel)
                }
                Unit
            } }

            CompactSwipeableKeyButton(
                text = KeysConfigHelper.getKeyDisplayLabel(key),
                onClick = compactOnClick,
                backgroundColor = config.keyBackgroundColor,
                textColor = config.keyTextColor,
                modifier = Modifier.weight(1f),
                swipeText = swipeUpText,
                swipeDownText = swipeDownBubbleText,
                swipeUpKeyLabel = swipeUpKeyLabel,
                onSwipe = if (swipeUpText != null && swipeUpAction != GestureAction.NONE) onKeyPress else null,
                onSwipeDown = compactOnSwipeDown,
                onSwipeStateChange = onSwipeStateChange,
                onPress = compactOnPress,
                onLongPressSelect = compactOnLongPressSelect,
                longPressItems = longPressLabels,
                fontSize = config.fontSize,
                swipeFontSize = config.swipeFontSize,
                shadowEnabled = config.shadowEnabled,
                shadowElevation = config.shadowElevation,
                shadowShapeRadius = config.shadowShapeRadius,
            )
        }
    }
}

/**
 * 横屏分体键盘专用空格键（简化版，不支持语音/滑动光标）
 */
@Composable
private fun SplitSpaceKey(
    onClick: () -> Unit,
    backgroundColor: Color,
    textColor: Color,
    schemaName: String = "",
    modifier: Modifier = Modifier,
    onPress: (() -> Unit)? = null,
    shadowEnabled: Boolean = true,
    shadowElevation: Dp = 1.dp,
    shadowShapeRadius: Dp = 8.dp,
) {
    val shadowShape = remember(shadowShapeRadius) { RoundedCornerShape(shadowShapeRadius) }
    val shadowModifier = remember(shadowEnabled, shadowElevation, shadowShapeRadius) {
        if (shadowEnabled) Modifier.shadow(shadowElevation, shadowShape) else Modifier
    }

    Box(
        modifier = modifier
            .fillMaxHeight()
            .then(shadowModifier)
            .clip(shadowShape)
            .background(backgroundColor)
            .clickable(
                interactionSource = null,
                indication = null,
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = schemaName,
            color = textColor,
            fontSize = 14.sp,
            fontWeight = FontWeight.Normal,
            textAlign = TextAlign.Center,
            maxLines = 1
        )

        Text(
            text = "空格",
            color = textColor.copy(alpha = 0.3f),
            fontSize = 10.sp,
            fontWeight = FontWeight.Normal,
            textAlign = TextAlign.Start,
            maxLines = 1,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(start = 6.dp, bottom = 2.dp)
        )
    }
}