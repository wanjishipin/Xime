package com.kingzcheung.xime.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kingzcheung.xime.clipboard.ClipboardItem
import kotlin.math.roundToInt

@Composable
fun ClipboardView(
    clipboardItems: List<ClipboardItem>,
    quickSendItems: List<ClipboardItem>,
    selectedTab: Int,
    isDarkTheme: Boolean,
    backgroundColor: Color,
    onSelectItem: (String) -> Unit,
    onRemoveItem: (Long) -> Unit,
    onAddToQuickSend: (Long) -> Unit,
    onSplitWords: (Long) -> Unit,
    onRemoveFromQuickSend: (Long) -> Unit,
    onBack: (() -> Unit)? = null,
    onClipboardTabChange: ((Int) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val itemBgColor = if (isDarkTheme) Color(0xFF45474A) else Color.White
    val textColor = if (isDarkTheme) Color(0xFFE8EAED) else Color(0xFF202124)
    val subTextColor = if (isDarkTheme) Color(0xFF9AA0A6) else Color(0xFF5F6368)
    val accentColor = if (isDarkTheme) Color(0xFF8AB4F8) else Color(0xFF1A73E8)
    val configuration = LocalConfiguration.current
    val isLandscape =
        configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(backgroundColor)
    ) {
        // 标签切换栏（原在 CandidateBar 中，现搬到这里）
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = if (isLandscape) 50.dp else 8.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(if (isDarkTheme) Color(0xFF374151) else Color(0xFFF3F4F6))
                    .clickable { onBack?.invoke() },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                    contentDescription = "关闭面板",
                    tint = accentColor,
                    modifier = Modifier.size(24.dp)
                )
            }

            Spacer(modifier = Modifier.width(8.dp))

            Box(
                modifier = Modifier
                    .height(28.dp)
                    .clip(RoundedCornerShape(13.dp))
                    .background(if (isDarkTheme) Color(0xFF374151) else Color(0xFFF3F4F6))
                    .padding(2.dp),
                contentAlignment = Alignment.Center
            ) {
                Row(
                    modifier = Modifier.fillMaxHeight(),
                    horizontalArrangement = Arrangement.spacedBy(0.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxHeight()
                            .clip(RoundedCornerShape(11.dp))
                            .background(if (selectedTab == 0) accentColor else Color.Transparent)
                            .clickable { onClipboardTabChange?.invoke(0) }
                            .padding(horizontal = 12.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "剪贴板",
                            color = if (selectedTab == 0) Color.White else textColor,
                            fontSize = 11.sp,
                            fontWeight = if (selectedTab == 0) FontWeight.Medium else FontWeight.Normal
                        )
                    }

                    Box(
                        modifier = Modifier
                            .fillMaxHeight()
                            .clip(RoundedCornerShape(11.dp))
                            .background(if (selectedTab == 1) accentColor else Color.Transparent)
                            .clickable { onClipboardTabChange?.invoke(1) }
                            .padding(horizontal = 12.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "快捷发送",
                            color = if (selectedTab == 1) Color.White else textColor,
                            fontSize = 11.sp,
                            fontWeight = if (selectedTab == 1) FontWeight.Medium else FontWeight.Normal
                        )
                    }
                }
            }
        }

        if (selectedTab == 0) {
            ClipboardTabContent(
                items = clipboardItems,
                itemBgColor = itemBgColor,
                textColor = textColor,
                subTextColor = subTextColor,
                accentColor = accentColor,
                onSelect = onSelectItem,
                onRemove = onRemoveItem,
                onAddToQuickSend = onAddToQuickSend,
                onSplitWords = onSplitWords
            )
        } else {
            QuickSendTabContent(
                items = quickSendItems,
                itemBgColor = itemBgColor,
                textColor = textColor,
                subTextColor = subTextColor,
                accentColor = accentColor,
                onSelect = onSelectItem,
                onRemove = onRemoveFromQuickSend
            )
        }
    }
}

@Composable
fun ClipboardTabContent(
    items: List<ClipboardItem>,
    itemBgColor: Color,
    textColor: Color,
    subTextColor: Color,
    accentColor: Color,
    onSelect: (String) -> Unit,
    onRemove: (Long) -> Unit,
    onAddToQuickSend: (Long) -> Unit,
    onSplitWords: (Long) -> Unit
) {
    if (items.isEmpty()) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "剪贴板为空",
                color = subTextColor,
                fontSize = 13.sp
            )
        }
    } else {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 4.dp, vertical = 2.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            items(items, key = { it.id }) { item ->
                CompactClipboardItem(
                    item = item,
                    bgColor = itemBgColor,
                    textColor = textColor,
                    subTextColor = subTextColor,
                    accentColor = accentColor,
                    onSelect = { onSelect(item.text) },
                    onRemove = { onRemove(item.id) },
                    onAddToQuickSend = { onAddToQuickSend(item.id) },
                    onSplitWords = { onSplitWords(item.id) }
                )
            }
        }
    }
}

@Composable
fun QuickSendTabContent(
    items: List<ClipboardItem>,
    itemBgColor: Color,
    textColor: Color,
    subTextColor: Color,
    accentColor: Color,
    onSelect: (String) -> Unit,
    onRemove: (Long) -> Unit
) {
    if (items.isEmpty()) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "快捷发送为空",
                color = subTextColor,
                fontSize = 13.sp
            )
        }
    } else {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 4.dp, vertical = 2.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            items(items, key = { it.id }) { item ->
                CompactQuickSendItem(
                    item = item,
                    bgColor = itemBgColor,
                    textColor = textColor,
                    subTextColor = subTextColor,
                    accentColor = accentColor,
                    onSelect = { onSelect(item.text) },
                    onRemove = { onRemove(item.id) }
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CompactClipboardItem(
    item: ClipboardItem,
    bgColor: Color,
    textColor: Color,
    subTextColor: Color,
    accentColor: Color,
    onSelect: () -> Unit,
    onRemove: () -> Unit,
    onAddToQuickSend: () -> Unit,
    onSplitWords: () -> Unit
) {
    var showActions by remember { mutableStateOf(false) }
    var actionsWidthPx by remember { mutableStateOf(0f) }

    val slideOffset by animateFloatAsState(
        targetValue = if (showActions) actionsWidthPx + 40f else 0f,
        animationSpec = tween(durationMillis = 300),
        label = "slideOffset"
    )

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(40.dp)
            .clip(RoundedCornerShape(8.dp))
    ) {
        // 背景层：操作按钮（始终在右侧），无背景色
        Row(
            modifier = Modifier.fillMaxSize(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.End
        ) {
            Row(
                modifier = Modifier
                    .height(40.dp)
                    .padding(end = 4.dp)
                    .onSizeChanged { actionsWidthPx = it.width.toFloat() },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                // 拆词：图标 + 文本
                Row(
                    modifier = Modifier
                        .height(38.dp)
                        .clip(RoundedCornerShape(20.dp))
                        .background(MaterialTheme.colorScheme.primaryContainer)
                        .clickable { showActions = false; onSplitWords() }
                        .padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Icon(
                        Icons.Default.ContentCut,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        "拆词",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        fontSize = 14.sp
                    )
                }

                // 添加快捷发送：图标 + 文本
                Row(
                    modifier = Modifier
                        .height(38.dp)
                        .clip(RoundedCornerShape(20.dp))
                        .background(MaterialTheme.colorScheme.primaryContainer)
                        .clickable { showActions = false; onAddToQuickSend() }
                        .padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Icon(
                        Icons.Outlined.StarBorder,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        "快捷",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        fontSize = 14.sp
                    )
                }

                // 删除：仅图标 + 红色浅背景
                Box(
                    modifier = Modifier
                        .size(38.dp)
                        .clip(RoundedCornerShape(20.dp))
                        .background(MaterialTheme.colorScheme.errorContainer.copy(0.5f))
                        .clickable { showActions = false; onRemove() },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Default.Delete,
                        contentDescription = "删除",
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }

        // 前景层：内容区域（滑动后露出背景层的操作按钮）
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(40.dp)
                .offset { IntOffset((-slideOffset).roundToInt(), 0) }
                .background(bgColor, RoundedCornerShape(8.dp))
                .clickable {
                    if (showActions) showActions = false
                    else onSelect()
                },
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = item.text,
                color = textColor,
                fontSize = 14.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 8.dp)
            )

            IconButton(
                onClick = { showActions = !showActions },
                modifier = Modifier.size(32.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.MoreVert,
                    contentDescription = "更多",
                    tint = accentColor,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CompactQuickSendItem(
    item: ClipboardItem,
    bgColor: Color,
    textColor: Color,
    subTextColor: Color,
    accentColor: Color,
    onSelect: () -> Unit,
    onRemove: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(36.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(bgColor)
            .clickable { onSelect() },
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = Icons.Filled.Star,
            contentDescription = "快捷发送",
            tint = accentColor,
            modifier = Modifier
                .size(16.dp)
                .padding(horizontal = 4.dp)
        )

        Text(
            text = item.text,
            color = textColor,
            fontSize = 13.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 4.dp)
        )

        IconButton(
            onClick = onRemove,
            modifier = Modifier.size(32.dp)
        ) {
            Icon(
                imageVector = Icons.Filled.Delete,
                contentDescription = "删除",
                tint = subTextColor,
                modifier = Modifier.size(16.dp)
            )
        }
    }
}