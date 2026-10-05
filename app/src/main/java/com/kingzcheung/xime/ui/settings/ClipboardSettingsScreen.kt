package com.kingzcheung.xime.ui.settings

import android.os.Environment
import android.text.format.Formatter
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.twotone.DataUsage
import androidx.compose.material.icons.twotone.Download
import androidx.compose.material.icons.twotone.Upload
import androidx.compose.material.icons.twotone.Image
import androidx.compose.material.icons.twotone.Sync
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.kingzcheung.xime.clipboard.ClipboardManager
import com.kingzcheung.xime.clipboard.ImageUsage
import com.kingzcheung.xime.plugin.ActivePluginSelection
import com.kingzcheung.xime.plugin.ExtensionManager
import com.kingzcheung.xime.settings.SettingsPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 剪贴板设置（设置 → 数据与同步 → 剪贴板）。
 *
 * 页面只保留"需要用户决定/只有此处能看到"的东西：图片采集开关、单张上限，
 * 以及调上限时作为参照的图片占用；**清理不在这里**——剪贴板历史（文字 + 图片 + 残留）
 * 统一在 关于 → 存储空间 → 「剪贴板历史」清理，避免两个入口做同一件事。
 * 其余限制（最长边 / 保留数量 / 总容量）是内置常量，不暴露也不罗列。
 * 「剪贴板同步」的**总开关就在本页**（就地可切），整行点击进入内页做「选服务 + 配置」——
 * 同步是剪贴板的一种流向，不另立设置分组；开关上提后无需为开关专门进一层页面。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ClipboardSettingsContent(
    onBack: () -> Unit,
    onNavigateToClipboardSync: () -> Unit,
) {
    val context = LocalContext.current
    val clipboardManager = remember { ClipboardManager.getInstance(context) }
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    // 从 JSON 文件导入剪贴板（导出见下方「导入与导出」分区）
    val importFileLauncher = rememberLauncherForActivityResult(
        contract = androidx.activity.result.contract.ActivityResultContracts.OpenDocument()
    ) { uri: android.net.Uri? ->
        if (uri != null) {
            scope.launch {
                try {
                    val result = withContext(Dispatchers.IO) {
                        val json = context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                            ?: return@withContext -1
                        clipboardManager.importFromJson(json)
                    }
                    if (result > 0) {
                        snackbarHostState.showSnackbar("已导入 $result 条剪贴板")
                    } else {
                        snackbarHostState.showSnackbar("导入失败或文件为空")
                    }
                } catch (e: Exception) {
                    snackbarHostState.showSnackbar("导入失败: ${e.message}")
                }
            }
        }
    }

    var captureEnabled by remember {
        mutableStateOf(SettingsPreferences.isClipboardImageCaptureEnabled(context))
    }
    var maxMb by remember {
        mutableFloatStateOf(SettingsPreferences.getClipboardImageMaxMb(context).toFloat())
    }
    val syncEnabled = remember { SettingsPreferences.isClipboardSyncEnabled(context) }
    // 实际生效的同步服务（与引擎同一判定规则 ActivePluginSelection）：副标题要说明"谁在跑 + 能不能同步图片"
    val activeSyncPlugin = remember {
        val activeId = ActivePluginSelection.resolve(
            SettingsPreferences.getClipboardSyncPluginId(context),
            ExtensionManager.getEnabledClipboardSyncPlugins(context).map { it.first }
        )
        ExtensionManager.getAllInstalledPlugins().firstOrNull { it.id == activeId }
    }
    var usage by remember { mutableStateOf<ImageUsage?>(null) }
    var syncOn by remember { mutableStateOf(syncEnabled) }

    // 占用统计读的是数据库 + 图片目录，放到 IO 线程
    LaunchedEffect(Unit) {
        usage = withContext(Dispatchers.IO) { clipboardManager.imageUsage() }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text("剪贴板") },
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
                ),
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
                .imePadding()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            SettingsSection(
                title = "图片记录",
                content = {
                    SettingsToggleItem(
                        icon = Icons.TwoTone.Image,
                        title = "收集复制的图片",
                        subtitle = "复制图片时保存到剪贴板历史，可在候选栏与剪贴板面板中选用",
                        checked = captureEnabled,
                        onCheckedChange = { checked ->
                            captureEnabled = checked
                            SettingsPreferences.setClipboardImageCaptureEnabled(context, checked)
                        }
                    )
                    if (!captureEnabled) {
                        Text(
                            text = "已关闭：只收集文字，复制图片不会再进入剪贴板历史。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp)
                        )
                    }
                }
            )

            SettingsSection(
                title = "图片上限",
                content = {
                    Column(
                        modifier = Modifier.padding(
                            start = 16.dp,
                            end = 16.dp,
                            top = 16.dp,
                            bottom = 12.dp
                        )
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = "单张最大体积",
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.Medium
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "${maxMb.toInt()} MB",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Slider(
                            value = maxMb,
                            onValueChange = { maxMb = it },
                            onValueChangeFinished = {
                                SettingsPreferences.setClipboardImageMaxMb(context, maxMb.toInt())
                            },
                            valueRange = SettingsPreferences.CLIPBOARD_IMAGE_MAX_MB_RANGE.first.toFloat()..
                                SettingsPreferences.CLIPBOARD_IMAGE_MAX_MB_RANGE.last.toFloat(),
                            steps = 18
                        )
                        Text(
                            text = "超过上限的图片不会被保存（不压缩、不改画质）",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    HorizontalDivider(
                        modifier = Modifier.padding(start = 56.dp),
                        thickness = 0.5.dp,
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.TwoTone.DataUsage,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = "图片占用",
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            text = usage?.let {
                                "${it.count} 张 · ${Formatter.formatFileSize(context, it.fileBytes)}"
                            } ?: "统计中…",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            )

            SettingsSection(
                title = "同步",
                content = {
                    // 开关就地上提到本页（原先藏在子页里），整行点击进子页做"选服务 + 配置"
                    val activePlugin = activeSyncPlugin
                    val subtitle = when {
                        !syncOn -> "通过插件与远端设备双向同步剪贴板 · 点按进入服务与配置"
                        activePlugin == null -> "已启用 · 未选择同步服务 · 点按进入服务与配置"
                        else -> {
                            // 能力按实际生效插件说明，避免出现"文案说仅文字、其实能同步图片"
                            val supportsImages =
                                activePlugin.capabilities?.clipboardSync?.attachments == true
                            "已启用 · ${activePlugin.name}（${
                                if (supportsImages) "文字与图片" else "仅文字"
                            }） · 点按进入服务与配置"
                        }
                    }
                    SettingsToggleItem(
                        icon = Icons.TwoTone.Sync,
                        title = "剪贴板同步",
                        subtitle = subtitle,
                        checked = syncOn,
                        onCheckedChange = { checked ->
                            syncOn = checked
                            SettingsPreferences.setClipboardSyncEnabled(context, checked)
                        },
                        onClick = onNavigateToClipboardSync,
                        // 右箭头表明"这一行还有下级页面"（与「语音转文本」行同一惯例）
                        showArrow = true
                    )
                }
            )

            SettingsSection(
                title = "导入与导出",
                content = {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp)
                    ) {
                        Text(
                            text = "导出剪贴板数据到文件，或从文件导入恢复",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            OutlinedButton(
                                onClick = {
                                    scope.launch {
                                        try {
                                            val result = withContext(Dispatchers.IO) {
                                                val json = clipboardManager.exportToJson()
                                                val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                                                if (!downloadsDir.exists()) downloadsDir.mkdirs()
                                                val file = java.io.File(downloadsDir, "xime_clipboard_${System.currentTimeMillis()}.json")
                                                file.writeText(json)
                                                file.absolutePath
                                            }
                                            snackbarHostState.showSnackbar("已导出到: $result")
                                        } catch (e: Exception) {
                                            snackbarHostState.showSnackbar("导出失败: ${e.message}")
                                        }
                                    }
                                },
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(
                                    imageVector = Icons.TwoTone.Download,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("导出")
                            }
                            OutlinedButton(
                                onClick = {
                                    importFileLauncher.launch(arrayOf("application/json", "text/plain", "*/*"))
                                },
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(
                                    imageVector = Icons.TwoTone.Upload,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("导入")
                            }
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "导出文件保存在下载目录，导入时选择 JSON 文件",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                        )
                    }
                }
            )
        }
    }
}