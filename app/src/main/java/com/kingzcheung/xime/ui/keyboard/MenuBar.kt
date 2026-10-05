package com.kingzcheung.xime.ui.keyboard

import androidx.compose.foundation.background
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
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.twotone.Assignment
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.twotone.DarkMode
import androidx.compose.material.icons.twotone.EmojiEmotions
import androidx.compose.material.icons.twotone.Keyboard
import androidx.compose.material.icons.twotone.LightMode
import androidx.compose.material.icons.twotone.Padding
import androidx.compose.material.icons.twotone.PictureInPicture
import androidx.compose.material.icons.twotone.Quickreply
import androidx.compose.material.icons.twotone.Rotate90DegreesCcw
import androidx.compose.material.icons.twotone.Settings
import androidx.compose.material.icons.twotone.SettingsOverscan
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kingzcheung.xime.viewmodel.SchemaSwitchUiState

data class MenuItem(
    val icon: Painter? = null,
    val label: String,
    val action: () -> Unit,
    val textIcon: String? = null,
)

data class MenuBarState(
    val isVisible: Boolean,
    val isDarkTheme: Boolean,
    val darkMode: Int = 2,
    val backgroundColor: Color,
    val keyBgColor: Color = Color.White,
    val keyTextColor: Color = Color(0xFF202124),
    val isFloatingMode: Boolean = false,
    val schemaSwitches: List<SchemaSwitchUiState> = emptyList(),
)

data class MenuBarCallbacks(
    val onDismiss: () -> Unit,
    val onClipboard: () -> Unit,
    val onQuickSend: () -> Unit,
    val onKeyboardResize: () -> Unit,
    val onEmoji: () -> Unit,
    val onReloadConfig: () -> Unit,
    val onSettings: () -> Unit,
    val onSchemaList: () -> Unit,
    val onToggleDarkMode: () -> Unit,
    val onFloatingModeToggle: (() -> Unit)? = null,
    val onToolbarCustomize: () -> Unit = {},
    val onToggleSchemaSwitch: ((SchemaSwitchUiState) -> Unit)? = null,
)

@Composable
fun MenuBar(
    state: MenuBarState,
    callbacks: MenuBarCallbacks,
    modifier: Modifier = Modifier
) {
    if (!state.isVisible) return
    
    val textColor = state.keyTextColor
    // 功能 item 背景：与键盘按键背景一致（keyBgColor，浅色纯白、深色跟随 keyboard.colors）
    val itemBgColor = state.keyBgColor
    // 图标按钮容器色：surface 与 primary 的混合色调（带种子色但不过于强烈）
    val iconButtonContainer = androidx.compose.ui.graphics.lerp(
        MaterialTheme.colorScheme.surface,
        MaterialTheme.colorScheme.primary,
        0.35f
    )
    val configuration = LocalConfiguration.current
    val isLandscape = !state.isFloatingMode && configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
    
    val clipboardIcon = rememberVectorPainter(Icons.AutoMirrored.TwoTone.Assignment)
    val quickSendIcon = rememberVectorPainter(Icons.TwoTone.Quickreply)
    val keyboardResizeIcon = rememberVectorPainter(Icons.TwoTone.SettingsOverscan)
    val emojiIcon = rememberVectorPainter(Icons.TwoTone.EmojiEmotions)
    val darkModeIcon = when (state.darkMode) {
        0 -> rememberVectorPainter(Icons.TwoTone.DarkMode)
        1 -> rememberVectorPainter(Icons.TwoTone.LightMode)
        else -> rememberVectorPainter(if (state.isDarkTheme) Icons.TwoTone.LightMode else Icons.TwoTone.DarkMode)
    }
    val deployIcon = rememberVectorPainter(Icons.TwoTone.Rotate90DegreesCcw)
    val customizeIcon = rememberVectorPainter(Icons.TwoTone.Padding)
    val schemaIcon = rememberVectorPainter(Icons.TwoTone.Keyboard)
    val settingsIcon = rememberVectorPainter(Icons.TwoTone.Settings)

    val darkModeLabel = when (state.darkMode) {
        0 -> "深色模式"
        1 -> "浅色模式"
        else -> "跟随系统"
    }

    val floatingIcon = rememberVectorPainter(Icons.TwoTone.PictureInPicture)
    val floatingLabel = if (state.isFloatingMode) "退出悬浮" else "悬浮模式"
    val floatingAction = callbacks.onFloatingModeToggle ?: {}

    // 动态方案开关：图标取第一个状态的首字；标题若有 abbrev 则用 abbrev（多个用 🔁 连接），否则用所有状态 🔁 连接
    val switchItems = state.schemaSwitches.map { sw ->
        val textIcon = sw.states.firstOrNull()?.firstOrNull()?.toString() ?: ""
        val label = if (sw.abbrev.isNotEmpty()) sw.abbrev.joinToString("🔁")
            else sw.states.joinToString("🔁")
        MenuItem(icon = null, label = label, action = { callbacks.onToggleSchemaSwitch?.invoke(sw) }, textIcon = textIcon)
    }

    val menuItems = remember(darkModeIcon, darkModeLabel, state.isFloatingMode, state.schemaSwitches) {
        listOf(
            MenuItem(clipboardIcon, "剪贴板", callbacks.onClipboard),
            MenuItem(quickSendIcon, "快捷发送", callbacks.onQuickSend),
            MenuItem(schemaIcon, "输入方案", callbacks.onSchemaList),
            MenuItem(emojiIcon, "表情", callbacks.onEmoji),
        ) + switchItems + listOf(
            MenuItem(customizeIcon, "定制工具栏", callbacks.onToolbarCustomize),
            // 悬浮模式下键盘内容为缩放的浮动卡片，高度不可调节，隐藏该入口
            if (!state.isFloatingMode) MenuItem(keyboardResizeIcon, "键盘调节", callbacks.onKeyboardResize) else null,
            MenuItem(darkModeIcon, darkModeLabel, callbacks.onToggleDarkMode),
            MenuItem(floatingIcon, floatingLabel, floatingAction),
            MenuItem(deployIcon, "部署方案", callbacks.onReloadConfig),
        ).filterNotNull()
    }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(state.backgroundColor),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(50.dp)
                .padding(horizontal = if (isLandscape) 50.dp else 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(CircleShape)
                    .background(iconButtonContainer)
                    .clickable { callbacks.onDismiss() },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.KeyboardArrowUp,
                    contentDescription = "关闭菜单",
                    tint = textColor,
                    modifier = Modifier.size(24.dp)
                )
            }
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceContainerLow)
                    .clickable { callbacks.onSettings() },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    painter = settingsIcon,
                    contentDescription = "设置",
                    tint = textColor,
                    modifier = Modifier.size(24.dp)
                )
            }
        }
        // 内容区（菜单项）
        if (isLandscape) {
            // 横屏：一行 8 列
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 50.dp)
                    .weight(1f),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                menuItems.forEach { item ->
                    MenuItemButton(
                        item = item,
                        bgColor = itemBgColor,
                        textColor = textColor,
                        modifier = Modifier.weight(1f),
                        isLandscape = true
                    )
                }
            }
        } else {
            // 竖屏：每页最多 8 项（2 行 × 4 列），支持横向翻页
            val itemsPerPage = 8
            val pages = menuItems.chunked(itemsPerPage).map { page ->
                page + List(itemsPerPage - page.size) { null }
            }
            val pagerState = rememberPagerState(pageCount = { pages.size })
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.SpaceAround
            ) {
                HorizontalPager(
                    state = pagerState,
                    modifier = Modifier.fillMaxWidth()
                ) { page ->
                    FlowRow(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        maxItemsInEachRow = 4
                    ) {
                        pages[page].forEach { item ->
                            if (item != null) {
                                MenuItemButton(
                                    item = item,
                                    bgColor = itemBgColor,
                                    textColor = textColor,
                                    modifier = Modifier.weight(1f)
                                )
                            } else {
                                Box(modifier = Modifier.weight(1f).aspectRatio(1f))
                            }
                        }
                    }
                }

                if (pages.size > 1) {

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
                                        if (index == pagerState.currentPage) textColor
                                        else textColor.copy(alpha = 0.3f)
                                    )
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                }
            }
        }
    }
}

@Composable
fun MenuItemButton(
    item: MenuItem,
    bgColor: Color,
    textColor: Color,
    modifier: Modifier = Modifier,
    isLandscape: Boolean = false
) {
    Column(
        modifier = modifier
            .then(if (isLandscape) Modifier.height(72.dp) else Modifier.aspectRatio(1f))
            .clip(RoundedCornerShape(12.dp))
            .background(bgColor)
            .clickable { item.action() }
            .padding(4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        if (item.textIcon != null) {
            Text(
                text = item.textIcon,
                color = textColor.copy(alpha = 0.7f),
                fontSize = if (isLandscape) 18.sp else 24.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1
            )
        } else if (item.icon != null) {
            Icon(
                painter = item.icon,
                contentDescription = item.label,
                tint = textColor.copy(alpha = 0.7f),
                modifier = Modifier.size(if (isLandscape) 18.dp else 24.dp)
            )
        }
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = item.label,
            color = textColor,
            fontSize = if (isLandscape) 9.sp else 10.sp,
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.Center,
            maxLines = 1
        )
    }
}
