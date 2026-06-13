package com.kingzcheung.xime.ui

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material3.Icon
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.kingzcheung.xime.clipboard.ClipboardManager
import com.kingzcheung.xime.plugin.ExtensionManager
import com.kingzcheung.xime.plugin.core.api.CategoryLayoutConfig
import com.kingzcheung.xime.plugin.core.api.EmojiItem
import com.kingzcheung.xime.plugin.core.api.PluginIcon

data class EmojiCategory(
    val name: String,
    val icon: String,
    val pluginIcon: PluginIcon? = null,
    val emojis: List<String>,
    val isPlugin: Boolean = false,
    val pluginId: String? = null,
    val emojiItems: List<EmojiItem>? = null,
    val layoutConfig: CategoryLayoutConfig? = null
)

object EmojiData {
    val categories = listOf(
        EmojiCategory(
            name = "笑脸",
            icon = "😊",
            emojis = listOf(
                "😀", "😃", "😄", "😁", "😆", "😅", "🤣", "😂", "🙂", "🙃",
                "😉", "😊", "😇", "🥰", "😍", "🤩", "😘", "😗", "😚", "😙",
                "🥲", "😋", "😛", "😜", "🤪", "😝", "🤑", "🤗", "🤭", "🤫",
                "🤔", "🤐", "🤨", "😐", "😑", "😶", "😏", "😒", "🙄", "😬",
                "🤥", "😌", "😔", "😪", "🤤", "😴", "😷", "🤒", "🤕", "🤢",
                "🤮", "🤧", "🥵", "🥶", "🥴", "😵", "🤯", "🤠", "🥳", "🥸",
                "😎", "🤓", "🧐", "😕", "😟", "🙁", "☹️", "😮", "😯", "😲",
                "😳", "🥺", "😦", "😧", "😨", "😰", "😥", "😢", "😭", "😱",
                "😖", "😣", "😞", "😓", "😩", "😫", "🥱", "😤", "😡", "😠",
                "🤬", "😈", "👿", "💀", "☠️", "💩", "🤡", "👹", "👺", "👻"
            )
        ),
        EmojiCategory(
            name = "手势",
            icon = "👋",
            emojis = listOf(
                "👋", "🤚", "🖐️", "✋", "🖖", "👌", "🤌", "🤏", "✌️", "🤞",
                "🤟", "🤘", "🤙", "👈", "👉", "👆", "🖕", "👇", "☝️", "👍",
                "👎", "✊", "👊", "🤛", "🤜", "👏", "🙌", "👐", "🤲", "🤝",
                "🙏", "✍️", "💅", "🤳", "💪", "🦾", "🦿", "🦵", "🦶", "👂",
                "🦻", "👃", "🧠", "🫀", "🫁", "🦷", "🦴", "👀", "👁️", "👅",
                "👄", "💪", "🦵", "🦶", "👂", "🦻", "👃", "🧠", "🫀", "🫁"
            )
        ),
        EmojiCategory(
            name = "动物",
            icon = "🐶",
            emojis = listOf(
                "🐶", "🐱", "🐭", "🐰", "🦊", "🐻", "🐼", "🐨", "🐯",
                "🦁", "🐮", "🐷", "🐸", "🐵", "🙈", "🙉", "🙊", "🐒", "🐔",
                "🐧", "🐦", "🐤", "🐣", "🐥", "🦆", "🦅", "🦉", "🦇", "🐺",
                "🐗", "🐴", "🦄", "🐝", "🐛", "🦋", "🐌", "🐞", "🐜", "🦟",
                "🦗", "🕷️", "🦂", "🐢", "🐍", "🦎", "🦖", "🦕", "🐙", "🦑",
                "🦐", "🦞", "🦀", "🐡", "🐠", "🐟", "🐬", "🐳", "🐋", "🦈"
            )
        ),
        EmojiCategory(
            name = "食物",
            icon = "🍎",
            emojis = listOf(
                "🍎", "🍐", "🍊", "🍋", "🍌", "🍉", "🍇", "🍓", "🫐", "🍈",
                "🍒", "🍑", "🥭", "🍍", "🥥", "🥝", "🍅", "🍆", "🥑", "🥦",
                "🥬", "🥒", "🌶️", "🫑", "🌽", "🥕", "🧄", "🧅", "🥔", "🍠",
                "🍞", "🍩", "🥖", "🥖", "🍪", "🧀", "🥚", "🍳", "🧈", "🥞",
                "🧇", "🥓", "🥩", "🍗", "🍖", "🦴", "🌭", "🍔", "🍟", "🍕",
                "🫓", "🥪", "🌯", "🥗", "🌮", "🍙", "🍚", "🍲", "🥘", "🧀"
            )
        ),
        EmojiCategory(
            name = "活动",
            icon = "⚽",
            emojis = listOf(
                "⚽", "🏀", "🏈", "⚾", "🥎", "🎾", "🏐", "🏉", "🥏", "🎱",
                "🪀", "🏓", "🏸", "🏒", "🏑", "🥍", "🏏", "🥏", "🪃", "🥅",
                "⛳", "🪁", "🏹", "🎣", "🤿", "🥊", "🥋", "🏃", "🛹", "🛼",
                "🪂", "⛸️", "🥌", "⛷️", "🏂", "⛸️", "🪂", "🏋️", "🤼", "🤸",
                "🤺", "🤾", "🥏", "⛳", "🏇", "🧘", "🏄", "🏊", "🤽", "🚣",
                "🧗", "🚵", "🚴", "🎖️", "🏆", "🥇", "🥈", "🥉", "🎖️", "🎪"
            )
        ),
        EmojiCategory(
            name = "物品",
            icon = "💻",
            emojis = listOf(
                "⌚", "📱", "☎️", "💻", "⌨️", "🖥️", "🖨️", "🖱️", "🖲️", "🕹️",
                "🗜️", "💿", "💾", "📀", "📀", "📼", "📷", "📸", "📹", "🎬",
                "📽️", "🎞️", "☎️", "📞", "📟", "📠", "📺", "📻", "🎤", "🎛️",
                "🎮", "🧭", "⏱️", "⏲️", "⏰", "🕰️", "⏳", "⌛", "📡", "🔋",
                "🔌", "💡", "🔦", "🕯️", "🪔", "🧯", "🛢️", "💵", "💵", "💴",
                "💶", "💷", "👛", "💳", "💎", "⚖️", "🧰", "🔧", "🔨", "⛏️"
            )
        ),
        EmojiCategory(
            name = "符号",
            icon = "❤️",
            emojis = listOf(
                "❤️", "🧡", "💛", "💚", "💙", "💜", "🖤", "🤍", "🤎", "💔",
                "❗", "💕", "💕", "💓", "💗", "✨", "💘", "🎁", "♻️", "☮️",
                "✝️", "☪️", "🕉️", "☸️", "✡️", "🔯", "✡️", "☯️", "☦️", "🙏",
                "⛎", "♈", "♉", "♊", "♋", "♌", "♎", "♏", "♐", "♑",
                "♒", "♓", "🪪", "⚛️", "✅", "☢️", "☣️", "📵", "📱",
                "🈶", "🈚", "🈸", "🈺", "🌙", "⭐", "⚔️", "🏵️", "🏅", "㊙️"
            )
        )
    )
}

@Composable
fun EmojiKeyboardLayout(
    onEmojiSelect: (String) -> Unit,
    onImageEmojiSelect: ((String) -> Unit)? = null,
    onBack: () -> Unit,
    backgroundColor: Color,
    textColor: Color,
    accentColor: Color,
    bottomPaddingDp: Int = 0,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val clipboardManager = remember { ClipboardManager.getInstance(context) }

    val isDarkTheme = textColor == Color(0xFFE8EAED)

    var selectedTopTabIndex by remember { mutableStateOf(0) }
    var selectedSubCategoryIndex by remember { mutableStateOf(0) }

    val allCategories by ExtensionManager.emojiCategoriesFlow.collectAsStateWithLifecycle()
    val pluginCategories = allCategories.filter { it.isPlugin }
    val builtinCategories = allCategories.filter { !it.isPlugin }

    // 按 pluginId 分组插件子分类（用于顶层 tab 和底部子分类 tab）
    val pluginGroupEntries = remember(pluginCategories) {
        pluginCategories.groupBy { it.pluginId ?: it.name }.entries.toList()
    }

    // 当前顶层 tab 对应的子分类列表
    val currentSubCategories = if (selectedTopTabIndex == 0) {
        builtinCategories
    } else {
        val groupIdx = selectedTopTabIndex - 1
        if (groupIdx < pluginGroupEntries.size) pluginGroupEntries[groupIdx].value
        else emptyList()
    }

    // 所有页面的扁平索引（用于动画过渡）
    val currentPageIndex = if (selectedTopTabIndex == 0) {
        selectedSubCategoryIndex.coerceIn(0, maxOf(0, builtinCategories.lastIndex))
    } else {
        val groupIdx = selectedTopTabIndex - 1
        val startPage = builtinCategories.size + pluginGroupEntries.take(groupIdx).sumOf { it.value.size }
        val groupSize = if (groupIdx < pluginGroupEntries.size) pluginGroupEntries[groupIdx].value.lastIndex else 0
        startPage + selectedSubCategoryIndex.coerceIn(0, maxOf(0, groupSize))
    }
    val totalPages = builtinCategories.size + pluginCategories.size

    val configuration = LocalConfiguration.current
    val isLandscape =
        configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
    val emojiColumns = if (isLandscape) 15 else 8

    // 当前显示的类别
    val currentCategory =
        if (selectedTopTabIndex == 0) {
            if (builtinCategories.isNotEmpty()) builtinCategories[selectedSubCategoryIndex.coerceIn(0, builtinCategories.lastIndex)]
            else EmojiData.categories.first()
        } else {
            val groupIdx = selectedTopTabIndex - 1
            if (pluginGroupEntries.isNotEmpty() && groupIdx < pluginGroupEntries.size) {
                val subCats = pluginGroupEntries[groupIdx].value
                subCats[selectedSubCategoryIndex.coerceIn(0, subCats.lastIndex)]
            } else EmojiData.categories.first()
        }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(backgroundColor)
    ) {
        // 导航区：返回按钮 + 顶层 Tab（Emoji / 插件）
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(50.dp)
                .padding(start = if (isLandscape) 50.dp else 8.dp, end = if (isLandscape) 50.dp else 8.dp),
            contentAlignment = Alignment.CenterStart
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 返回按钮
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .clip(CircleShape)
                        .background(if (isDarkTheme) Color(0xFF374151) else Color(0xFFF3F4F6))
                        .clickable { onBack() },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.KeyboardArrowLeft,
                        contentDescription = "返回",
                        tint = textColor,
                        modifier = Modifier.size(24.dp)
                    )
                }

                Spacer(modifier = Modifier.width(8.dp))

                // 顶层 Tab（ClipboardView 样式）
                Box(
                    modifier = Modifier
                        .height(28.dp)
                        .clip(RoundedCornerShape(13.dp))
                        .background(if (isDarkTheme) Color(0xFF374151) else Color(0xFFF3F4F6))
                        .padding(2.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxHeight(),
                        horizontalArrangement = Arrangement.spacedBy(0.dp)
                    ) {
                        // Emoji 主 Tab
                        Box(
                            modifier = Modifier
                                .fillMaxHeight()
                                .clip(RoundedCornerShape(11.dp))
                                .background(
                                    if (selectedTopTabIndex == 0) accentColor.copy(0.4f)
                                    else Color.Transparent
                                )
                                .clickable {
                                    selectedTopTabIndex = 0
                                    selectedSubCategoryIndex = 0
                                }
                                .padding(horizontal = 10.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "😊",
                                fontSize = 14.sp
                            )
                        }

                        // 插件 Tab（按 pluginId 分组，每插件一个顶层 tab）
                        pluginGroupEntries.forEachIndexed { index, (_, subCats) ->
                            val firstCat = subCats.first()
                            val pluginIcon = firstCat.pluginIcon
                            Box(
                                modifier = Modifier
                                    .fillMaxHeight()
                                    .clip(RoundedCornerShape(11.dp))
                                    .background(
                                        if (selectedTopTabIndex == index + 1) accentColor.copy(0.4f)
                                        else Color.Transparent
                                    )
                                    .clickable {
                                        selectedTopTabIndex = index + 1
                                        selectedSubCategoryIndex = 0
                                    }
                                    .padding(horizontal = 10.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                if (pluginIcon?.assetName != null) {
                                    AsyncImage(
                                        model = ImageRequest.Builder(context)
                                            .data(pluginIcon.assetName)
                                            .crossfade(true)
                                            .build(),
                                        contentDescription = firstCat.name,
                                        modifier = Modifier
                                            .fillMaxHeight()
                                            .padding(2.dp),
                                        contentScale = ContentScale.Fit
                                    )
                                } else {
                                    Text(
                                        text = pluginIcon?.text ?: firstCat.icon,
                                        fontSize = 14.sp
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        val pagerState = rememberPagerState(
            initialPage = currentPageIndex,
            pageCount = { totalPages }
        )

        // 外部切换分类时同步到 Pager
        LaunchedEffect(currentPageIndex) {
            pagerState.animateScrollToPage(currentPageIndex)
        }

        // Pager 滑动时同步到外部状态
        LaunchedEffect(pagerState.currentPage, pagerState.isScrollInProgress) {
            val page = pagerState.currentPage
            if (!pagerState.isScrollInProgress && page != currentPageIndex) {
                if (page < builtinCategories.size) {
                    selectedTopTabIndex = 0
                    selectedSubCategoryIndex = page
                } else {
                    // 找到该 page 属于哪个插件组的哪个子分类
                    var remaining = page - builtinCategories.size
                    for ((groupIdx, entry) in pluginGroupEntries.withIndex()) {
                        if (remaining < entry.value.size) {
                            selectedTopTabIndex = groupIdx + 1
                            selectedSubCategoryIndex = remaining
                            break
                        }
                        remaining -= entry.value.size
                    }
                }
            }
        }

        HorizontalPager(
            state = pagerState,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(horizontal = if (isLandscape) 50.dp else 4.dp)
                .padding(bottom = 4.dp)
        ) { pageIndex ->
            val category = if (pageIndex < builtinCategories.size) {
                builtinCategories[pageIndex]
            } else {
                pluginCategories[pageIndex - builtinCategories.size]
            }

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                if (category.isPlugin && category.emojiItems != null) {
                    val config = category.layoutConfig
                    val defaultCols =
                        if (category.emojiItems.any { it.imageUrl != null }) 6 else 8
                    val columns = config?.columns ?: if (isLandscape) 15 else defaultCols
                    val itemHeightDp = config?.itemHeightDp
                        ?: (if (category.emojiItems.any { it.imageUrl != null }) 60 else 40)

                    category.emojiItems.chunked(columns).forEach { rowItems ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            rowItems.forEach { item ->
                                PluginEmojiButton(
                                    emojiItem = item,
                                    defaultHeightDp = itemHeightDp,
                                    backgroundColor = backgroundColor,
                                    textColor = textColor,
                                    onClick = {
                                        val imageUrl = item.imageUrl
                                        if (imageUrl != null && onImageEmojiSelect != null) {
                                            onImageEmojiSelect(imageUrl)
                                        } else if (imageUrl != null) {
                                            val success =
                                                clipboardManager.copyImageToSystemClipboard(
                                                    imageUrl,
                                                    item.displayText
                                                )
                                            if (success) {
                                                Toast.makeText(
                                                    context,
                                                    "已复制表情，可粘贴发送",
                                                    Toast.LENGTH_SHORT
                                                ).show()
                                            } else {
                                                Toast.makeText(
                                                    context,
                                                    "复制失败",
                                                    Toast.LENGTH_SHORT
                                                ).show()
                                            }
                                        } else {
                                            onEmojiSelect(item.insertText)
                                        }
                                    },
                                    modifier = Modifier.weight(1f)
                                )
                            }
                            repeat(columns - rowItems.size) {
                                Spacer(modifier = Modifier
                                    .weight(1f)
                                    .height((itemHeightDp).dp))
                            }
                        }
                        Spacer(modifier = Modifier.height(2.dp))
                    }
                } else {
                    val emojis = category.emojis
                    val columns = if (isLandscape) 15 else 8

                    emojis.chunked(columns).forEach { rowEmojis ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            rowEmojis.forEach { emoji ->
                                EmojiButton(
                                    emoji = emoji,
                                    onClick = { onEmojiSelect(emoji) },
                                    modifier = Modifier.weight(1f)
                                )
                            }
                            repeat(columns - rowEmojis.size) {
                                Spacer(modifier = Modifier.weight(1f))
                            }
                        }
                    }
                }
            }
        }

        // 底部：子分类 Tab 或留空 + 删除按钮
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(44.dp)
                .padding(horizontal = if (isLandscape) 50.dp else 4.dp, vertical = 0.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (currentSubCategories.isNotEmpty()) {
                // 显示当前顶层 tab 的子分类
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    currentSubCategories.forEachIndexed { index, category ->
                        if (category.isPlugin) {
                            // 插件子分类：显示分类名，不用 pluginIcon（那是插件级图标）
                            EmojiCategoryTab(
                                icon = category.name,
                                pluginIcon = null,
                                isSelected = index == selectedSubCategoryIndex,
                                onClick = { selectedSubCategoryIndex = index },
                                backgroundColor = backgroundColor,
                                textColor = textColor,
                                selectedBackgroundColor = accentColor,
                                modifier = Modifier.widthIn(min = 36.dp)
                            )
                        } else {
                            EmojiCategoryTab(
                                icon = category.icon,
                                pluginIcon = category.pluginIcon,
                                isSelected = index == selectedSubCategoryIndex,
                                onClick = { selectedSubCategoryIndex = index },
                                backgroundColor = backgroundColor,
                                textColor = textColor,
                                selectedBackgroundColor = accentColor,
                                modifier = Modifier.width(36.dp)
                            )
                        }
                    }
                }
            } else {
                Spacer(modifier = Modifier.weight(1f))
            }

            KeyButton(
                text = "删除",
                onClick = { onEmojiSelect("delete") },
                backgroundColor = backgroundColor,
                textColor = textColor,
                modifier = Modifier.width(48.dp),
                fontSize = 12.sp
            )
        }

        // 底部留空（竖屏至少 40dp，与普通键盘一致）
        Spacer(modifier = Modifier.height(if (isLandscape) 15.dp else maxOf(bottomPaddingDp, 40).dp))
    }
}

@Composable
fun EmojiCategoryTab(
    icon: String,
    pluginIcon: PluginIcon? = null,
    isSelected: Boolean,
    onClick: () -> Unit,
    backgroundColor: Color,
    textColor: Color,
    selectedBackgroundColor: Color = textColor.copy(alpha = 0.15f),
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current

    Box(
        modifier = modifier
            .height(30.dp)
            .clip(RoundedCornerShape(15.dp))

            .background(
                if (isSelected) selectedBackgroundColor.copy(0.4f)
                else backgroundColor
            )
            .padding(horizontal = 5.dp)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        if (pluginIcon?.assetName != null) {
            AsyncImage(
                model = ImageRequest.Builder(context)
                    .data(pluginIcon.assetName)
                    .crossfade(true)
                    .build(),
                contentDescription = icon,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(4.dp),
                contentScale = ContentScale.Fit
            )
        } else {
            Text(
                text = pluginIcon?.text ?: icon,
                fontSize = 16.sp,
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
fun EmojiButton(
    emoji: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(4.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = emoji,
            fontSize = 22.sp,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
fun PluginEmojiButton(
    emojiItem: EmojiItem,
    onClick: () -> Unit,
    defaultHeightDp: Int = 40,
    backgroundColor: Color = Color.Unspecified,
    textColor: Color = Color.Unspecified,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val config = emojiItem.displayConfig
    val heightDp = config?.heightDp ?: defaultHeightDp
    val aspectRatio = config?.aspectRatio

    val isLightTheme =
        (backgroundColor.red + backgroundColor.green + backgroundColor.blue) / 3f > 0.5f
    val buttonBackgroundColor = if (isLightTheme) Color.White.copy(alpha = 0.8f)
    else Color.LightGray.copy(alpha = 0.15f)
    val contentColor = if (isLightTheme) Color.Black else textColor

    Box(
        modifier = modifier
            .height(heightDp.dp)
            .then(
                if (emojiItem.imageUrl != null && aspectRatio != null) Modifier.aspectRatio(
                    aspectRatio
                )
                else if (emojiItem.imageUrl != null) Modifier.aspectRatio(1f)
                else Modifier.fillMaxWidth()
            )
            .clip(RoundedCornerShape(4.dp))
            .background(buttonBackgroundColor)
            .clickable(onClick = onClick)
            .padding(horizontal = 4.dp, vertical = 2.dp),
        contentAlignment = Alignment.Center
    ) {
        if (emojiItem.imageUrl != null) {
            AsyncImage(
                model = ImageRequest.Builder(context)
                    .data(emojiItem.imageUrl)
                    .crossfade(true)
                    .build(),
                contentDescription = emojiItem.displayText,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(2.dp),
                contentScale = ContentScale.Fit
            )
        } else {
            Text(
                text = emojiItem.displayText,
                fontSize = 12.sp,
                color = contentColor,
                textAlign = TextAlign.Center,
                maxLines = 2,
                softWrap = true,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}