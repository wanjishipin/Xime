package com.kingzcheung.xime.ui.keyboard

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Backspace
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.unit.Dp
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
    modifier: Modifier = Modifier,
    onKeyPressDown: ((String) -> Unit)? = null,
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

    val isDarkTheme = keyTextColor == Color(0xFFE8EAED)
    var swipeState by remember { mutableStateOf(SwipeState()) }
    var keyboardBounds by remember { mutableStateOf(Rect(0f, 0f, 0f, 0f)) }
    var lastKeyBounds by remember { mutableStateOf(Rect(0f, 0f, 0f, 0f)) }

    val bubbleData = rememberSwipeBubbleDrawData(
        swipeState = swipeState,
        keyBounds = lastKeyBounds,
        isDarkTheme = isDarkTheme,
        keyWidth = if (swipeState.isSwiping || swipeState.isPressed) lastKeyBounds.width else 0f,
        keyboardWidth = keyboardBounds.width,
    )

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
            },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight()
                .padding(vertical = 8.dp, horizontal = 4.dp),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
            ) {
                (0..9).forEach { n ->
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
                    onSwipeStateChange = { state, bounds ->
                        swipeState = state
                        lastKeyBounds = Rect(
                            left = bounds.left - keyboardBounds.left,
                            top = bounds.top - keyboardBounds.top,
                            right = bounds.right - keyboardBounds.left,
                            bottom = bounds.bottom - keyboardBounds.top,
                        )
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
                    text = "数字",
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
