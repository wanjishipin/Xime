package com.kingzcheung.xime.ui.keyboard

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Backspace
import androidx.compose.material.icons.filled.EmojiEmotions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kingzcheung.xime.util.SubcharHelper

/**
 * 笔画 T9 键盘布局 — 结构与 [NumberKeyboardLayout] 一致。
 *
 * 第1行：。 | 一(h) | 丨(s) | 丿(p) | 退格
 * 第2行：？ | 丶(n) | 乛(z) | *    | 换行
 * 第3行：* | 分词  | ，    | 英   | 确定
 * 第4行：符号 | 123  | 空格  | .   | 确定
 * 空格上滑输入 0
 */
@Composable
fun StrokeKeyboardLayout(
    onKeyPress: (String) -> Unit,
    keyBackgroundColor: Color,
    keyTextColor: Color,
    specialKeyBackgroundColor: Color,
    keyboardBackgroundColor: Color = Color.Transparent,
    shadowEnabled: Boolean = true,
    shadowElevation: Dp = 1.dp,
    shadowShapeRadius: Dp = 8.dp,
    modifier: Modifier = Modifier,
    onKeyPressDown: ((String) -> Unit)? = null,
    isFloatingMode: Boolean = false,
) {
    val configuration = LocalConfiguration.current
    val isLandscape =
        configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
    val commonSymbols = listOf(
        "~", "!", "#", "$", "%", "^", "&", "*",
        "(", ")", "_", "=", "[", "]", "{", "}",
        "\\", "|", ";", ":", "'", "\"", "<", ">"
    )

    var swipeState by remember { mutableStateOf(SwipeState()) }
    var keyboardBounds by remember { mutableStateOf(Rect(0f, 0f, 0f, 0f)) }
    var lastKeyBounds by remember { mutableStateOf(Rect(0f, 0f, 0f, 0f)) }

    val isDarkTheme = keyTextColor == Color(0xFFE8EAED)

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
            .padding(bottom = if (isFloatingMode) 0.dp else 10.dp)) {
        if (isLandscape) {
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(vertical = 8.dp, horizontal = 50.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight(),
                ) {
                    commonSymbols.chunked(6).forEach { rowSymbols ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f),
                        ) {
                            rowSymbols.forEach { sym ->
                                KeyButton(
                                    text = sym,
                                    onClick = { onKeyPress(sym) },
                                    backgroundColor = keyBackgroundColor,
                                    textColor = keyTextColor,
                                    modifier = Modifier.weight(1f),
                                    onPress = { onKeyPressDown?.invoke(sym) },
                                    shadowEnabled = shadowEnabled,
                                    shadowElevation = shadowElevation,
                                    shadowShapeRadius = shadowShapeRadius,
                                )
                            }
                            repeat(6 - rowSymbols.size) {
                                Box(modifier = Modifier.weight(1f))
                            }
                        }
                    }
                }

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                ) {
                    StrokeRows(
                        onKeyPress = onKeyPress,
                        keyBackgroundColor = keyBackgroundColor,
                        keyTextColor = keyTextColor,
                        specialKeyBackgroundColor = specialKeyBackgroundColor,
                        shadowEnabled = shadowEnabled,
                        shadowElevation = shadowElevation,
                        shadowShapeRadius = shadowShapeRadius,
                        onKeyPressDown = onKeyPressDown,
                        onSwipeStateChange = { state, bounds ->
                            val newState = if (state.isSwipeDown && state.swipeText != null) {
                                state.copy(charInfos = SubcharHelper.parseSwipeDownText(state.swipeText))
                            } else state
                            swipeState = newState
                            lastKeyBounds = Rect(
                                left = bounds.left - keyboardBounds.left,
                                top = bounds.top - keyboardBounds.top,
                                right = bounds.right - keyboardBounds.left,
                                bottom = bounds.bottom - keyboardBounds.top
                            )
                        }
                    )
                }
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight()
                    .background(keyboardBackgroundColor)
                    .padding(vertical = 8.dp, horizontal = 4.dp),
            ) {
                StrokeRows(
                    onKeyPress = onKeyPress,
                    keyBackgroundColor = keyBackgroundColor,
                    keyTextColor = keyTextColor,
                    specialKeyBackgroundColor = specialKeyBackgroundColor,
                    shadowEnabled = shadowEnabled,
                    shadowElevation = shadowElevation,
                    shadowShapeRadius = shadowShapeRadius,
                    onKeyPressDown = onKeyPressDown,
                    onSwipeStateChange = { state, bounds ->
                        val newState = if (state.isSwipeDown && state.swipeText != null) {
                            state.copy(charInfos = SubcharHelper.parseSwipeDownText(state.swipeText))
                        } else state
                        swipeState = newState
                        lastKeyBounds = Rect(
                            left = bounds.left - keyboardBounds.left,
                            top = bounds.top - keyboardBounds.top,
                            right = bounds.right - keyboardBounds.left,
                            bottom = bounds.bottom - keyboardBounds.top
                        )
                    })
            }
        }
    }
}

private data class StrokeKeyDef(
    val mainLabel: String,
    val swipeDigit: String,
    val commit: String,
)

private val strokeKeys = listOf(
    StrokeKeyDef("一", "1", "h"),
    StrokeKeyDef("丨", "2", "s"),
    StrokeKeyDef("丿", "3", "p"),
    StrokeKeyDef("丶", "4", "n"),
    StrokeKeyDef("乛", "5", "z"),
)

@Composable
private fun StrokeRows(
    onKeyPress: (String) -> Unit,
    keyBackgroundColor: Color,
    keyTextColor: Color,
    specialKeyBackgroundColor: Color,
    shadowEnabled: Boolean = true,
    shadowElevation: Dp = 1.dp,
    shadowShapeRadius: Dp = 8.dp,
    onKeyPressDown: ((String) -> Unit)? = null,
    onSwipeStateChange: ((SwipeState, Rect) -> Unit)? = null
) {
    val suppressCursorMove = LocalSuppressCursorMove.current
    val symbols = listOf("。", "？", "！", "~")
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .fillMaxHeight()
            .padding(horizontal = 2.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(3f),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Column(
                    modifier = Modifier
                        .padding(vertical = 2.dp)
                        .fillMaxHeight()
                        .weight(1f),
                ) {
                    symbols.forEachIndexed { index, symbol ->
                        StrokeSymbolKey(
                            text = symbol,
                            onClick = { onKeyPress(symbol) },
                            backgroundColor = keyBackgroundColor,
                            textColor = keyTextColor,
                            modifier = Modifier.weight(1f),
                            onPress = { onKeyPressDown?.invoke(symbol) },
                            isFirst = index == 0,
                            isLast = index == symbols.lastIndex
                        )
                    }
                }
                Column(
                    modifier = Modifier
                        .fillMaxHeight()
                        .weight(4f),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                    ) {
                        strokeKeys.take(3).forEach { key ->
                            StrokeKeyButton(
                                mainLabel = key.mainLabel,
                                swipeDigit = key.swipeDigit,
                                onClick = { onKeyPress(key.commit) },
                                backgroundColor = keyBackgroundColor,
                                textColor = keyTextColor,
                                modifier = Modifier.weight(1f),
                                onPress = { onKeyPressDown?.invoke(key.commit) },
                                onSwipeUp = { onKeyPress(key.swipeDigit) },
                                shadowEnabled = shadowEnabled,
                                shadowElevation = shadowElevation,
                                shadowShapeRadius = shadowShapeRadius,
                            )
                        }
                        SwipeableIconKeyButton(
                            icon = rememberVectorPainter(Icons.AutoMirrored.Filled.Backspace),
                            onClick = { onKeyPress("delete") },
                            backgroundColor = specialKeyBackgroundColor,
                            iconColor = keyTextColor,
                            modifier = Modifier.weight(1f),
                            swipeText = "清空",
                            onSwipe = { onKeyPress("clear_composition") },
                            onLongClick = { onKeyPress("delete") },
                            onPress = { onKeyPressDown?.invoke("delete") },
                            swipeUpLabel = "上滑清空",
                            swipeDownLabel = "下滑撤回",
                            onSwipeUp = { onKeyPress("clear_all") },
                            onSwipeDown = { onKeyPress("undo_clear") },
                            onSwipeLeft = {
                                suppressCursorMove.value = true
                                onKeyPress("clear_composition")
                            },
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
                        strokeKeys.drop(3).forEach { key ->
                            StrokeKeyButton(
                                mainLabel = key.mainLabel,
                                swipeDigit = key.swipeDigit,
                                onClick = { onKeyPress(key.commit) },
                                backgroundColor = keyBackgroundColor,
                                textColor = keyTextColor,
                                modifier = Modifier.weight(1f),
                                onPress = { onKeyPressDown?.invoke(key.commit) },
                                onSwipeUp = { onKeyPress(key.swipeDigit) },
                                shadowEnabled = shadowEnabled,
                                shadowElevation = shadowElevation,
                                shadowShapeRadius = shadowShapeRadius,
                            )
                        }
                        SwipeableKeyButton(
                            text = "*",
                            onClick = { onKeyPress("*") },
                            backgroundColor = keyBackgroundColor,
                            textColor = keyTextColor,
                            modifier = Modifier.weight(1f),
                            swipeText = "6",
                            swipeUpKeyLabel = "6",
                            onSwipe = { onKeyPress("6") },
                            onPress = { onKeyPressDown?.invoke("*") },
                            shadowEnabled = shadowEnabled,
                            shadowElevation = shadowElevation,
                            shadowShapeRadius = shadowShapeRadius,
                        )
                        KeyButton(
                            text = "换行",
                            onClick = { onKeyPress("enter") },
                            backgroundColor = specialKeyBackgroundColor,
                            textColor = keyTextColor,
                            modifier = Modifier.weight(1f),
                            onPress = { onKeyPressDown?.invoke("enter") },
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
                        SwipeableKeyButton(
                            text = "分词",
                            onClick = { onKeyPress("word_separator") },
                            backgroundColor = keyBackgroundColor,
                            textColor = keyTextColor,
                            modifier = Modifier.weight(1f),
                            swipeText = "7",
                            swipeUpKeyLabel = "7",
                            onSwipe = { onKeyPress("7") },
                            onPress = { onKeyPressDown?.invoke("word_separator") },
                            shadowEnabled = shadowEnabled,
                            shadowElevation = shadowElevation,
                            shadowShapeRadius = shadowShapeRadius,
                        )
                        SwipeableKeyButton(
                            text = "，",
                            onClick = { onKeyPress("，") },
                            backgroundColor = keyBackgroundColor,
                            textColor = keyTextColor,
                            modifier = Modifier.weight(1f),
                            swipeText = "8",
                            swipeUpKeyLabel = "8",
                            onSwipe = { onKeyPress("8") },
                            onPress = { onKeyPressDown?.invoke("，") },
                            shadowEnabled = shadowEnabled,
                            shadowElevation = shadowElevation,
                            shadowShapeRadius = shadowShapeRadius,
                        )
                        SwipeableKeyButton(
                            text = "英",
                            onClick = { onKeyPress("ime_switch") },
                            backgroundColor = keyBackgroundColor,
                            textColor = keyTextColor,
                            modifier = Modifier.weight(1f),
                            swipeText = "9",
                            swipeUpKeyLabel = "9",
                            onSwipe = { onKeyPress("9") },
                            onPress = { onKeyPressDown?.invoke("ime_switch") },
                            shadowEnabled = shadowEnabled,
                            shadowElevation = shadowElevation,
                            shadowShapeRadius = shadowShapeRadius,
                        )
                        IconKeyButton(
                            icon = rememberVectorPainter(Icons.Default.EmojiEmotions),
                            onClick = { onKeyPress("emoji") },
                            backgroundColor = specialKeyBackgroundColor,
                            iconColor = keyTextColor,
                            modifier = Modifier.weight(1f),
                            onPress = { onKeyPressDown?.invoke("emoji") },
                            shadowEnabled = shadowEnabled,
                            shadowElevation = shadowElevation,
                            shadowShapeRadius = shadowShapeRadius,
                        )
                    }
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
                    textColor = keyTextColor,
                    modifier = Modifier.weight(1f),
                    onPress = { onKeyPressDown?.invoke("symbol") },
                    shadowEnabled = shadowEnabled,
                    shadowElevation = shadowElevation,
                    shadowShapeRadius = shadowShapeRadius,
                )
                KeyButton(
                    text = "123",
                    onClick = { onKeyPress("number") },
                    backgroundColor = keyBackgroundColor,
                    textColor = keyTextColor,
                    modifier = Modifier.weight(1f),
                    onPress = { onKeyPressDown?.invoke("number") },
                    shadowEnabled = shadowEnabled,
                    shadowElevation = shadowElevation,
                    shadowShapeRadius = shadowShapeRadius,
                )
                SwipeableKeyButton(
                    text = "空格",
                    onClick = { onKeyPress("space") },
                    backgroundColor = keyBackgroundColor,
                    textColor = keyTextColor,
                    modifier = Modifier.weight(1.5f),
                    swipeText = "0",
                    onSwipe = { onKeyPress("0") },
                    onPress = { onKeyPressDown?.invoke("space") },
                    shadowEnabled = shadowEnabled,
                    shadowElevation = shadowElevation,
                    shadowShapeRadius = shadowShapeRadius,
                )
                KeyButton(
                    text = ".",
                    onClick = { onKeyPress(".") },
                    backgroundColor = keyBackgroundColor,
                    textColor = keyTextColor,
                    modifier = Modifier.weight(1f),
                    onPress = { onKeyPressDown?.invoke(".") },
                    shadowEnabled = shadowEnabled,
                    shadowElevation = shadowElevation,
                    shadowShapeRadius = shadowShapeRadius,
                )
                KeyButton(
                    text = "确定",
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

@Composable
private fun StrokeSymbolKey(
    text: String,
    onClick: () -> Unit,
    backgroundColor: Color,
    textColor: Color,
    modifier: Modifier = Modifier,
    onPress: (() -> Unit)? = null,
    isFirst: Boolean = false,
    isLast: Boolean = false
) {
    var isPressed by remember { mutableStateOf(false) }
    val currentOnClick by rememberUpdatedState(onClick)
    val currentOnPress by rememberUpdatedState(onPress)
    val shape = RoundedCornerShape(
        topStart = if (isFirst) 8.dp else 0.dp,
        topEnd = if (isFirst) 8.dp else 0.dp,
        bottomStart = if (isLast) 8.dp else 0.dp,
        bottomEnd = if (isLast) 8.dp else 0.dp
    )
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(if (isPressed) backgroundColor.copy(alpha = 0.7f) else backgroundColor)
            .pointerInput(Unit) {
                detectTapGestures(onPress = {
                    isPressed = true
                    currentOnPress?.invoke()
                    tryAwaitRelease()
                    isPressed = false
                }, onTap = { currentOnClick() })
            }, contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            color = textColor,
            fontSize = 18.sp,
            fontWeight = FontWeight.Normal,
            modifier = Modifier.padding(vertical = 2.dp)
        )
    }
}

@Composable
private fun StrokeKeyButton(
    mainLabel: String,
    swipeDigit: String,
    onClick: () -> Unit,
    backgroundColor: Color,
    textColor: Color,
    modifier: Modifier = Modifier,
    onPress: (() -> Unit)? = null,
    onSwipeUp: (() -> Unit)? = null,
    shadowEnabled: Boolean = true,
    shadowElevation: Dp = 1.dp,
    shadowShapeRadius: Dp = 8.dp,
) {
    var isPressed by remember { mutableStateOf(false) }
    var dragOffsetY by remember { mutableStateOf(0f) }
    var hasTriggeredSwipeUp by remember { mutableStateOf(false) }

    val density = LocalDensity.current
    val swipeUpThreshold = with(density) { (-40).dp.toPx() }

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
            .fillMaxWidth()
            .fillMaxHeight()
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = {
                        isPressed = true
                        dragOffsetY = 0f
                        hasTriggeredSwipeUp = false
                    },
                    onDragEnd = {
                        isPressed = false
                        dragOffsetY = 0f
                        hasTriggeredSwipeUp = false
                    },
                    onDragCancel = {
                        isPressed = false
                        dragOffsetY = 0f
                        hasTriggeredSwipeUp = false
                    },
                    onDrag = { change, dragAmount ->
                        dragOffsetY += dragAmount.y
                        if (dragOffsetY < swipeUpThreshold && !hasTriggeredSwipeUp && onSwipeUp != null) {
                            hasTriggeredSwipeUp = true
                            onSwipeUp()
                        }
                    }
                )
            }
            .pointerInput(Unit) {
                detectTapGestures(
                    onPress = {
                        isPressed = true
                        onPress?.invoke()
                        tryAwaitRelease()
                        isPressed = false
                    },
                    onTap = {
                        if (!hasTriggeredSwipeUp) onClick()
                    }
                )
            }
            .padding(horizontal = 2.dp, vertical = 2.dp)
            .then(shadowModifier)
            .clip(shadowShape)
            .background(
                if (isPressed) darkenColor(backgroundColor, 0.2f)
                else backgroundColor
            ),
    ) {
        Text(
            text = mainLabel,
            color = textColor,
            fontSize = 20.sp,
            fontWeight = FontWeight.Normal,
            textAlign = TextAlign.Center,
            modifier = Modifier.align(Alignment.Center)
        )

        Text(
            text = swipeDigit,
            color = textColor.copy(alpha = 0.5f),
            fontSize = 9.sp,
            fontWeight = FontWeight.Normal,
            textAlign = TextAlign.Center,
            lineHeight = 1.sp,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(top = 4.dp, end = 4.dp),
        )
    }
}
