package com.kingzcheung.xime.ui

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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kingzcheung.xime.util.CharInfo

/**
 * 纯英文键盘布局，无中文字根/手势配置，跟随 Shift 切换大小写。
 * 样式完全对齐 KeyboardLayout。
 */
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
    modifier: Modifier = Modifier,
    onKeyPressDown: ((String) -> Unit)? = null,
) {
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

    Box(
        modifier = modifier
            .background(keyboardBackgroundColor)
            .onGloballyPositioned { coordinates ->
                keyboardBounds = coordinates.boundsInRoot()
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
                onKeyPressDown = onKeyPressDown,
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
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    // 第一行
                    Box(modifier = Modifier.weight(1f)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
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
                                    onLongPressSelect = { selected -> onKeyPress(selected) }
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
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
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
                                    onLongPressSelect = { selected -> onKeyPress(selected) }
                                )
                            }
                        }
                    }

                    // 第三行 — 对齐 KeyboardLayout 的权重 (1.2f)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .background(keyboardBackgroundColor),
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        // Shift 键
                        IconKeyButton(
                            icon = rememberVectorPainter(Icons.TwoTone.KeyboardCapslock),
                            onClick = { onKeyPress("shift") },
                            backgroundColor = specialKeyBackgroundColor,
                            iconColor = keyTextColor,
                            modifier = Modifier.width(40.dp).fillMaxHeight(),
                            isHighlighted = isShifted,
                            onPress = { onKeyPressDown?.invoke("shift") }
                        )
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
                                onLongPressSelect = { selected -> onKeyPress(selected) }
                            )
                        }
                        // 退格键 — 对齐 KeyboardLayout 功能：长按连续删除、上滑清空等
                        SwipeableIconKeyButton(
                            icon = rememberVectorPainter(Icons.AutoMirrored.Filled.Backspace),
                            onClick = { onKeyPress("delete") },
                            backgroundColor = specialKeyBackgroundColor,
                            iconColor = keyTextColor,
                            modifier = Modifier.width(48.dp).fillMaxHeight(),
                            swipeText = "清空",
                            onSwipe = { onKeyPress("clear_composition") },
                            onLongClick = { onKeyPress("delete") },
                            onPress = { onKeyPressDown?.invoke("delete") },
                            swipeUpLabel = "上滑清空",
                            swipeDownLabel = "下滑撤回",
                            onSwipeUp = { onKeyPress("clear_all") },
                            onSwipeDown = { onKeyPress("undo_clear") },
                            onSwipeLeft = { onKeyPress("clear_composition") },
                            onSwipeStateChange = { state, bounds -> processSwipeState(state, bounds) }
                        )
                    }

                    // 第四行（控制行）— 对齐 KeyboardLayout 样式
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .background(keyboardBackgroundColor),
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        KeyButton(
                            text = "123",
                            onClick = { onKeyPress("mode_change") },
                            backgroundColor = specialKeyBackgroundColor,
                            textColor = keyTextColor,
                            modifier = Modifier.weight(1.2f),
                            onPress = { onKeyPressDown?.invoke("mode_change") }
                        )

                        // 标点键 .
                        KeyButton(
                            text = ".",
                            onClick = { onKeyPress(".") },
                            backgroundColor = keyBackgroundColor,
                            textColor = keyTextColor,
                            modifier = Modifier.weight(0.8f),
                            onPress = { onKeyPressDown?.invoke(".") }
                        )

                        // 空格键 — 使用 KeyButton 以获得震动/音效和按下效果
                        KeyButton(
                            text = "英文",
                            onClick = { onKeyPress("space") },
                            backgroundColor = keyBackgroundColor,
                            textColor = keyTextColor,
                            modifier = Modifier.weight(3f),
                            fontSize = 14.sp,
                            onPress = { onKeyPressDown?.invoke("space") }
                        )

                        KeyButton(
                            text = "英",
                            onClick = { onKeyPress("ime_switch") },
                            backgroundColor = specialKeyBackgroundColor,
                            textColor = keyTextColor,
                            modifier = Modifier.weight(0.8f),
                            onPress = { onKeyPressDown?.invoke("ime_switch") }
                        )

                        // 回车键
                        KeyButton(
                            text = enterKeyText,
                            onClick = { onKeyPress("enter") },
                            backgroundColor = specialKeyBackgroundColor,
                            textColor = keyTextColor,
                            modifier = Modifier.weight(1.2f),
                            onPress = { onKeyPressDown?.invoke("enter") }
                        )
                    }
                }
            }
        }

        SwipeBubble(
            swipeState = swipeState,
            keyBounds = lastKeyBounds,
            isDarkTheme = isDarkTheme,
            keyWidth = if (swipeState.isSwiping || swipeState.isPressed) lastKeyBounds.width else 0f,
            keyboardWidth = keyboardBounds.width
        )
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
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Box(modifier = Modifier.weight(1f)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    listOf("q", "w", "e", "r", "t").forEach { key ->
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
            Box(modifier = Modifier.weight(1f).padding(start = staggerStep)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
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
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
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
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                IconKeyButton(
                    icon = rememberVectorPainter(Icons.Default.EmojiEmotions),
                    onClick = { onKeyPress("emoji") },
                    backgroundColor = specialKeyBackgroundColor,
                    iconColor = keyTextColor,
                    modifier = Modifier.weight(1.2f),
                    onPress = { onKeyPressDown?.invoke("emoji") }
                )
                KeyButton(
                    text = ".",
                    onClick = { onKeyPress(".") },
                    backgroundColor = keyBackgroundColor,
                    textColor = keyTextColor,
                    modifier = Modifier.weight(0.8f),
                    onPress = { onKeyPressDown?.invoke(".") }
                )
                SplitSpaceKey(
                    onClick = { onKeyPress("space") },
                    backgroundColor = keyBackgroundColor,
                    textColor = keyTextColor,
                    schemaName = "英文",
                    modifier = Modifier.weight(3f),
                    onPress = { onKeyPressDown?.invoke("space") }
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
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Box(modifier = Modifier.weight(1f)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
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
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
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
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Box(modifier = Modifier.weight(4f)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        listOf("v", "b", "n", "m").forEach { key ->
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
                IconKeyButton(
                    icon = rememberVectorPainter(Icons.AutoMirrored.Filled.Backspace),
                    onClick = { onKeyPress("delete") },
                    backgroundColor = specialKeyBackgroundColor,
                    iconColor = keyTextColor,
                    modifier = Modifier.width(48.dp).fillMaxHeight(),
                    onPress = { onKeyPressDown?.invoke("delete") }
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth().weight(1f),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                SplitSpaceKey(
                    onClick = { onKeyPress("space") },
                    backgroundColor = keyBackgroundColor,
                    textColor = keyTextColor,
                    schemaName = "",
                    modifier = Modifier.weight(2f),
                    onPress = { onKeyPressDown?.invoke("space") }
                )
                KeyButton(
                    text = "123",
                    onClick = { onKeyPress("mode_change") },
                    backgroundColor = specialKeyBackgroundColor,
                    textColor = keyTextColor,
                    modifier = Modifier.weight(1.2f),
                    onPress = { onKeyPressDown?.invoke("mode_change") }
                )
                KeyButton(
                    text = "英",
                    onClick = { onKeyPress("ime_switch") },
                    backgroundColor = specialKeyBackgroundColor,
                    textColor = keyTextColor,
                    modifier = Modifier.weight(0.8f),
                    onPress = { onKeyPressDown?.invoke("ime_switch") }
                )
                KeyButton(
                    text = enterKeyText,
                    onClick = { onKeyPress("enter") },
                    backgroundColor = specialKeyBackgroundColor,
                    textColor = keyTextColor,
                    modifier = Modifier.weight(1.2f),
                    onPress = { onKeyPressDown?.invoke("enter") }
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
    onPress: (() -> Unit)? = null
) {
    Box(
        modifier = modifier
            .fillMaxHeight()
            .shadow(1.dp, RoundedCornerShape(8.dp), ambientColor = Color(0x80000000), spotColor = Color(0x80000000))
            .clip(RoundedCornerShape(8.dp))
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
