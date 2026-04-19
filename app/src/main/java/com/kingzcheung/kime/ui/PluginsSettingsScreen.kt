package com.kingzcheung.kime.ui

import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AddBox
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kingzcheung.kime.plugin.core.model.PluginInfo
import com.kingzcheung.kime.plugin.core.runtime.PluginManager
import com.kingzcheung.kime.plugin.core.security.PluginErrorLog
import com.kingzcheung.kime.settings.SettingsPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PluginsSettingsContent(
    onBack: () -> Unit,
    onNavigateToPluginSettings: (String) -> Unit = {}
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    
    var extensions by remember { mutableStateOf<List<PluginInfo>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    
    // 收集已加载插件的状态（运行中）
    val loadedPlugins by PluginManager.loadedPluginsFlow.collectAsState()
    
    fun refreshPlugins() {
        isLoading = true
        errorMsg = null
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val scanned = PluginManager.scanAndInstallSystemPlugins()
                    Log.d("PluginsSettings", "Scanned $scanned new plugins")
                    val loaded = PluginManager.loadEnabledPlugins()
                    Log.d("PluginsSettings", "Loaded $loaded plugins")
                }
                extensions = PluginManager.getAllInstallPlugins()
                Log.d("PluginsSettings", "Loaded ${extensions.size} plugins: ${extensions.map { it.id }}")
            } catch (e: Exception) {
                e.printStackTrace()
                errorMsg = e.message
            } finally {
                isLoading = false
            }
        }
    }
    
    LaunchedEffect(Unit) {
        refreshPlugins()
    }
    
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("插件管理") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "返回"
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { refreshPlugins() }) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = "刷新"
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = MaterialTheme.colorScheme.onBackground
                ),
                windowInsets = WindowInsets(0.dp)
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(MaterialTheme.colorScheme.background),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
        ) {
            if (isLoading) {
                item {
                    Box(
                        modifier = Modifier.fillMaxWidth(),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator()
                    }
                }
            } else if (errorMsg != null) {
                item {
                    Text(
                        text = "加载失败: $errorMsg",
                        color = MaterialTheme.colorScheme.error
                    )
                }
            } else {
                if (extensions.isEmpty()) {
                    item {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(32.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.AddBox,
                                    contentDescription = null,
                                    modifier = Modifier.size(48.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                                )
                                Text(
                                    text = "暂无已安装的插件",
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    text = "安装插件后将在此显示",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                                )
                            }
                        }
                    }
                } else {
                    item {
                        Text(
                            text = "已安装插件 (${extensions.size})",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(bottom = 8.dp)
                        )
                    }
                    
                    items(extensions, key = { it.id }) { extension ->
                        val isRunning = loadedPlugins.containsKey(extension.id)
                        ExtensionItem(
                            extension = extension,
                            pluginInstance = PluginManager.getPluginInstance(extension.id),
                            isRunning = isRunning,
                            onClick = { onNavigateToPluginSettings(extension.id) }
                        )
                    }
                }
                
                item {
                    Spacer(modifier = Modifier.height(8.dp))
                    
                    Text(
                        text = "提示: 插件以独立 APK 形式安装，安装后点击右上角刷新按钮",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline.copy(alpha = 0.6f),
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun ExtensionItem(
    extension: PluginInfo,
    pluginInstance: Any?,
    isRunning: Boolean,
    onClick: () -> Unit = {}
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var isEnabled by remember { mutableStateOf(SettingsPreferences.isPluginEnabled(context, extension.id)) }
    var showErrorDialog by remember { mutableStateOf(false) }
    var isExpanded by remember { mutableStateOf(false) }
    
    val errors = PluginErrorLog.getErrors(extension.id)
    val hasErrors = errors.isNotEmpty()
    
    val hasSettings = pluginInstance?.let { 
        when (it) {
            is com.kingzcheung.kime.plugin.core.api.SpeechPlugin -> it.hasSettings()
            is com.kingzcheung.kime.plugin.core.api.EmojiPlugin -> it.hasSettings()
            is com.kingzcheung.kime.plugin.core.api.PredictionPlugin -> it.hasSettings()
            else -> false
        }
    } ?: false
    
    if (showErrorDialog && hasErrors) {
        PluginErrorDialog(
            pluginId = extension.id,
            pluginName = extension.name,
            errors = errors,
            onDismiss = { showErrorDialog = false },
            onClear = { 
                PluginErrorLog.clearErrors(extension.id)
                showErrorDialog = false
            }
        )
    }
    
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { isExpanded = !isExpanded }
                .padding(12.dp)
        ) {
            // 第一行：标题 + 状态指示器
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = extension.name,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.weight(1f),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                
                // 状态指示器（固定在右侧）
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    val runningColor = Color(0xFF4CAF50)  // 绿色
                    Box(
                        modifier = Modifier
                            .size(6.dp)
                            .background(
                                if (isRunning) runningColor 
                                else MaterialTheme.colorScheme.outline,
                                shape = RoundedCornerShape(3.dp)
                            )
                    )
                    Text(
                        text = if (isRunning) "运行中" else "未运行",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (isRunning) runningColor 
                               else MaterialTheme.colorScheme.outline
                    )
                    
                    if (hasErrors) {
                        Icon(
                            Icons.Default.Warning,
                            contentDescription = "有错误",
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(14.dp)
                        )
                    }
                    
                    // 展开指示器
                    Icon(
                        if (isExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = if (isExpanded) "收起" else "展开",
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                    )
                }
            }
            
            // 第二行：类型 + 版本
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    text = getTypeName(extension.type),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (isEnabled) MaterialTheme.colorScheme.primary 
                           else MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text("•", style = MaterialTheme.typography.bodySmall, 
                     color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f))
                Text(
                    text = "v${extension.versionName}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                
                if (hasSettings) {
                    Text("•", style = MaterialTheme.typography.bodySmall, 
                         color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f))
                    Text(
                        text = "可配置",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f)
                    )
                }
            }
            
            // 展开详情
            AnimatedVisibility(visible = isExpanded) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
                    
                    if (extension.description.isNotEmpty()) {
                        Text(
                            text = extension.description,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                        )
                    }
                    
                    // 操作按钮行
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        // 启用开关
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = "启用",
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Switch(
                                checked = isEnabled,
                                onCheckedChange = { enabled ->
                                    isEnabled = enabled
                                    SettingsPreferences.setPluginEnabled(context, extension.id, enabled)
                                    
                                    scope.launch {
                                        try {
                                            PluginManager.setPluginEnabled(extension.id, enabled)
                                            if (enabled) {
                                                PluginManager.launchPlugin(extension.id)
                                            } else {
                                                PluginManager.unloadPlugin(extension.id)
                                            }
                                        } catch (e: Exception) {
                                            Log.e("PluginsSettings", "Failed to toggle plugin", e)
                                        }
                                    }
                                },
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = MaterialTheme.colorScheme.primary,
                                    checkedTrackColor = MaterialTheme.colorScheme.primaryContainer
                                )
                            )
                        }
                        
                        Spacer(modifier = Modifier.weight(1f))
                        
                        // 设置按钮
                        if (hasSettings) {
                            OutlinedButton(
                                onClick = onClick,
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                            ) {
                                Icon(Icons.Default.Settings, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("设置", style = MaterialTheme.typography.labelMedium)
                            }
                        }
                        
                        // 删除按钮
                        IconButton(
                            onClick = {
                                try {
                                    val intent = Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                                    intent.data = Uri.parse("package:${extension.id}")
                                    context.startActivity(intent)
                                    android.widget.Toast.makeText(context, "请在应用信息页面卸载", android.widget.Toast.LENGTH_SHORT).show()
                                } catch (e: Exception) {
                                    android.widget.Toast.makeText(context, "无法打开: ${e.message}", android.widget.Toast.LENGTH_SHORT).show()
                                }
                            }
                        ) {
                            Icon(Icons.Default.Delete, contentDescription = "卸载", 
                                 tint = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }
        }
    }
}

private fun getTypeName(type: String): String {
    return when (type.lowercase()) {
        "prediction" -> "联想词"
        "speech" -> "语音转文字"
        "emoji" -> "表情推荐"
        else -> type
    }
}

private fun getTypeIcon(type: String): ImageVector {
    return when (type.lowercase()) {
        "prediction" -> Icons.Default.AutoAwesome
        "speech" -> Icons.Default.Mic
        "emoji" -> Icons.Default.Face
        else -> Icons.Default.Extension
    }
}

@Composable
private fun PluginErrorDialog(
    pluginId: String,
    pluginName: String,
    errors: List<PluginErrorLog.PluginError>,
    onDismiss: () -> Unit,
    onClear: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { 
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.Warning,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text("$pluginName 错误日志")
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(300.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                errors.forEachIndexed { index, error ->
                    Card(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f)
                        )
                    ) {
                        Column(modifier = Modifier.padding(8.dp)) {
                            Text(
                                text = "#${index + 1} ${error.operation}",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.error
                            )
                            Text(
                                text = error.message,
                                style = MaterialTheme.typography.bodySmall
                            )
                            val stackTraceText = error.stackTrace
                            if (stackTraceText != null && stackTraceText.isNotEmpty()) {
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = stackTraceText.take(200) + "...",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onClear) {
                Text("清除日志", color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("关闭")
            }
        }
    )
}