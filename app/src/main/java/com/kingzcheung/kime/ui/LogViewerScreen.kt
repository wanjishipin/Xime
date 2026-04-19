package com.kingzcheung.kime.ui

import android.util.Log
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material3.Button
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.io.File

private const val TAG = "LogViewer"
private fun LOGI(msg: String) = Log.i(TAG, msg)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogViewerScreen(
    onBack: () -> Unit
) {
    val context = LocalContext.current
    var logFiles by remember { mutableStateOf<List<File>>(emptyList()) }
    var selectedLogFile by remember { mutableStateOf<File?>(null) }
    var logContent by remember { mutableStateOf<String>("") }
    var isLoading by remember { mutableStateOf(false) }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    
    fun loadLogFiles() {
        isLoading = true
        errorMsg = null
        
        try {
            // 确保 logs 目录存在
            val logsDir = File(context.filesDir, "logs")
            if (!logsDir.exists()) {
                logsDir.mkdirs()
                LOGI("Created logs directory: ${logsDir.absolutePath}")
            }
            
            // 获取所有日志文件（包括 RIME 日志和 App 日志）
            val allFiles = logsDir.listFiles()?.toList() ?: emptyList()
            
            logFiles = allFiles
                .filter { it.isFile }
                .filter { file ->
                    // RIME 日志文件
                    file.name.contains("rime.kime") ||
                    file.name.contains(".INFO") ||
                    file.name.contains(".WARNING") ||
                    file.name.contains(".ERROR") ||
                    file.name.contains(".FATAL") ||
                    // App 日志文件
                    file.name.startsWith("kime_") && file.name.endsWith(".log") ||
                    file.name.endsWith(".log")
                }
                .sortedByDescending { it.lastModified() }
            
            LOGI("Found ${logFiles.size} log files")
            
            if (logFiles.isEmpty()) {
                errorMsg = null
            }
        } catch (e: Exception) {
            errorMsg = "加载日志文件失败: ${e.message}"
            LOGI("Error loading log files: ${e.message}")
            logFiles = emptyList()
        }
        
        isLoading = false
    }
    
    fun loadLogFileContent(file: File) {
        isLoading = true
        errorMsg = null
        
        try {
            logContent = file.readText()
            selectedLogFile = file
        } catch (e: Exception) {
            errorMsg = "读取日志失败: ${e.message}"
            logContent = ""
        }
        
        isLoading = false
    }
    
    fun deleteLogFile(file: File) {
        try {
            if (file.delete()) {
                loadLogFiles()
                if (selectedLogFile == file) {
                    selectedLogFile = null
                    logContent = ""
                }
            }
        } catch (e: Exception) {
            errorMsg = "删除日志失败: ${e.message}"
        }
    }
    
    fun clearAllLogs() {
        try {
            val logsDir = File(context.filesDir, "logs")
            logsDir.listFiles()?.forEach { it.delete() }
            loadLogFiles()
            selectedLogFile = null
            logContent = ""
        } catch (e: Exception) {
            errorMsg = "清空日志失败: ${e.message}"
        }
    }
    
    LaunchedEffect(Unit) {
        loadLogFiles()
    }
    
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("日志查看器") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "返回"
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { loadLogFiles() }) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = "刷新"
                        )
                    }
                    if (logFiles.isNotEmpty()) {
                        IconButton(onClick = { clearAllLogs() }) {
                            Icon(
                                imageVector = Icons.Default.Delete,
                                contentDescription = "清空所有日志"
                            )
                        }
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
        if (isLoading) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator()
            }
        } else if (errorMsg != null) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = errorMsg!!,
                        color = MaterialTheme.colorScheme.error
                    )
                    Button(onClick = { loadLogFiles() }) {
                        Text("重新加载")
                    }
                }
            }
        } else if (selectedLogFile != null) {
            // 显示日志内容
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = selectedLogFile!!.name,
                        fontWeight = FontWeight.Medium
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = { 
                            selectedLogFile = null
                            logContent = ""
                        }) {
                            Text("返回列表")
                        }
                        IconButton(onClick = { deleteLogFile(selectedLogFile!!) }) {
                            Icon(
                                Icons.Default.Delete,
                                contentDescription = "删除此日志",
                                tint = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                }
                
                HorizontalDivider()
                
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    if (logContent.isEmpty()) {
                        item {
                            Text("日志文件为空")
                        }
                    } else {
                        // 分行显示日志内容
                        val lines = logContent.lines()
                        items(lines) { line ->
                            Text(
                                text = line,
                                fontSize = 12.sp,
                                color = when {
                                    line.contains("ERROR") -> MaterialTheme.colorScheme.error
                                    line.contains("WARNING") -> MaterialTheme.colorScheme.secondary
                                    line.contains("FATAL") -> MaterialTheme.colorScheme.error.copy(alpha = 0.8f)
                                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                                }
                            )
                        }
                    }
                }
            }
        } else {
            // 显示日志文件列表
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (logFiles.isEmpty()) {
                    item {
                        Box(
                            modifier = Modifier.fillMaxWidth(),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.Description,
                                    contentDescription = null,
                                    modifier = Modifier.size(48.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                                )
                                Text(
                                    text = "暂无日志文件",
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                            text = "应用运行正常，没有 WARNING 或 ERROR 日志",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                        )
                        Text(
                            text = "日志类型:",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                        )
                        Text(
                            text = "• RIME引擎日志：WARNING及以上（INFO已关闭）",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.7f)
                        )
                        Text(
                            text = "• 主应用日志：所有级别（DEBUG/INFO/WARNING/ERROR）",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f)
                        )
                                Text(
                                    text = "日志级别: WARNING 及以上（INFO 已关闭）",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                                )
                                Text(
                                    text = "日志目录已创建: ${context.filesDir.absolutePath}/logs/",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                Button(onClick = { loadLogFiles() }) {
                                    Text("刷新")
                                }
                            }
                        }
                    }
                } else {
                    item {
                        Text(
                            text = "日志文件 (${logFiles.size})",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    
                    items(logFiles) { file ->
                        LogFileItem(
                            file = file,
                            onClick = { loadLogFileContent(file) },
                            onDelete = { deleteLogFile(file) }
                        )
                    }
                    
                    item {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "日志位置: ${context.filesDir.absolutePath}/logs/",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                        )
                        Text(
                            text = "日志级别: WARNING 及以上（INFO 已关闭）",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun LogFileItem(
    file: File,
    onClick: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        onClick = onClick,
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = file.name,
                    fontWeight = FontWeight.Medium
                )
                
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = formatFileSize(file.length()),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "•",
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                    )
                    Text(
                        text = formatTimestamp(file.lastModified()),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                
                // 显示日志类型标签
                val logType = when {
                    file.name.contains("WARNING") -> "WARNING"
                    file.name.contains("ERROR") -> "ERROR"
                    file.name.contains("FATAL") -> "FATAL"
                    file.name.startsWith("kime_") -> "APP日志"
                    file.name.contains("rime.kime") -> "RIME日志"
                    else -> "LOG"
                }
                
                Text(
                    text = logType,
                    style = MaterialTheme.typography.labelSmall,
                    color = when (logType) {
                        "ERROR" -> MaterialTheme.colorScheme.error
                        "WARNING" -> MaterialTheme.colorScheme.secondary
                        "FATAL" -> MaterialTheme.colorScheme.error.copy(alpha = 0.8f)
                        "APP日志" -> MaterialTheme.colorScheme.primary
                        "RIME日志" -> MaterialTheme.colorScheme.tertiary
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    }
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

private fun formatFileSize(size: Long): String {
    return when {
        size < 1024 -> "$size B"
        size < 1024 * 1024 -> "${size / 1024} KB"
        else -> "${size / (1024 * 1024)} MB"
    }
}

private fun formatTimestamp(timestamp: Long): String {
    val sdf = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault())
    return sdf.format(java.util.Date(timestamp))
}