package com.kingzcheung.xime.ui.settings

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusEvent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.kingzcheung.xime.settings.MarketSchemeItem
import com.kingzcheung.xime.settings.SchemeVersion
import com.kingzcheung.xime.viewmodel.SchemaMarketViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SchemaMarketContent(
    onBack: () -> Unit,
    onNavigateToDetail: (String) -> Unit = {},
) {
    val context = LocalContext.current
    val viewModel: SchemaMarketViewModel = viewModel()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(uiState.toastMessage) {
        uiState.toastMessage?.let { message ->
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
            viewModel.clearToast()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("方案市场") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(
                        onClick = { viewModel.loadSchemes(manual = true) },
                        enabled = !uiState.isLoading,
                    ) {
                        if (uiState.isLoading) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        } else {
                            Icon(Icons.Default.Refresh, contentDescription = "刷新")
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = MaterialTheme.colorScheme.onBackground,
                ),
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            var searchFocused by remember { mutableStateOf(false) }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
                    .onFocusEvent { searchFocused = it.isFocused }
                    .clip(RoundedCornerShape(28.dp))
                    .background(
                        if (searchFocused) MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                        else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.04f)
                    )
                    .padding(horizontal = 16.dp, vertical = 12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Default.Search,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Box(modifier = Modifier.weight(1f)) {
                        BasicTextField(
                            value = uiState.searchQuery,
                            onValueChange = viewModel::setSearchQuery,
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            textStyle = MaterialTheme.typography.bodyMedium.copy(
                                color = MaterialTheme.colorScheme.onSurface
                            ),
                            decorationBox = { innerTextField ->
                                Box {
                                    if (uiState.searchQuery.isEmpty() && !searchFocused) {
                                        Text(
                                            "搜索方案 / 标签",
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                                        )
                                    }
                                    innerTextField()
                                }
                            }
                        )
                    }
                }
            }

            when {
                uiState.isLoading && uiState.schemes.isEmpty() -> CenterBox {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator()
                        Spacer(Modifier.height(12.dp))
                        Text("正在加载方案市场…", style = MaterialTheme.typography.bodyMedium)
                    }
                }

                uiState.errorMessage != null && uiState.schemes.isEmpty() -> CenterBox {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            uiState.errorMessage ?: "加载失败",
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Spacer(Modifier.height(12.dp))
                        Button(onClick = { viewModel.loadSchemes() }) { Text("重试") }
                    }
                }

                else -> LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(uiState.filteredSchemes, key = { it.scheme.id }) { item ->
                        SchemeCard(
                            item = item,
                            downloading = uiState.downloadingId == item.scheme.id,
                            downloadProgress = uiState.downloadProgress,
                            extracting = uiState.extractingId == item.scheme.id,
                            downloaded = item.scheme.id in uiState.downloadedIds,
                            installed = item.scheme.id in uiState.installedIds,
                            sha256Status = uiState.sha256Status[item.scheme.id],
                            deploying = uiState.isDeploying,
                            selectedVersion = uiState.selectedVersions[item.scheme.id]
                                ?: item.scheme.currentVersion,
                            onSelectVersion = { viewModel.selectVersion(item.scheme.id, it) },
                            onDownload = { viewModel.downloadScheme(item) },
                            onInstall = { viewModel.installFromMarket(item) },
                            onDeploy = { viewModel.deploy() },
                            onDelete = { viewModel.deleteDownloadedScheme(item.scheme.id) },
                        )
                    }
                    item {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "共 ${uiState.filteredSchemes.size} 个方案" +
                                if (uiState.source.isNotBlank()) "（来源：${uiState.source}）" else "（来自 Xime 官方源）",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.outline,
                            modifier = Modifier.padding(bottom = 16.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CenterBox(content: @Composable () -> Unit) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { content() }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SchemeCard(
    item: MarketSchemeItem,
    downloading: Boolean,
    downloadProgress: Float,
    extracting: Boolean,
    downloaded: Boolean,
    installed: Boolean,
    sha256Status: Boolean?,
    deploying: Boolean,
    selectedVersion: String,
    onSelectVersion: (String) -> Unit,
    onDownload: () -> Unit,
    onInstall: () -> Unit,
    onDeploy: () -> Unit,
    onDelete: () -> Unit = {},
) {
    val scheme = item.scheme
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            // 标题行：名称 + 版本号（无v前缀）
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    scheme.name.ifEmpty { scheme.id },
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (scheme.versions.size > 1) {
                    VersionSelector(
                        versions = scheme.versions,
                        selectedVersion = selectedVersion,
                        onSelectVersion = onSelectVersion,
                    )
                } else if (selectedVersion.isNotEmpty()) {
                    Spacer(Modifier.width(8.dp))
                    Text(
                        selectedVersion,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                }
                if (scheme.type == "built-in") {
                    Spacer(Modifier.width(6.dp))
                    Tag("内置")
                }
            }
            if (scheme.description.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    scheme.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            // 作者
            if (scheme.author.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                Text(
                    "作者：${scheme.author}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
            // 标签（逗号分隔）
            if (scheme.tags.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    scheme.tags.joinToString("、"),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
            // 依赖
            if (scheme.dependencies.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    scheme.dependencies.take(4).forEach { dep ->
                        Tag(dep)
                    }
                }
            }
            // APP 版本要求
            if (scheme.appVersion.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                Text(
                    "APP 版本要求 ：${scheme.appVersion}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
            // 许可证
            if (scheme.license.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    scheme.license,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
            // 警告
            if (scheme.warning.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.6f),
                ) {
                    Text(
                        scheme.warning,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    )
                }
            }
            if (downloaded) {
                Spacer(Modifier.height(6.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    when (sha256Status) {
                        true -> VerificationTag("已校验", MaterialTheme.colorScheme.primary)
                        false -> VerificationTag("校验失败", MaterialTheme.colorScheme.error)
                        null -> VerificationTag("未验证", MaterialTheme.colorScheme.outline)
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
            val versionForSize = scheme.versions.firstOrNull { it.version == selectedVersion }
            val totalBytes = versionForSize?.downloadUrls?.sumOf { dl ->
                dl.size?.removeSuffix(" MB")?.trim()?.toDoubleOrNull()
                    ?.let { (it * 1024.0 * 1024.0).toLong() } ?: 0L
            } ?: 0L
            val sizeLabel = if (totalBytes > 0) {
                "%.1f MB".format(totalBytes / (1024.0 * 1024.0))
            } else null
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (sizeLabel != null) {
                    Text(
                        "大小: $sizeLabel",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                }
                Spacer(Modifier.weight(1f))
                when {
                    downloading -> {
                        OutlinedButton(onClick = onDownload, enabled = false) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                                Spacer(Modifier.width(6.dp))
                                Text("下载中 ${(downloadProgress * 100).toInt()}%")
                            }
                        }
                    }

                    extracting -> {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(8.dp))
                            Text("安装中…", style = MaterialTheme.typography.labelMedium)
                        }
                    }

                    !item.compatible -> OutlinedButton(onClick = {}, enabled = false) {
                        Text("需 App ≥ ${item.minAppVersion}")
                    }

                    downloaded && !installed -> OutlinedButton(onClick = onInstall) {
                        Text("安装")
                    }

                    installed -> {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            OutlinedButton(onClick = onDownload) { Text("重新下载") }
                            Spacer(Modifier.width(4.dp))
                            IconButton(
                                onClick = onDelete,
                                modifier = Modifier.size(36.dp),
                            ) {
                                Icon(
                                    Icons.Default.Delete,
                                    contentDescription = "删除压缩包",
                                    tint = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.size(20.dp),
                                )
                            }
                            Spacer(Modifier.weight(1f))
                            Spacer(Modifier.width(8.dp))
                            if (deploying) {
                                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                                Spacer(Modifier.width(8.dp))
                                Text("部署中…", style = MaterialTheme.typography.labelMedium)
                            } else {
                                OutlinedButton(onClick = onDeploy) { Text("部署") }
                            }
                        }
                    }

                    else -> OutlinedButton(onClick = onDownload) {
                        Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("下载")
                    }
                }
            }
        }
    }
}

@Composable
private fun VersionSelector(
    versions: List<SchemeVersion>,
    selectedVersion: String,
    onSelectVersion: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        TextButton(
            onClick = { expanded = true },
            contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp),
            modifier = Modifier.height(IntrinsicSize.Min),
        ) {
            Text(
                selectedVersion,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
            )
            Icon(
                Icons.Default.ArrowDropDown,
                contentDescription = "选择版本",
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            versions.forEach { v ->
                val label = buildString {
                    append(v.version)
                    if (v.date.isNotBlank()) append("  ·  ${v.date}")
                    if (v.size.isNotBlank()) append("  ·  ${v.size}")
                }
                DropdownMenuItem(
                    text = { Text(label, style = MaterialTheme.typography.bodySmall) },
                    onClick = {
                        onSelectVersion(v.version)
                        expanded = false
                    },
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                )
            }
        }
    }
}

/** 方案详情占位页（待实现）。 */
@Composable
fun SchemaMarketDetailContent(
    schemeId: String,
    onBack: () -> Unit,
) {
    SchemaMarketContent(onBack = onBack)
}

@Composable
private fun Tag(text: String) {
    Surface(
        shape = RoundedCornerShape(6.dp),
        color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f),
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
        )
    }
}

@Composable
private fun VerificationTag(text: String, color: androidx.compose.ui.graphics.Color) {
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
