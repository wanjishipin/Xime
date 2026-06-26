package com.kingzcheung.xime.ui.keyboard

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Backspace
import androidx.compose.material.icons.filled.KeyboardCapslock
import androidx.compose.material.icons.filled.EmojiEmotions
import androidx.compose.material.icons.twotone.KeyboardCapslock
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kingzcheung.xime.keyboard.KeyboardDimensions
import com.kingzcheung.xime.util.CharInfo

/**
 * 纯英文键盘布局，无中文字根/手势配置，跟随 Shift 切换大小写。
 * 样式完全对齐 KeyboardLayout。
 */
@Deprecated("已废弃，英文键盘由 KeyboardLayout(isAsciiMode=true) 统一渲染", ReplaceWith("KeyboardLayout"))
@Composable
fun EnglishKeyboardLayout(
    onKeyPress: (String) -> Unit,
    isShifted: Boolean,
    isLandscape: Boolean = false,
    enterKeyText: String = "发送",
    isDarkTheme: Boolean = false,
    keyBackgroundColor: Color,
    keyTextColor: Color,
    specialKeyBackgroundColor: Color,
    keyboardBackgroundColor: Color = Color.Transparent,
    shadowEnabled: Boolean = true,
    shadowElevation: Dp = 1.dp,
    shadowShapeRadius: Dp = 8.dp,
    modifier: Modifier = Modifier,
    onKeyPressDown: ((String) -> Unit)? = null,
) {
    val suppressCursorMove = LocalSuppressCursorMove.current
    val row1 = listOf("q", "w", "e", "r", "t", "y", "u", "i", "o", "p")
    val row2 = listOf("a", "s", "d", "f", "g", "h", "j", "k", "l")
    val row3 = listOf("z", "x", "c", "v", "b", "n", "m")
    val swipeSymbols = mapOf(
        "q" to "1", "w" to "2", "e" to "3", "r" to "4", "t" to "5",
        "y" to "6", "u" to "7", "i" to "8", "o" to "9", "p" to "0",
        "a" to "~", "s" to "/", "d" to ":", "f" to ";", "g" to "'",
        "h" to "\"", "j" to "-", "k" to "(", "l" to ")",
        "z" to "*", "x" to "@", "c" to "&", "v" to "?", "b" to "!",
        "n" to "/", "m" to "_"
    )

    var swipeState by remember { mutableStateOf(SwipeState()) }
    var keyboardBounds by remember { mutableStateOf(Rect(0f, 0f, 0f, 0f)) }
    var lastKeyBounds by remember { mutableStateOf(Rect(0f, 0f, 0f, 0f)) }

    fun processSwipeState(state: SwipeState, bounds: Rect) {
        val newState = if (state.isSwipeDown && state.swipeText != null) {
            state.copy(charInfos = emptyList())
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
            LandscapeEnglishKeyboardContent(
                onKeyPress = onKeyPress,
                isShifted = isShifted,
                enterKeyText = enterKeyText,
                keyBackgroundColor = keyBackgroundColor,
                keyTextColor = keyTextColor,
                specialKeyBackgroundColor = specialKeyBackgroundColor,
                keyboardBackgroundColor = keyboardBackgroundColor,
                shadowEnabled = shadowEnabled,
                shadowElevation = shadowElevation,
                shadowShapeRadius = shadowShapeRadius,
                onKeyPressDown = onKeyPressDown,
            )
        } else {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight()
                    .background(keyboardBackgroundColor)
                    .padding(start = 4.dp, end = 4.dp, bottom = 8.dp)
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    verticalArrangement = Arrangement.spacedBy(KeyboardDimensions.RowSpacing),
                ) {
                    // 第一行
                    Box(modifier = Modifier.weight(1f)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            row1.forEach { key ->
                                val swipeText = swipeSymbols[key]
                                val longPressItems = listOfNotNull(key.lowercase(), swipeText, key.uppercase())
                                SwipeableKeyButton(
                                    text = if (isShifted) key.uppercase() else key,
                                    onClick = { onKeyPress(if (isShifted) key.uppercase() else key) },
                                    backgroundColor = keyBackgroundColor,
                                    textColor = keyTextColor,
                                    modifier = Modifier.weight(1f),
                                    swipeText = swipeText,
                                    onSwipe = if (swipeText != null) onKeyPress else null,
                                    onPress = { onKeyPressDown?.invoke(key) },
                                    onSwipeStateChange = { state, bounds -> processSwipeState(state, bounds) },
                                    longPressItems = longPressItems,
                                    onLongPressSelect = { selected -> onKeyPress(selected) },
                                    shadowEnabled = shadowEnabled,
                                    shadowElevation = shadowElevation,
                                    shadowShapeRadius = shadowShapeRadius,
                                )
                            }
                        }
                    }

                    // 第二行
                    Box(modifier = Modifier.weight(1f)) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp),
                        ) {
                            row2.forEach { key ->
                                val swipeText = swipeSymbols[key]
                                val longPressItems = listOfNotNull(key.lowercase(), swipeText, key.uppercase())
                                SwipeableKeyButton(
                                    text = if (isShifted) key.uppercase() else key,
                                    onClick = { onKeyPress(if (isShifted) key.uppercase() else key) },
                                    backgroundColor = keyBackgroundColor,
                                    textColor = keyTextColor,
                                    modifier = Modifier.weight(1f),
                                    swipeText = swipeText,
                                    onSwipe = if (swipeText != null) onKeyPress else null,
                                    onPress = { onKeyPressDown?.invoke(key) },
                                    onSwipeStateChange = { state, bounds -> processSwipeState(state, bounds) },
                                    longPressItems = longPressItems,
                                    onLongPressSelect = { selected -> onKeyPress(selected) },
                                    shadowEnabled = shadowEnabled,
                                    shadowElevation = shadowElevation,
                                    shadowShapeRadius = shadowShapeRadius,
                                )
                            }
                        }
                    }

                    // 第三行 — 对齐 KeyboardLayout 的权重 (1.4f + 7f + 1.4f)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .background(keyboardBackgroundColor),
                    ) {
                        // Shift 键
                        IconKeyButton(
                            icon = rememberVectorPainter(Icons.TwoTone.KeyboardCapslock),
                            onClick = { onKeyPress("shift") },
                            backgroundColor = specialKeyBackgroundColor,
                            iconColor = keyTextColor,
                            modifier = Modifier.weight(1.4f).fillMaxHeight(),
                            isHighlighted = isShifted,
                            onPress = { onKeyPressDown?.invoke("shift") },
                            shadowEnabled = shadowEnabled,
                            shadowElevation = shadowElevation,
                            shadowShapeRadius = shadowShapeRadius,
                        )
                        Row(
                            modifier = Modifier.weight(7f).fillMaxHeight().background(keyboardBackgroundColor),
                        ) {
                            row3.forEach { key ->
                                val swipeText = swipeSymbols[key]
                                val longPressItems = listOfNotNull(key.lowercase(), swipeText, key.uppercase())
                                SwipeableKeyButton(
                                    text = if (isShifted) key.uppercase() else key,
                                    onClick = { onKeyPress(if (isShifted) key.uppercase() else key) },
                                    backgroundColor = keyBackgroundColor,
                                    textColor = keyTextColor,
                                    modifier = Modifier.weight(1f),
                                    swipeText = swipeText,
                                    onSwipe = if (swipeText != null) onKeyPress else null,
                                    onPress = { onKeyPressDown?.invoke(key) },
                                    onSwipeStateChange = { state, bounds -> processSwipeState(state, bounds) },
                                    longPressItems = longPressItems,
                                    onLongPressSelect = { selected -> onKeyPress(selected) },
                                    shadowEnabled = shadowEnabled,
                                    shadowElevation = shadowElevation,
                                    shadowShapeRadius = shadowShapeRadius,
                                )
                            }
                        }
                        // 退格键 — 对齐 KeyboardLayout 功能：长按连续删除、上滑清空等
                        SwipeableIconKeyButton(
                            icon = rememberVectorPainter(Icons.AutoMirrored.Filled.Backspace),
                            onClick = { onKeyPress("delete") },
                            backgroundColor = specialKeyBackgroundColor,
                            iconColor = keyTextColor,
                            modifier = Modifier.weight(1.4f).fillMaxHeight(),
                            swipeText = "清空",
                            onSwipe = { onKeyPress("clear_composition") },
                            onLongClick = { onKeyPress("delete") },
                            onPress = { onKeyPressDown?.invoke("delete") },
                            swipeUpLabel = "上滑清空",
                            swipeDownLabel = "下滑撤回",
                            onSwipeUp = { onKeyPress("clear_all") },
                            onSwipeDown = { onKeyPress("undo_clear") },
                            onSwipeLeft = { suppressCursorMove.value = true; onKeyPress("clear_composition") },
                            onSwipeStateChange = { state, bounds -> processSwipeState(state, bounds) },
                            shadowEnabled = shadowEnabled,
                            shadowElevation = shadowElevation,
                            shadowShapeRadius = shadowShapeRadius,
                        )
                    }

                    // 第四行（控制行）— 对齐 KeyboardLayout 样式
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .background(keyboardBackgroundColor),
                    ) {
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

                        // 标点键 ，上滑 .
                        SwipeableKeyButton(
                            text = ",",
                            onClick = { onKeyPress(",") },
                            backgroundColor = keyBackgroundColor,
                            textColor = keyTextColor,
                            modifier = Modifier.weight(0.8f),
                            swipeText = ".",
                            onSwipe = onKeyPress,
                            onPress = { onKeyPressDown?.invoke(",") },
                            onSwipeStateChange = { state, bounds -> processSwipeState(state, bounds) },
                            shadowEnabled = shadowEnabled,
                            shadowElevation = shadowElevation,
                            shadowShapeRadius = shadowShapeRadius,
                        )

                        // 空格键 — 使用 KeyButton 以获得震动/音效和按下效果
                        KeyButton(
                            text = "英文",
                            onClick = { onKeyPress("space") },
                            backgroundColor = keyBackgroundColor,
                            textColor = keyTextColor,
                            modifier = Modifier.weight(3f),
                            fontSize = 14.sp,
                            onPress = { onKeyPressDown?.invoke("space") },
                            shadowEnabled = shadowEnabled,
                            shadowElevation = shadowElevation,
                            shadowShapeRadius = shadowShapeRadius,
                        )

                        KeyButton(
                            text = "英",
                            onClick = { onKeyPress("ime_switch") },
                            backgroundColor = specialKeyBackgroundColor,
                            textColor = keyTextColor,
                            modifier = Modifier.weight(0.8f),
                            onPress = { onKeyPressDown?.invoke("ime_switch") },
                            shadowEnabled = shadowEnabled,
                            shadowElevation = shadowElevation,
                            shadowShapeRadius = shadowShapeRadius,
                        )

                        // 回车键
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
}

/**
 * 横屏分体英文键盘内容 — 当 [EnglishKeyboardLayout.isLandscape] 为 true 时渲染。
 */
@Composable
private fun LandscapeEnglishKeyboardContent(
    onKeyPress: (String) -> Unit,
    isShifted: Boolean,
    enterKeyText: String,
    keyBackgroundColor: Color,
    keyTextColor: Color,
    specialKeyBackgroundColor: Color,
    keyboardBackgroundColor: Color,
    shadowEnabled: Boolean = true,
    shadowElevation: Dp = 1.dp,
    shadowShapeRadius: Dp = 8.dp,
    onKeyPressDown: ((String) -> Unit)?,
) {
    val staggerStep = 10.dp

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
                Row(
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    listOf("q", "w", "e", "r", "t").forEach { key ->
                        KeyButton(
                            text = if (isShifted) key.uppercase() else key,
                            onClick = { onKeyPress(if (isShifted) key.uppercase() else key) },
                            backgroundColor = keyBackgroundColor,
                            textColor = keyTextColor,
                            modifier = Modifier.weight(1f),
                            onPress = { onKeyPressDown?.invoke(key) },
                            shadowEnabled = shadowEnabled,
                            shadowElevation = shadowElevation,
                            shadowShapeRadius = shadowShapeRadius,
                        )
                    }
                }
            }
            Box(modifier = Modifier.weight(1f).padding(start = staggerStep)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    listOf("a", "s", "d", "f", "g").forEach { key ->
                        KeyButton(
                            text = if (isShifted) key.uppercase() else key,
                            onClick = { onKeyPress(if (isShifted) key.uppercase() else key) },
                            backgroundColor = keyBackgroundColor,
                            textColor = keyTextColor,
                            modifier = Modifier.weight(1f),
                            onPress = { onKeyPressDown?.invoke(key) }
                        )
                    }
                }
            }
            Box(modifier = Modifier.weight(1f).padding(start = staggerStep * 2)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    listOf("z", "x", "c", "v").forEach { key ->
                        KeyButton(
                            text = if (isShifted) key.uppercase() else key,
                            onClick = { onKeyPress(if (isShifted) key.uppercase() else key) },
                            backgroundColor = keyBackgroundColor,
                            textColor = keyTextColor,
                            modifier = Modifier.weight(1f),
                            onPress = { onKeyPressDown?.invoke(key) }
                        )
                    }
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth().weight(1f),
            ) {
                IconKeyButton(
                    icon = rememberVectorPainter(Icons.Default.EmojiEmotions),
                    onClick = { onKeyPress("emoji") },
                    backgroundColor = specialKeyBackgroundColor,
                    iconColor = keyTextColor,
                    modifier = Modifier.weight(1.2f),
                    onPress = { onKeyPressDown?.invoke("emoji") },
                    shadowEnabled = shadowEnabled,
                    shadowElevation = shadowElevation,
                    shadowShapeRadius = shadowShapeRadius,
                )
                KeyButton(
                    text = ".",
                    onClick = { onKeyPress(".") },
                    backgroundColor = keyBackgroundColor,
                    textColor = keyTextColor,
                    modifier = Modifier.weight(0.8f),
                    onPress = { onKeyPressDown?.invoke(".") },
                    shadowEnabled = shadowEnabled,
                    shadowElevation = shadowElevation,
                    shadowShapeRadius = shadowShapeRadius,
                )
                SplitSpaceKey(
                    onClick = { onKeyPress("space") },
                    backgroundColor = keyBackgroundColor,
                    textColor = keyTextColor,
                    schemaName = "英文",
                    modifier = Modifier.weight(3f),
                    onPress = { onKeyPressDown?.invoke("space") },
                    shadowEnabled = shadowEnabled,
                    shadowElevation = shadowElevation,
                    shadowShapeRadius = shadowShapeRadius,
                )
            }
        }

        Spacer(modifier = Modifier.weight(0.16f))

        // ========== 右面板 ==========
        Column(
            modifier = Modifier
                .fillMaxHeight()
                .weight(0.42f)
                .padding(end = 4.dp),
        ) {
            Box(modifier = Modifier.weight(1f)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    listOf("y", "u", "i", "o", "p").forEach { key ->
                        KeyButton(
                            text = if (isShifted) key.uppercase() else key,
                            onClick = { onKeyPress(if (isShifted) key.uppercase() else key) },
                            backgroundColor = keyBackgroundColor,
                            textColor = keyTextColor,
                            modifier = Modifier.weight(1f),
                            onPress = { onKeyPressDown?.invoke(key) }
                        )
                    }
                }
            }
            Box(modifier = Modifier.weight(1f).padding(end = staggerStep)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    listOf("g", "h", "j", "k", "l").forEach { key ->
                        KeyButton(
                            text = if (isShifted) key.uppercase() else key,
                            onClick = { onKeyPress(if (isShifted) key.uppercase() else key) },
                            backgroundColor = keyBackgroundColor,
                            textColor = keyTextColor,
                            modifier = Modifier.weight(1f),
                            onPress = { onKeyPressDown?.invoke(key) }
                        )
                    }
                }
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(end = staggerStep * 2),
            ) {
                Box(modifier = Modifier.weight(4f)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        listOf("v", "b", "n", "m").forEach { key ->
                            KeyButton(
                                text = if (isShifted) key.uppercase() else key,
                                onClick = { onKeyPress(if (isShifted) key.uppercase() else key) },
                                backgroundColor = keyBackgroundColor,
                                textColor = keyTextColor,
                                modifier = Modifier.weight(1f),
                                onPress = { onKeyPressDown?.invoke(key) },
                                shadowEnabled = shadowEnabled,
                                shadowElevation = shadowElevation,
                                shadowShapeRadius = shadowShapeRadius,
                            )
                        }
                    }
                }
                IconKeyButton(
                    icon = rememberVectorPainter(Icons.AutoMirrored.Filled.Backspace),
                    onClick = { onKeyPress("delete") },
                    backgroundColor = specialKeyBackgroundColor,
                    iconColor = keyTextColor,
                    modifier = Modifier.width(48.dp).fillMaxHeight(),
                    onPress = { onKeyPressDown?.invoke("delete") },
                    shadowEnabled = shadowEnabled,
                    shadowElevation = shadowElevation,
                    shadowShapeRadius = shadowShapeRadius,
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth().weight(1f),
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
                    text = "123",
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
                KeyButton(
                    text = "英",
                    onClick = { onKeyPress("ime_switch") },
                    backgroundColor = specialKeyBackgroundColor,
                    textColor = keyTextColor,
                    modifier = Modifier.weight(0.8f),
                    onPress = { onKeyPressDown?.invoke("ime_switch") },
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
