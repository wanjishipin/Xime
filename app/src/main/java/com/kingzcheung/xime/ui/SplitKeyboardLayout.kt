package com.kingzcheung.xime.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.automirrored.filled.Backspace
import androidx.compose.material.icons.filled.EmojiEmotions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kingzcheung.xime.settings.KeysConfigHelper
import com.kingzcheung.xime.settings.SettingsPreferences
import com.kingzcheung.xime.keyboard.GestureAction
import androidx.compose.ui.platform.LocalContext

/**
 * 横屏专用分体键盘布局
 *
 * 将键盘拆分为左右两个面板，紧贴屏幕左右边缘，中间留空方便双手持机拇指操作。
 * 每侧面板的键行采用错开布局（第二行向内缩进，第三行再向内缩进），
 * 模拟全键盘的错位手感，空格键一分为二左右各一。
 * 复用竖屏已有的 KeyButton / SwipeableKeyButton / IconKeyButton / SwipeableIconKeyButton。
 */
@Composable
fun SplitKeyboardLayout(
    onKeyPress: (String) -> Unit,
    isShifted: Boolean,
    isAsciiMode: Boolean = false,
    schemaName: String = "",
    enterKeyText: String = "发送",
    isDarkTheme: Boolean = false,
    keyBackgroundColor: Color,
    keyTextColor: Color,
    specialKeyBackgroundColor: Color,
    keyboardBackgroundColor: Color = Color.Transparent,
    modifier: Modifier = Modifier,
    onKeyPressDown: ((String) -> Unit)? = null,
    onGestureAction: ((GestureAction, String) -> Unit)? = null
) {
    val staggerStep = 10.dp
    val context = LocalContext.current
    val swipeDownHintsEnabled = SettingsPreferences.isSwipeDownHintsEnabled(context)
    val landscapeFontSize = 12.sp
    val landscapeSwipeFontSize = 7.sp

    Box(
        modifier = modifier
            .background(keyboardBackgroundColor)
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(vertical = 6.dp, horizontal = 50.dp)
        ) {
            // ========== 左面板（紧贴左边缘） ==========
            // 错开方式：第二行向右缩进（靠近中缝），第三行继续右缩
            //   q  w  e  r  t
            //     a  s  d  f  g
            //       z  x  c  v  b
            //   [😀] [   左空格   ]
            Column(
                modifier = Modifier
                    .fillMaxHeight()
                    .weight(0.42f)
                    .padding(start = 4.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                // 第一行：q w e r t（不缩进）
                Box(modifier = Modifier.weight(1f)) {
                    CompactKeyboardRowWithConfig(
                        keys = listOf("q", "w", "e", "r", "t"),
                        onKeyPress = onKeyPress,
                        keyBackgroundColor = keyBackgroundColor,
                        keyTextColor = keyTextColor,
                        isShifted = isShifted,
                        isAsciiMode = isAsciiMode,
                        keyboardBackgroundColor = keyboardBackgroundColor,
                        fontSize = landscapeFontSize,
                        swipeFontSize = landscapeSwipeFontSize,
                        onKeyPressDown = onKeyPressDown,
                        swipeDownHintsEnabled = swipeDownHintsEnabled,
                        onGestureAction = onGestureAction
                    )
                }

                // 第二行：a s d f g（向右缩进）
                Box(modifier = Modifier.weight(1f).padding(start = staggerStep)) {
                    CompactKeyboardRowWithConfig(
                        keys = listOf("a", "s", "d", "f", "g"),
                        onKeyPress = onKeyPress,
                        keyBackgroundColor = keyBackgroundColor,
                        keyTextColor = keyTextColor,
                        isShifted = isShifted,
                        isAsciiMode = isAsciiMode,
                        keyboardBackgroundColor = keyboardBackgroundColor,
                        fontSize = landscapeFontSize,
                        swipeFontSize = landscapeSwipeFontSize,
                        onKeyPressDown = onKeyPressDown,
                        swipeDownHintsEnabled = swipeDownHintsEnabled,
                        onGestureAction = onGestureAction
                    )
                }

                // 第三行：z x c v（B 移到右面板，V 两边都有）
                Box(modifier = Modifier.weight(1f).padding(start = staggerStep * 2)) {
                    CompactKeyboardRowWithConfig(
                        keys = listOf("z", "x", "c", "v"),
                        onKeyPress = onKeyPress,
                        keyBackgroundColor = keyBackgroundColor,
                        keyTextColor = keyTextColor,
                        isShifted = isShifted,
                        isAsciiMode = isAsciiMode,
                        keyboardBackgroundColor = keyboardBackgroundColor,
                        fontSize = landscapeFontSize,
                        swipeFontSize = landscapeSwipeFontSize,
                        onKeyPressDown = onKeyPressDown,
                        swipeDownHintsEnabled = swipeDownHintsEnabled,
                        onGestureAction = onGestureAction
                    )
                }

                // 第四行：emoji + 左空格
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
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
                    SplitSpaceKey(
                        onClick = { onKeyPress("space") },
                        backgroundColor = keyBackgroundColor,
                        textColor = keyTextColor,
                        schemaName = schemaName,
                        modifier = Modifier.weight(3f),
                        onPress = { onKeyPressDown?.invoke("space") }
                    )
                }
            }

            // 中间留空（约16%宽度），方便双手持机拇指操作
            Spacer(modifier = Modifier.weight(0.16f))

            // ========== 右面板（紧贴右边缘） ==========
            // 错开方式：第二行向左缩进（靠近中缝），第三行继续左缩
            //   y  u  i  o  p
            //   g h  j  k  l
            //   v b n  m     [⌫]
            //    [右空格] [123] [，] [↵]
            Column(
                modifier = Modifier
                    .fillMaxHeight()
                    .weight(0.42f)
                    .padding(end = 4.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                // 第一行：y u i o p（不缩进）
                Box(modifier = Modifier.weight(1f)) {
                    CompactKeyboardRowWithConfig(
                        keys = listOf("y", "u", "i", "o", "p"),
                        onKeyPress = onKeyPress,
                        keyBackgroundColor = keyBackgroundColor,
                        keyTextColor = keyTextColor,
                        isShifted = isShifted,
                        isAsciiMode = isAsciiMode,
                        fontSize = landscapeFontSize,
                        swipeFontSize = landscapeSwipeFontSize,
                        keyboardBackgroundColor = keyboardBackgroundColor,
                        onKeyPressDown = onKeyPressDown,
                        swipeDownHintsEnabled = swipeDownHintsEnabled,
                        onGestureAction = onGestureAction
                    )
                }

                // 第二行：h j k l g（G 两边都有，正好 5 键与左对称）
                Box(modifier = Modifier.weight(1f).padding(end = staggerStep)) {
                    CompactKeyboardRowWithConfig(
                        keys = listOf( "g", "h", "j", "k", "l"),
                        onKeyPress = onKeyPress,
                        keyBackgroundColor = keyBackgroundColor,
                        keyTextColor = keyTextColor,
                        isShifted = isShifted,
                        isAsciiMode = isAsciiMode,
                        fontSize = landscapeFontSize,
                        swipeFontSize = landscapeSwipeFontSize,
                        keyboardBackgroundColor = keyboardBackgroundColor,
                        onKeyPressDown = onKeyPressDown,
                        swipeDownHintsEnabled = swipeDownHintsEnabled,
                        onGestureAction = onGestureAction
                    )
                }

                // 第三行：v b n m + 退格
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .padding(end = staggerStep * 2),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Box(modifier = Modifier.weight(4f)) {
                        CompactKeyboardRowWithConfig(
                            keys = listOf("v", "b", "n", "m"),
                            onKeyPress = onKeyPress,
                            keyBackgroundColor = keyBackgroundColor,
                            keyTextColor = keyTextColor,
                            isShifted = isShifted,
                            isAsciiMode = isAsciiMode,
                            keyboardBackgroundColor = keyboardBackgroundColor,
                            fontSize = landscapeFontSize,
                            swipeFontSize = landscapeSwipeFontSize,
                            onKeyPressDown = onKeyPressDown,
                            swipeDownHintsEnabled = swipeDownHintsEnabled,
                            onGestureAction = onGestureAction
                        )
                    }
                    SwipeableIconKeyButton(
                        icon = rememberVectorPainter(Icons.AutoMirrored.Filled.Backspace),
                        onClick = { onKeyPress("delete") },
                        backgroundColor = specialKeyBackgroundColor,
                        iconColor = keyTextColor,
                        modifier = Modifier.weight(1.2f),
                        onSwipe = { onKeyPress("clear_composition") },
                        onLongClick = { onKeyPress("delete") },
                        onPress = { onKeyPressDown?.invoke("delete") },
                        swipeUpLabel = "上滑清空",
                        swipeDownLabel = "下滑撤回",
                        onSwipeUp = { onKeyPress("clear_all") },
                        onSwipeDown = { onKeyPress("undo_clear") },
                        onSwipeLeft = { onKeyPress("clear_composition") }
                    )
                }

                // 第四行：右空格 + 123 + 逗号 + 回车
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
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
                    CompactSwipeableKeyButton(
                        text = if (isAsciiMode) "." else "，",
                        onClick = { onKeyPress(if (isAsciiMode) "." else "，") },
                        backgroundColor = keyBackgroundColor,
                        textColor = keyTextColor,
                        modifier = Modifier.weight(0.8f),
                        swipeText = if (isAsciiMode) "," else "。",
                        swipeFontSize = landscapeSwipeFontSize,
                        onSwipe = { onSwipeText -> onKeyPress(onSwipeText) },
                        onPress = { onKeyPressDown?.invoke(if (isAsciiMode) "," else "。") }
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
}

/**
 * 横屏紧凑版按键 —— 主字符和上滑字符垂直堆叠居中，避免 offset 溢出
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
    onSwipe: ((String) -> Unit)? = null,
    onSwipeDown: ((String) -> Unit)? = null,
    onPress: (() -> Unit)? = null,
    onLongPressSelect: ((String) -> Unit)? = null,
    longPressItems: List<String>? = null,
    fontSize: androidx.compose.ui.unit.TextUnit = androidx.compose.ui.unit.TextUnit.Unspecified,
    swipeFontSize: androidx.compose.ui.unit.TextUnit = 8.sp
) {
    var isPressed by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    var dragOffsetY by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(0f) }
    var hasTriggeredSwipeUp by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    var hasTriggeredSwipeDown by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    var isSwiping by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    var isSwipeDown by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    var longPressCycleIndex by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(0) }

    val density = androidx.compose.ui.platform.LocalDensity.current
    val swipeUpThreshold = with(density) { (-15).dp.toPx() }
    val swipeDownThreshold = with(density) { 15.dp.toPx() }

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
            .shadow(1.dp, RoundedCornerShape(8.dp), ambientColor = Color(0x80000000), spotColor = Color(0x80000000))
            .clip(RoundedCornerShape(8.dp))
            .background(if (isPressed) darkenColor(backgroundColor) else backgroundColor)
            .pointerInput(onClick, swipeText, swipeDownText, longPressItems, onLongPressSelect) {
                if (longPressItems.isNullOrEmpty() || onLongPressSelect == null) {
                    detectTapGestures(
                        onPress = {
                            isPressed = true
                            onPress?.invoke()
                            tryAwaitRelease()
                            isPressed = false
                        },
                        onTap = {
                            if (!hasTriggeredSwipeUp && !hasTriggeredSwipeDown) onClick()
                        }
                    )
                } else {
                    detectTapGestures(
                        onPress = {
                            isPressed = true
                            onPress?.invoke()
                            tryAwaitRelease()
                            isPressed = false
                        },
                        onLongPress = {
                            val items = longPressItems
                            val idx = longPressCycleIndex % items.size
                            longPressCycleIndex = (idx + 1) % items.size
                            onLongPressSelect(items[idx])
                        },
                        onTap = {
                            if (!hasTriggeredSwipeUp && !hasTriggeredSwipeDown) onClick()
                        }
                    )
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
                    },
                    onDragCancel = {
                        isPressed = false
                        dragOffsetY = 0f
                        hasTriggeredSwipeUp = false
                        hasTriggeredSwipeDown = false
                        isSwiping = false
                        isSwipeDown = false
                    },
                    onDrag = { change: androidx.compose.ui.input.pointer.PointerInputChange, dragAmount: androidx.compose.ui.geometry.Offset ->
                        dragOffsetY += dragAmount.y

                        if (dragOffsetY < 0 && !hasTriggeredSwipeUp && swipeText != null && onSwipe != null) {
                            if (dragOffsetY < swipeUpThreshold) {
                                hasTriggeredSwipeUp = true
                                onSwipe(swipeText)
                            }
                        } else if (dragOffsetY > 0 && !hasTriggeredSwipeDown && swipeDownText != null && onSwipeDown != null) {
                            if (dragOffsetY > swipeDownThreshold) {
                                hasTriggeredSwipeDown = true
                                onSwipeDown(swipeDownText)
                            }
                        }
                    }
                )
            },
        contentAlignment = Alignment.Center
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            Text(
                text = text,
                color = textColor,
                fontSize = if (fontSize != androidx.compose.ui.unit.TextUnit.Unspecified) fontSize else 14.sp,
                fontWeight = FontWeight.Medium,
                textAlign = TextAlign.Center,
                maxLines = 1,
                modifier = Modifier.align(Alignment.Center)
            )
            if (!swipeText.isNullOrEmpty()) {
                Text(
                    text = swipeText,
                    color = textColor.copy(alpha = 0.5f),
                    fontSize = swipeFontSize,
                    fontWeight = FontWeight.Normal,
                    textAlign = TextAlign.End,
                    maxLines = 1,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(top = 0.dp, end = 4.dp)
                )
            }
            if (!swipeDownText.isNullOrEmpty()) {
                Text(
                    text = swipeDownText,
                    color = textColor.copy(alpha = 0.5f),
                    fontSize = swipeFontSize,
                    fontWeight = FontWeight.Normal,
                    textAlign = TextAlign.Start,
                    maxLines = 1,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(start = 4.dp, bottom = 0.dp)
                )
            }
        }
    }
}

/**
 * 横屏紧凑版键盘行 —— 使用 CompactSwipeableKeyButton 替代 SwipeableKeyButton
 */
@Composable
fun CompactKeyboardRowWithConfig(
    keys: List<String>,
    onKeyPress: (String) -> Unit,
    keyBackgroundColor: Color,
    keyTextColor: Color,
    isShifted: Boolean,
    isAsciiMode: Boolean,
    keyboardBackgroundColor: Color = Color.Transparent,
    modifier: Modifier = Modifier,
    onKeyPressDown: ((String) -> Unit)? = null,
    swipeDownHintsEnabled: Boolean = true,
    swipeUpHintsEnabled: Boolean = true,
    onCommitText: ((String) -> Unit)? = null,
    onGestureAction: ((GestureAction, String) -> Unit)? = null,
    fontSize: androidx.compose.ui.unit.TextUnit = androidx.compose.ui.unit.TextUnit.Unspecified,
    swipeFontSize: androidx.compose.ui.unit.TextUnit = 9.sp
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(keyboardBackgroundColor),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        keys.forEach { key ->
            val rawSwipeUpText = KeysConfigHelper.getSwipeUpText(key)
            val swipeUpText = if (swipeUpHintsEnabled) rawSwipeUpText else null
            val swipeDownRaw = KeysConfigHelper.getKeyGesture(key)?.swipeDown
            val swipeDownLabel = swipeDownRaw?.label?.takeIf { it.isNotEmpty() }
            val swipeDownAction = swipeDownRaw?.action
            val swipeDownValue = swipeDownRaw?.value
            val swipeDownDisplay = swipeDownRaw?.display ?: "key"
            val swipeDownBubbleText = if (swipeDownDisplay != "key" && swipeDownHintsEnabled) swipeDownLabel else null

            val longPressConfig = KeysConfigHelper.getKeyGesture(key)?.longPress
            val longPressDisplay = longPressConfig?.display ?: "key"
            val longPressLabels = if (longPressDisplay == "bubble") {
                longPressConfig?.values?.map { it.label }?.filter { it.isNotEmpty() }?.ifEmpty { null }
            } else null
            val longPressGestureMap = if (longPressDisplay == "bubble") {
                longPressConfig?.values?.associateBy { it.label }
            } else null

            CompactSwipeableKeyButton(
                text = if (isShifted || !isAsciiMode) key.uppercase() else key,
                onClick = { onKeyPress(key) },
                backgroundColor = keyBackgroundColor,
                textColor = keyTextColor,
                modifier = Modifier.weight(1f),
                swipeText = swipeUpText,
                swipeDownText = swipeDownBubbleText,
                onSwipe = if (swipeUpText != null) onKeyPress else null,
                onSwipeDown = if (swipeDownAction != null && swipeDownHintsEnabled && swipeDownLabel != null) {
                    { _ ->
                        if (swipeDownAction == GestureAction.COMMIT) {
                            onKeyPress(key)
                        } else {
                            onGestureAction?.invoke(swipeDownAction, swipeDownValue?.ifEmpty { swipeDownLabel!! } ?: swipeDownLabel!!)
                        }
                    }
                } else null,
                onPress = { onKeyPressDown?.invoke(key) },
                onLongPressSelect = { selectedLabel ->
                    val gesture = longPressGestureMap?.get(selectedLabel)
                    if (gesture != null && gesture.action != GestureAction.COMMIT) {
                        onGestureAction?.invoke(gesture.action!!, gesture.value.ifEmpty { selectedLabel!! })
                    } else {
                        (onCommitText ?: onKeyPress)(selectedLabel)
                    }
                },
                longPressItems = longPressLabels,
                fontSize = fontSize,
                swipeFontSize = swipeFontSize
            )
        }
    }
}

/**
 * 横屏分体键盘专用的空格键（简化版，不支持语音/滑动光标）
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
