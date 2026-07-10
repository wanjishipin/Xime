package com.kingzcheung.xime.ui.keyboard

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kingzcheung.xime.speech.RecognitionState
import com.kingzcheung.xime.speech.TranscriptionManager

@Composable
fun TranscriptionOverlay(
    manager: TranscriptionManager,
    backgroundColor: Color,
    accentColor: Color,
    textColor: Color,
    onDismiss: () -> Unit,
    bottomPaddingDp: Int = 0,
    modifier: Modifier = Modifier
) {
    val transcriptText by manager.transcriptText.collectAsState()
    val partialText by manager.partialText.collectAsState()
    val isRunning by manager.isRunning.collectAsState()
    val amplitude by manager.amplitude.collectAsState()
    val state by manager.state.collectAsState()
    val providerName by manager.providerName.collectAsState()

    val clipboardManager = LocalClipboardManager.current
    val listState = rememberLazyListState()

    val fullText = if (partialText.isNotEmpty()) "$transcriptText$partialText" else transcriptText
    val lines = fullText.split("\n").toMutableList()

    LaunchedEffect(fullText) {
        if (lines.isNotEmpty()) {
            listState.animateScrollToItem(lines.lastIndex)
        }
    }

    LaunchedEffect(isRunning) {
        if (!isRunning) {
            // 停止时滚动到底部
            if (lines.isNotEmpty()) {
                listState.animateScrollToItem(lines.lastIndex)
            }
        }
    }

    Column(
        modifier = modifier
            .background(backgroundColor)
            .fillMaxSize()
            .padding(12.dp)
            .padding(bottom = bottomPaddingDp.dp)
    ) {
        // 顶部标题栏
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onDismiss) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "返回",
                    tint = textColor
                )
            }
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = "听录",
                style = MaterialTheme.typography.titleMedium,
                color = textColor,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = providerName,
                style = MaterialTheme.typography.labelSmall,
                color = textColor.copy(alpha = 0.5f)
            )
            Spacer(modifier = Modifier.weight(1f))
            // 状态指示
            if (isRunning && state == RecognitionState.LISTENING) {
                // 音量指示圆点
                val dotAlpha = (0.3f + amplitude * 0.7f).coerceIn(0f, 1f)
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(accentColor.copy(alpha = dotAlpha))
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "正在听…",
                    style = MaterialTheme.typography.labelSmall,
                    color = accentColor
                )
            } else if (isRunning && state == RecognitionState.PROCESSING) {
                CircularProgressIndicator(
                    modifier = Modifier.size(14.dp),
                    strokeWidth = 2.dp,
                    color = accentColor
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "准备中…",
                    style = MaterialTheme.typography.labelSmall,
                    color = textColor.copy(alpha = 0.6f)
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // 文本内容区
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .clip(RoundedCornerShape(12.dp)
                )
                .background(textColor.copy(alpha = 0.05f))
        ) {
            if (fullText.isEmpty()) {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(
                        imageVector = Icons.Filled.Mic,
                        contentDescription = null,
                        tint = textColor.copy(alpha = 0.2f),
                        modifier = Modifier.size(48.dp)
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "点击下方按钮开始听录",
                        color = textColor.copy(alpha = 0.3f),
                        fontSize = 14.sp
                    )
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    items(lines.size) { index ->
                        Text(
                            text = lines[index],
                            color = textColor,
                            fontSize = 16.sp,
                            lineHeight = 24.sp
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // 底部操作栏
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 清空按钮
            OutlinedButton(
                onClick = { manager.clear() },
                enabled = fullText.isNotEmpty()
            ) {
                Icon(
                    imageVector = Icons.Filled.Delete,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text("清空")
            }

            // 开始/停止按钮
            OutlinedButton(
                onClick = {
                    if (isRunning) {
                        manager.stop()
                    } else {
                        manager.start()
                    }
                },
                modifier = Modifier.weight(1f)
            ) {
                Icon(
                    imageVector = if (isRunning) Icons.Filled.Stop else Icons.Filled.Mic,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = if (isRunning) Color.Red else accentColor
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = if (isRunning) "停止" else "开始听录",
                    color = if (isRunning) Color.Red else accentColor,
                    fontWeight = FontWeight.Bold
                )
            }

            // 复制按钮
            OutlinedButton(
                onClick = {
                    if (fullText.isNotEmpty()) {
                        clipboardManager.setText(androidx.compose.ui.text.AnnotatedString(fullText))
                    }
                },
                enabled = fullText.isNotEmpty()
            ) {
                Icon(
                    imageVector = Icons.Filled.ContentCopy,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text("复制")
            }
        }
    }
}
