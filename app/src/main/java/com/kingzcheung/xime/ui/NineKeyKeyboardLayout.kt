package com.kingzcheung.xime.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Backspace
import androidx.compose.material.icons.filled.EmojiEmotions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kingzcheung.xime.rime.T9Decoder

@Composable
fun NineKeyKeyboardLayout(
    onReplaceFullPinyin: (String) -> Unit,
    onKeyPress: (String) -> Unit,
    keyBackgroundColor: Color,
    keyTextColor: Color,
    specialKeyBackgroundColor: Color,
    keyboardBackgroundColor: Color = Color.Transparent,
    isDarkTheme: Boolean = false,
    modifier: Modifier = Modifier,
    onKeyPressDown: ((String) -> Unit)? = null,
    resetSignal: Long = 0
) {
    val context = LocalContext.current
    val decoder = remember { T9Decoder(context) }
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
    var digits by remember { mutableStateOf("") }
    var confirmedPinyins by remember { mutableStateOf(listOf<String>()) }
    var firstOptions by remember { mutableStateOf<List<T9Decoder.SyllableOption>>(emptyList()) }

    LaunchedEffect(resetSignal) {
        digits = ""
        confirmedPinyins = emptyList()
        firstOptions = emptyList()
    }

    fun updateCandidates() {
        firstOptions = decoder.firstSyllableOptions(digits)
    }

    fun sendToRime() {
        val fullPinyin = confirmedPinyins.joinToString("") + decoder.bestPinyin(digits)
        onReplaceFullPinyin(fullPinyin)
    }

    fun onDigitPressed(digit: String) {
        if (digit == "1") {
            val best = firstOptions.firstOrNull()
            if (best != null) {
                confirmedPinyins = confirmedPinyins + best.pinyin
                digits = digits.drop(best.digitLength)
                updateCandidates()
            }
            sendToRime()
            return
        }
        digits += digit
        updateCandidates()
        sendToRime()
    }

    fun onChoiceSelected(option: T9Decoder.SyllableOption) {
        confirmedPinyins = confirmedPinyins + option.pinyin
        digits = digits.drop(option.digitLength)
        updateCandidates()
        sendToRime()
    }

    fun onDeleted() {
        if (digits.isNotEmpty()) {
            digits = digits.dropLast(1)
            updateCandidates()
            if (digits.isEmpty() && confirmedPinyins.isEmpty()) {
                onReplaceFullPinyin("")
            } else {
                sendToRime()
            }
        } else if (confirmedPinyins.isNotEmpty()) {
            confirmedPinyins = confirmedPinyins.dropLast(1)
            if (confirmedPinyins.isNotEmpty()) {
                onReplaceFullPinyin(confirmedPinyins.joinToString(""))
            } else {
                onReplaceFullPinyin("")
            }
        } else {
            onKeyPress("delete")
        }
    }

    Box(
        modifier = modifier
            .background(keyboardBackgroundColor)
            .padding(horizontal = 4.dp)
            .onGloballyPositioned { coordinates ->
                keyboardBounds = coordinates.boundsInRoot()
            }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight()
                .background(keyboardBackgroundColor)
                .padding(vertical = 6.dp, horizontal = 4.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(3f),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    val showCandidates = firstOptions.isNotEmpty()
                    val displayItems: List<String> = if (showCandidates) {
                        firstOptions.map { it.pinyin }
                    } else {
                        listOf("，", "。", "？", "！")
                    }
                    Column(
                        modifier = Modifier
                            .fillMaxHeight()
                            .weight(1f)
                            .padding(end = 3.dp),
                        verticalArrangement = Arrangement.spacedBy(0.dp)
                    ) {
                        displayItems.forEachIndexed { index, item ->
                            if (showCandidates) {
                                val option = firstOptions[index]
                                PinyinChoiceKey(
                                    text = option.pinyin,
                                    onClick = { onChoiceSelected(option) },
                                    backgroundColor = keyBackgroundColor,
                                    textColor = keyTextColor,
                                    modifier = Modifier.weight(1f),
                                    onPress = { onKeyPressDown?.invoke(option.pinyin) },
                                    isFirst = index == 0,
                                    isLast = index == displayItems.lastIndex
                                )
                            } else {
                                PunctuationKey2(
                                    text = item,
                                    onClick = { onKeyPress(item) },
                                    backgroundColor = keyBackgroundColor,
                                    textColor = keyTextColor,
                                    modifier = Modifier.weight(1f),
                                    onPress = { onKeyPressDown?.invoke(item) },
                                    isFirst = index == 0,
                                    isLast = index == displayItems.lastIndex
                                )
                            }
                        }
                    }

                    Column(
                        modifier = Modifier
                            .fillMaxHeight()
                            .weight(4f),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f),
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            NineKeyButton2(
                                digit = "1", letters = "分词",
                                onClick = { onDigitPressed("1") },
                                backgroundColor = keyBackgroundColor, textColor = keyTextColor,
                                modifier = Modifier.weight(1f),
                                onPress = { onKeyPressDown?.invoke("1") }
                            )
                            NineKeyButton2(digit = "2", letters = "ABC", onClick = { onDigitPressed("2") }, backgroundColor = keyBackgroundColor, textColor = keyTextColor, modifier = Modifier.weight(1f), onPress = { onKeyPressDown?.invoke("2") })
                            NineKeyButton2(digit = "3", letters = "DEF", onClick = { onDigitPressed("3") }, backgroundColor = keyBackgroundColor, textColor = keyTextColor, modifier = Modifier.weight(1f), onPress = { onKeyPressDown?.invoke("3") })
                            SwipeableIconKeyButton(
                                icon = rememberVectorPainter(Icons.AutoMirrored.Filled.Backspace),
                                onClick = { onKeyPress("delete") },
                                backgroundColor = keyBackgroundColor, iconColor = keyTextColor,
                                modifier = Modifier.weight(1f),
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
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f),
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            NineKeyButton2(digit = "4", letters = "GHI", onClick = { onDigitPressed("4") }, backgroundColor = keyBackgroundColor, textColor = keyTextColor, modifier = Modifier.weight(1f), onPress = { onKeyPressDown?.invoke("4") })
                            NineKeyButton2(digit = "5", letters = "JKL", onClick = { onDigitPressed("5") }, backgroundColor = keyBackgroundColor, textColor = keyTextColor, modifier = Modifier.weight(1f), onPress = { onKeyPressDown?.invoke("5") })
                            NineKeyButton2(digit = "6", letters = "MNO", onClick = { onDigitPressed("6") }, backgroundColor = keyBackgroundColor, textColor = keyTextColor, modifier = Modifier.weight(1f), onPress = { onKeyPressDown?.invoke("6") })
                            val hasInput = digits.isNotEmpty() || firstOptions.isNotEmpty() || confirmedPinyins.isNotEmpty()
                            val resetKeyText = if (hasInput) "重输" else "换行"
                            KeyButton(
                                text = resetKeyText,
                                onClick = {
                                    if (hasInput) {
                                        digits = ""
                                        confirmedPinyins = emptyList()
                                        firstOptions = emptyList()
                                        onReplaceFullPinyin("")
                                    } else {
                                        onKeyPress("enter")
                                    }
                                },
                                backgroundColor = keyBackgroundColor,
                                textColor = keyTextColor,
                                modifier = Modifier.weight(1f),
                                onPress = {
                                    onKeyPressDown?.invoke(if (hasInput) "clear" else "enter")
                                }
                            )
                        }
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f),
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            NineKeyButton2(digit = "7", letters = "PQRS", onClick = { onDigitPressed("7") }, backgroundColor = keyBackgroundColor, textColor = keyTextColor, modifier = Modifier.weight(1f), onPress = { onKeyPressDown?.invoke("7") })
                            NineKeyButton2(digit = "8", letters = "TUV", onClick = { onDigitPressed("8") }, backgroundColor = keyBackgroundColor, textColor = keyTextColor, modifier = Modifier.weight(1f), onPress = { onKeyPressDown?.invoke("8") })
                            NineKeyButton2(digit = "9", letters = "WXYZ", onClick = { onDigitPressed("9") }, backgroundColor = keyBackgroundColor, textColor = keyTextColor, modifier = Modifier.weight(1f), onPress = { onKeyPressDown?.invoke("9") })
                            IconKeyButton(icon = rememberVectorPainter(Icons.Default.EmojiEmotions), onClick = { onKeyPress("emoji") }, backgroundColor = keyBackgroundColor, iconColor = keyTextColor, modifier = Modifier.weight(1f), onPress = { onKeyPressDown?.invoke("emoji") })
                        }
                    }
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    KeyButton(text = "符号", onClick = { onKeyPress("symbol") }, backgroundColor = keyBackgroundColor, textColor = keyTextColor, modifier = Modifier.weight(1.2f), onPress = { onKeyPressDown?.invoke("symbol") })
                    KeyButton(text = "abc", onClick = { onKeyPress("abc") }, backgroundColor = keyBackgroundColor, textColor = keyTextColor, modifier = Modifier.weight(0.8f), onPress = { onKeyPressDown?.invoke("abc") })
                    KeyButton(text = "空格", onClick = { onKeyPress("space") }, backgroundColor = keyBackgroundColor, textColor = keyTextColor, modifier = Modifier.weight(2f), onPress = { onKeyPressDown?.invoke("space") })
                    KeyButton(text = "123", onClick = { onKeyPress("mode_change") }, backgroundColor = keyBackgroundColor, textColor = keyTextColor, modifier = Modifier.weight(0.8f), onPress = { onKeyPressDown?.invoke("mode_change") })
                    KeyButton(text = "确定", onClick = { onKeyPress("enter") }, backgroundColor = specialKeyBackgroundColor, textColor = keyTextColor, modifier = Modifier.weight(1.2f), onPress = { onKeyPressDown?.invoke("enter") })
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

// ── 子组件 ──

@Composable
private fun PinyinChoiceKey(
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
    Box(modifier = modifier.fillMaxWidth().clip(shape).background(if (isPressed) backgroundColor.copy(alpha = 0.7f) else backgroundColor).pointerInput(Unit) {
        detectTapGestures(
            onPress = {
                isPressed = true
                currentOnPress?.invoke()
                tryAwaitRelease()
                isPressed = false
            },
            onTap = { currentOnClick() }
        )
    }, contentAlignment = Alignment.Center) {
        Text(text = text, color = textColor, fontSize = 13.sp, fontWeight = FontWeight.Normal, textAlign = TextAlign.Center)
    }
}

@Composable
private fun PunctuationKey2(
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
    Box(modifier = modifier.fillMaxWidth().clip(shape).background(if (isPressed) backgroundColor.copy(alpha = 0.7f) else backgroundColor).pointerInput(Unit) {
        detectTapGestures(
            onPress = {
                isPressed = true
                currentOnPress?.invoke()
                tryAwaitRelease()
                isPressed = false
            },
            onTap = { currentOnClick() }
        )
    }, contentAlignment = Alignment.Center) {
        Text(text = text, color = textColor, fontSize = 20.sp, fontWeight = FontWeight.Normal, textAlign = TextAlign.Center)
    }
}

@Composable
private fun NineKeyButton2(
    digit: String,
    letters: String,
    onClick: () -> Unit,
    backgroundColor: Color,
    textColor: Color,
    modifier: Modifier = Modifier,
    onPress: (() -> Unit)? = null
) {
    var isPressed by remember { mutableStateOf(false) }
    val currentOnClick by rememberUpdatedState(onClick)
    val currentOnPress by rememberUpdatedState(onPress)
    Box(modifier = modifier.fillMaxHeight().shadow(1.dp, RoundedCornerShape(8.dp), ambientColor = Color(0x80000000), spotColor = Color(0x80000000)).clip(RoundedCornerShape(8.dp)).background(if (isPressed) backgroundColor.copy(alpha = 0.7f) else backgroundColor).pointerInput(Unit) {
        detectTapGestures(
            onPress = {
                isPressed = true
                currentOnPress?.invoke()
                tryAwaitRelease()
                isPressed = false
            },
            onTap = { currentOnClick() }
        )
    }) {
        if (digit.isNotEmpty()) Text(text = digit, color = textColor.copy(alpha = 0.5f), fontSize = 10.sp, fontWeight = FontWeight.Normal, textAlign = TextAlign.End, modifier = Modifier.align(Alignment.TopEnd).padding(top = 0.dp, end = 6.dp))
        if (letters.isNotEmpty()) Text(text = letters, color = textColor, fontSize = 15.sp, fontWeight = FontWeight.Medium, textAlign = TextAlign.Center, maxLines = 1, modifier = Modifier.align(Alignment.Center))
    }
}
