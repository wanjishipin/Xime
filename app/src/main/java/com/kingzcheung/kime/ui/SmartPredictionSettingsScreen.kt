package com.kingzcheung.kime.ui

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.kingzcheung.kime.association.AssociationManager
import com.kingzcheung.kime.settings.SettingsPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.URL

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SmartPredictionSettingsContent(
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    
    var isEnabled by remember { mutableStateOf(SettingsPreferences.isSmartPredictionEnabled(context)) }
    var isInitialized by remember { mutableStateOf(AssociationManager.isInitialized()) }
    var cacheSize by remember { mutableStateOf(0) }
    var isSaving by remember { mutableStateOf(false) }
    var isLoading by remember { mutableStateOf(false) }
    var isDownloading by remember { mutableStateOf(false) }
    var downloadProgress by remember { mutableStateOf(0f) }
    var downloadStatus by remember { mutableStateOf("") }
    var modelRepo by remember { mutableStateOf(SettingsPreferences.getPredictionModelRepo(context)) }
    var hasModel by remember { mutableStateOf(false) }
    var showRepoDialog by remember { mutableStateOf(false) }
    var tempRepo by remember { mutableStateOf(modelRepo) }
    
    fun checkModelState() {
        val vocabFile = context.filesDir.resolve("vocab.json")
        val modelFile = context.filesDir.resolve("model_int8_dynamic.onnx")
        hasModel = vocabFile.exists() && modelFile.exists()
    }
    
    fun downloadModelFiles() {
        scope.launch {
            isDownloading = true
            downloadProgress = 0f
            downloadStatus = "准备下载..."
            
            try {
                withContext(Dispatchers.IO) {
                    val baseUrl = modelRepo.trimEnd('/')
                    
                    val filesToDownload = listOf(
                        "vocab.json" to File(context.filesDir, "vocab.json"),
                        "model_int8_dynamic.onnx" to File(context.filesDir, "model_int8_dynamic.onnx")
                    )
                    
                    val totalFiles = filesToDownload.size
                    
                    filesToDownload.forEachIndexed { index, (fileName, targetFile) ->
                        downloadStatus = "下载 $fileName (${index + 1}/$totalFiles)..."
                        
                        val downloadUrl = when {
                            baseUrl.contains("modelscope.cn") -> {
                                val cleanUrl = baseUrl.trimEnd('/')
                                "$cleanUrl/resolve/master/$fileName"
                            }
                            else -> "$baseUrl/$fileName"
                        }
                        
                        URL(downloadUrl).openStream().use { input ->
                            FileOutputStream(targetFile).use { output ->
                                input.copyTo(output)
                            }
                        }
                        
                        downloadProgress = (index + 1).toFloat() / totalFiles
                    }
                }
                
                downloadStatus = "下载完成"
                checkModelState()
                
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "模型下载成功", Toast.LENGTH_SHORT).show()
                    
                    if (isEnabled && !isInitialized) {
                        isLoading = true
                        val success = withContext(Dispatchers.IO) {
                            AssociationManager.initialize(context)
                        }
                        isInitialized = success
                        isLoading = false
                    }
                }
            } catch (e: Exception) {
                downloadStatus = "下载失败: ${e.message}"
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "下载失败: ${e.message}", Toast.LENGTH_LONG).show()
                }
            } finally {
                isDownloading = false
                downloadProgress = 0f
                downloadStatus = ""
            }
        }
    }
    
    LaunchedEffect(Unit) {
        checkModelState()
        cacheSize = AssociationManager.getCacheSize()
        
        if (isEnabled && !hasModel) {
            isEnabled = false
            SettingsPreferences.setSmartPredictionEnabled(context, false)
            Toast.makeText(context, "模型文件不存在，已自动关闭智能联想", Toast.LENGTH_LONG).show()
        }
    }
    
    LaunchedEffect(isEnabled) {
        if (isEnabled && !isInitialized && hasModel) {
            isLoading = true
            val success = withContext(Dispatchers.IO) {
                AssociationManager.initialize(context)
            }
            isInitialized = success
            isLoading = false
        } else if (!isEnabled && isInitialized) {
            withContext(Dispatchers.IO) {
                AssociationManager.release()
            }
            isInitialized = false
        }
    }
    
    if (showRepoDialog) {
        AlertDialog(
            onDismissRequest = { showRepoDialog = false },
            title = { Text("模型仓库地址") },
            text = {
                Column {
                    Text(
                        text = "设置模型下载来源",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedTextField(
                        value = tempRepo,
                        onValueChange = { tempRepo = it },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { 
                            Text(
                                "输入模型仓库 URL",
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                            ) 
                        },
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp)
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "默认: https://www.modelscope.cn/models/bikeand/predictive-text-small",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    modelRepo = tempRepo
                    SettingsPreferences.setPredictionModelRepo(context, tempRepo)
                    showRepoDialog = false
                }) {
                    Text("保存")
                }
            },
            dismissButton = {
                TextButton(onClick = { showRepoDialog = false }) {
                    Text("取消")
                }
            }
        )
    }
    
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("智能联想") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "返回"
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
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            contentPadding = PaddingValues(vertical = 8.dp)
        ) {
            item {
                SettingsSection(title = "功能开关", content = {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "启用智能联想",
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.Medium
                            )
                            Text(
                                text = "基于 AI 模型的智能联想词预测",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        if (isLoading) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(24.dp),
                                strokeWidth = 2.dp
                            )
                        } else {
                            Switch(
                                checked = isEnabled,
                                onCheckedChange = { newValue ->
                                    if (newValue && !hasModel) {
                                        Toast.makeText(context, "请先下载模型文件", Toast.LENGTH_SHORT).show()
                                    } else {
                                        isEnabled = newValue
                                        SettingsPreferences.setSmartPredictionEnabled(context, newValue)
                                    }
                                },
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = MaterialTheme.colorScheme.primary,
                                    checkedTrackColor = MaterialTheme.colorScheme.primaryContainer
                                )
                            )
                        }
                    }
                    
                    if (!hasModel) {
                        HorizontalDivider(
                            modifier = Modifier.padding(start = 16.dp),
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
                                Icons.Default.Info,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "未检测到模型文件，请先下载模型",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                })
            }
            
            if (isInitialized) {
                item {
                    SettingsSection(title = "用户学习数据", content = {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "缓存大小",
                                        style = MaterialTheme.typography.bodyLarge,
                                        fontWeight = FontWeight.Medium
                                    )
                                    Text(
                                        text = "记录用户输入习惯以提升预测准确度",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Text(
                                    text = "$cacheSize 条",
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.primary,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                            
                            if (cacheSize > 0) {
                                Spacer(modifier = Modifier.height(12.dp))
                                
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    OutlinedButton(
                                        onClick = {
                                            scope.launch {
                                                isSaving = true
                                                AssociationManager.saveUserData()
                                                isSaving = false
                                            }
                                        },
                                        enabled = !isSaving,
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        if (isSaving) {
                                            CircularProgressIndicator(
                                                modifier = Modifier.size(16.dp),
                                                strokeWidth = 2.dp
                                            )
                                            Spacer(modifier = Modifier.width(8.dp))
                                        }
                                        Text(if (isSaving) "保存中..." else "保存数据")
                                    }
                                    
                                    OutlinedButton(
                                        onClick = {
                                            scope.launch {
                                                withContext(Dispatchers.IO) {
                                                    AssociationManager.saveUserData()
                                                }
                                                cacheSize = AssociationManager.getCacheSize()
                                            }
                                        },
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Icon(Icons.Default.Settings, contentDescription = null, modifier = Modifier.size(16.dp))
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text("刷新")
                                    }
                                }
                            }
                        }
                    })
                }
            }
            
            item {
                SettingsSection(title = "模型管理", content = {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "模型状态",
                                    style = MaterialTheme.typography.bodyLarge,
                                    fontWeight = FontWeight.Medium
                                )
                                Text(
                                    text = if (hasModel) "已安装" else "未安装",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (hasModel) MaterialTheme.colorScheme.primary 
                                           else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            if (hasModel) {
                                Icon(
                                    Icons.Default.AutoAwesome,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(24.dp)
                                )
                            }
                        }
                        
                        if (isDownloading) {
                            Spacer(modifier = Modifier.height(16.dp))
                            
                            Column(modifier = Modifier.fillMaxWidth()) {
                                Text(
                                    text = downloadStatus,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                LinearProgressIndicator(
                                    progress = { downloadProgress },
                                    modifier = Modifier.fillMaxWidth(),
                                    color = MaterialTheme.colorScheme.primary,
                                    trackColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
                                )
                            }
                        } else if (!hasModel) {
                            Spacer(modifier = Modifier.height(16.dp))
                            
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                OutlinedButton(
                                    onClick = {
                                        tempRepo = modelRepo
                                        showRepoDialog = true
                                    },
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Icon(Icons.Default.Settings, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text("设置仓库")
                                }
                                
                                Button(
                                    onClick = { downloadModelFiles() },
                                    modifier = Modifier.weight(1f),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = MaterialTheme.colorScheme.primary,
                                        contentColor = MaterialTheme.colorScheme.onPrimary
                                    )
                                ) {
                                    Icon(Icons.Default.CloudDownload, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text("下载模型")
                                }
                            }
                        }
                        
                        if (hasModel) {
                            Spacer(modifier = Modifier.height(16.dp))
                            
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                OutlinedButton(
                                    onClick = {
                                        tempRepo = modelRepo
                                        showRepoDialog = true
                                    },
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Icon(Icons.Default.Settings, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text("设置仓库")
                                }
                                
                                OutlinedButton(
                                    onClick = {
                                        val vocabFile = context.filesDir.resolve("vocab.json")
                                        val modelFile = context.filesDir.resolve("model_int8_dynamic.onnx")
                                        vocabFile.delete()
                                        modelFile.delete()
                                        hasModel = false
                                        isEnabled = false
                                        SettingsPreferences.setSmartPredictionEnabled(context, false)
                                        if (isInitialized) {
                                            scope.launch {
                                                AssociationManager.release()
                                            }
                                            isInitialized = false
                                        }
                                        Toast.makeText(context, "模型已删除", Toast.LENGTH_SHORT).show()
                                    },
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text("卸载模型")
                                }
                            }
                        }
                        
                        Spacer(modifier = Modifier.height(12.dp))
                        
                        Text(
                            text = "模型文件位置：${context.filesDir.absolutePath}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                        )
                        Text(
                            text = "必需文件：vocab.json, model_int8_dynamic.onnx",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                        )
                    }
                })
            }
            
            item {
                Spacer(modifier = Modifier.height(8.dp))
                
                Text(
                    text = "提示: 点击【下载模型】自动从仓库下载文件。如需更换来源，点击【设置仓库】修改地址",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline.copy(alpha = 0.6f),
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                )
            }
        }
    }
}