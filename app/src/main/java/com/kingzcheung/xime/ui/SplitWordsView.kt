package com.kingzcheung.xime.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.max

/**
 * 将文本拆分为独立单元：
 * - 中文按字拆分
 * - 英文按空格拆分
 * - 标点符号作为独立拆分点
 */
private fun splitText(text: String): List<String> {
    val result = mutableListOf<String>()
    val englishBuffer = StringBuilder()

    fun flushEnglish() {
        if (englishBuffer.isNotEmpty()) {
            result.add(englishBuffer.toString())
            englishBuffer.clear()
        }
    }

    for (char in text) {
        when {
            // CJK 字符（中文、日文、韩文）
            char in '\u4E00'..'\u9FFF' || char in '\u3400'..'\u4DBF' || char in '\uF900'..'\uFAFF' -> {
                flushEnglish()
                result.add(char.toString())
            }
            // 英文字母
            char.isLetter() -> {
                englishBuffer.append(char)
            }
            // 空格
            char.isWhitespace() -> {
                flushEnglish()
            }
            // 数字
            char.isDigit() -> {
                englishBuffer.append(char)
            }
            // 其他（标点、符号等）
            else -> {
                flushEnglish()
                result.add(char.toString())
            }
        }
    }
    flushEnglish()

    return result
}

@Composable
fun SplitWordsView(
    text: String,
    backgroundColor: Color,
    onBack: () -> Unit,
    onAddQuickSendText: (String) -> Unit,
    onNavigateToQuickSend: (() -> Unit)? = null,
    onSelectChar: (String) -> Unit,
    onDeleteText: ((Int) -> Unit)? = null,
    bottomPaddingDp: Int = 0,
    modifier: Modifier = Modifier
) {
    val textColor = MaterialTheme.colorScheme.onSurface
    val accentColor = MaterialTheme.colorScheme.primary
    val chipBgColor = MaterialTheme.colorScheme.surfaceContainerLow
    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE

    val splitParts = remember(text) { splitText(text) }
    val selectedIndices = remember { mutableStateListOf<Int>() }
    var committedText by remember { mutableStateOf("") }

    fun addSelected(index: Int) {
        val pos = selectedIndices.binarySearch(index)
        if (pos < 0) selectedIndices.add(-(pos + 1), index)
    }

    fun commitSelection() {
        val newText = selectedIndices.joinToString("") { splitParts[it] }
        if (newText == committedText) return
        onDeleteText?.invoke(committedText.length)
        newText.forEach { c -> onSelectChar(c.toString()) }
        committedText = newText
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(backgroundColor)
    ) {
        // 导航区
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(50.dp)
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                    .clickable { onBack() },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                    contentDescription = "返回",
                    tint = accentColor,
                    modifier = Modifier.size(24.dp)
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            Text(
                text = "拆词",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = textColor,
                modifier = Modifier.weight(1f)
            )

            TextButton(
                onClick = {
                    val text = selectedIndices.joinToString("") { splitParts[it] }
                    if (text.isNotEmpty()) {
                        onAddQuickSendText(text)
                        onNavigateToQuickSend?.invoke()
                    }
                }
            ) {
                Icon(
                    imageVector = Icons.Default.Star,
                    contentDescription = null,
                    tint = accentColor,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text("添加到快捷发送", color = accentColor, fontSize = 13.sp)
            }
        }

        // 内容区（白色卡片样式）
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .clip(RoundedCornerShape(16.dp))
                    .background(MaterialTheme.colorScheme.surface)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp)
                ) {

                    // 拆词结果（支持点击 + 滑动选词）
                    val chipBounds = remember { mutableMapOf<Int, Rect>() }
                    var containerRootPos by remember { mutableStateOf(Offset.Zero) }

                    fun findChipAt(pos: Offset): Int? {
                        val rootPos = pos + containerRootPos
                        return chipBounds.entries.firstOrNull { (_, bounds) ->
                            bounds.contains(rootPos)
                        }?.key
                    }

                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .onGloballyPositioned { containerRootPos = it.positionInRoot() }
                            .pointerInput(splitParts) {
                                awaitEachGesture {
                                    val down = awaitFirstDown(requireUnconsumed = false)
                                    val firstChip = findChipAt(down.position)
                                    val dragStartIndex = firstChip
                                    val isRemoveMode = firstChip != null && firstChip in selectedIndices
                                    var didDrag = false

                                    // 处理首个词块
                                    if (firstChip != null) {
                                        if (isRemoveMode) {
                                            selectedIndices.remove(firstChip)
                                        } else {
                                            addSelected(firstChip)
                                        }
                                        commitSelection()
                                    }

                                    // 滑动过程（边滑边上屏）
                                    do {
                                        val event = awaitPointerEvent(PointerEventPass.Final)
                                        val change = event.changes.firstOrNull() ?: break
                                        if (change.pressed) {
                                            change.consume()
                                            val chipIndex = findChipAt(change.position)
                                            if (chipIndex != null && dragStartIndex != null) {
                                                didDrag = true
                                                val from = minOf(dragStartIndex, chipIndex)
                                                val to = maxOf(dragStartIndex, chipIndex)
                                                for (i in from..to) {
                                                    if (isRemoveMode) {
                                                        selectedIndices.remove(i)
                                                    } else if (i !in selectedIndices) {
                                                        addSelected(i)
                                                    }
                                                }
                                                commitSelection()
                                            }
                                        } else {
                                            break
                                        }
                                    } while (true)
                                }
                            }
                    ) {
                        FlowRow(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            splitParts.forEachIndexed { index, part ->
                                val isSelected = index in selectedIndices
                                Box(
                                    modifier = Modifier
                                        .onGloballyPositioned { chipBounds[index] = it.boundsInRoot() }
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(if (isSelected) accentColor else chipBgColor)
                                        .padding(horizontal = 8.dp, vertical = 4.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = part,
                                        color = if (isSelected) Color.White else textColor,
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.Medium
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // 底部留空（竖屏至少 40dp）
        Spacer(modifier = Modifier.height(if (isLandscape) 15.dp else max(bottomPaddingDp, 40).dp))
    }
}
