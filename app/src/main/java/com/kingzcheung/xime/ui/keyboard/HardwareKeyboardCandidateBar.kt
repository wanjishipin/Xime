package com.kingzcheung.xime.ui.keyboard

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

private const val MAX_VISIBLE_CANDIDATES = 10

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun HardwareKeyboardCandidateBar(
    inputText: String,
    preeditText: String,
    candidates: List<String>,
    hasNextPage: Boolean,
    hasPrevPage: Boolean,
    cursorX: Int,
    cursorY: Int,
    cursorVisible: Boolean,
    highlightIndex: Int,
    cardBackgroundColor: Color,
    candidateTextColor: Color,
    activeColor: Color,
) {
    if (candidates.isEmpty() && inputText.isEmpty()) return

    val density = LocalDensity.current
    val displayText = if (preeditText.isNotEmpty()) preeditText else inputText

    val screenWidthDp = LocalConfiguration.current.screenWidthDp
    val screenWidthPx = with(density) { screenWidthDp.dp.toPx() }.roundToInt()
    val screenHeightPx = with(density) {
        LocalConfiguration.current.screenHeightDp.dp.toPx()
    }.roundToInt()

    val cardHeightEstPx = with(density) { 80.dp.toPx() }.roundToInt()
    val maxCardWidthDp = (screenWidthDp * 0.85f).roundToInt().coerceIn(260, 420)
    val halfEstPx = with(density) { (maxCardWidthDp / 2).dp.toPx() }.roundToInt()

    val cardXPx = remember(cursorX, cursorVisible) {
        val maxX = (screenWidthPx - halfEstPx * 2 - 8).coerceAtLeast(8)
        if (cursorVisible && cursorX > 0) {
            (cursorX - halfEstPx).coerceIn(8, maxX)
        } else {
            (screenWidthPx - halfEstPx * 2).coerceAtLeast(0) / 2
        }
    }

    val cardYPx = remember(cursorY, cursorVisible) {
        if (cursorVisible && cursorY > 0) {
            if (cursorY + cardHeightEstPx + 24 > screenHeightPx) {
                (cursorY - cardHeightEstPx - 16).coerceIn(8, screenHeightPx - 16)
            } else {
                (cursorY + 16).coerceAtMost(screenHeightPx - 8)
            }
        } else {
            with(density) { 60.dp.toPx() }.roundToInt()
        }
    }

    Box(
        modifier = Modifier.fillMaxSize()
    ) {
        Box(
            modifier = Modifier
                .offset { IntOffset(cardXPx, cardYPx) }
                .widthIn(min = 160.dp, max = maxCardWidthDp.dp)
                .wrapContentWidth()
                .shadow(12.dp, RoundedCornerShape(8.dp))
                .clip(RoundedCornerShape(8.dp))
                .background(cardBackgroundColor)
        ) {
            Column(
                modifier = Modifier
                    .padding(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 4.dp)
                    .widthIn(max = maxCardWidthDp.dp - 24.dp)
            ) {
                if (displayText.isNotEmpty()) {
                    Text(
                        text = displayText,
                        fontSize = 13.sp,
                        color = activeColor,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(bottom = 2.dp)
                    )
                }

                if (candidates.isNotEmpty()) {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        candidates.take(MAX_VISIBLE_CANDIDATES).forEachIndexed { index, candidate ->
                            val isActive = index == highlightIndex
                            val label = (index + 1) % 10
                            val labelText = if (index == 9) "0" else "$label"
                            Column {
                                Text(
                                    text = "$labelText $candidate",
                                    fontSize = 15.sp,
                                    color = if (isActive) activeColor else candidateTextColor,
                                    fontWeight = if (isActive) FontWeight.Bold else FontWeight.Normal,
                                    maxLines = 1,
                                )
                            }
                        }
                    }
                }
            }

            if (hasNextPage || hasPrevPage) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(end = 8.dp, bottom = 6.dp)
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                        if (hasPrevPage) {
                            Text("◀", fontSize = 10.sp, color = candidateTextColor.copy(alpha = 0.5f))
                        }
                        if (hasNextPage) {
                            Text("▶", fontSize = 10.sp, color = candidateTextColor.copy(alpha = 0.5f))
                        }
                    }
                }
            }
        }
    }
}
