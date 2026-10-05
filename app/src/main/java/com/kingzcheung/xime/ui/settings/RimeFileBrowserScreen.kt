package com.kingzcheung.xime.ui.settings

import android.graphics.Typeface
import android.widget.Toast
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.IosShare
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.kingzcheung.xime.settings.ExportMode
import com.kingzcheung.xime.settings.ExportResult
import com.kingzcheung.xime.settings.RimeExportManager
import com.kingzcheung.xime.settings.SchemaManager
import io.github.rosemoe.sora.event.ContentChangeEvent
import io.github.rosemoe.sora.langs.textmate.TextMateColorScheme
import io.github.rosemoe.sora.langs.textmate.TextMateLanguage
import io.github.rosemoe.sora.langs.textmate.registry.FileProviderRegistry
import io.github.rosemoe.sora.langs.textmate.registry.GrammarRegistry
import io.github.rosemoe.sora.langs.textmate.registry.ThemeRegistry
import io.github.rosemoe.sora.langs.textmate.registry.model.ThemeModel
import io.github.rosemoe.sora.langs.textmate.registry.provider.AssetsFileResolver
import io.github.rosemoe.sora.widget.CodeEditor
import io.github.rosemoe.sora.widget.component.Magnifier
import org.eclipse.tm4e.core.registry.IThemeSource
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class FileEntry(
    val file: File,
    val isDirectory: Boolean,
    val name: String,
    val size: Long,
    val lastModified: Long
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RimeFileBrowserContent(onBack: () -> Unit) {
    val context = LocalContext.current
    val rootDir = remember { SchemaManager.getRimeDir(context) }
    var currentDir by remember { mutableStateOf(rootDir) }
    var entries by remember { mutableStateOf(listOf<FileEntry>()) }
    var viewingFile by remember { mutableStateOf<File?>(null) }
    var showDeleteDialog by remember { mutableStateOf<File?>(null) }
    var showExportDialog by remember { mutableStateOf(false) }
    var exportMode by remember { mutableStateOf(ExportMode.CONFIG_ONLY) }
    var exportInProgress by remember { mutableStateOf(false) }
    var exportResult by remember { mutableStateOf<ExportResult?>(null) }
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    fun refresh() {
        entries = currentDir.listFiles()
            ?.map { FileEntry(it, it.isDirectory, it.name, it.length(), it.lastModified()) }
            ?.sortedWith(compareByDescending<FileEntry> { it.isDirectory }.thenBy { it.name })
            ?: emptyList()
    }

    fun deleteFile(file: File) {
        if (file.isDirectory) {
            file.deleteRecursively()
        } else {
            file.delete()
        }
        showDeleteDialog = null
        refresh()
        Toast.makeText(context, "已删除: ${file.name}", Toast.LENGTH_SHORT).show()
    }

    LaunchedEffect(currentDir) { refresh() }

    if (viewingFile != null) {
        YamlEditor(
            file = viewingFile!!,
            onDeleted = { viewingFile = null },
            onBack = { viewingFile = null }
        )
        return
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("文件管理器", maxLines = 1, overflow = TextOverflow.Ellipsis)
                        val rel = if (currentDir == rootDir) "" else
                            currentDir.absolutePath.removePrefix(rootDir.absolutePath)
                        if (rel.isNotEmpty()) {
                            Text(rel, style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                },
                navigationIcon = {
                    if (currentDir == rootDir) {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                        }
                    } else {
                        IconButton(onClick = { currentDir = currentDir.parentFile ?: rootDir }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "上级目录")
                        }
                    }
                },
                actions = {
                    IconButton(onClick = { currentDir = rootDir }) {
                        Icon(Icons.Default.Home, contentDescription = "根目录")
                    }
                    IconButton(onClick = { refresh() }) {
                        Icon(Icons.Default.Refresh, contentDescription = "刷新")
                    }
                    IconButton(onClick = { showExportDialog = true }) {
                        Icon(Icons.Default.IosShare, contentDescription = "导出")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface
                )
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { innerPadding ->
        if (entries.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize().padding(innerPadding),
                contentAlignment = Alignment.Center) {
                Text("空目录", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(innerPadding)
            ) {
                items(entries, key = { currentDir.absolutePath + "/" + it.name }) { entry ->
                    FileEntryRow(
                        entry = entry,
                        onView = {
                            if (entry.isDirectory) {
                                currentDir = entry.file
                            } else if (entry.name.endsWith(".bin") || entry.name.endsWith(".gram")) {
                                Toast.makeText(context, "不支持打开二进制文件", Toast.LENGTH_SHORT).show()
                            } else {
                                viewingFile = entry.file
                            }
                        },
                        onDelete = {
                            showDeleteDialog = entry.file
                        },
                        onShare = {
                            RimeExportManager.shareSingleFile(context, entry.file)
                        }
                    )
                    HorizontalDivider(
                        modifier = Modifier.padding(start = 72.dp),
                        color = MaterialTheme.colorScheme.outlineVariant
                    )
                }
            }
        }
    }

    showDeleteDialog?.let { file ->
        val isDir = file.isDirectory
        AlertDialog(
            onDismissRequest = { showDeleteDialog = null },
            title = { Text(if (isDir) "删除目录" else "删除文件") },
            text = { Text("确定删除「${file.name}」？${if (isDir) "\n目录内的所有内容将被删除。" else ""}") },
            confirmButton = {
                TextButton(onClick = { deleteFile(file) }) {
                    Text("删除", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = null }) {
                    Text("取消")
                }
            }
        )
    }

    if (showExportDialog) {
        AlertDialog(
            onDismissRequest = { showExportDialog = false },
            title = { Text("导出 Rime 配置") },
            text = {
                Column {
                    Text("选择导出范围：")
                    Spacer(Modifier.height(12.dp))
                    ExportMode.entries.forEach { mode ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { exportMode = mode }
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = exportMode == mode,
                                onClick = { exportMode = mode }
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(mode.label, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        if (exportMode == ExportMode.CONFIG_ONLY)
                            "排除 build/ 目录和 .bin/.gram 等编译产物"
                        else
                            "包含 Rime 目录下所有文件和子目录",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showExportDialog = false
                        exportInProgress = true
                        scope.launch {
                            val result = withContext(Dispatchers.IO) {
                                RimeExportManager.exportArchive(context, exportMode)
                            }
                            exportInProgress = false
                            result.fold(
                                onSuccess = { exportResult ->
                                    val msg = if (exportResult.savedToDownloads)
                                        "已保存到 Downloads/${exportResult.fileName}"
                                    else
                                        "导出完成"
                                    snackbarHostState.showSnackbar(msg)
                                },
                                onFailure = { error ->
                                    snackbarHostState.showSnackbar("导出失败: ${error.message}")
                                }
                            )
                        }
                    },
                    enabled = !exportInProgress
                ) {
                    Text(if (exportInProgress) "导出中..." else "开始导出")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showExportDialog = false },
                    enabled = !exportInProgress
                ) {
                    Text("取消")
                }
            }
        )
    }

    if (exportInProgress) {
        AlertDialog(
            onDismissRequest = {},
            title = { Text("导出中") },
            text = { Text("正在打包 Rime 配置…") },
            confirmButton = {}
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun YamlEditor(file: File, onDeleted: () -> Unit, onBack: () -> Unit) {
    val context = LocalContext.current
    var isEditing by remember { mutableStateOf(false) }
    var isModified by remember { mutableStateOf(false) }
    var editor by remember { mutableStateOf<CodeEditor?>(null) }
    var textLength by remember { mutableIntStateOf(0) }
    var showDeleteConfirm by remember { mutableStateOf(false) }

    val initialContent = remember(file) {
        try {
            val text = file.readText(Charsets.UTF_8)
            if (text.startsWith('\uFEFF')) text.substring(1) else text
        } catch (_: Exception) {
            ""
        }
    }

    fun save() {
        val content = editor?.text?.toString() ?: return
        try {
            file.writeText(content, Charsets.UTF_8)
            isModified = false
            Toast.makeText(context, "已保存", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(context, "保存失败: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("删除文件") },
            text = { Text("确定删除「${file.name}」？此操作不可撤销。") },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteConfirm = false
                    file.delete()
                    Toast.makeText(context, "已删除: ${file.name}", Toast.LENGTH_SHORT).show()
                    onDeleted()
                }) {
                    Text("删除", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) {
                    Text("取消")
                }
            }
        )
    }

    val textmateReady = remember {
        try {
            val appCtx = context.applicationContext
            FileProviderRegistry.getInstance().addFileProvider(AssetsFileResolver(appCtx.assets))
            val themeReg = ThemeRegistry.getInstance()
            themeReg.loadTheme(
                ThemeModel(
                    IThemeSource.fromInputStream(
                        FileProviderRegistry.getInstance().tryGetInputStream("textmate/themes/light.json"),
                        "light.json", null
                    ), "Light"
                )
            )
            themeReg.setTheme("Light")
            GrammarRegistry.getInstance().loadGrammars("textmate/languages.json")
            true
        } catch (_: Exception) {
            false
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(file.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        val sizeStr = formatSize(file.length())
                        val dateStr = dateFormat.format(Date(file.lastModified()))
                        val flag = if (isModified) " · 未保存" else ""
                        Text("$sizeStr · $dateStr$flag",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (isModified) MaterialTheme.colorScheme.error
                                    else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    if (isEditing) {
                        IconButton(onClick = { save() }) {
                            Icon(Icons.Default.Check, contentDescription = "保存",
                                tint = MaterialTheme.colorScheme.primary)
                        }
                    }
                    IconButton(onClick = {
                        isEditing = !isEditing
                        editor?.editable = isEditing
                    }) {
                        Icon(
                            if (isEditing) Icons.Default.Visibility else Icons.Default.Edit,
                            contentDescription = if (isEditing) "预览" else "编辑",
                            tint = if (isEditing) MaterialTheme.colorScheme.primary
                                   else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    IconButton(onClick = { showDeleteConfirm = true }) {
                        Icon(
                            Icons.Default.Delete,
                            contentDescription = "删除",
                            tint = MaterialTheme.colorScheme.error
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface
                )
            )
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .background(MaterialTheme.colorScheme.surfaceContainerLowest)
        ) {
            AndroidView(
                factory = { ctx ->
                    CodeEditor(ctx).apply {
                        editable = isEditing
                        typefaceText = Typeface.MONOSPACE
                        setText(initialContent)
                        setTextSize(12f)
                        tabWidth = 4
                        isLineNumberEnabled = true
                        isWordwrap = false
                        isHighlightCurrentLine = false
                        nonPrintablePaintingFlags = 0
                        getComponent(Magnifier::class.java).setEnabled(false)

                        if (textmateReady) {
                            colorScheme = TextMateColorScheme.create(ThemeRegistry.getInstance())
                            setEditorLanguage(TextMateLanguage.create("source.yaml", false))
                        }

                        subscribeEvent(ContentChangeEvent::class.java) { _, _ ->
                            textLength = text.toString().length
                            isModified = text.toString() != initialContent
                        }

                        editor = this
                    }
                },
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}

private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FileEntryRow(entry: FileEntry, onView: () -> Unit, onDelete: () -> Unit, onShare: () -> Unit = {}) {
    ListItem(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onView),
        colors = ListItemDefaults.colors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        leadingContent = {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(MaterialTheme.shapes.small)
                    .background(
                        if (entry.isDirectory) MaterialTheme.colorScheme.primaryContainer
                        else MaterialTheme.colorScheme.secondaryContainer
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = if (entry.isDirectory) Icons.Default.Folder else Icons.Default.Description,
                    contentDescription = null,
                    tint = if (entry.isDirectory) MaterialTheme.colorScheme.onPrimaryContainer
                           else MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.size(22.dp)
                )
            }
        },
        headlineContent = {
            Text(
                text = entry.name,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        },
        supportingContent = {
            Row {
                if (!entry.isDirectory && entry.size > 0) {
                    Text(
                        text = formatSize(entry.size),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(" · ", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text(
                    text = dateFormat.format(Date(entry.lastModified)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        trailingContent = {
            if (!entry.isDirectory) {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    IconButton(onClick = onShare) {
                        Icon(
                            Icons.Default.Share,
                            contentDescription = "分享",
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                    IconButton(onClick = onDelete) {
                        Icon(
                            Icons.Default.Delete,
                            contentDescription = "删除",
                            tint = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }
        }
    )
}

private fun formatSize(bytes: Long): String {
    return when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "${bytes / 1024} KB"
        else -> "%.1f MB".format(bytes.toDouble() / (1024 * 1024))
    }
}
