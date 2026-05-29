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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kingzcheung.xime.rime.T9Decoder

/**
 * 拼音九宫格键盘布局（T9）
 *
 * 使用 T9Decoder 将数字序列解码为拼音串，发送到 Rime。
 *
 * @param onReplaceFullPinyin 发送完整拼音到 Rime（清除当前组合 + 逐字母发送）
 * @param onKeyPress 控制键回调
 */
@Composable
fun NineKeyKeyboardLayout(
    onReplaceFullPinyin: (String) -> Unit,
    onKeyPress: (String) -> Unit,
    keyBackgroundColor: Color,
    keyTextColor: Color,
    specialKeyBackgroundColor: Color,
    keyboardBackgroundColor: Color = Color.Transparent,
    modifier: Modifier = Modifier,
    onKeyPressDown: ((String) -> Unit)? = null,
    resetSignal: Long = 0
) {
        val context = LocalContext.current
        val decoder = remember { T9Decoder(context) }
    var digits by remember { mutableStateOf("") }
    var pinyinChoices by remember { mutableStateOf<List<String>?>(null) }

    // 已确认的拼音前缀（通过左栏候选选择累积）
    // 新输入的 digits 解码后追加到其后，实现连续输入
    var confirmedPinyinPrefix by remember { mutableStateOf("") }

    // 当 resetSignal 变化时，立即重置 T9 输入状态
    // 使用 LaunchedEffect 而非 remember(key)，确保在下次按键事件前完成重置
    LaunchedEffect(resetSignal) {
        digits = ""
        pinyinChoices = null
        confirmedPinyinPrefix = ""
    }

    fun onDigitPressed(digit: String) {
        if (digit == "1") {
            // 分词键：将当前 digits 解码的拼音作为已确认音节，追加分隔符
            if (digits.isNotEmpty()) {
                val currentPinyin = decoder.bestPinyin(digits)
                if (currentPinyin.isNotEmpty()) {
                    confirmedPinyinPrefix += currentPinyin + "'"
                }
            } else {
                confirmedPinyinPrefix += "'"
            }
            digits = ""
            pinyinChoices = null
            onReplaceFullPinyin(confirmedPinyinPrefix)
            return
        }
        digits += digit
        val candidates = decoder.candidates(digits, maxResults = 4)
        pinyinChoices = candidates.ifEmpty { null }
        val bestPinyin = decoder.bestPinyin(digits)
        if (bestPinyin.isNotEmpty()) {
            // 已确认的前缀 + 当前数字解码的拼音 = 完整拼音
            onReplaceFullPinyin(confirmedPinyinPrefix + bestPinyin)
        }
    }

    fun onDeleted() {
        if (digits.isNotEmpty()) {
            digits = digits.dropLast(1)
            if (digits.isEmpty()) {
                pinyinChoices = null
                // 清空当前输入的拼音但保留已确认的前缀
                val prefix = confirmedPinyinPrefix
                if (prefix.isNotEmpty()) {
                    onReplaceFullPinyin(prefix)
                } else {
                    onReplaceFullPinyin("")
                }
            } else {
                val candidates = decoder.candidates(digits, maxResults = 4)
                pinyinChoices = candidates.ifEmpty { null }
                val bestPinyin = decoder.bestPinyin(digits)
                if (bestPinyin.isNotEmpty()) {
                    onReplaceFullPinyin(confirmedPinyinPrefix + bestPinyin)
                }
            }
        } else if (confirmedPinyinPrefix.isNotEmpty()) {
            // 当前无 digits 但有已确认前缀 → 删除最后一个已确认音节的最后一个字母
            confirmedPinyinPrefix = confirmedPinyinPrefix.dropLast(1)
            if (confirmedPinyinPrefix.isNotEmpty()) {
                onReplaceFullPinyin(confirmedPinyinPrefix)
            } else {
                onReplaceFullPinyin("")
            }
        } else {
            onKeyPress("delete")
        }
    }

    fun onChoiceSelected(pinyin: String) {
        // 用选中的拼音替换最后一个音节，保留前面的已确认音节
        val paths = decoder.decode(digits, maxPaths = 5)
        val fullPinyin = if (paths.isNotEmpty()) {
            val bestPath = paths.first()
            if (bestPath.pinyins.size > 1) {
                (bestPath.pinyins.dropLast(1) + pinyin).joinToString("")
            } else {
                pinyin
            }
        } else {
            pinyin
        }
        // 将选中的拼音设为已确认前缀，后续数字输入会追加在其后
        confirmedPinyinPrefix = fullPinyin
        onReplaceFullPinyin(confirmedPinyinPrefix)
        digits = ""
        pinyinChoices = null
    }

    Box(modifier = modifier.background(keyboardBackgroundColor)) {
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
                    val leftItems = pinyinChoices ?: listOf("，", "。", "？", "！")
                    Column(
                        modifier = Modifier
                            .fillMaxHeight()
                            .weight(1f)
                            .padding(end = 3.dp),
                        verticalArrangement = Arrangement.spacedBy(0.dp)
                    ) {
                        leftItems.forEachIndexed { index, item ->
                            if (pinyinChoices != null) {
                                PinyinChoiceKey(
                                    text = item,
                                    onClick = { onChoiceSelected(item) },
                                    backgroundColor = keyBackgroundColor,
                                    textColor = keyTextColor,
                                    modifier = Modifier.weight(1f),
                                    onPress = { onKeyPressDown?.invoke(item) },
                                    isFirst = index == 0,
                                    isLast = index == leftItems.lastIndex
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
                                    isLast = index == leftItems.lastIndex
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
                            modifier = Modifier.fillMaxWidth().weight(1f),
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
                                onClick = { onDeleted() },
                                backgroundColor = keyBackgroundColor, iconColor = keyTextColor,
                                modifier = Modifier.weight(1f),
                                onSwipe = { digits = ""; pinyinChoices = null },
                                onLongClick = { onDeleted() },
                                onPress = { onKeyPressDown?.invoke("delete") }
                            )
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth().weight(1f),
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            NineKeyButton2(digit = "4", letters = "GHI", onClick = { onDigitPressed("4") }, backgroundColor = keyBackgroundColor, textColor = keyTextColor, modifier = Modifier.weight(1f), onPress = { onKeyPressDown?.invoke("4") })
                            NineKeyButton2(digit = "5", letters = "JKL", onClick = { onDigitPressed("5") }, backgroundColor = keyBackgroundColor, textColor = keyTextColor, modifier = Modifier.weight(1f), onPress = { onKeyPressDown?.invoke("5") })
                            NineKeyButton2(digit = "6", letters = "MNO", onClick = { onDigitPressed("6") }, backgroundColor = keyBackgroundColor, textColor = keyTextColor, modifier = Modifier.weight(1f), onPress = { onKeyPressDown?.invoke("6") })
                            val hasInput = digits.isNotEmpty() || pinyinChoices != null || confirmedPinyinPrefix.isNotEmpty()
                            val resetKeyText = if (hasInput) "重输" else "换行"
                            KeyButton(
                                text = resetKeyText,
                                onClick = {
                                    if (hasInput) {
                                        digits = ""
                                        pinyinChoices = null
                                        confirmedPinyinPrefix = ""
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
                            modifier = Modifier.fillMaxWidth().weight(1f),
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
                    KeyButton(text = "符号", onClick = { onKeyPress("symbol") }, backgroundColor = keyBackgroundColor, textColor = keyTextColor, modifier = Modifier.weight(1f), onPress = { onKeyPressDown?.invoke("symbol") })
                    KeyButton(text = "abc", onClick = { onKeyPress("abc") }, backgroundColor = keyBackgroundColor, textColor = keyTextColor, modifier = Modifier.weight(1f), onPress = { onKeyPressDown?.invoke("abc") })
                    KeyButton(text = "空格", onClick = { onKeyPress("space") }, backgroundColor = keyBackgroundColor, textColor = keyTextColor, modifier = Modifier.weight(2f), onPress = { onKeyPressDown?.invoke("space") })
                    KeyButton(text = "123", onClick = { onKeyPress("mode_change") }, backgroundColor = keyBackgroundColor, textColor = keyTextColor, modifier = Modifier.weight(1f), onPress = { onKeyPressDown?.invoke("mode_change") })
                    KeyButton(text = "确定", onClick = { onKeyPress("enter") }, backgroundColor = specialKeyBackgroundColor, textColor = keyTextColor, modifier = Modifier.weight(1.2f), onPress = { onKeyPressDown?.invoke("enter") })
                }
            }
        }
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
                onPress?.invoke()
                tryAwaitRelease()
                isPressed = false
            },
            onTap = { onClick() }
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
                onPress?.invoke()
                tryAwaitRelease()
                isPressed = false
            },
            onTap = { onClick() }
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
    Box(modifier = modifier.fillMaxHeight().shadow(1.dp, RoundedCornerShape(8.dp), ambientColor = Color(0x80000000), spotColor = Color(0x80000000)).clip(RoundedCornerShape(8.dp)).background(if (isPressed) backgroundColor.copy(alpha = 0.7f) else backgroundColor).pointerInput(Unit) {
        detectTapGestures(
            onPress = {
                isPressed = true
                onPress?.invoke()
                tryAwaitRelease()
                isPressed = false
            },
            onTap = { onClick() }
        )
    }) {
        if (digit.isNotEmpty()) Text(text = digit, color = textColor.copy(alpha = 0.5f), fontSize = 10.sp, fontWeight = FontWeight.Normal, textAlign = TextAlign.End, modifier = Modifier.align(Alignment.TopEnd).padding(top = 0.dp, end = 6.dp))
        if (letters.isNotEmpty()) Text(text = letters, color = textColor, fontSize = 15.sp, fontWeight = FontWeight.Medium, textAlign = TextAlign.Center, maxLines = 1, modifier = Modifier.align(Alignment.Center))
    }
}
