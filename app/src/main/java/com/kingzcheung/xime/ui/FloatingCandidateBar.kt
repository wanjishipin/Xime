package com.kingzcheung.xime.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 悬浮键盘样式的内联候选栏。
 * 连接蓝牙键盘时显示，支持拖拽移动。
 */
@Composable
fun FloatingCandidateBar(
    inputText: String,
    candidates: Array<String>,
    candidateComments: Array<String>,
    isComposing: Boolean,
    onCandidateSelect: (Int) -> Unit,
    onDrag: (dx: Int, dy: Int) -> Unit
) {
    val dragModifier = Modifier.pointerInput(Unit) {
        detectDragGestures { change, dragAmount ->
            change.consume()
            onDrag(dragAmount.x.toInt(), dragAmount.y.toInt())
        }
    }

    Column(
        modifier = Modifier
            .wrapContentWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .then(dragModifier),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // 拖拽手柄
        Box(
            modifier = Modifier
                .padding(bottom = 3.dp)
                .width(36.dp)
                .height(5.dp)
                .clip(RoundedCornerShape(2.5.dp))
                .background(Color(0xFF6B7280))
        )
        // 自适应宽度的悬浮候选卡片
        Column(
            modifier = Modifier
                .wrapContentWidth()
                .height(72.dp)
                .shadow(8.dp, RoundedCornerShape(14.dp))
                .clip(RoundedCornerShape(14.dp))
                .background(Color(0xE61E1E1E))
                .padding(horizontal = 10.dp, vertical = 6.dp)
        ) {
            if (isComposing && inputText.isNotEmpty()) {
                Text(
                    text = inputText,
                    fontSize = 13.sp,
                    color = Color(0xFF4FC3F7),
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    modifier = Modifier.padding(bottom = 4.dp)
                )
            }
            if (candidates.isNotEmpty()) {
                Row(
                    modifier = Modifier.wrapContentWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    candidates.take(8).forEachIndexed { index, candidate ->
                        val comment = candidateComments.getOrElse(index) { "" }
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(Color(0xFF374151))
                                .clickable { onCandidateSelect(index) }
                                .padding(horizontal = 14.dp, vertical = 6.dp)
                        ) {
                            Text(
                                text = if (comment.isNotEmpty()) "$candidate  $comment" else candidate,
                                fontSize = 13.sp,
                                color = Color.White
                            )
                        }
                    }
                }
            }
        }
    }
}
