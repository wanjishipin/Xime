package com.kingzcheung.xime.ui.keyboard

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Backspace
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kingzcheung.xime.R
import com.kingzcheung.xime.rime.T9InputController
import com.kingzcheung.xime.util.PermissionHelper
import com.kingzcheung.xime.util.SubcharHelper
import com.kingzcheung.xime.viewmodel.KeyboardUiState
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 拼音九键（T9）键盘布局。
 *
 * 布局说明：
 * - 左侧候选区：占用右侧第 1/4/7 三个数字键的垂直高度，显示当前音节候选拼音。
 * - 右侧主键区：
 *   第1行：分词(1) | ABC(2) | DEF(3) | 退格
 *   第2行：GHI(4) | JKL(5) | MNO(6) | 重输
 *   第3行：PQRS(7) | TUV(8) | WXYZ(9) | 0
 *   第4行：符 | 123 | 空格(显示方案名) | 中/En(地球图标) | 发送
 */
@Composable
fun T9KeyboardLayout(
    onKeyPress: (String) -> Unit,
    callbacks: KeyboardCallbacks,
    uiState: KeyboardUiState,
    t9Controller: T9InputController,
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
    val controller = t9Controller

    var swipeState by remember { mutableStateOf(SwipeState()) }
    var keyboardBounds by remember { mutableStateOf(Rect(0f, 0f, 0f, 0f)) }
    var lastKeyBounds by remember { mutableStateOf(Rect(0f, 0f, 0f, 0f)) }

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

    fun handleDelete() {
        when (val result = controller.onDeleted()) {
            T9InputController.DeleteResult.UNDO_COMMIT -> {
                controller.clearRimeAndResend()
            }

            T9InputController.DeleteResult.NOT_CONSUMED -> {
                onKeyPress("delete")
            }

            T9InputController.DeleteResult.DELETED, T9InputController.DeleteResult.UNDO_CHOICE -> {
                // 已消费
            }
        }
    }

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
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(start = 4.dp, end = 4.dp, bottom = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            // 内层包装：与 NumberKeyboardLayout 的 NumberRows 对齐
            // 131
            Column(
                modifier = Modifier
                    .fillMaxHeight()
                    .weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                // 左侧候选区：外层统一圆角/阴影，内部候选项无缝排列
                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .weight(3f)
                        .then(
                            if (shadowEnabled) {
                                Modifier.shadow(
                                    shadowElevation, RoundedCornerShape(shadowShapeRadius)
                                )
                            } else {
                                Modifier
                            }
                        )
                        .clip(RoundedCornerShape(shadowShapeRadius))
                        .background(keyBackgroundColor)
                ) {
                    val showCandidates = controller.firstOptions.isNotEmpty()
                    val displayItems: List<String> = if (showCandidates) {
                        controller.firstOptions.map { it.pinyin }
                    } else {
                        listOf("，", "。", "？", "！")
                    }
                    if (displayItems.size <= 4) {
                        Column(
                            modifier = Modifier.fillMaxSize(),
                            verticalArrangement = Arrangement.spacedBy(0.dp)
                        ) {
                            displayItems.forEachIndexed { index, item ->
                                if (showCandidates) {
                                    val option = controller.firstOptions[index]
                                    CandidateItem(
                                        text = option.pinyin,
                                        onClick = { controller.onChoiceSelected(option) },
                                        onPress = { onKeyPressDown?.invoke(option.pinyin) },
                                        textColor = keyTextColor,
                                        backgroundColor = keyBackgroundColor,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .weight(1f)
                                    )
                                } else {
                                    CandidateItem(
                                        text = item,
                                        onClick = { onKeyPress(item) },
                                        onPress = { onKeyPressDown?.invoke(item) },
                                        textColor = keyTextColor,
                                        backgroundColor = keyBackgroundColor,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .weight(1f)
                                    )
                                }
                            }
                        }
                    } else {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            verticalArrangement = Arrangement.spacedBy(0.dp)
                        ) {
                            itemsIndexed(displayItems) { index, _ ->
                                val option = controller.firstOptions[index]
                                CandidateItem(
                                    text = option.pinyin,
                                    onClick = { controller.onChoiceSelected(option) },
                                    onPress = { onKeyPressDown?.invoke(option.pinyin) },
                                    textColor = keyTextColor,
                                    backgroundColor = keyBackgroundColor,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(32.dp)
                                )
                            }
                        }
                    }
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
            Column(
                modifier = Modifier
                    .fillMaxHeight()
                    .weight(3f),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                // 第1行
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    NineKeyButton(
                        digit = "1",
                        letters = "分词",
                        onClick = { controller.onDigitPressed("1") },
                        backgroundColor = keyBackgroundColor,
                        textColor = keyTextColor,
                        modifier = Modifier.weight(1f),
                        onPress = { onKeyPressDown?.invoke("1") },
                        shadowEnabled = shadowEnabled,
                        shadowElevation = shadowElevation,
                        shadowShapeRadius = shadowShapeRadius,
                    )

                    T9DigitKey(
                        digit = "2",
                        letters = "ABC",
                        longPressItems = listOf("A", "B", "C"),
                        onClick = { controller.onDigitPressed("2") },
                        onLongPressSelect = { letter ->
                            controller.clearAll()
                            onKeyPress(letter)
                        },
                        onSwipeStateChange = { state, bounds ->
                            if (state.isPressed && !state.isLongPress && state.pressedText != null) {
                                // 普通点按不显示 press 气泡，避免误现字母选择
                            } else {
                                processSwipeState(state, bounds)
                            }
                        },
                        backgroundColor = keyBackgroundColor,
                        textColor = keyTextColor,
                        modifier = Modifier.weight(1f),
                        onPress = { onKeyPressDown?.invoke("2") },
                        shadowEnabled = shadowEnabled,
                        shadowElevation = shadowElevation,
                        shadowShapeRadius = shadowShapeRadius,
                    )
                    T9DigitKey(
                        digit = "3",
                        letters = "DEF",
                        longPressItems = listOf("D", "E", "F"),
                        onClick = { controller.onDigitPressed("3") },
                        onLongPressSelect = { letter ->
                            controller.clearAll()
                            onKeyPress(letter)
                        },
                        onSwipeStateChange = { state, bounds ->
                            if (state.isPressed && !state.isLongPress && state.pressedText != null) {
                                // 普通点按不显示 press 气泡
                            } else {
                                processSwipeState(state, bounds)
                            }
                        },
                        backgroundColor = keyBackgroundColor,
                        textColor = keyTextColor,
                        modifier = Modifier.weight(1f),
                        onPress = { onKeyPressDown?.invoke("3") },
                        shadowEnabled = shadowEnabled,
                        shadowElevation = shadowElevation,
                        shadowShapeRadius = shadowShapeRadius,
                    )

                }

                // 第2行
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    T9DigitKey(
                        digit = "4",
                        letters = "GHI",
                        longPressItems = listOf("G", "H", "I"),
                        onClick = { controller.onDigitPressed("4") },
                        onLongPressSelect = { letter ->
                            controller.clearAll()
                            onKeyPress(letter)
                        },
                        onSwipeStateChange = { state, bounds ->
                            if (state.isPressed && !state.isLongPress && state.pressedText != null) {
                                // 普通点按不显示 press 气泡
                            } else {
                                processSwipeState(state, bounds)
                            }
                        },
                        backgroundColor = keyBackgroundColor,
                        textColor = keyTextColor,
                        modifier = Modifier.weight(1f),
                        onPress = { onKeyPressDown?.invoke("4") },
                        shadowEnabled = shadowEnabled,
                        shadowElevation = shadowElevation,
                        shadowShapeRadius = shadowShapeRadius,
                    )
                    T9DigitKey(
                        digit = "5",
                        letters = "JKL",
                        longPressItems = listOf("J", "K", "L"),
                        onClick = { controller.onDigitPressed("5") },
                        onLongPressSelect = { letter ->
                            controller.clearAll()
                            onKeyPress(letter)
                        },
                        onSwipeStateChange = { state, bounds ->
                            if (state.isPressed && !state.isLongPress && state.pressedText != null) {
                                // 普通点按不显示 press 气泡
                            } else {
                                processSwipeState(state, bounds)
                            }
                        },
                        backgroundColor = keyBackgroundColor,
                        textColor = keyTextColor,
                        modifier = Modifier.weight(1f),
                        onPress = { onKeyPressDown?.invoke("5") },
                        shadowEnabled = shadowEnabled,
                        shadowElevation = shadowElevation,
                        shadowShapeRadius = shadowShapeRadius,
                    )
                    T9DigitKey(
                        digit = "6",
                        letters = "MNO",
                        longPressItems = listOf("M", "N", "O"),
                        onClick = { controller.onDigitPressed("6") },
                        onLongPressSelect = { letter ->
                            controller.clearAll()
                            onKeyPress(letter)
                        },
                        onSwipeStateChange = { state, bounds ->
                            if (state.isPressed && !state.isLongPress && state.pressedText != null) {
                                // 普通点按不显示 press 气泡
                            } else {
                                processSwipeState(state, bounds)
                            }
                        },
                        backgroundColor = keyBackgroundColor,
                        textColor = keyTextColor,
                        modifier = Modifier.weight(1f),
                        onPress = { onKeyPressDown?.invoke("6") },
                        shadowEnabled = shadowEnabled,
                        shadowElevation = shadowElevation,
                        shadowShapeRadius = shadowShapeRadius,
                    )

                }

                // 第3行
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    T9DigitKey(
                        digit = "7",
                        letters = "PQRS",
                        longPressItems = listOf("P", "Q", "R", "S"),
                        onClick = { controller.onDigitPressed("7") },
                        onLongPressSelect = { letter ->
                            controller.clearAll()
                            onKeyPress(letter)
                        },
                        onSwipeStateChange = { state, bounds ->
                            if (state.isPressed && !state.isLongPress && state.pressedText != null) {
                                // 普通点按不显示 press 气泡
                            } else {
                                processSwipeState(state, bounds)
                            }
                        },
                        backgroundColor = keyBackgroundColor,
                        textColor = keyTextColor,
                        modifier = Modifier.weight(1f),
                        onPress = { onKeyPressDown?.invoke("7") },
                        shadowEnabled = shadowEnabled,
                        shadowElevation = shadowElevation,
                        shadowShapeRadius = shadowShapeRadius,
                    )
                    T9DigitKey(
                        digit = "8",
                        letters = "TUV",
                        longPressItems = listOf("T", "U", "V"),
                        onClick = { controller.onDigitPressed("8") },
                        onLongPressSelect = { letter ->
                            controller.clearAll()
                            onKeyPress(letter)
                        },
                        onSwipeStateChange = { state, bounds ->
                            if (state.isPressed && !state.isLongPress && state.pressedText != null) {
                                // 普通点按不显示 press 气泡
                            } else {
                                processSwipeState(state, bounds)
                            }
                        },
                        backgroundColor = keyBackgroundColor,
                        textColor = keyTextColor,
                        modifier = Modifier.weight(1f),
                        onPress = { onKeyPressDown?.invoke("8") },
                        shadowEnabled = shadowEnabled,
                        shadowElevation = shadowElevation,
                        shadowShapeRadius = shadowShapeRadius,
                    )
                    T9DigitKey(
                        digit = "9",
                        letters = "WXYZ",
                        longPressItems = listOf("W", "X", "Y", "Z"),
                        onClick = { controller.onDigitPressed("9") },
                        onLongPressSelect = { letter ->
                            controller.clearAll()
                            onKeyPress(letter)
                        },
                        onSwipeStateChange = { state, bounds ->
                            if (state.isPressed && !state.isLongPress && state.pressedText != null) {
                                // 普通点按不显示 press 气泡
                            } else {
                                processSwipeState(state, bounds)
                            }
                        },
                        backgroundColor = keyBackgroundColor,
                        textColor = keyTextColor,
                        modifier = Modifier.weight(1f),
                        onPress = { onKeyPressDown?.invoke("9") },
                        shadowEnabled = shadowEnabled,
                        shadowElevation = shadowElevation,
                        shadowShapeRadius = shadowShapeRadius,
                    )
                }
                Row(modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                    KeyButton(
                        text = "123",
                        onClick = { onKeyPress("number") },
                        backgroundColor = keyBackgroundColor,
                        textColor = keyTextColor,
                        modifier = Modifier.weight(1f),
                        onPress = { onKeyPressDown?.invoke("mode_change") },
                        shadowEnabled = shadowEnabled,
                        shadowElevation = shadowElevation,
                        shadowShapeRadius = shadowShapeRadius,
                    )
                    T9SpaceKey(
                        schemaName = uiState.schemaName,
                        isSttEnabled = uiState.isSttEnabled,
                        onKeyPress = onKeyPress,
                        onKeyPressDown = onKeyPressDown,
                        onVoiceModeChange = callbacks.onVoiceModeChange,
                        backgroundColor = keyBackgroundColor,
                        textColor = keyTextColor,
                        modifier = Modifier.weight(1.8f),
                        shadowEnabled = shadowEnabled,
                        shadowElevation = shadowElevation,
                        shadowShapeRadius = shadowShapeRadius,
                    )
                    IconKeyButton(
                        icon = rememberVectorPainter(Icons.Default.Language),
                        onClick = { onKeyPress("ime_switch") },
                        backgroundColor = keyBackgroundColor,
                        iconColor = keyTextColor,
                        modifier = Modifier.weight(1f),
                        onPress = { onKeyPressDown?.invoke("ime_switch") },
                        shadowEnabled = shadowEnabled,
                        shadowElevation = shadowElevation,
                        shadowShapeRadius = shadowShapeRadius,
                    )
                }
            }
            Column(
                modifier = Modifier
                    .fillMaxHeight()
                    .weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                SwipeableIconKeyButton(
                    icon = rememberVectorPainter(Icons.AutoMirrored.Filled.Backspace),
                    onClick = { handleDelete() },
                    backgroundColor = specialKeyBackgroundColor,
                    iconColor = keyTextColor,
                    modifier = Modifier.weight(1f),
                    swipeText = "清空",
                    onSwipe = { onKeyPress("clear_composition") },
                    onPress = { onKeyPressDown?.invoke("delete") },
                    swipeUpLabel = "上滑清空",
                    swipeDownLabel = "下滑撤回",
                    onSwipeUp = { onKeyPress("clear_all") },
                    onSwipeDown = { onKeyPress("undo_clear") },
                    onSwipeLeft = { onKeyPress("clear_composition") },
                    onSwipeStateChange = { state, bounds ->
                        processSwipeState(
                            state, bounds
                        )
                    },
                    shadowEnabled = shadowEnabled,
                    shadowElevation = shadowElevation,
                    shadowShapeRadius = shadowShapeRadius,
                )
                ResetKey(
                    onClick = { controller.clearAll() },
                    onPress = { onKeyPressDown?.invoke("clear") },
                    backgroundColor = specialKeyBackgroundColor,
                    textColor = keyTextColor,
                    modifier = Modifier.weight(1f),
                    shadowEnabled = shadowEnabled,
                    shadowElevation = shadowElevation,
                    shadowShapeRadius = shadowShapeRadius,
                )
                KeyButton(
                    text = uiState.enterKeyText,
                    onClick = { onKeyPress("enter") },
                    backgroundColor = specialKeyBackgroundColor,
                    textColor = keyTextColor,
                    modifier = Modifier.weight(2f),
                    onPress = { onKeyPressDown?.invoke("enter") },
                    shadowEnabled = shadowEnabled,
                    shadowElevation = shadowElevation,
                    shadowShapeRadius = shadowShapeRadius,
                )
            }
        }
    }
}

// ─── 九键数字键（可长按） ──────────────────────────────────────────────

/**
 * 九键数字键，复用 [SwipeableKeyButton] 的长按弹出逻辑。
 * - 主体显示字母（ABC），右上角叠加数字浮标。
 * - 点按走 [onClick]，长按走 [onLongPressSelect]。
 */
@Composable
private fun T9DigitKey(
    digit: String,
    letters: String,
    longPressItems: List<String>,
    onClick: () -> Unit,
    onLongPressSelect: ((String) -> Unit)?,
    onSwipeStateChange: ((SwipeState, Rect) -> Unit)?,
    backgroundColor: Color,
    textColor: Color,
    modifier: Modifier = Modifier,
    onPress: (() -> Unit)? = null,
    shadowEnabled: Boolean = true,
    shadowElevation: Dp = 1.dp,
    shadowShapeRadius: Dp = 8.dp,
) {
    val currentOnClick by rememberUpdatedState(onClick)
    val currentOnLongPressSelect by rememberUpdatedState(onLongPressSelect)
    val currentOnPress by rememberUpdatedState(onPress)

    SwipeableKeyButton(
        text = letters,
        onClick = { currentOnClick() },
        backgroundColor = backgroundColor,
        textColor = textColor,
        fontSize = 16.sp,
        modifier = modifier,
        onPress = { currentOnPress?.invoke() },
        longPressItems = longPressItems,
        onLongPressSelect = { letter -> currentOnLongPressSelect?.invoke(letter) },
        onSwipeStateChange = onSwipeStateChange,
        badgeText = digit,
        shadowEnabled = shadowEnabled,
        shadowElevation = shadowElevation,
        shadowShapeRadius = shadowShapeRadius,
    )
}

// ─── 子组件 ───────────────────────────────────────────────────────────

@Composable
private fun CandidateItem(
    text: String,
    onClick: () -> Unit,
    onPress: (() -> Unit)?,
    textColor: Color,
    backgroundColor: Color = Color.Transparent,
    modifier: Modifier = Modifier,
) {
    var isPressed by remember { mutableStateOf(false) }
    val currentOnClick by rememberUpdatedState(onClick)
    val currentOnPress by rememberUpdatedState(onPress)
    Box(
        modifier = modifier
            .fillMaxWidth()
            .background(
                if (isPressed) backgroundColor.copy(alpha = 0.7f)
                else Color.Transparent
            )
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
            fontSize = 13.sp,
            fontWeight = FontWeight.Normal,
            textAlign = TextAlign.Center,
            maxLines = 1
        )
    }
}

/** 仅用于数字 1（分词），不支持长按 */
@Composable
private fun NineKeyButton(
    digit: String,
    letters: String,
    onClick: () -> Unit,
    backgroundColor: Color,
    textColor: Color,
    modifier: Modifier = Modifier,
    onPress: (() -> Unit)? = null,
    shadowEnabled: Boolean = true,
    shadowElevation: Dp = 1.dp,
    shadowShapeRadius: Dp = 8.dp,
) {
    KeyButton(
        text = letters,
        onClick = onClick,
        backgroundColor = backgroundColor,
        textColor = textColor,
        modifier = modifier,
        onPress = onPress,
        badgeText = digit,
        shadowEnabled = shadowEnabled,
        shadowElevation = shadowElevation,
        shadowShapeRadius = shadowShapeRadius,
    )
}

/** 重输键 —— 文本在上、图标在下，参考功能键风格 */
@Composable
private fun ResetKey(
    onClick: () -> Unit,
    onPress: (() -> Unit)?,
    backgroundColor: Color,
    textColor: Color,
    modifier: Modifier = Modifier,
    shadowEnabled: Boolean = true,
    shadowElevation: Dp = 1.dp,
    shadowShapeRadius: Dp = 8.dp,
) {
    var isPressed by remember { mutableStateOf(false) }
    val currentOnClick by rememberUpdatedState(onClick)
    val currentOnPress by rememberUpdatedState(onPress)
    val shape = RoundedCornerShape(shadowShapeRadius)
    val shadowModifier = remember(shadowEnabled, shadowElevation, shadowShapeRadius) {
        if (shadowEnabled) Modifier.shadow(shadowElevation, shape) else Modifier
    }

    Box(
        modifier = modifier
            .fillMaxSize()
//            .padding(horizontal = 2.dp, vertical = 4.25.dp)
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
        Icon(
            imageVector = Icons.Default.Refresh,
            contentDescription = "重输",
            tint = textColor,
            modifier = Modifier.size(20.dp)
        )

        Text(
            text = "重输",
            color = textColor.copy(alpha = 0.5f),
            fontSize = 9.sp,
            fontWeight = FontWeight.Normal,
            textAlign = TextAlign.Center,
            maxLines = 1,
            modifier = Modifier.offset(y = (-14).dp)
        )
    }
}

/** T9 空格键 */
@Composable
private fun T9SpaceKey(
    schemaName: String,
    isSttEnabled: Boolean,
    onKeyPress: (String) -> Unit,
    onKeyPressDown: ((String) -> Unit)?,
    onVoiceModeChange: ((Boolean) -> Unit)?,
    backgroundColor: Color,
    textColor: Color,
    modifier: Modifier = Modifier,
    shadowEnabled: Boolean = true,
    shadowElevation: Dp = 1.dp,
    shadowShapeRadius: Dp = 8.dp,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val currentOnKeyPress by rememberUpdatedState(onKeyPress)
    val currentOnKeyPressDown by rememberUpdatedState(onKeyPressDown)
    val currentOnVoiceModeChange by rememberUpdatedState(onVoiceModeChange)
    val shape = RoundedCornerShape(shadowShapeRadius)
    val shadowModifier = remember(shadowEnabled, shadowElevation, shadowShapeRadius) {
        if (shadowEnabled) Modifier.shadow(shadowElevation, shape) else Modifier
    }

    Box(
        modifier = modifier
            .fillMaxHeight()
//            .padding(horizontal = 2.dp, vertical = 4.25.dp)
            .then(shadowModifier)
            .clip(shape)
            .background(backgroundColor)
            .pointerInput(isSttEnabled) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    currentOnKeyPressDown?.invoke("space")

                    var longPressTriggered = false
                    val longPressJob = scope.launch {
                        delay(400)
                        longPressTriggered = true
                        if (isSttEnabled) {
                            if (!PermissionHelper.hasRecordAudioPermission(context)) {
                                Toast.makeText(
                                    context, "需要麦克风权限才能使用语音输入", Toast.LENGTH_SHORT
                                ).show()
                                PermissionHelper.requestRecordAudioPermission(context)
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

                    longPressJob.cancel()
                    if (!longPressTriggered) {
                        currentOnKeyPress("space")
                    }
                }
            }, contentAlignment = Alignment.Center
    ) {
        Text(
            text = schemaName,
            color = textColor,
            fontSize = 14.sp,
            fontWeight = FontWeight.Normal,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 8.dp)
        )
        if (isSttEnabled) {
            Icon(
                painter = painterResource(R.drawable.voice),
                contentDescription = "语音输入",
                tint = textColor.copy(alpha = 0.3f),
                modifier = Modifier
                    .size(18.dp)
                    .align(Alignment.BottomStart)
                    .padding(start = 6.dp, bottom = 2.dp)
            )
        } else {
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
}
