package com.kingzcheung.xime.ui.keyboard

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kingzcheung.xime.util.SubcharHelper

/**
 * 九宫格数字键盘布局
 * 第1行：+ | 1 | 2 | 3 | 退格
 * 第2行：- | 4 | 5 | 6 | 符号切换
 * 第3行：* | 7 | 8 | 9 | 表情
 * 第4行：ABC | / | 0 | . | 确定
 *
 * [backKeyOnLeft] 为 true 时，左下角的「符号」键与数字区底行的「返回(ABC)」键互换位置：
 * 从全键盘 ?123（位于左下角）进入数字键盘时，返回键保持在用户的进入位置；
 * 默认 false 保持九键布局习惯（符号键在最左下角）。
 */
@Composable
fun NumberKeyboardLayout(
    onKeyPress: (String) -> Unit,
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
    backKeyOnLeft: Boolean = false,
) {

    val configuration = LocalConfiguration.current
    val isLandscape = !isFloatingMode && configuration.screenWidthDp > configuration.screenHeightDp
    val commonSymbols = listOf(
        "~", "!", "#", "$", "%", "^", "&", "?",
        "(", ")", "_", "=", "[", "]", "{", "}",
        "\\", "|", ";", ":", "'", "\"", "<", ">"
    )

    val swipeBubble = rememberSwipeBubbleController()
    var keyboardBounds by remember { mutableStateOf(Rect(0f, 0f, 0f, 0f)) }

    val isDarkTheme = keyTextColor == Color(0xFFE8EAED)

    val bubbleData = rememberSwipeBubbleDrawData(
        swipeState = swipeBubble.state,
        keyBounds = swipeBubble.keyBounds,
        keyBackgroundColor = bubbleBackgroundColor,
        keyTextColor = keyTextColor,
        accentColor = specialKeyTextColor,
        keyWidth = if (swipeBubble.state.isSwiping || swipeBubble.state.isPressed) swipeBubble.keyBounds.width else 0f,
        keyboardWidth = keyboardBounds.width
    )

    fun processSwipeState(state: SwipeState, bounds: Rect) {
        val newState = if (state.isSwipeDown && state.swipeText != null) {
            state.copy(charInfos = SubcharHelper.parseSwipeDownText(state.swipeText))
        } else state
        swipeBubble.update(
            newState,
            Rect(
                left = bounds.left - keyboardBounds.left,
                top = bounds.top - keyboardBounds.top,
                right = bounds.right - keyboardBounds.left,
                bottom = bounds.bottom - keyboardBounds.top
            )
        )
    }

    CompositionLocalProvider(LocalKeyCornerRadius provides keyCornerRadius) {
    Box(
        modifier = modifier
            .onGloballyPositioned { coordinates ->
                keyboardBounds = coordinates.boundsInRoot()
            }
            .drawWithContent {
                drawContent()
                bubbleData?.let { drawSwipeBubble(it) }
            }
            .padding(bottom = if (isFloatingMode || isLandscape) 0.dp else 0.dp)) {
        if (isLandscape) {
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(vertical = 2.dp, horizontal = 50.dp),
            ) {
                // 左侧：常用符号区（6列 × 4行）
                Column(
                    modifier = Modifier
                        .weight(0.42f)
                        .fillMaxHeight(),
                ) {
                    CompositionLocalProvider(
                        LocalKeyVisualPadding provides PaddingValues(
                            horizontal = keySpacingX ?: 2.dp,
                            vertical = keySpacingY ?: 2.dp,
                        )
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
                                    fontSize = 14.sp,
                                )
                            }
                            repeat(6 - rowSymbols.size) {
                                Box(modifier = Modifier.weight(1f))
                            }
                        }
                    }
                    }
                }

                Spacer(modifier = Modifier.weight(0.16f))

                // 右侧：数字键盘（与竖屏完全一致）
                Box(
                    modifier = Modifier
                        .weight(0.42f)
                        .fillMaxHeight()
                ) {
                    CompositionLocalProvider(
                        LocalKeyVisualPadding provides PaddingValues(
                            horizontal = keySpacingX ?: 2.dp,
                            vertical = keySpacingY ?: 2.dp,
                        )
                    ) {
                    NumberRows(
                        onKeyPress = onKeyPress,
                        keyBackgroundColor = keyBackgroundColor,
                        keyTextColor = keyTextColor,
                        specialKeyBackgroundColor = specialKeyBackgroundColor,
                        shadowEnabled = shadowEnabled,
                        shadowElevation = shadowElevation,
                        shadowShapeRadius = shadowShapeRadius,
                        onKeyPressDown = onKeyPressDown,
                        compactMode = true,
                        specialKeyTextColor = specialKeyTextColor,
                        backKeyOnLeft = backKeyOnLeft,
                        onSwipeStateChange = ::processSwipeState
                    )
                    }
                }
            }
        } else {
            // 竖屏：原有布局
            CompositionLocalProvider(
                LocalKeyVisualPadding provides PaddingValues(
                    horizontal = keySpacingX ?: 2.dp,
                    vertical = keySpacingY ?: 2.dp,
                )
            ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight()
                    .padding(start = 4.dp, end = 4.dp, bottom = 8.dp),
            ) {
                NumberRows(
                    onKeyPress = onKeyPress,
                    keyBackgroundColor = keyBackgroundColor,
                    keyTextColor = keyTextColor,
                    specialKeyBackgroundColor = specialKeyBackgroundColor,
                    shadowEnabled = shadowEnabled,
                    shadowElevation = shadowElevation,
                    shadowShapeRadius = shadowShapeRadius,
                    onKeyPressDown = onKeyPressDown,
                    specialKeyTextColor = specialKeyTextColor,
                    backKeyOnLeft = backKeyOnLeft,
                    onSwipeStateChange = ::processSwipeState
                )
            }
            }
        }

    }
    }
}

@Composable
private fun NumberRows(
    onKeyPress: (String) -> Unit,
    keyBackgroundColor: Color,
    keyTextColor: Color,
    specialKeyBackgroundColor: Color,
    shadowEnabled: Boolean = true,
    shadowElevation: Dp = 1.dp,
    shadowShapeRadius: Dp = 8.dp,
    onKeyPressDown: ((String) -> Unit)? = null,
    onSwipeStateChange: ((SwipeState, Rect) -> Unit)? = null,
    compactMode: Boolean = false,
    specialKeyTextColor: Color = Color.White,
    backKeyOnLeft: Boolean = false,
) {
    val symFontSize = if (compactMode) 14.sp else 18.sp
    val keyFontSize = if (compactMode) 16.sp else androidx.compose.ui.unit.TextUnit.Unspecified
    val ctrlFontSize = if (compactMode) 12.sp else androidx.compose.ui.unit.TextUnit.Unspecified
    val suppressCursorMove = LocalSuppressCursorMove.current
    val symbols = listOf("+", "-", "*", "/")
    Row(
        modifier = Modifier
            .fillMaxSize(),
        horizontalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight()
                .weight(0.8f),
            verticalArrangement = Arrangement.spacedBy(if (compactMode) 2.dp else 4.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight()
                    .weight(3f),
                horizontalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Column(
                    modifier = Modifier
//                        .padding(vertical = 2.dp)
                        .fillMaxHeight()
                        .weight(0.8f),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxHeight()
                            .weight(3f)
                            .padding(LocalKeyVisualPadding.current),
                    ) {
                        symbols.forEach { symbol ->
                            NumberSymbolKey(
                                text = symbol,
                                onClick = { onKeyPress(symbol) },
                                backgroundColor = keyBackgroundColor,
                                textColor = keyTextColor,
                                modifier = Modifier.weight(1f),
                                onPress = { onKeyPressDown?.invoke(symbol) },
                                isFirst = symbol == "+",
                                isLast = symbol == "/",
                                fontSize = symFontSize,
                                shadowEnabled = shadowEnabled,
                                shadowElevation = shadowElevation,
                                shadowShapeRadius = shadowShapeRadius,
                            )
                        }
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxHeight()
                            .weight(1f),
                    ) {
                        if (backKeyOnLeft) {
                            IconKeyButton(
                                icon = rememberVectorPainter(Icons.AutoMirrored.Filled.ArrowBack),
                                onClick = { onKeyPress("abc") },
                                backgroundColor = specialKeyBackgroundColor,
                                iconColor = specialKeyTextColor,
                                modifier = Modifier.weight(1f),
                                a11yDescription = "返回字母键盘",
                                onPress = { onKeyPressDown?.invoke("abc") },
                                shadowEnabled = shadowEnabled,
                                shadowElevation = shadowElevation,
                                shadowShapeRadius = shadowShapeRadius,
                            )
                        } else {
                            KeyButton(
                                text = "符号",
                                onClick = { onKeyPress("symbol") },
                                backgroundColor = specialKeyBackgroundColor,
                                textColor = specialKeyTextColor,
                                modifier = Modifier.weight(1f),
                                onPress = { onKeyPressDown?.invoke("symbol") },
                                shadowEnabled = shadowEnabled,
                                shadowElevation = shadowElevation,
                                shadowShapeRadius = shadowShapeRadius,
                                fontSize = ctrlFontSize,
                            )
                        }
                    }

                }

                Column(
                    modifier = Modifier
                        .fillMaxHeight()
                        .weight(3.4f),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                    ) {
                        listOf("1", "2", "3").forEach { key ->
                            KeyButton(
                                text = key,
                                onClick = { onKeyPress(key) },
                                backgroundColor = keyBackgroundColor,
                                textColor = keyTextColor,
                                onPress = { onKeyPressDown?.invoke(key) },
                                shadowEnabled = shadowEnabled,
                                shadowElevation = shadowElevation,
                                shadowShapeRadius = shadowShapeRadius,
                                modifier = Modifier
                                    .weight(1f),
                                fontSize = keyFontSize,
                            )
                        }

                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                    ) {

                        listOf("4", "5", "6").forEach { key ->
                            KeyButton(
                                text = key,
                                onClick = { onKeyPress(key) },
                                backgroundColor = keyBackgroundColor,
                                textColor = keyTextColor,
                                onPress = { onKeyPressDown?.invoke(key) },
                                shadowEnabled = shadowEnabled,
                                shadowElevation = shadowElevation,
                                shadowShapeRadius = shadowShapeRadius,
                                modifier = Modifier
                                    .weight(1f),
                                fontSize = keyFontSize,
                            )
                        }

                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                    ) {

                        listOf("7", "8", "9").forEach { key ->
                            KeyButton(
                                text = key,
                                onClick = { onKeyPress(key) },
                                backgroundColor = keyBackgroundColor,
                                textColor = keyTextColor,
                                modifier = Modifier
                                    .weight(1f),
                                onPress = { onKeyPressDown?.invoke(key) },
                                shadowEnabled = shadowEnabled,
                                shadowElevation = shadowElevation,
                                shadowShapeRadius = shadowShapeRadius,
                                fontSize = keyFontSize,
                            )
                        }

                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                    ) {


                        if (backKeyOnLeft) {
                            KeyButton(
                                text = "符号",
                                onClick = { onKeyPress("symbol") },
                                backgroundColor = specialKeyBackgroundColor,
                                textColor = specialKeyTextColor,
                                modifier = Modifier.weight(1f),
                                onPress = { onKeyPressDown?.invoke("symbol") },
                                shadowEnabled = shadowEnabled,
                                shadowElevation = shadowElevation,
                                shadowShapeRadius = shadowShapeRadius,
                                fontSize = ctrlFontSize,
                            )
                        } else {
                            IconKeyButton(
                                icon = rememberVectorPainter(Icons.AutoMirrored.Filled.ArrowBack),
                                onClick = { onKeyPress("abc") },
                                backgroundColor = specialKeyBackgroundColor,
                                iconColor = specialKeyTextColor,
                                modifier = Modifier.weight(1f),
                                a11yDescription = "返回字母键盘",
                                onPress = { onKeyPressDown?.invoke("abc") },
                                shadowEnabled = shadowEnabled,
                                shadowElevation = shadowElevation,
                                shadowShapeRadius = shadowShapeRadius,
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
                            fontSize = keyFontSize,
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
                            fontSize = keyFontSize,
                        )

                    }

                }
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .fillMaxHeight()
                        .weight(0.8f),
                ) {
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
                            suppressCursorMove.value = true
                            onKeyPress("clear_composition")
                        },
                        onSwipeStateChange = onSwipeStateChange,
                        shadowEnabled = shadowEnabled,
                        shadowElevation = shadowElevation,
                        shadowShapeRadius = shadowShapeRadius,
                    )

                    KeyButton(
                        text = "空格",
                        onClick = { onKeyPress("space") },
                        backgroundColor = specialKeyBackgroundColor,
                        textColor = specialKeyTextColor,
                        modifier = Modifier.weight(1f),
                        onPress = { onKeyPressDown?.invoke("space") },
                        shadowEnabled = shadowEnabled,
                        shadowElevation = shadowElevation,
                        shadowShapeRadius = shadowShapeRadius,
                        fontSize = ctrlFontSize,
                    )
                    IconKeyButton(
                        icon = rememberVectorPainter(Icons.Default.EmojiEmotions),
                        onClick = { onKeyPress("emoji") },
                        backgroundColor = specialKeyBackgroundColor,
                        iconColor = specialKeyTextColor,
                        modifier = Modifier.weight(1f),
                        a11yDescription = "表情",
                        onPress = { onKeyPressDown?.invoke("emoji") },
                        shadowEnabled = shadowEnabled,
                        shadowElevation = shadowElevation,
                        shadowShapeRadius = shadowShapeRadius,
                    )
                    KeyButton(
                        text = "确定",
                        onClick = { onKeyPress("enter") },
                        backgroundColor = specialKeyBackgroundColor,
                        textColor = specialKeyTextColor,
                        modifier = Modifier.weight(1f),
                        onPress = { onKeyPressDown?.invoke("enter") },
                        shadowEnabled = shadowEnabled,
                        shadowElevation = shadowElevation,
                        shadowShapeRadius = shadowShapeRadius,
                        fontSize = ctrlFontSize,
                    )
                }
            }
        }
    }
}

@Composable
private fun NumberSymbolKey(
    text: String,
    onClick: () -> Unit,
    backgroundColor: Color,
    textColor: Color,
    modifier: Modifier = Modifier,
    onPress: (() -> Unit)? = null,
    isFirst: Boolean = false,
    isLast: Boolean = false,
    fontSize: androidx.compose.ui.unit.TextUnit = 18.sp,
    shadowEnabled: Boolean = true,
    shadowElevation: Dp = 1.dp,
    shadowShapeRadius: Dp = 8.dp,
) {
    var isPressed by remember { mutableStateOf(false) }
    val currentOnClick by rememberUpdatedState(onClick)
    val currentOnPress by rememberUpdatedState(onPress)
    val cornerRadius = LocalKeyCornerRadius.current
    val shape = RoundedCornerShape(
        topStart = if (isFirst) cornerRadius else 0.dp,
        topEnd = if (isFirst) cornerRadius else 0.dp,
        bottomStart = if (isLast) cornerRadius else 0.dp,
        bottomEnd = if (isLast) cornerRadius else 0.dp
    )
    val density = LocalDensity.current
    val shadowModifier = remember(shadowEnabled, shadowElevation, shadowShapeRadius, density, backgroundColor) {
        if (shadowEnabled) {
            val offsetPx = with(density) { shadowElevation.toPx() }
            val cornerPx = with(density) { shadowShapeRadius.toPx() }
            val color = crispShadowColor(backgroundColor)
            Modifier.drawBehind {
                drawRoundRect(
                    color = color,
                    topLeft = Offset(0f, offsetPx),
                    size = size,
                    cornerRadius = CornerRadius(cornerPx)
                )
            }
        } else Modifier
    }
    Box(
        modifier = modifier
            .fillMaxWidth()
            .then(shadowModifier)
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
            fontSize = fontSize,
            fontWeight = FontWeight.Normal,
            modifier = Modifier.padding(vertical = 2.dp),
            fontFamily = AppFonts.keyFontFamily
        )
    }
}
