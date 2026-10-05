package com.kingzcheung.xime.ui.keyboard

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kingzcheung.xime.plugin.core.api.PluginResultItem
import com.kingzcheung.xime.plugin.core.config.UiNode
import com.kingzcheung.xime.plugin.core.config.UiNodeType

private const val MAX_UI_NODES = 64

/**
 * passive 纯展示面板（全屏 Overlay 页面，与表情/符号同级，覆盖键盘）。
 *
 * 渲染插件通过 getPanelState.ui 声明的白名单节点树（统一 [UiNode] 声明模型）：
 *   SECTION / TEXT / METRIC / DIVIDER / BUTTON
 * 以及 items 候选条目（点击 → onItemClick → 宿主上屏，替代原 SELECT 全屏结果页）。
 * 表单型节点（TEXTAREA/SECRET/SELECT 等）在面板降级为只读文本；
 * 节点数截断 [MAX_UI_NODES]；不做嵌套渲染。
 */
@Composable
fun InfoPanel(
    title: String,
    nodes: List<UiNode>,
    items: List<PluginResultItem> = emptyList(),
    isLoading: Boolean = false,
    backgroundColor: Color,
    textColor: Color,
    accentColor: Color,
    itemBgColor: Color,
    bottomPaddingDp: Int = 0,
    onClose: () -> Unit,
    onAction: (String) -> Unit,
    onItemClick: (PluginResultItem) -> Unit,
    modifier: Modifier = Modifier,
) {
    val closeButtonBg = androidx.compose.ui.graphics.lerp(
        MaterialTheme.colorScheme.surface,
        MaterialTheme.colorScheme.primary,
        0.35f
    )

    Column(
        modifier = modifier
            .fillMaxWidth()
            .fillMaxSize()
            .background(backgroundColor)
            .padding(bottom = bottomPaddingDp.dp)
    ) {
        // 导航区：关闭按钮 + 标题
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(50.dp)
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(CircleShape)
                    .background(closeButtonBg)
                    .clickable(onClick = onClose),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "关闭",
                    tint = accentColor,
                    modifier = Modifier.size(18.dp)
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = title,
                color = textColor,
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 14.dp, vertical = 4.dp),
        ) {
            if (nodes.isEmpty()) {
                Text(
                    text = if (isLoading) "加载中..." else "暂无数据",
                    color = textColor.copy(alpha = 0.5f),
                    fontSize = 12.sp,
                    modifier = Modifier.padding(vertical = 8.dp),
                )
            }
            nodes.take(MAX_UI_NODES).forEach { node ->
                when (node.type) {
                    UiNodeType.SECTION -> InfoSection(node.label ?: "", accentColor)
                    UiNodeType.TEXT,
                    UiNodeType.TEXTAREA -> InfoText(node, textColor)
                    UiNodeType.METRIC -> InfoMetric(node, textColor, accentColor)
                    UiNodeType.DIVIDER -> HorizontalDivider(
                        modifier = Modifier.padding(vertical = 6.dp),
                        color = textColor.copy(alpha = 0.15f),
                    )
                    UiNodeType.BUTTON -> InfoAction(node, onAction)
                    else -> // 表单型字段降级为只读文本（前向兼容，插件新节点跑旧宿主不崩溃）
                        Text(
                            text = node.value ?: node.label ?: "",
                            color = textColor.copy(alpha = 0.7f),
                            fontSize = 13.sp,
                            modifier = Modifier.padding(vertical = 2.dp),
                        )
                }
            }
            // 候选条目（插件 getPanelState.items，点击上屏；替代原 SELECT 全屏结果页）
            if (items.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                items.forEach { item ->
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(itemBgColor)
                            .clickable { onItemClick(item) }
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                    ) {
                        Text(
                            text = item.text,
                            color = textColor,
                            fontSize = 15.sp,
                            maxLines = 6,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                }
            }
        }
    }
}

@Composable
private fun InfoSection(title: String, accentColor: Color) {
    Text(
        text = title,
        color = accentColor,
        fontSize = 13.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(top = 6.dp, bottom = 2.dp),
    )
}

@Composable
private fun InfoText(node: UiNode, textColor: Color) {
    val style = node.style
    val isCaption = style == "caption"
    Text(
        text = node.value ?: "",
        color = textColor.copy(alpha = if (isCaption) 0.6f else 0.9f),
        fontSize = if (isCaption) 11.sp else 14.sp,
        modifier = Modifier.padding(vertical = 2.dp),
    )
}

@Composable
private fun InfoMetric(node: UiNode, textColor: Color, accentColor: Color) {
    val value = node.value ?: ""
    val label = node.label ?: ""
    val unit = node.unit ?: ""
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Bottom,
    ) {
        Text(text = label, color = textColor.copy(alpha = 0.6f), fontSize = 12.sp)
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                text = value,
                color = accentColor,
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold,
            )
            if (unit.isNotEmpty()) {
                Spacer(Modifier.height(0.dp))
                Text(
                    text = " $unit",
                    color = textColor.copy(alpha = 0.6f),
                    fontSize = 11.sp,
                )
            }
        }
    }
}

@Composable
private fun InfoAction(node: UiNode, onAction: (String) -> Unit) {
    val actionId = node.key ?: return
    val label = node.label ?: return
    FilledTonalButton(
        onClick = { onAction(actionId) },
        modifier = Modifier
            .padding(top = 6.dp)
            .height(34.dp),
    ) {
        Text(text = label, fontSize = 12.sp)
    }
}
