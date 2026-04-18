package com.kingzcheung.kime.plugin.funasr.ui

import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.kingzcheung.kime.plugin.funasr.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FunAsrSettingsScreen(
    initialApiKey: String,
    onSaveApiKey: (String) -> Unit,
    onNavigateBack: () -> Unit
) {
    val context = LocalContext.current
    
    var apiKey by remember { mutableStateOf(initialApiKey) }
    var showApiKey by remember { mutableStateOf(false) }
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Card(
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(Icons.Default.Key, contentDescription = null)
                        Text(
                            text = stringResource(R.string.api_key_label),
                            style = MaterialTheme.typography.titleMedium
                        )
                    }
                    
                    OutlinedTextField(
                        value = apiKey,
                        onValueChange = { apiKey = it },
                        label = { Text(stringResource(R.string.api_key_hint)) },
                        singleLine = true,
                        visualTransformation = if (showApiKey) 
                            VisualTransformation.None 
                        else 
                            PasswordVisualTransformation(),
                        trailingIcon = {
                            IconButton(onClick = { showApiKey = !showApiKey }) {
                                Icon(
                                    imageVector = if (showApiKey) 
                                        Icons.Default.VisibilityOff 
                                    else 
                                        Icons.Default.Visibility,
                                    contentDescription = if (showApiKey) "隐藏" else "显示"
                                )
                            }
                        },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        modifier = Modifier.fillMaxWidth()
                    )
                    
                    Button(
                        onClick = {
                            if (apiKey.isBlank()) {
                                Toast.makeText(
                                    context,
                                    context.getString(R.string.api_key_empty_error),
                                    Toast.LENGTH_SHORT
                                ).show()
                            } else {
                                onSaveApiKey(apiKey)
                                Toast.makeText(
                                    context,
                                    context.getString(R.string.api_key_saved),
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(stringResource(R.string.save_button))
                    }
                }
            }
            
            Card(
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(Icons.Default.Info, contentDescription = null)
                        Text(
                            text = "使用说明",
                            style = MaterialTheme.typography.titleMedium
                        )
                    }
                    
                    Text(
                        text = "1. 访问阿里云百炼平台获取API Key",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        text = "2. 将API Key粘贴到上方输入框",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        text = "3. 点击保存按钮保存设置",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        text = "4. 在输入法中启用语音识别功能即可使用",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    
                    Spacer(modifier = Modifier.height(8.dp))
                    
                    Text(
                        text = "获取API Key: https://help.aliyun.com/zh/model-studio/get-api-key",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }
    }
}