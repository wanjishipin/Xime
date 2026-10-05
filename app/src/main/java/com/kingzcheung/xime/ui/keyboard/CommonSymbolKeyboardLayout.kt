package com.kingzcheung.xime.ui.keyboard

import com.kingzcheung.xime.util.FileLogger
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Backspace
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.Dp
import com.kingzcheung.xime.keyboard.KeyboardDimensions
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** 符号键：同一键在中文模式输出全角、英文模式输出半角（per-key 模型） */
internal data class SymbolKey(val full: String, val ascii: String)

private val row2Keys = listOf(
    SymbolKey("＠", "@"),
    SymbolKey("＃", "#"),
    SymbolKey("＄", "$"),
    SymbolKey("＆", "&"),
    SymbolKey("＿", "_"),
    SymbolKey("－", "-"),
    SymbolKey("＋", "+"),
    SymbolKey("（", "("),
    SymbolKey("）", ")"),
    SymbolKey("／", "/"),
)

private val row3Keys = listOf(
    SymbolKey("＊", "*"),
    SymbolKey("，", ","),
    SymbolKey("“", "\""),
    SymbolKey("’", "'"),
    SymbolKey("。", "."),
    SymbolKey("！", "!"),
    SymbolKey("？", "?"),
)

/** 当前模式下的显示/输出字符 */
private fun SymbolKey.resolve(asciiMode: Boolean): String = if (asciiMode) ascii else full

@Composable
fun CommonSymbolKeyboardLayout(
    onKeyPress: (String) -> Unit,
    isAsciiMode: Boolean,
    keyBackgroundColor: Color,
    keyTextColor: Color,
    specialKeyBackgroundColor: Color,
    bubbleBackgroundColor: Color = keyBackgroundColor,
    keyboardBackgroundColor: Color = Color.Transparent,
    shadowEnabled: Boolean = true,
    shadowElevation: Dp = 1.dp,
    shadowShapeRadius: Dp = 8.dp,
    keyCornerRadius: Dp = 8.dp,
    keySpacingX: Dp? = null,
    keySpacingY: Dp? = null,
    modifier: Modifier = Modifier,
    onKeyPressDown: ((String) -> Unit)? = null,
    isFloatingMode: Boolean = false,
    specialKeyTextColor: Color = Color.White,
    /** 进入面板时的初始模式（来自 ascii 记忆状态机），为 null 时退回 [isAsciiMode]。 */
    initialAsciiMode: Boolean? = null,
) {
    var localAsciiMode by remember(initialAsciiMode) { mutableStateOf(initialAsciiMode ?: isAsciiMode) }

    val configuration = LocalConfiguration.current
    val isLandscape = !isFloatingMode && configuration.screenWidthDp > configuration.screenHeightDp
    val isDarkTheme = keyTextColor == Color(0xFFE8EAED)
    val suppressCursorMove = LocalSuppressCursorMove.current
    val swipeBubble = rememberSwipeBubbleController()
    var keyboardBounds by remember { mutableStateOf(Rect(0f, 0f, 0f, 0f)) }

    fun processSwipeState(state: SwipeState, bounds: Rect) {
        swipeBubble.update(
            state,
            Rect(
                left = bounds.left - keyboardBounds.left,
                top = bounds.top - keyboardBounds.top,
                right = bounds.right - keyboardBounds.left,
                bottom = bounds.bottom - keyboardBounds.top,
            )
        )
    }

    val bubbleData = rememberSwipeBubbleDrawData(
        swipeState = swipeBubble.state,
        keyBounds = swipeBubble.keyBounds,
        keyBackgroundColor = bubbleBackgroundColor,
        keyTextColor = keyTextColor,
        accentColor = specialKeyTextColor,
        keyWidth = if (swipeBubble.state.isSwiping || swipeBubble.state.isPressed) swipeBubble.keyBounds.width else 0f,
        keyboardWidth = keyboardBounds.width,
    )

    CompositionLocalProvider(LocalKeyCornerRadius provides keyCornerRadius) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .fillMaxHeight()
            .onGloballyPositioned { coordinates ->
                keyboardBounds = coordinates.boundsInRoot()
            }
            .drawWithContent {
                drawContent()
                bubbleData?.let { drawSwipeBubble(it) }
            }
            .padding(bottom = if (isFloatingMode || isLandscape) 0.dp else 0.dp),
    ) {
        if (isLandscape) {
            CommonSymbolLandscapeContent(
                onKeyPress = onKeyPress,
                row2Keys = row2Keys,
                row3Keys = row3Keys,
                keyBackgroundColor = keyBackgroundColor,
                keyTextColor = keyTextColor,
                specialKeyBackgroundColor = specialKeyBackgroundColor,
                shadowEnabled = shadowEnabled,
                shadowElevation = shadowElevation,
                shadowShapeRadius = shadowShapeRadius,
                onKeyPressDown = onKeyPressDown,
                suppressCursorMove = suppressCursorMove,
                onSwipeStateChange = { state, bounds -> processSwipeState(state, bounds) },
                specialKeyTextColor = specialKeyTextColor,
                isAsciiMode = localAsciiMode,
                onToggleAsciiMode = {
                    FileLogger.i("XimeKeyboard", "panel En key tapped (landscape): localAsciiMode=$localAsciiMode -> ${!localAsciiMode}, uiAscii=$isAsciiMode")
                    localAsciiMode = !localAsciiMode
                    // 面板内中英键走 ime_switch_panel（PANEL_SYNC）：切引擎但不持久化
                    onKeyPress("ime_switch_panel")
                },
                keySpacingX = keySpacingX,
                keySpacingY = keySpacingY,
            )
        } else {
            CompositionLocalProvider(
                LocalKeyVisualPadding provides PaddingValues(
                    horizontal = keySpacingX ?: 2.dp,
                    vertical = keySpacingY ?: 4.dp,
                )
            ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight()
                    .padding(start = 4.dp, end = 4.dp, bottom = 8.dp),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .fillMaxHeight(),
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f),
                        ) {
                            (0..9).forEach { n ->
                                val digit = ((n + 1) % 10).toString()
                                KeyButton(
                                    text = digit,
                                    onClick = { onKeyPress(digit) },
                                    backgroundColor = keyBackgroundColor,
                                    textColor = keyTextColor,
                                    modifier = Modifier.weight(1f),
                                    onPress = { onKeyPressDown?.invoke(digit) },
                                    shadowEnabled = shadowEnabled,
                                    shadowElevation = shadowElevation,
                                    shadowShapeRadius = shadowShapeRadius,
                                    fontSize = 20.sp,
                                )
                            }
                        }

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f),
                        ) {
                            row2Keys.forEach { sym ->
                                val ch = sym.resolve(localAsciiMode)
                                KeyButton(
                                    text = ch,
                                    onClick = { onKeyPress(ch) },
                                    backgroundColor = keyBackgroundColor,
                                    textColor = keyTextColor,
                                    modifier = Modifier.weight(1f),
                                    onPress = { onKeyPressDown?.invoke(ch) },
                                    shadowEnabled = shadowEnabled,
                                    shadowElevation = shadowElevation,
                                    shadowShapeRadius = shadowShapeRadius,
                                    fontSize = 20.sp,
                                )
                            }
                        }

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f),
                        ) {
                            KeyButton(
                                text = "符号",
                                onClick = { onKeyPress("symbol") },
                                backgroundColor = specialKeyBackgroundColor,
                                textColor = specialKeyTextColor,
                                modifier = Modifier.weight(1.3f),
                                onPress = { onKeyPressDown?.invoke("symbol") },
                                shadowEnabled = shadowEnabled,
                                shadowElevation = shadowElevation,
                                shadowShapeRadius = shadowShapeRadius,
                                fontSize = 14.sp,
                            )
                            row3Keys.forEach { sym ->
                                val ch = sym.resolve(localAsciiMode)
                                KeyButton(
                                    text = ch,
                                    onClick = { onKeyPress(ch) },
                                    backgroundColor = keyBackgroundColor,
                                    textColor = keyTextColor,
                                    modifier = Modifier.weight(1f),
                                    onPress = { onKeyPressDown?.invoke(ch) },
                                    shadowEnabled = shadowEnabled,
                                    shadowElevation = shadowElevation,
                                    shadowShapeRadius = shadowShapeRadius,
                                    fontSize = 20.sp,
                                )
                            }
                            SwipeableIconKeyButton(
                                icon = rememberVectorPainter(Icons.AutoMirrored.Filled.Backspace),
                                onClick = { onKeyPress("delete") },
                                backgroundColor = specialKeyBackgroundColor,
                                iconColor = specialKeyTextColor,
                                modifier = Modifier.weight(1.2f),
                                a11yDescription = "退格",
                                swipeText = "清空",
                                onSwipe = { onKeyPress("clear_composition") },
                                onLongClick = { onKeyPress("delete") },
                                onPress = { onKeyPressDown?.invoke("delete") },
                                swipeUpLabel = "上滑清空",
                                swipeDownLabel = "下滑撤回",
                                onSwipeUp = { onKeyPress("clear_all") },
                                onSwipeDown = { onKeyPress("undo_clear") },
                                onSwipeLeft = {
                                    suppressCursorMove.value = true; onKeyPress("clear_composition")
                                },
                                onSwipeStateChange = { state, bounds ->
                                    processSwipeState(state, bounds)
                                },
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
                            KeyButton(
                                text = "返回",
                                onClick = { onKeyPress("abc") },
                                backgroundColor = specialKeyBackgroundColor,
                                textColor = specialKeyTextColor,
                                modifier = Modifier.weight(1.2f),
                                onPress = { onKeyPressDown?.invoke("abc") },
                                shadowEnabled = shadowEnabled,
                                shadowElevation = shadowElevation,
                                shadowShapeRadius = shadowShapeRadius,
                                fontSize = 14.sp,
                            )
                            KeyButton(
                                text = "123",
                                onClick = { onKeyPress("number") },
                                backgroundColor = specialKeyBackgroundColor,
                                textColor = specialKeyTextColor,
                                modifier = Modifier.weight(1f),
                                onPress = { onKeyPressDown?.invoke("number") },
                                shadowEnabled = shadowEnabled,
                                shadowElevation = shadowElevation,
                                shadowShapeRadius = shadowShapeRadius,
                                fontSize = 14.sp,
                            )
                            KeyButton(
                                text = "空格",
                                onClick = { onKeyPress("space") },
                                backgroundColor = keyBackgroundColor,
                                textColor = keyTextColor,
                                modifier = Modifier.weight(2.5f),
                                onPress = { onKeyPressDown?.invoke("space") },
                                shadowEnabled = shadowEnabled,
                                shadowElevation = shadowElevation,
                                shadowShapeRadius = shadowShapeRadius,
                                fontSize = 14.sp,
                            )
                            KeyButton(
                                text = if (localAsciiMode) "中" else "En",
                                onClick = {
                                    FileLogger.i("XimeKeyboard", "panel En key tapped: localAsciiMode=$localAsciiMode -> ${!localAsciiMode}, uiAscii=$isAsciiMode")
                                    localAsciiMode = !localAsciiMode
                                    // 面板内中英键走 ime_switch_panel（PANEL_SYNC）：切引擎但不持久化
                                    onKeyPress("ime_switch_panel")
                                },
                                backgroundColor = specialKeyBackgroundColor,
                                textColor = specialKeyTextColor,
                                modifier = Modifier.weight(1f),
                                shadowEnabled = shadowEnabled,
                                shadowElevation = shadowElevation,
                                shadowShapeRadius = shadowShapeRadius,
                                fontSize = 12.sp,
                            )
                            KeyButton(
                                text = "确定",
                                onClick = { onKeyPress("enter") },
                                backgroundColor = specialKeyBackgroundColor,
                                textColor = specialKeyTextColor,
                                modifier = Modifier.weight(1.2f),
                                onPress = { onKeyPressDown?.invoke("enter") },
                                shadowEnabled = shadowEnabled,
                                shadowElevation = shadowElevation,
                                shadowShapeRadius = shadowShapeRadius,
                                fontSize = 14.sp,
                            )
                        }
                    }
                }
            }
        }
    }
    }
    }
}

@Composable
internal fun CommonSymbolLandscapeContent(
    onKeyPress: (String) -> Unit,
    row2Keys: List<SymbolKey>,
    row3Keys: List<SymbolKey>,
    keyBackgroundColor: Color,
    keyTextColor: Color,
    specialKeyBackgroundColor: Color,
    shadowEnabled: Boolean,
    shadowElevation: Dp,
    shadowShapeRadius: Dp,
    onKeyPressDown: ((String) -> Unit)?,
    suppressCursorMove: androidx.compose.runtime.MutableState<Boolean>,
    onSwipeStateChange: (SwipeState, Rect) -> Unit,
    specialKeyTextColor: Color = Color.White,
    isAsciiMode: Boolean = false,
    onToggleAsciiMode: (() -> Unit)? = null,
    keySpacingX: Dp? = null,
    keySpacingY: Dp? = null,
) {
    val keyVisualPadding = PaddingValues(
        horizontal = keySpacingX ?: 2.dp,
        vertical = keySpacingY ?: 2.dp,
    )

    Row(
        modifier = Modifier
            .fillMaxSize()
            .padding(vertical = 2.dp, horizontal = 50.dp),
    ) {
        Column(
            modifier = Modifier
                .weight(0.42f)
                .fillMaxHeight(),
        ) {
            CompositionLocalProvider(LocalKeyVisualPadding provides keyVisualPadding) {
                Row(modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)) {
                    (1..5).forEach { n ->
                        val digit = n.toString()
                        KeyButton(
                            text = digit,
                            onClick = { onKeyPress(digit) },
                            backgroundColor = keyBackgroundColor,
                            textColor = keyTextColor,
                            modifier = Modifier.weight(1f),
                            onPress = { onKeyPressDown?.invoke(digit) },
                            shadowEnabled = shadowEnabled,
                            shadowElevation = shadowElevation,
                            shadowShapeRadius = shadowShapeRadius,
                            fontSize = 16.sp,
                        )
                    }
                }
                Row(modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)) {
                    row2Keys.take(5).forEach { sym ->
                        val ch = sym.resolve(isAsciiMode)
                        KeyButton(
                            text = ch,
                            onClick = { onKeyPress(ch) },
                            backgroundColor = keyBackgroundColor,
                            textColor = keyTextColor,
                            modifier = Modifier.weight(1f),
                            onPress = { onKeyPressDown?.invoke(ch) },
                            shadowEnabled = shadowEnabled,
                            shadowElevation = shadowElevation,
                            shadowShapeRadius = shadowShapeRadius,
                            fontSize = 16.sp,
                        )
                    }
                }
                Row(modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)) {
                    KeyButton(
                        text = "符号",
                        onClick = { onKeyPress("symbol") },
                        backgroundColor = specialKeyBackgroundColor,
                        textColor = specialKeyTextColor,
                        modifier = Modifier.weight(1.3f),
                        onPress = { onKeyPressDown?.invoke("symbol") },
                        shadowEnabled = shadowEnabled,
                        shadowElevation = shadowElevation,
                        shadowShapeRadius = shadowShapeRadius,
                        fontSize = 12.sp,
                    )
                    row3Keys.take(4).forEach { sym ->
                        val ch = sym.resolve(isAsciiMode)
                        KeyButton(
                            text = ch,
                            onClick = { onKeyPress(ch) },
                            backgroundColor = keyBackgroundColor,
                            textColor = keyTextColor,
                            modifier = Modifier.weight(1f),
                            onPress = { onKeyPressDown?.invoke(ch) },
                            shadowEnabled = shadowEnabled,
                            shadowElevation = shadowElevation,
                            shadowShapeRadius = shadowShapeRadius,
                            fontSize = 16.sp,
                        )
                    }
                }
                Row(modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)) {
                    KeyButton(
                        text = "返回",
                        onClick = { onKeyPress("abc") },
                        backgroundColor = specialKeyBackgroundColor,
                        textColor = specialKeyTextColor,
                        modifier = Modifier.weight(1.2f),
                        onPress = { onKeyPressDown?.invoke("abc") },
                        shadowEnabled = shadowEnabled,
                        shadowElevation = shadowElevation,
                        shadowShapeRadius = shadowShapeRadius,
                        fontSize = 12.sp,
                    )
                    KeyButton(
                        text = "123",
                        onClick = { onKeyPress("number") },
                        backgroundColor = specialKeyBackgroundColor,
                        textColor = specialKeyTextColor,
                        modifier = Modifier.weight(1.2f),
                        onPress = { onKeyPressDown?.invoke("number") },
                        shadowEnabled = shadowEnabled,
                        shadowElevation = shadowElevation,
                        shadowShapeRadius = shadowShapeRadius,
                        fontSize = 12.sp,
                    )
                    KeyButton(
                        text = "空格",
                        onClick = { onKeyPress("space") },
                        backgroundColor = keyBackgroundColor,
                        textColor = keyTextColor,
                        modifier = Modifier.weight(1.25f),
                        onPress = { onKeyPressDown?.invoke("space") },
                        shadowEnabled = shadowEnabled,
                        shadowElevation = shadowElevation,
                        shadowShapeRadius = shadowShapeRadius,
                        fontSize = 12.sp,
                    )
                }
            }
        }

        Spacer(modifier = Modifier.weight(0.16f))

        Column(
            modifier = Modifier
                .weight(0.42f)
                .fillMaxHeight(),
        ) {
            CompositionLocalProvider(LocalKeyVisualPadding provides keyVisualPadding) {
                Row(modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)) {
                    (6..9).forEach { n ->
                        val digit = n.toString()
                        KeyButton(
                            text = digit,
                            onClick = { onKeyPress(digit) },
                            backgroundColor = keyBackgroundColor,
                            textColor = keyTextColor,
                            modifier = Modifier.weight(1f),
                            onPress = { onKeyPressDown?.invoke(digit) },
                            shadowEnabled = shadowEnabled,
                            shadowElevation = shadowElevation,
                            shadowShapeRadius = shadowShapeRadius,
                            fontSize = 16.sp,
                        )
                    }
                    KeyButton(
                        text = "0",
                        onClick = { onKeyPress("0") },
                        backgroundColor = keyBackgroundColor,
                        textColor = keyTextColor,
                        modifier = Modifier.weight(1f),
                        onPress = { onKeyPressDown?.invoke("0") },
                        shadowEnabled = shadowEnabled,
                        shadowElevation = shadowElevation,
                        shadowShapeRadius = shadowShapeRadius,
                        fontSize = 16.sp,
                    )
                }
                Row(modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)) {
                    row2Keys.drop(5).forEach { sym ->
                        val ch = sym.resolve(isAsciiMode)
                        KeyButton(
                            text = ch,
                            onClick = { onKeyPress(ch) },
                            backgroundColor = keyBackgroundColor,
                            textColor = keyTextColor,
                            modifier = Modifier.weight(1f),
                            onPress = { onKeyPressDown?.invoke(ch) },
                            shadowEnabled = shadowEnabled,
                            shadowElevation = shadowElevation,
                            shadowShapeRadius = shadowShapeRadius,
                            fontSize = 16.sp,
                        )
                    }
                }
                Row(modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)) {
                    row3Keys.drop(4).forEach { sym ->
                        val ch = sym.resolve(isAsciiMode)
                        KeyButton(
                            text = ch,
                            onClick = { onKeyPress(ch) },
                            backgroundColor = keyBackgroundColor,
                            textColor = keyTextColor,
                            modifier = Modifier.weight(1f),
                            onPress = { onKeyPressDown?.invoke(ch) },
                            shadowEnabled = shadowEnabled,
                            shadowElevation = shadowElevation,
                            shadowShapeRadius = shadowShapeRadius,
                            fontSize = 16.sp,
                        )
                    }
                    SwipeableIconKeyButton(
                        icon = rememberVectorPainter(Icons.AutoMirrored.Filled.Backspace),
                        onClick = { onKeyPress("delete") },
                        backgroundColor = specialKeyBackgroundColor,
                        iconColor = specialKeyTextColor,
                        modifier = Modifier.weight(1f),
                        a11yDescription = "退格",
                        swipeText = "清空",
                        onSwipe = { onKeyPress("clear_composition") },
                        onLongClick = { onKeyPress("delete") },
                        onPress = { onKeyPressDown?.invoke("delete") },
                        swipeUpLabel = "上滑清空",
                        swipeDownLabel = "下滑撤回",
                        onSwipeUp = { onKeyPress("clear_all") },
                        onSwipeDown = { onKeyPress("undo_clear") },
                        onSwipeLeft = {
                            suppressCursorMove.value = true; onKeyPress("clear_composition")
                        },
                        onSwipeStateChange = onSwipeStateChange,
                        shadowEnabled = shadowEnabled,
                        shadowElevation = shadowElevation,
                        shadowShapeRadius = shadowShapeRadius,
                    )
                }
                Row(modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)) {
                    KeyButton(
                        text = "空格",
                        onClick = { onKeyPress("space") },
                        backgroundColor = keyBackgroundColor,
                        textColor = keyTextColor,
                        modifier = Modifier.weight(1.25f),
                        onPress = { onKeyPressDown?.invoke("space") },
                        shadowEnabled = shadowEnabled,
                        shadowElevation = shadowElevation,
                        shadowShapeRadius = shadowShapeRadius,
                        fontSize = 12.sp,
                    )
                    KeyButton(
                        text = if (isAsciiMode) "中" else "En",
                        onClick = { onToggleAsciiMode?.invoke() },
                        backgroundColor = specialKeyBackgroundColor,
                        textColor = specialKeyTextColor,
                        modifier = Modifier.weight(0.7f),
                        shadowEnabled = shadowEnabled,
                        shadowElevation = shadowElevation,
                        shadowShapeRadius = shadowShapeRadius,
                        fontSize = 12.sp,
                    )
                    KeyButton(
                        text = "确定",
                        onClick = { onKeyPress("enter") },
                        backgroundColor = specialKeyBackgroundColor,
                        textColor = specialKeyTextColor,
                        modifier = Modifier.weight(1.2f),
                        onPress = { onKeyPressDown?.invoke("enter") },
                        shadowEnabled = shadowEnabled,
                        shadowElevation = shadowElevation,
                        shadowShapeRadius = shadowShapeRadius,
                        fontSize = 12.sp,
                    )
                }
            }
        }
    }
}
