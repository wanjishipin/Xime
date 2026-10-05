package com.kingzcheung.xime.ui.settings

import android.text.format.Formatter
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.twotone.Archive
import androidx.compose.material.icons.twotone.CloudDownload
import androidx.compose.material.icons.twotone.DataObject
import androidx.compose.material.icons.twotone.Description
import androidx.compose.material.icons.twotone.Extension
import androidx.compose.material.icons.twotone.Folder
import androidx.compose.material.icons.twotone.Memory
import androidx.compose.material.icons.twotone.Schedule
import androidx.compose.material.icons.twotone.Storage
import androidx.compose.material.icons.twotone.Widgets
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.kingzcheung.xime.settings.StorageStats
import com.kingzcheung.xime.viewmodel.StorageSpaceViewModel

/**
 * 存储空间（设置 → 关于 → 存储空间）：分类统计数据目录占用并提供分类清除，
 * 低风险数据（缓存/日志/编译缓存/模型/剪贴板）就地清理，需谨慎操作的数据
 * （插件/方案词库）只统计并引导到对应管理页。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StorageSpaceScreen(
    onBack: () -> Unit,
    onNavigateToPlugins: () -> Unit = {},
) {
    val context = LocalContext.current
    val viewModel: StorageSpaceViewModel = viewModel()
    val categories by viewModel.categories.collectAsStateWithLifecycle()
    val cleaningId by viewModel.cleaningId.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()

    var pendingClear by remember { mutableStateOf<StorageStats.Category?>(null) }

    LaunchedEffect(message) {
        if (message != null) {
            android.widget.Toast.makeText(context, message, android.widget.Toast.LENGTH_SHORT).show()
            viewModel.consumeMessage()
        }
    }

    pendingClear?.let { target ->
        AlertDialog(
            onDismissRequest = { pendingClear = null },
            title = { Text("清理${target.title}") },
            text = { Text(clearConfirmText(target.id)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.clear(target.id, target.title)
                        pendingClear = null
                    }
                ) { Text("清理") }
            },
            dismissButton = {
                TextButton(onClick = { pendingClear = null }) { Text("取消") }
            }
        )
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            TopAppBar(
                title = { Text("存储空间") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "返回"
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface
                )
            )
        }
    ) { paddingValues ->
        val list = categories
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
                ) {
                    Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                        Text(
                            text = "数据目录占用",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        if (list == null) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "正在扫描…",
                                    fontSize = 13.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        } else {
                            Text(
                                text = Formatter.formatFileSize(context, StorageStats.totalSize(list)),
                                fontSize = 24.sp,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = "仅统计应用数据目录，不含应用本体",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
            if (list != null) {
                itemsIndexed(list) { index, category ->
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .then(
                                if (category.navigable) {
                                    Modifier.clickable { onNavigateToPlugins() }
                                } else {
                                    Modifier
                                }
                            ),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
                    ) {
                        Column {
                            if (index > 0) {
                                HorizontalDivider(
                                    modifier = Modifier.padding(start = 52.dp),
                                    thickness = 0.5.dp,
                                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                                )
                            }
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = categoryIcon(category.id),
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(24.dp)
                                )
                                Spacer(modifier = Modifier.width(12.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = category.title,
                                        fontSize = 16.sp,
                                        fontWeight = FontWeight.Medium
                                    )
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Text(
                                        text = category.description,
                                        fontSize = 12.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        lineHeight = 16.sp
                                    )
                                }
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = Formatter.formatFileSize(context, category.sizeBytes),
                                    fontSize = 14.sp,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                if (category.navigable) {
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Icon(
                                        imageVector = Icons.Default.ChevronRight,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                } else if (category.clearable) {
                                    TextButton(
                                        enabled = cleaningId == null,
                                        onClick = { pendingClear = category }
                                    ) {
                                        if (cleaningId == category.id) {
                                            CircularProgressIndicator(
                                                modifier = Modifier.size(14.dp),
                                                strokeWidth = 2.dp
                                            )
                                        } else {
                                            Text("清理")
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun categoryIcon(id: String): ImageVector = when (id) {
    StorageStats.ID_MODELS -> Icons.TwoTone.Memory
    StorageStats.ID_SCHEMA_CACHE -> Icons.TwoTone.CloudDownload
    StorageStats.ID_SCHEMAS -> Icons.TwoTone.Description
    StorageStats.ID_MARKET_PACKAGES -> Icons.TwoTone.Archive
    StorageStats.ID_PLUGINS -> Icons.TwoTone.Extension
    StorageStats.ID_CLIPBOARD -> Icons.TwoTone.Widgets
    StorageStats.ID_LOGS -> Icons.TwoTone.Schedule
    StorageStats.ID_CACHE -> Icons.TwoTone.Folder
    else -> Icons.TwoTone.DataObject
}

/** 剪贴板类目清理确认文案（清理剪贴板历史的唯一入口：关于 → 存储空间）。 */
internal const val CLIPBOARD_CLEAR_CONFIRM_TEXT =
    "将清空本地剪贴板历史记录与已复制的图片（保留快捷发送与置顶内容），且不可恢复。确定继续吗？"

private fun clearConfirmText(id: String): String = when (id) {
    StorageStats.ID_MODELS ->
        "将删除所有已下载的离线模型（联想 / 手写 / 语音识别），对应功能在重新下载模型前不可用。确定继续吗？"
    StorageStats.ID_SCHEMA_CACHE ->
        "将删除输入方案的编译缓存与编译日志，不会影响词库和自定义配置；下次启动输入法时会自动重新编译。确定继续吗？"
    StorageStats.ID_MARKET_PACKAGES ->
        "将删除方案市场中已下载的安装包（内置方案除外）。已安装的方案不受影响，但重新安装时需要重新下载。确定继续吗？"
    StorageStats.ID_CLIPBOARD -> CLIPBOARD_CLEAR_CONFIRM_TEXT
    StorageStats.ID_LOGS ->
        "将删除历史运行日志（保留当日日志）。确定继续吗？"
    StorageStats.ID_CACHE ->
        "将删除图片与临时文件缓存，清理后首次加载会稍慢。确定继续吗？"
    else -> "确定清理吗？"
}
