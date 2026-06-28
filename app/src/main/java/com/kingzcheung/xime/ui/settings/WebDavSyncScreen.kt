package com.kingzcheung.xime.ui.settings

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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.twotone.Cloud
import androidx.compose.material.icons.twotone.CloudDownload
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusEvent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kingzcheung.xime.settings.SettingsPreferences
import com.kingzcheung.xime.settings.WebDavSyncHelper
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WebDavSyncContent(
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var serverUrl by remember { mutableStateOf(SettingsPreferences.getWebDavUrl(context)) }
    var username by remember { mutableStateOf(SettingsPreferences.getWebDavUsername(context)) }
    var password by remember { mutableStateOf(SettingsPreferences.getWebDavPassword(context)) }
    var remotePath by remember { mutableStateOf(SettingsPreferences.getWebDavPath(context)) }
    var showPassword by remember { mutableStateOf(false) }

    var connectionStatus by remember { mutableStateOf<String?>(null) }
    var connectionOk by remember { mutableStateOf<Boolean?>(null) }
    var isTesting by remember { mutableStateOf(false) }

    var isUploading by remember { mutableStateOf(false) }
    var isDownloading by remember { mutableStateOf(false) }
    var syncProgress by remember { mutableStateOf<String?>(null) }

    DisposableEffect(Unit) {
        onDispose {
            SettingsPreferences.setWebDavUrl(context, serverUrl)
            SettingsPreferences.setWebDavUsername(context, username)
            SettingsPreferences.setWebDavPassword(context, password)
            SettingsPreferences.setWebDavPath(context, remotePath)
        }
    }

    fun saveConfig() {
        SettingsPreferences.setWebDavUrl(context, serverUrl)
        SettingsPreferences.setWebDavUsername(context, username)
        SettingsPreferences.setWebDavPassword(context, password)
        SettingsPreferences.setWebDavPath(context, remotePath)
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("WebDAV 同步") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = MaterialTheme.colorScheme.onBackground
                )
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .imePadding()
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
            Spacer(modifier = Modifier.height(4.dp))

            SettingsSection(title = "服务器配置") {
                Column(modifier = Modifier.padding(16.dp)) {
                    var urlFocused by remember { mutableStateOf(false) }
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .onFocusEvent { urlFocused = it.isFocused }
                            .clip(RoundedCornerShape(14.dp))
                            .background(
                                if (urlFocused) MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                                else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.04f)
                            )
                    ) {
                        BasicTextField(
                            value = serverUrl,
                            onValueChange = { serverUrl = it; connectionOk = null; connectionStatus = null },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 14.dp),
                            singleLine = true,
                            textStyle = MaterialTheme.typography.bodyMedium.copy(
                                color = MaterialTheme.colorScheme.onSurface
                            ),
                            decorationBox = { innerTextField ->
                                Box(modifier = Modifier.fillMaxWidth()) {
                                    if (serverUrl.isEmpty() && !urlFocused) {
                                        Text(
                                            "服务器地址",
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                                        )
                                    }
                                    innerTextField()
                                }
                            }
                        )
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    var userFocused by remember { mutableStateOf(false) }
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .onFocusEvent { userFocused = it.isFocused }
                            .clip(RoundedCornerShape(14.dp))
                            .background(
                                if (userFocused) MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                                else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.04f)
                            )
                    ) {
                        BasicTextField(
                            value = username,
                            onValueChange = { username = it; connectionOk = null; connectionStatus = null },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 14.dp),
                            singleLine = true,
                            textStyle = MaterialTheme.typography.bodyMedium.copy(
                                color = MaterialTheme.colorScheme.onSurface
                            ),
                            decorationBox = { innerTextField ->
                                Box(modifier = Modifier.fillMaxWidth()) {
                                    if (username.isEmpty() && !userFocused) {
                                        Text(
                                            "用户名",
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                                        )
                                    }
                                    innerTextField()
                                }
                            }
                        )
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    var pwdFocused by remember { mutableStateOf(false) }
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .onFocusEvent { pwdFocused = it.isFocused }
                            .clip(RoundedCornerShape(14.dp))
                            .background(
                                if (pwdFocused) MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                                else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.04f)
                            )
                            .padding(horizontal = 16.dp, vertical = 14.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(modifier = Modifier.weight(1f)) {
                                BasicTextField(
                                    value = password,
                                    onValueChange = { password = it; connectionOk = null; connectionStatus = null },
                                    modifier = Modifier.fillMaxWidth(),
                                    singleLine = true,
                                    textStyle = MaterialTheme.typography.bodyMedium.copy(
                                        color = MaterialTheme.colorScheme.onSurface
                                    ),
                                    visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                                    decorationBox = { innerTextField ->
                                        Box {
                                            if (password.isEmpty() && !pwdFocused) {
                                                Text(
                                                    "密码",
                                                    style = MaterialTheme.typography.bodyMedium,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                                                )
                                            }
                                            innerTextField()
                                        }
                                    }
                                )
                            }
                            Icon(
                                imageVector = if (showPassword) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                contentDescription = if (showPassword) "隐藏密码" else "显示密码",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                                modifier = Modifier
                                    .size(20.dp)
                                    .clickable { showPassword = !showPassword }
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    var pathFocused by remember { mutableStateOf(false) }
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .onFocusEvent { pathFocused = it.isFocused }
                            .clip(RoundedCornerShape(14.dp))
                            .background(
                                if (pathFocused) MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                                else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.04f)
                            )
                    ) {
                        BasicTextField(
                            value = remotePath,
                            onValueChange = { remotePath = it; connectionOk = null; connectionStatus = null },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 14.dp),
                            singleLine = true,
                            textStyle = MaterialTheme.typography.bodyMedium.copy(
                                color = MaterialTheme.colorScheme.onSurface
                            ),
                            decorationBox = { innerTextField ->
                                Box(modifier = Modifier.fillMaxWidth()) {
                                    if (remotePath.isEmpty() && !pathFocused) {
                                        Text(
                                            "远程路径",
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                                        )
                                    }
                                    innerTextField()
                                }
                            }
                        )
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Button(
                            onClick = {
                                saveConfig()
                                isTesting = true
                                connectionStatus = null
                                connectionOk = null
                                scope.launch {
                                    val result = WebDavSyncHelper.testConnection(
                                        serverUrl, username, password, remotePath
                                    )
                                    connectionOk = result.isSuccess
                                    connectionStatus = result.getOrNull() ?: result.exceptionOrNull()?.message
                                    isTesting = false
                                }
                            },
                            enabled = serverUrl.isNotBlank() && !isTesting,
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            if (isTesting) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    strokeWidth = 2.dp,
                                    color = MaterialTheme.colorScheme.onPrimary
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                            }
                            Icon(Icons.TwoTone.Cloud, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("测试连接")
                        }
                        if (connectionStatus != null) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    if (connectionOk == true) Icons.Default.CheckCircle else Icons.Default.Error,
                                    contentDescription = null,
                                    tint = if (connectionOk == true) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    connectionStatus ?: "",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (connectionOk == true) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                                )
                            }
                        }
                    }
                }
            }

            SettingsSection(title = "同步操作") {
                if (syncProgress != null) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        if (isUploading || isDownloading) {
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                            Spacer(modifier = Modifier.height(8.dp))
                        }
                        Text(
                            syncProgress ?: "",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    HorizontalDivider(
                        thickness = 0.5.dp,
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                        modifier = Modifier.padding(horizontal = 16.dp)
                    )
                }

                Column(modifier = Modifier.padding(8.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        OutlinedButton(
                            onClick = {
                                saveConfig()
                                isUploading = true
                                syncProgress = null
                                scope.launch {
                                    val ok = WebDavSyncHelper.uploadSchemas(
                                        context, serverUrl, username, password, remotePath
                                    ) { msg -> syncProgress = msg }
                                    isUploading = false
                                    if (ok) Toast.makeText(context, "上传完成", Toast.LENGTH_SHORT).show()
                                    else Toast.makeText(context, "上传失败", Toast.LENGTH_SHORT).show()
                                }
                            },
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp),
                            enabled = serverUrl.isNotBlank() && !isUploading && !isDownloading,
                            shape = RoundedCornerShape(12.dp)
                        ) {
                    if (isUploading) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp
                        )
                    } else {
                        Icon(Icons.TwoTone.CloudDownload, contentDescription = null, modifier = Modifier.size(18.dp))
                    }
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("上传到服务器", fontSize = 12.sp)
                }
                Button(
                    onClick = {
                        saveConfig()
                        isDownloading = true
                        syncProgress = null
                        scope.launch {
                            val ok = WebDavSyncHelper.downloadSchemas(
                                context, serverUrl, username, password, remotePath
                            ) { msg -> syncProgress = msg }
                            isDownloading = false
                            if (ok) {
                                Toast.makeText(context, "下载完成", Toast.LENGTH_SHORT).show()
                            } else {
                                Toast.makeText(context, "下载失败", Toast.LENGTH_SHORT).show()
                            }
                        }
                    },
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp),
                    enabled = serverUrl.isNotBlank() && !isUploading && !isDownloading,
                    shape = RoundedCornerShape(12.dp)
                ) {
                    if (isDownloading) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                    } else {
                        Icon(Icons.TwoTone.CloudDownload, contentDescription = null, modifier = Modifier.size(18.dp))
                    }
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("从服务器下载", fontSize = 12.sp)
                }
            }

                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        "同步内容：rime/ 目录下的方案文件、词典和用户配置，不含 build/ 目录。上传会覆盖远程文件，下载会覆盖本地文件。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))
            }
        }
    }
}
