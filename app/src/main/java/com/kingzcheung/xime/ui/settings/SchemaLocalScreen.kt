package com.kingzcheung.xime.ui.settings

import android.widget.Toast
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.kingzcheung.xime.viewmodel.LocalPackageItem
import com.kingzcheung.xime.viewmodel.SchemaLocalViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SchemaLocalContent(
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val viewModel: SchemaLocalViewModel = viewModel()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(uiState.toastMessage) {
        uiState.toastMessage?.let { message ->
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
            viewModel.clearToast()
        }
    }

    if (uiState.conflictPackageId != null) {
        AlertDialog(
            onDismissRequest = { viewModel.cancelConflictInstall() },
            title = { Text("文件冲突") },
            text = {
                Text("需要先卸载冲突方案：${uiState.conflictingSchemeIds.joinToString("、")}，是否继续？")
            },
            confirmButton = {
                TextButton(onClick = { viewModel.confirmInstallWithUninstall() }) {
                    Text("确认卸载并安装")
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.cancelConflictInstall() }) {
                    Text("取消")
                }
            },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("本地方案") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.loadLocalPackages() }) {
                        Icon(Icons.Default.Refresh, contentDescription = "刷新")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface,
                ),
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            if (uiState.packages.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            if (uiState.isLoading) "加载中…" else "暂无本地方案",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    item {
                        Spacer(Modifier.height(4.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            val installedCount = uiState.packages.count { it.installed }
                            val downloadedCount = uiState.packages.count { it.downloaded }
                            Text(
                                "共 ${uiState.packages.size} 个（已安装 $installedCount，可安装 ${downloadedCount - installedCount}）",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.outline,
                            )
                        }
                        Spacer(Modifier.height(8.dp))
                    }
                    items(uiState.packages, key = { it.packageId }) { item ->
                        LocalPackageCard(
                            item = item,
                            installing = uiState.installingId == item.packageId,
                            onInstall = { viewModel.installPackage(item) },
                            onDelete = { viewModel.deleteDownloaded(item) },
                            onUninstall = { viewModel.uninstall(item) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun LocalPackageCard(
    item: LocalPackageItem,
    installing: Boolean,
    onInstall: () -> Unit,
    onDelete: () -> Unit,
    onUninstall: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    item.displayName,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Spacer(Modifier.width(8.dp))
                val tagColor = when {
                    item.installed -> MaterialTheme.colorScheme.primary
                    item.downloaded -> MaterialTheme.colorScheme.outline
                    else -> MaterialTheme.colorScheme.error
                }
                TagText(
                    if (item.installed) "已安装" else if (item.downloaded) "已下载" else "未知",
                    tagColor,
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(
                "ID: ${item.packageId}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
            )
            if (item.version.isNotEmpty() || item.fileCount > 0) {
                Spacer(Modifier.height(2.dp))
                Text(
                    buildString {
                        if (item.version.isNotEmpty()) append("版本: ${item.version}")
                        if (item.fileCount > 0) {
                            if (isNotEmpty()) append(" · ")
                            append("${item.fileCount} 个文件")
                        }
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
            Spacer(Modifier.height(10.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                when {
                    installing -> {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(6.dp))
                            Text("安装中…", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                    item.installed -> {
                        OutlinedButton(onClick = onUninstall) { Text("卸载") }
                    }
                    item.downloaded -> OutlinedButton(onClick = onInstall) { Text("安装") }
                }
                Spacer(Modifier.weight(1f))
                if (item.packageId != "builtin" && item.downloaded) {
                    IconButton(
                        onClick = onDelete,
                        modifier = Modifier.size(36.dp),
                    ) {
                        Icon(
                            Icons.Default.Delete,
                            contentDescription = "删除下载文件",
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TagText(text: String, color: androidx.compose.ui.graphics.Color) {
    Surface(
        shape = RoundedCornerShape(6.dp),
        color = color.copy(alpha = 0.12f),
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelSmall,
            color = color,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
        )
    }
}
