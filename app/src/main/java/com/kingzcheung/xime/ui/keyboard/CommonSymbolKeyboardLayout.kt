package com.kingzcheung.xime.ui.keyboard

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

@Composable
fun CommonSymbolKeyboardLayout(
    onKeyPress: (String) -> Unit,
    isAsciiMode: Boolean,
    keyBackgroundColor: Color,
    keyTextColor: Color,
    specialKeyBackgroundColor: Color,
    keyboardBackgroundColor: Color = Color.Transparent,
    shadowEnabled: Boolean = true,
    shadowElevation: Dp = 1.dp,
    shadowShapeRadius: Dp = 8.dp,
    keyCornerRadius: Dp = 8.dp,
    modifier: Modifier = Modifier,
    onKeyPressDown: ((String) -> Unit)? = null,
    isFloatingMode: Boolean = false,
) {
    val row2Symbols = if (isAsciiMode) {
        listOf("@", "#", "$", "&", "_", "-", "+", "(", ")", "/")
    } else {
        listOf("＠", "＃", "＄", "＆", "＿", "－", "＋", "（", "）", "／")
    }

    val row3Symbols = if (isAsciiMode) {
        listOf("*", "\"", "'", ":", ";", "!", "?")
    } else {
        listOf("＊", "＂", "＇", "：", "；", "！", "？")
    }

    val commaChar = if (isAsciiMode) "," else "，"
    val periodChar = if (isAsciiMode) "." else "。"

    val configuration = LocalConfiguration.current
    val isLandscape = !isFloatingMode && configuration.screenWidthDp > configuration.screenHeightDp
    val isDarkTheme = keyTextColor == Color(0xFFE8EAED)
    val suppressCursorMove = LocalSuppressCursorMove.current
    var swipeState by remember { mutableStateOf(SwipeState()) }
    var keyboardBounds by remember { mutableStateOf(Rect(0f, 0f, 0f, 0f)) }
    var lastKeyBounds by remember { mutableStateOf(Rect(0f, 0f, 0f, 0f)) }

    fun processSwipeState(state: SwipeState, bounds: Rect) {
        swipeState = state
        lastKeyBounds = Rect(
            left = bounds.left - keyboardBounds.left,
            top = bounds.top - keyboardBounds.top,
            right = bounds.right - keyboardBounds.left,
            bottom = bounds.bottom - keyboardBounds.top,
        )
    }

    val bubbleData = rememberSwipeBubbleDrawData(
        swipeState = swipeState,
        keyBounds = lastKeyBounds,
        isDarkTheme = isDarkTheme,
        keyWidth = if (swipeState.isSwiping || swipeState.isPressed) lastKeyBounds.width else 0f,
        keyboardWidth = keyboardBounds.width,
    )

    CompositionLocalProvider(LocalKeyCornerRadius provides keyCornerRadius) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .fillMaxHeight()
            .background(keyboardBackgroundColor)
            .onGloballyPositioned { coordinates ->
                keyboardBounds = coordinates.boundsInRoot()
            }
            .drawWithContent {
                drawContent()
                bubbleData?.let { drawSwipeBubble(it) }
            }
            .padding(bottom = if (isFloatingMode || isLandscape) 0.dp else 10.dp),
    ) {
        if (isLandscape) {
            CommonSymbolLandscapeContent(
                onKeyPress = onKeyPress,
                row2Symbols = row2Symbols,
                row3Symbols = row3Symbols,
                commaChar = commaChar,
                periodChar = periodChar,
                keyBackgroundColor = keyBackgroundColor,
                keyTextColor = keyTextColor,
                specialKeyBackgroundColor = specialKeyBackgroundColor,
                shadowEnabled = shadowEnabled,
                shadowElevation = shadowElevation,
                shadowShapeRadius = shadowShapeRadius,
                onKeyPressDown = onKeyPressDown,
                suppressCursorMove = suppressCursorMove,
                onSwipeStateChange = { state, bounds -> processSwipeState(state, bounds) },
            )
        } else {
            CompositionLocalProvider(
                LocalKeyVisualPadding provides PaddingValues(horizontal = 2.dp, vertical = 4.dp)
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
                    (0..9).forEach { n ->
                        val digit = ((n+1)%10).toString()
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
                    row2Symbols.forEach { sym ->
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
                        textColor = keyTextColor,
                        modifier = Modifier.weight(1.3f),
                        onPress = { onKeyPressDown?.invoke("symbol") },
                        shadowEnabled = shadowEnabled,
                        shadowElevation = shadowElevation,
                        shadowShapeRadius = shadowShapeRadius,
                        fontSize = 14.sp,
                    )
                    row3Symbols.forEach { sym ->
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
                            fontSize = 20.sp,
                        )
                    }
                    SwipeableIconKeyButton(
                        icon = rememberVectorPainter(Icons.AutoMirrored.Filled.Backspace),
                        onClick = { onKeyPress("delete") },
                        backgroundColor = specialKeyBackgroundColor,
                        iconColor = keyTextColor,
                        modifier = Modifier.weight(1.2f),
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
                        textColor = keyTextColor,
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
                        textColor = keyTextColor,
                        modifier = Modifier.weight(1.2f),
                        onPress = { onKeyPressDown?.invoke("number") },
                        shadowEnabled = shadowEnabled,
                        shadowElevation = shadowElevation,
                        shadowShapeRadius = shadowShapeRadius,
                        fontSize = 14.sp,
                    )
                    KeyButton(
                        text = commaChar,
                        onClick = { onKeyPress(commaChar) },
                        backgroundColor = keyBackgroundColor,
                        textColor = keyTextColor,
                        modifier = Modifier.weight(1f),
                        onPress = { onKeyPressDown?.invoke(commaChar) },
                        shadowEnabled = shadowEnabled,
                        shadowElevation = shadowElevation,
                        shadowShapeRadius = shadowShapeRadius,
                        fontSize = 20.sp,
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
                        text = periodChar,
                        onClick = { onKeyPress(periodChar) },
                        backgroundColor = keyBackgroundColor,
                        textColor = keyTextColor,
                        modifier = Modifier.weight(1f),
                        onPress = { onKeyPressDown?.invoke(periodChar) },
                        shadowEnabled = shadowEnabled,
                        shadowElevation = shadowElevation,
                        shadowShapeRadius = shadowShapeRadius,
                        fontSize = 20.sp,
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
                        fontSize = 14.sp,
                    )
                }
            }
            }
        }
    }
    }
}

@Composable
private fun CommonSymbolLandscapeContent(
    onKeyPress: (String) -> Unit,
    row2Symbols: List<String>,
    row3Symbols: List<String>,
    commaChar: String,
    periodChar: String,
    keyBackgroundColor: Color,
    keyTextColor: Color,
    specialKeyBackgroundColor: Color,
    shadowEnabled: Boolean,
    shadowElevation: Dp,
    shadowShapeRadius: Dp,
    onKeyPressDown: ((String) -> Unit)?,
    suppressCursorMove: androidx.compose.runtime.MutableState<Boolean>,
    onSwipeStateChange: (SwipeState, Rect) -> Unit,
) {
    val keyVisualPadding = PaddingValues(horizontal = 1.dp, vertical = 2.dp)

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
                Row(modifier = Modifier.fillMaxWidth().weight(1f)) {
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
                Row(modifier = Modifier.fillMaxWidth().weight(1f)) {
                    row2Symbols.take(5).forEach { sym ->
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
                            fontSize = 16.sp,
                        )
                    }
                }
                Row(modifier = Modifier.fillMaxWidth().weight(1f)) {
                    KeyButton(
                        text = "符号",
                        onClick = { onKeyPress("symbol") },
                        backgroundColor = specialKeyBackgroundColor,
                        textColor = keyTextColor,
                        modifier = Modifier.weight(1.3f),
                        onPress = { onKeyPressDown?.invoke("symbol") },
                        shadowEnabled = shadowEnabled,
                        shadowElevation = shadowElevation,
                        shadowShapeRadius = shadowShapeRadius,
                        fontSize = 12.sp,
                    )
                    row3Symbols.take(4).forEach { sym ->
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
                            fontSize = 16.sp,
                        )
                    }
                }
                Row(modifier = Modifier.fillMaxWidth().weight(1f)) {
                    KeyButton(
                        text = "返回",
                        onClick = { onKeyPress("abc") },
                        backgroundColor = specialKeyBackgroundColor,
                        textColor = keyTextColor,
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
                        textColor = keyTextColor,
                        modifier = Modifier.weight(1.2f),
                        onPress = { onKeyPressDown?.invoke("number") },
                        shadowEnabled = shadowEnabled,
                        shadowElevation = shadowElevation,
                        shadowShapeRadius = shadowShapeRadius,
                        fontSize = 12.sp,
                    )
                    KeyButton(
                        text = commaChar,
                        onClick = { onKeyPress(commaChar) },
                        backgroundColor = keyBackgroundColor,
                        textColor = keyTextColor,
                        modifier = Modifier.weight(1f),
                        onPress = { onKeyPressDown?.invoke(commaChar) },
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
                Row(modifier = Modifier.fillMaxWidth().weight(1f)) {
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
                Row(modifier = Modifier.fillMaxWidth().weight(1f)) {
                    row2Symbols.drop(5).forEach { sym ->
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
                            fontSize = 16.sp,
                        )
                    }
                }
                Row(modifier = Modifier.fillMaxWidth().weight(1f)) {
                    row3Symbols.drop(4).forEach { sym ->
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
                            fontSize = 16.sp,
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
                        onSwipeLeft = { suppressCursorMove.value = true; onKeyPress("clear_composition") },
                        onSwipeStateChange = onSwipeStateChange,
                        shadowEnabled = shadowEnabled,
                        shadowElevation = shadowElevation,
                        shadowShapeRadius = shadowShapeRadius,
                    )
                }
                Row(modifier = Modifier.fillMaxWidth().weight(1f)) {
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
                        text = periodChar,
                        onClick = { onKeyPress(periodChar) },
                        backgroundColor = keyBackgroundColor,
                        textColor = keyTextColor,
                        modifier = Modifier.weight(1f),
                        onPress = { onKeyPressDown?.invoke(periodChar) },
                        shadowEnabled = shadowEnabled,
                        shadowElevation = shadowElevation,
                        shadowShapeRadius = shadowShapeRadius,
                        fontSize = 14.sp,
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
                        fontSize = 12.sp,
                    )
                }
            }
        }
    }
}
