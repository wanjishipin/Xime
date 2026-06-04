package com.kingzcheung.xime.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun ToolbarCustomizeView(
    toolbarButtons: List<String>,
    keyTextColor: Color,
    keyBgColor: Color,
    accentColor: Color,
    onUpdateToolbarButtons: ((List<String>) -> Unit)?,
    onDismiss: () -> Unit,
    bottomPaddingDp: Int = 0,
    modifier: Modifier = Modifier
) {
    val allButtons = ToolbarButton.entries
    val originalButtons = remember { toolbarButtons }
    var enabledIds by remember(toolbarButtons) { mutableStateOf(toolbarButtons.toSet()) }

    fun toggleButton(button: ToolbarButton) {
        enabledIds = if (button.id in enabledIds) enabledIds - button.id else enabledIds + button.id
        // 保持 toolbarButtons 的原始顺序，只增删对应项
        val newList = toolbarButtons.toMutableList()
        if (button.id in toolbarButtons) newList.remove(button.id) else newList.add(button.id)
        onUpdateToolbarButtons?.invoke(newList)
    }

    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
    val isDarkTheme = keyTextColor == Color(0xFFE8EAED)

    val itemsPerPage = 8
    val pages = allButtons.chunked(itemsPerPage).map { page ->
        page + List(itemsPerPage - page.size) { null }
    }
    val pagerState = rememberPagerState(pageCount = { pages.size })

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(keyBgColor),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // 导航区：关闭按钮 + 实时预览
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(50.dp)
                .padding(horizontal = if (isLandscape) 50.dp else 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(CircleShape)
                    .background(if (isDarkTheme) Color(0xFF374151) else Color(0xFFF3F4F6))
                    .clickable { onDismiss() },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = "确定",
                    tint = keyTextColor,
                    modifier = Modifier.size(24.dp)
                )
            }

            Spacer(modifier = Modifier.width(8.dp))

            // 工具栏预览
            Row(
                modifier = Modifier
                    .weight(1f)
                    .padding(end = 4.dp),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                val previewButtons = toolbarButtons.mapNotNull { ToolbarButton.fromId(it) }
                if (previewButtons.isNotEmpty()) {
                    previewButtons.forEach { button ->
                        Box(
                            modifier = Modifier
                                .padding(horizontal = 3.dp)
                                .size(28.dp)
                                .clip(CircleShape)
                                .background(if (isDarkTheme) Color.White.copy(alpha = 0.15f) else Color.Black.copy(alpha = 0.1f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = button.icon,
                                contentDescription = null,
                                tint = keyTextColor.copy(0.6f),
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }
            }
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(MaterialTheme.colorScheme.surface)
                .padding(16.dp)
        ) {
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxWidth()
            ) { page ->
                FlowRow(
                    modifier = Modifier
                        .fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    maxItemsInEachRow = 4
                ) {
                    pages[page].forEach { button ->
                        if (button != null) {
                            val isEnabled = button.id in enabledIds
                            Column(
                                modifier = Modifier.weight(1f),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(12.dp)
                                        .aspectRatio(1f)
                                        .clip(CircleShape)
                                        .background(
                                            if (isEnabled) accentColor.copy(0.2f)
                                            else Color.Transparent
                                        )
                                        .border(
                                            width = 1.dp,
                                            color = if (isEnabled) Color.Transparent
                                            else keyTextColor.copy(alpha = 0.15f),
                                            shape = CircleShape
                                        )
                                        .clickable { toggleButton(button) },
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = button.icon,
                                        contentDescription = button.label,
                                        tint = if (isEnabled) accentColor else keyTextColor,
                                        modifier = Modifier.size(22.dp)
                                    )
                                }
                                Text(
                                    text = button.label,
                                    fontSize = 11.sp,
                                    color = keyTextColor.copy(alpha = 0.8f),
                                    maxLines = 1
                                )
                            }
                        } else {
                            Box(modifier = Modifier.weight(1f).aspectRatio(1f))
                        }
                    }
                }
            }
        }        }
        if (pages.size > 1) {
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                repeat(pages.size) { index ->
                    Box(
                        modifier = Modifier
                            .size(if (index == pagerState.currentPage) 8.dp else 6.dp)
                            .clip(CircleShape)
                            .background(
                                if (index == pagerState.currentPage) keyTextColor
                                else keyTextColor.copy(alpha = 0.3f)
                            )
                    )
                }
            }
        }

        // 底部留空（竖屏至少 40dp）
        Spacer(modifier = Modifier.height(if (isLandscape) 15.dp else maxOf(bottomPaddingDp, 40).dp))
    }
}
