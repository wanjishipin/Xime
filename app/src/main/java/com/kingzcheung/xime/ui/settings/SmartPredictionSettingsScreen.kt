package com.kingzcheung.xime.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.kingzcheung.xime.model.ModelCategory
import com.kingzcheung.xime.model.ModelManager
import com.kingzcheung.xime.settings.SettingsPreferences
import com.kingzcheung.xime.viewmodel.SmartPredictionSettingsViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SmartPredictionSettingsContent(
    onBack: () -> Unit,
    onNavigateToModelManagement: () -> Unit = {},
    onNavigateToModelDetail: (String) -> Unit = {},
) {
    val context = LocalContext.current
    val viewModel: SmartPredictionSettingsViewModel = viewModel()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    // 模型清单来自远程 index，响应式收集以在加载完成后刷新（仅 PREDICTION 类别）
    val allModels by ModelManager.modelsFlow.collectAsStateWithLifecycle()
    val predictionModels = allModels.filter { it.category == ModelCategory.PREDICTION }

    val savedModelId = remember {
        SettingsPreferences.getPredictionSelectedModel(context)
    }
    var selectedModelId by remember {
        mutableStateOf(
            if (savedModelId.isNotEmpty() && predictionModels.any { it.id == savedModelId })
                savedModelId
            else ""
        )
    }

    LaunchedEffect(uiState.toastMessage) {
        uiState.toastMessage?.let { message ->
            viewModel.showToast(message)
            viewModel.clearToast()
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
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
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface
                ),
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
                        if (uiState.isLoading) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(24.dp),
                                strokeWidth = 2.dp
                            )
                        } else {
                            Switch(
                                checked = uiState.isEnabled,
                                onCheckedChange = { viewModel.setEnabled(it) },
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = MaterialTheme.colorScheme.primary,
                                    checkedTrackColor = MaterialTheme.colorScheme.primaryContainer
                                )
                            )
                        }
                    }

                    if (!uiState.hasModel) {
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

            if (uiState.isEnabled) {
                item {
                    var spaceCommitEnabled by remember {
                        mutableStateOf(SettingsPreferences.isSpaceCommitAssociationEnabled(context))
                    }
                    var singleMode by remember {
                        mutableStateOf(SettingsPreferences.isSingleAssociationMode(context))
                    }
                    SettingsSection(title = "联想行为", content = {
                        SettingsToggleItem(
                            icon = Icons.Default.AutoAwesome,
                            title = "空格上屏联想候选",
                            subtitle = "有联想候选时，按空格键直接上屏第一个联想词",
                            checked = spaceCommitEnabled,
                            onCheckedChange = {
                                spaceCommitEnabled = it
                                SettingsPreferences.setSpaceCommitAssociationEnabled(context, it)
                            }
                        )
                        HorizontalDivider(
                            modifier = Modifier.padding(start = 16.dp),
                            thickness = 0.5.dp,
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                        )
                        AssociationModeRow(
                            title = "连续联想",
                            subtitle = "联想词上屏后继续推理，可连续空格上屏联想词",
                            isSelected = !singleMode,
                            onClick = {
                                singleMode = false
                                SettingsPreferences.setAssociationSingleMode(context, false)
                            }
                        )
                        HorizontalDivider(
                            modifier = Modifier.padding(start = 16.dp),
                            thickness = 0.5.dp,
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                        )
                        AssociationModeRow(
                            title = "单次联想",
                            subtitle = "联想词上屏一次后停止推理，待下次输入再联想",
                            isSelected = singleMode,
                            onClick = {
                                singleMode = true
                                SettingsPreferences.setAssociationSingleMode(context, true)
                            }
                        )
                    })
                }
            }

            if (predictionModels.isNotEmpty()) {
                item {
                    SettingsSection(title = "选择模型", content = {
                        predictionModels.forEach { model ->
                            PredictionModelCard(
                                modelInfo = model,
                                isSelected = model.id == selectedModelId,
                                onSelect = {
                                    selectedModelId = model.id
                                    SettingsPreferences.setPredictionSelectedModel(context, model.id)
                                },
                                onOpenInStore = { onNavigateToModelDetail(model.id) }
                            )
                            if (model != predictionModels.last()) {
                                HorizontalDivider(
                                    modifier = Modifier.padding(start = 56.dp),
                                    thickness = 0.5.dp,
                                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                                )
                            }
                        }
                    })
                }
            }

            if (uiState.isInitialized) {
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
                                    text = "${uiState.cacheSize} 条",
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.primary,
                                    fontWeight = FontWeight.Bold
                                )
                            }

                            if (uiState.cacheSize > 0) {
                                Spacer(modifier = Modifier.height(12.dp))

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    OutlinedButton(
                                        onClick = { viewModel.saveUserData() },
                                        enabled = !uiState.isSaving,
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        if (uiState.isSaving) {
                                            CircularProgressIndicator(
                                                modifier = Modifier.size(16.dp),
                                                strokeWidth = 2.dp
                                            )
                                            Spacer(modifier = Modifier.width(8.dp))
                                        }
                                        Text(if (uiState.isSaving) "保存中..." else "保存数据")
                                    }

                                    OutlinedButton(
                                        onClick = { viewModel.refreshCacheSize() },
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
                    SettingsItem(
                        icon = Icons.Default.Build,
                        title = "模型管理",
                        subtitle = "管理所有已下载的 AI 模型",
                        onClick = onNavigateToModelManagement,
                        showArrow = true
                    )
                })
            }
        }
    }
}

@Composable
private fun AssociationModeRow(
    title: String,
    subtitle: String,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                color = if (isSelected) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        RadioButton(selected = isSelected, onClick = onClick)
    }
}

@Composable
private fun PredictionModelCard(
    modelInfo: com.kingzcheung.xime.model.ModelInfo,
    isSelected: Boolean,
    onSelect: () -> Unit,
    onOpenInStore: () -> Unit
) {
    val context = LocalContext.current
    val isDownloaded = remember { ModelManager.isModelDownloaded(context, modelInfo.id) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(
                    if (isDownloaded) MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                    else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.1f)
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.AutoAwesome,
                contentDescription = null,
                tint = if (isDownloaded) MaterialTheme.colorScheme.primary
                       else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                modifier = Modifier.size(22.dp)
            )
        }

        Spacer(modifier = Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = modelInfo.name,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (modelInfo.size.isNotEmpty()) {
                    Text(
                        text = modelInfo.size,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Surface(
                    shape = RoundedCornerShape(4.dp),
                    color = if (isDownloaded)
                        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
                    else
                        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                ) {
                    Text(
                        text = if (isDownloaded) "已下载" else "未下载",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (isDownloaded) MaterialTheme.colorScheme.primary
                               else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
            }
        }

        if (isDownloaded) {
            Spacer(modifier = Modifier.width(8.dp))
            OutlinedButton(
                onClick = onSelect,
                enabled = !isSelected,
                shape = RoundedCornerShape(50)
            ) {
                if (isSelected) {
                    Icon(
                        Icons.Default.Check,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(Modifier.width(4.dp))
                }
                Text(if (isSelected) "使用中" else "使用")
            }
        } else {
            Spacer(modifier = Modifier.width(8.dp))
            OutlinedButton(
                onClick = onOpenInStore,
                shape = RoundedCornerShape(50)
            ) {
                Icon(
                    Icons.Default.CloudDownload,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(Modifier.width(4.dp))
                Text("去商店")
            }
        }
    }
}
