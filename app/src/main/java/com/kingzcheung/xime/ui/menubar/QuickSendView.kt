package com.kingzcheung.xime.ui.menubar

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kingzcheung.xime.clipboard.ClipboardItem
import com.kingzcheung.xime.viewmodel.KeyboardViewModel

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun QuickSendTabContent(
    items: List<ClipboardItem>,
    itemBgColor: Color,
    textColor: Color,
    subTextColor: Color,
    accentColor: Color,
    viewModel: KeyboardViewModel,
    onSelect: (String) -> Unit,
    onQuickSendAddClick: (() -> Unit)? = null,
    onQuickSendEditItem: ((Long, String, String) -> Unit)? = null,
    onLongPressItem: (ClipboardItem, Boolean) -> Unit,
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
        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 10.dp, vertical = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            itemsIndexed(items, key = { _, it -> it.id }) { index, item ->
                GridItemCard(
                    text = item.text,
                    highlighted = false,
                    bgColor = itemBgColor,
                    textColor = textColor,
                    accentColor = accentColor,
                    modifier = Modifier.height(62.dp),
                    onClick = { onSelect(item.text) },
                    onLongClick = { onLongPressItem(item, index % 2 == 0) }
                )
            }
        }
    }
}
