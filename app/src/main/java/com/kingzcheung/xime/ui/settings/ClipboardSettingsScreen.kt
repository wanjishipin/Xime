package com.kingzcheung.xime.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.twotone.ContentPasteGo
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.kingzcheung.xime.clipboard.ClipboardManager
import com.kingzcheung.xime.settings.SettingsPreferences

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ClipboardSettingsContent(
    onBack: () -> Unit
) {
    val context = LocalContext.current

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("剪贴板设置") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "返回"
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                SettingsSection(title = "容量", content = {
                    val currentMax = SettingsPreferences.getClipboardMaxItems(context)
                    var sliderValue by remember(currentMax) {
                        mutableStateOf(currentMax.toFloat())
                    }

                    Column(
                        modifier = Modifier.padding(16.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "最大条数",
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.Medium
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "${sliderValue.toInt()} 条",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        Slider(
                            value = sliderValue,
                            onValueChange = { sliderValue = it },
                            onValueChangeFinished = {
                                val intValue = sliderValue.toInt()
                                SettingsPreferences.setClipboardMaxItems(context, intValue)
                                ClipboardManager.getInstance(context).applyMaxItems(intValue)
                            },
                            valueRange = 50f..5000f,
                            steps = 494
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "超出上限时，最早的未置顶条目会被自动清除（置顶条目不受限制）",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                        )
                    }
                })
            }

            item {
                SettingsSection(title = "信息", content = {
                    val count = ClipboardManager.getInstance(context).clipboardItems.value.size
                    val pinnedCount = ClipboardManager.getInstance(context)
                        .clipboardItems.value.count { it.isPinned }

                    SettingsItem(
                        icon = Icons.TwoTone.ContentPasteGo,
                        title = "当前条目数",
                        subtitle = "共 $count 条（其中 $pinnedCount 条已置顶）",
                        onClick = {},
                        showArrow = false
                    )
                })
            }
        }
    }
}
