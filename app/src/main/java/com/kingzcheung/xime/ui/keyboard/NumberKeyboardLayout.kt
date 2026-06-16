package com.kingzcheung.xime.ui.keyboard

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Backspace
import androidx.compose.material.icons.filled.EmojiEmotions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.kingzcheung.xime.util.SubcharHelper

/**
 * 九宫格数字键盘布局
 * 第1行：符号 | 1 | 2 | 3 | 退格
 * 第2行：符号 | 4 | 5 | 6 | 符号切换
 * 第3行：符号 | 7 | 8 | 9 | 表情
 * 第4行：ABC | / | 0 | . | 确定
 */
@Composable
fun NumberKeyboardLayout(
    onKeyPress: (String) -> Unit,
    keyBackgroundColor: Color,
    keyTextColor: Color,
    specialKeyBackgroundColor: Color,
    keyboardBackgroundColor: Color = Color.Transparent,
    shadowEnabled: Boolean = true,
    shadowElevation: Dp = 1.dp,
    shadowShapeRadius: Dp = 8.dp,
    modifier: Modifier = Modifier,
    onKeyPressDown: ((String) -> Unit)? = null
) {
    val symbols = listOf("+", "-", "*")
    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
    val commonSymbols = listOf(
        "~", "!", "#", "$", "%", "^",
        "&", "*", "(", ")", "_", "=",
        "[", "]", "{", "}", "\\", "|",
        ";", ":", "'", "\"", "<", ">"
    )

    var swipeState by remember { mutableStateOf(SwipeState()) }
    var keyboardBounds by remember { mutableStateOf(Rect(0f, 0f, 0f, 0f)) }
    var lastKeyBounds by remember { mutableStateOf(Rect(0f, 0f, 0f, 0f)) }

    val isDarkTheme = keyTextColor == Color(0xFFE8EAED)

    Box(
        modifier = modifier
            .background(keyboardBackgroundColor)
            .onGloballyPositioned { coordinates ->
                keyboardBounds = coordinates.boundsInRoot()
            }
    ) {
        if (isLandscape) {
            // 横屏：左侧常用符号区 + 右侧数字键盘
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(vertical = 8.dp, horizontal = 50.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // 左侧：常用符号区（6列 × 5行）
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

                // 右侧：数字键盘（与原竖屏布局完全一致）
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight(),
                ) {
                    // 第一行：符号 | 1 | 2 | 3 | 退格
                    Row(
                        modifier = Modifier.fillMaxWidth().weight(1f),
                    ) {
                        KeyButton(text = "+", onClick = { onKeyPress("+") }, backgroundColor = keyBackgroundColor, textColor = keyTextColor, modifier = Modifier.weight(1f), onPress = { onKeyPressDown?.invoke("+") }, shadowEnabled = shadowEnabled, shadowElevation = shadowElevation, shadowShapeRadius = shadowShapeRadius)
                        listOf("1","2","3").forEach { k -> KeyButton(text = k, onClick = { onKeyPress(k) }, backgroundColor = keyBackgroundColor, textColor = keyTextColor, modifier = Modifier.weight(1f), onPress = { onKeyPressDown?.invoke(k) }, shadowEnabled = shadowEnabled, shadowElevation = shadowElevation, shadowShapeRadius = shadowShapeRadius) }
                        SwipeableIconKeyButton(icon = rememberVectorPainter(Icons.AutoMirrored.Filled.Backspace), onClick = { onKeyPress("delete") }, backgroundColor = specialKeyBackgroundColor, iconColor = keyTextColor, modifier = Modifier.weight(1f), swipeText = "清空", onSwipe = { onKeyPress("clear_composition") }, onLongClick = { onKeyPress("delete") }, onPress = { onKeyPressDown?.invoke("delete") }, swipeUpLabel = "上滑清空", swipeDownLabel = "下滑撤回", onSwipeUp = { onKeyPress("clear_all") }, onSwipeDown = { onKeyPress("undo_clear") }, onSwipeStateChange = { state, bounds ->     val ns = if (state.isSwipeDown && state.swipeText != null) state.copy(charInfos = SubcharHelper.parseSwipeDownText(state.swipeText)) else state
                            swipeState = ns
                            lastKeyBounds = Rect(left = bounds.left - keyboardBounds.left, top = bounds.top - keyboardBounds.top, right = bounds.right - keyboardBounds.left, bottom = bounds.bottom - keyboardBounds.top)
                        }, shadowEnabled = shadowEnabled, shadowElevation = shadowElevation, shadowShapeRadius = shadowShapeRadius)
                    }
                    // 第二行：符号 | 4 | 5 | 6 | 空格
                    Row(
                        modifier = Modifier.fillMaxWidth().weight(1f),
                    ) {
                        KeyButton(text = "-", onClick = { onKeyPress("-") }, backgroundColor = keyBackgroundColor, textColor = keyTextColor, modifier = Modifier.weight(1f), onPress = { onKeyPressDown?.invoke("-") }, shadowEnabled = shadowEnabled, shadowElevation = shadowElevation, shadowShapeRadius = shadowShapeRadius)
                        listOf("4","5","6").forEach { k -> KeyButton(text = k, onClick = { onKeyPress(k) }, backgroundColor = keyBackgroundColor, textColor = keyTextColor, modifier = Modifier.weight(1f), onPress = { onKeyPressDown?.invoke(k) }, shadowEnabled = shadowEnabled, shadowElevation = shadowElevation, shadowShapeRadius = shadowShapeRadius) }
                        KeyButton(text = "符号", onClick = { onKeyPress("symbol") }, backgroundColor = specialKeyBackgroundColor, textColor = keyTextColor, modifier = Modifier.weight(1f), onPress = { onKeyPressDown?.invoke("symbol") }, shadowEnabled = shadowEnabled, shadowElevation = shadowElevation, shadowShapeRadius = shadowShapeRadius)
                    }
                    // 第三行：符号 | 7 | 8 | 9 | 表情
                    Row(
                        modifier = Modifier.fillMaxWidth().weight(1f),
                    ) {
                        KeyButton(text = "*", onClick = { onKeyPress("*") }, backgroundColor = keyBackgroundColor, textColor = keyTextColor, modifier = Modifier.weight(1f), onPress = { onKeyPressDown?.invoke("*") }, shadowEnabled = shadowEnabled, shadowElevation = shadowElevation, shadowShapeRadius = shadowShapeRadius)
                        listOf("7","8","9").forEach { k -> KeyButton(text = k, onClick = { onKeyPress(k) }, backgroundColor = keyBackgroundColor, textColor = keyTextColor, modifier = Modifier.weight(1f), onPress = { onKeyPressDown?.invoke(k) }, shadowEnabled = shadowEnabled, shadowElevation = shadowElevation, shadowShapeRadius = shadowShapeRadius) }
                        IconKeyButton(icon = rememberVectorPainter(Icons.Default.EmojiEmotions), onClick = { onKeyPress("emoji") }, backgroundColor = specialKeyBackgroundColor, iconColor = keyTextColor, modifier = Modifier.weight(1f), onPress = { onKeyPressDown?.invoke("emoji") }, shadowEnabled = shadowEnabled, shadowElevation = shadowElevation, shadowShapeRadius = shadowShapeRadius)
                    }
                    // 第四行：返回 | 符号切换 | 0 | . | 确定
                    Row(
                        modifier = Modifier.fillMaxWidth().weight(1f),
                    ) {
                        IconKeyButton(icon = rememberVectorPainter(Icons.AutoMirrored.Filled.ArrowBack), onClick = { onKeyPress("abc") }, backgroundColor = specialKeyBackgroundColor, iconColor = keyTextColor, modifier = Modifier.weight(1f), onPress = { onKeyPressDown?.invoke("abc") }, shadowEnabled = shadowEnabled, shadowElevation = shadowElevation, shadowShapeRadius = shadowShapeRadius)
                        KeyButton(text = "/", onClick = { onKeyPress("/") }, backgroundColor = keyBackgroundColor, textColor = keyTextColor, modifier = Modifier.weight(1f), onPress = { onKeyPressDown?.invoke("/") }, shadowEnabled = shadowEnabled, shadowElevation = shadowElevation, shadowShapeRadius = shadowShapeRadius)
                        KeyButton(text = "0", onClick = { onKeyPress("0") }, backgroundColor = keyBackgroundColor, textColor = keyTextColor, modifier = Modifier.weight(1.5f), onPress = { onKeyPressDown?.invoke("0") }, shadowEnabled = shadowEnabled, shadowElevation = shadowElevation, shadowShapeRadius = shadowShapeRadius)
                        KeyButton(text = ".", onClick = { onKeyPress(".") }, backgroundColor = keyBackgroundColor, textColor = keyTextColor, modifier = Modifier.weight(1f), onPress = { onKeyPressDown?.invoke(".") }, shadowEnabled = shadowEnabled, shadowElevation = shadowElevation, shadowShapeRadius = shadowShapeRadius)
                        KeyButton(text = "确定", onClick = { onKeyPress("enter") }, backgroundColor = specialKeyBackgroundColor, textColor = keyTextColor, modifier = Modifier.weight(1.2f), onPress = { onKeyPressDown?.invoke("enter") }, shadowEnabled = shadowEnabled, shadowElevation = shadowElevation, shadowShapeRadius = shadowShapeRadius)
                    }
                }
            }
        } else {
            // 竖屏：原有布局
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight()
                    .background(keyboardBackgroundColor)
                    .padding(vertical = 8.dp, horizontal = 4.dp),
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

        Box(Modifier.fillMaxSize()) {
            SwipeBubble(
                swipeState = swipeState,
                keyBounds = lastKeyBounds,
                isDarkTheme = isDarkTheme,
                keyWidth = if (swipeState.isSwiping || swipeState.isPressed) lastKeyBounds.width else 0f,
                keyboardWidth = keyboardBounds.width
            )
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
    onSwipeStateChange: ((SwipeState, Rect) -> Unit)? = null
) {
    val symbols = listOf("+", "-", "*")
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .fillMaxHeight(),
    ) {
        // 第一行：符号 | 1 | 2 | 3 | 退格
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
        ) {
            KeyButton(
                text = symbols[0],
                onClick = { onKeyPress(symbols[0]) },
                backgroundColor = keyBackgroundColor,
                textColor = keyTextColor,
                modifier = Modifier.weight(1f),
                onPress = { onKeyPressDown?.invoke(symbols[0]) },
                shadowEnabled = shadowEnabled,
                shadowElevation = shadowElevation,
                shadowShapeRadius = shadowShapeRadius,
            )
            listOf("1", "2", "3").forEach { key ->
                KeyButton(
                    text = key,
                    onClick = { onKeyPress(key) },
                    backgroundColor = keyBackgroundColor,
                    textColor = keyTextColor,
                    modifier = Modifier.weight(1f),
                    onPress = { onKeyPressDown?.invoke(key) },
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
                onSwipeStateChange = onSwipeStateChange,
                shadowEnabled = shadowEnabled,
                shadowElevation = shadowElevation,
                shadowShapeRadius = shadowShapeRadius,
            )
        }

        // 第二行：符号 | 4 | 5 | 6 | 空格
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
        ) {
            KeyButton(
                text = symbols[1],
                onClick = { onKeyPress(symbols[1]) },
                backgroundColor = keyBackgroundColor,
                textColor = keyTextColor,
                modifier = Modifier.weight(1f),
                onPress = { onKeyPressDown?.invoke(symbols[1]) },
                shadowEnabled = shadowEnabled,
                shadowElevation = shadowElevation,
                shadowShapeRadius = shadowShapeRadius,
            )
            listOf("4", "5", "6").forEach { key ->
                KeyButton(
                    text = key,
                    onClick = { onKeyPress(key) },
                    backgroundColor = keyBackgroundColor,
                    textColor = keyTextColor,
                    modifier = Modifier.weight(1f),
                    onPress = { onKeyPressDown?.invoke(key) },
                    shadowEnabled = shadowEnabled,
                    shadowElevation = shadowElevation,
                    shadowShapeRadius = shadowShapeRadius,
                )
            }
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
        }

        // 第三行：符号 | 7 | 8 | 9 | 表情
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
        ) {
            KeyButton(
                text = symbols[2],
                onClick = { onKeyPress(symbols[2]) },
                backgroundColor = keyBackgroundColor,
                textColor = keyTextColor,
                modifier = Modifier.weight(1f),
                onPress = { onKeyPressDown?.invoke(symbols[2]) },
                shadowEnabled = shadowEnabled,
                shadowElevation = shadowElevation,
                shadowShapeRadius = shadowShapeRadius,
            )
            listOf("7", "8", "9").forEach { key ->
                KeyButton(
                    text = key,
                    onClick = { onKeyPress(key) },
                    backgroundColor = keyBackgroundColor,
                    textColor = keyTextColor,
                    modifier = Modifier.weight(1f),
                    onPress = { onKeyPressDown?.invoke(key) },
                    shadowEnabled = shadowEnabled,
                    shadowElevation = shadowElevation,
                    shadowShapeRadius = shadowShapeRadius,
                )
            }
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

        // 第四行：返回 | 符号切换 | 0 | . | 确定
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
        ) {
            IconKeyButton(
                icon = rememberVectorPainter(Icons.AutoMirrored.Filled.ArrowBack),
                onClick = { onKeyPress("abc") },
                backgroundColor = specialKeyBackgroundColor,
                iconColor = keyTextColor,
                modifier = Modifier.weight(1f),
                onPress = { onKeyPressDown?.invoke("abc") },
                shadowEnabled = shadowEnabled,
                shadowElevation = shadowElevation,
                shadowShapeRadius = shadowShapeRadius,
            )
            KeyButton(
                text = "/",
                onClick = { onKeyPress("/") },
                backgroundColor = keyBackgroundColor,
                textColor = keyTextColor,
                modifier = Modifier.weight(1f),
                onPress = { onKeyPressDown?.invoke("/") },
                shadowEnabled = shadowEnabled,
                shadowElevation = shadowElevation,
                shadowShapeRadius = shadowShapeRadius,
            )
            KeyButton(
                text = "0",
                onClick = { onKeyPress("0") },
                backgroundColor = keyBackgroundColor,
                textColor = keyTextColor,
                modifier = Modifier.weight(1.5f),
                onPress = { onKeyPressDown?.invoke("0") },
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