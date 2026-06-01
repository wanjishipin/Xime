package com.kingzcheung.xime.ui

import android.content.Context
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kingzcheung.xime.R
import com.kingzcheung.xime.settings.SettingsPreferences
import com.kingzcheung.xime.speech.punctuation.PunctuationModelManager
import com.kingzcheung.xime.speech.sherpa.ModelDownloadManager
import com.kingzcheung.xime.speech.sherpa.SherpaAsrEngine
import kotlinx.coroutines.launch
import java.text.DecimalFormat

data class AsrProvider(
    val id: String,
    val name: String,
    val description: String,
    val iconRes: Int? = null,
    val icon: ImageVector? = null,
    val isOnline: Boolean,
    val isConfigured: Boolean,
    val features: List<String> = emptyList(),
    val needsAutoPunctuation: Boolean = false
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SpeechToTextSettingsContent(
    onBack: () -> Unit,
    onNavigateToFunAsrSettings: () -> Unit
) {
    val context = LocalContext.current
    var selectedTab by remember { mutableStateOf(0) }
    val tabs = listOf("在线 ASR", "本地模型")

    val onlineProviders = remember {
        mutableStateListOf(
            AsrProvider(
                id = "funasr",
                name = "阿里百炼 FunAsr",
                description = "阿里云实时语音识别",
                iconRes = R.drawable.bailian,
                isOnline = true,
                isConfigured = SettingsPreferences.getFunAsrApiKey(context).isNotEmpty(),
                features = listOf("实时流式", "高准确率", "多格式支持")
            )
        )
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("语音转文本") },
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
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            Spacer(modifier = Modifier.height(8.dp))
            
            EngineSelectorComposable()

            Spacer(modifier = Modifier.height(8.dp))

            TabRow(
                selectedTabIndex = selectedTab,
                indicator = { tabPositions ->
                    TabRowDefaults.SecondaryIndicator(
                        modifier = Modifier.tabIndicatorOffset(tabPositions[selectedTab]),
                        color = MaterialTheme.colorScheme.primary
                    )
                },
                containerColor = MaterialTheme.colorScheme.background,
                divider = {}
            ) {
                tabs.forEachIndexed { index, title ->
                    Tab(
                        selected = selectedTab == index,
                        onClick = { selectedTab = index },
                        text = {
                            Text(
                                text = title,
                                fontWeight = if (selectedTab == index) FontWeight.Bold else FontWeight.Normal
                            )
                        },
                        selectedContentColor = MaterialTheme.colorScheme.primary,
                        unselectedContentColor = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(modifier = Modifier.height(4.dp))

            when (selectedTab) {
                0 -> OnlineAsrTab(
                    providers = onlineProviders,
                    onProviderClick = { provider ->
                        if (provider.id == "funasr") {
                            onNavigateToFunAsrSettings()
                        }
                    }
                )
                1 -> LocalAsrTab()
            }
        }
    }
}

@Composable
fun EngineSelectorComposable() {
    val context = LocalContext.current
    var useLocal by remember { mutableStateOf(SettingsPreferences.isSttUseLocal(context)) }
    val downloadedModels = remember { mutableStateOf(
        com.kingzcheung.xime.speech.sherpa.ModelDownloadManager(context).getDownloadedModelIds().toSet()
    ) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "使用本地模型",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = if (useLocal) "离线运行，无需网络" else "使用阿里百炼在线 API",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline.copy(alpha = 0.7f)
                )
            }
            Switch(
                checked = useLocal,
                onCheckedChange = {
                    useLocal = it
                    SettingsPreferences.setSttUseLocal(context, it)
                },
                enabled = if (useLocal) true else downloadedModels.value.any { m -> m.isNotEmpty() } || true,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = MaterialTheme.colorScheme.primary,
                    checkedTrackColor = MaterialTheme.colorScheme.primaryContainer
                )
            )
        }
    }
}

@Composable
fun OnlineAsrTab(
    providers: List<AsrProvider>,
    onProviderClick: (AsrProvider) -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Text(
                text = "选择在线语音识别服务商",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 8.dp)
            )
        }

        items(providers) { provider ->
            AsrProviderCardModern(
                provider = provider,
                onClick = { onProviderClick(provider) }
            )
        }

        item {
            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "在线 ASR 需要网络连接，适合需要高准确率的场景",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.7f),
                modifier = Modifier.padding(top = 8.dp)
            )
        }
    }
}

@Composable
fun LocalAsrTab() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val downloadManager = remember { ModelDownloadManager(context) }

    val models = remember { SherpaAsrEngine.AVAILABLE_MODELS }

    val downloadedModels = remember { mutableStateOf(downloadManager.getDownloadedModelIds().toSet()) }

    val savedModelId = remember {
        context.getSharedPreferences("sherpa_asr", Context.MODE_PRIVATE)
            .getString("selected_model", "") ?: ""
    }
    var selectedModelId by remember {
        mutableStateOf(
            if (savedModelId.isNotEmpty() && savedModelId in downloadedModels.value) savedModelId else ""
        )
    }

    val downloadStates = remember {
        mutableStateListOf<Pair<String, ModelDownloadManager.DownloadState>>().also {
            models.forEach { model -> it.add(model.id to ModelDownloadManager.DownloadState.Idle) }
        }
    }

    fun refreshDownloaded() {
        downloadedModels.value = downloadManager.getDownloadedModelIds().toSet()
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "下载本地模型",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
            }
        }

        items(models) { modelInfo ->
            val stateIndex = downloadStates.indexOfFirst { it.first == modelInfo.id }
            val downloadState = if (stateIndex >= 0) downloadStates[stateIndex].second
            else ModelDownloadManager.DownloadState.Idle

            val isDownloaded = modelInfo.id in downloadedModels.value
            val isSelected = modelInfo.id == selectedModelId

            ModelCard(
                modelInfo = modelInfo,
                isDownloaded = isDownloaded,
                isSelected = isSelected,
                downloadState = downloadState,
                onDownload = {
                    scope.launch {
                        val idx = downloadStates.indexOfFirst { p -> p.first == modelInfo.id }
                        if (idx >= 0) downloadStates[idx] = modelInfo.id to ModelDownloadManager.DownloadState.Downloading(0f, 0L, -1L)

                        downloadManager.downloadModel(
                            downloadUrl = modelInfo.downloadUrl,
                            modelId = modelInfo.id,
                            onProgress = { state ->
                                val i = downloadStates.indexOfFirst { p -> p.first == modelInfo.id }
                                if (i >= 0) downloadStates[i] = modelInfo.id to state
                                if (state is ModelDownloadManager.DownloadState.Complete) {
                                    refreshDownloaded()
                                }
                            }
                        )
                    }
                },
                onDelete = {
                    downloadManager.deleteModel(modelInfo.id)
                    refreshDownloaded()
                    if (isSelected) {
                        selectedModelId = ""
                        context.getSharedPreferences("sherpa_asr", Context.MODE_PRIVATE)
                            .edit().putString("selected_model", "").apply()
                    }
                },
                onSelect = {
                    selectedModelId = modelInfo.id
                    context.getSharedPreferences("sherpa_asr", Context.MODE_PRIVATE)
                        .edit().putString("selected_model", modelInfo.id).apply()
                    // 选择本地模型时自动开启本地模式，无需用户手动去顶部切换
                    SettingsPreferences.setSttUseLocal(context, true)
                }
            )
        }

        item {
            Spacer(modifier = Modifier.height(8.dp))

            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                )
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.Top,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Icon(
                        Icons.Default.Warning,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.size(20.dp)
                    )
                    Column {
                        Text(
                            text = "说明",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.outline
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "• 模型下载后存储在应用内部存储空间\n" +
                                    "• 选择模型后会自动应用于语音识别\n" +
                                    "• 切换到已下载的模型无需重新下载\n" +
                                    "• 建议优先使用 int8 量化模型以节省空间",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.outline.copy(alpha = 0.7f)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            KeepModelInRamToggle()
            
            Spacer(modifier = Modifier.height(16.dp))
            
            PunctuationModelSection()
        }
    }
}

@Composable
fun KeepModelInRamToggle() {
    val context = LocalContext.current
    var keepInRam by remember { mutableStateOf(SettingsPreferences.isSttKeepModelInRam(context)) }
    val sherpaAvailable = try {
        System.loadLibrary("sherpa-onnx-jni"); true
    } catch (e: UnsatisfiedLinkError) { false }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "保持模型常驻内存",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = if (keepInRam) "首次语音后模型保持加载，后续秒级启动" else "每次语音后释放模型，下次需重新加载",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline.copy(alpha = 0.7f)
                )
            }
            Switch(
                checked = keepInRam,
                onCheckedChange = {
                    keepInRam = it
                    SettingsPreferences.setSttKeepModelInRam(context, it)
                },
                enabled = sherpaAvailable,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = MaterialTheme.colorScheme.primary,
                    checkedTrackColor = MaterialTheme.colorScheme.primaryContainer
                )
            )
        }
    }
}

@Composable
fun PunctuationModelSection() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val punctuationManager = remember { PunctuationModelManager(context) }
    
    var isDownloaded by remember { mutableStateOf(punctuationManager.isModelDownloaded()) }
    var downloadState by remember { mutableStateOf<PunctuationModelManager.DownloadState>(
        PunctuationModelManager.DownloadState.Idle
    ) }
    var isEnabled by remember { mutableStateOf(SettingsPreferences.isPunctuationModelEnabled(context)) }
    
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = "标点预测模型",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(bottom = 8.dp)
        )
        
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(
                containerColor = if (isDownloaded && isEnabled)
                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.8f)
                else
                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
            )
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(
                                if (isDownloaded)
                                    MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                                else
                                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.1f)
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Storage,
                            contentDescription = null,
                            tint = if (isDownloaded)
                                MaterialTheme.colorScheme.primary
                            else
                                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                            modifier = Modifier.size(24.dp)
                        )
                    }
                    
                    Spacer(modifier = Modifier.width(16.dp))
                    
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = PunctuationModelManager.PUNCTUATION_MODEL.name,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        
                        Spacer(modifier = Modifier.height(2.dp))
                        
                        Text(
                            text = PunctuationModelManager.PUNCTUATION_MODEL.description,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        
                        Spacer(modifier = Modifier.height(4.dp))
                        
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Surface(
                                shape = RoundedCornerShape(4.dp),
                                color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f)
                            ) {
                                Text(
                                    text = PunctuationModelManager.PUNCTUATION_MODEL.size,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.secondary,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                            if (isDownloaded) {
                                Surface(
                                    shape = RoundedCornerShape(4.dp),
                                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
                                ) {
                                    Text(
                                        text = "已下载",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                    )
                                }
                            }
                        }
                    }
                }
                
                when (downloadState) {
                    is PunctuationModelManager.DownloadState.Downloading -> {
                        Spacer(modifier = Modifier.height(12.dp))
                        val state = downloadState as PunctuationModelManager.DownloadState.Downloading
                        val animatedProgress by animateFloatAsState(
                            targetValue = state.progress.coerceIn(0f, 1f),
                            label = "punc_progress"
                        )
                        Column {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = "下载中...",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Text(
                                    text = "${(state.progress * 100).toInt()}%",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                            LinearProgressIndicator(
                                progress = { animatedProgress },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(6.dp)
                                    .clip(RoundedCornerShape(3.dp)),
                                color = MaterialTheme.colorScheme.primary,
                                trackColor = MaterialTheme.colorScheme.surfaceVariant
                            )
                        }
                    }
                    is PunctuationModelManager.DownloadState.Error -> {
                        Spacer(modifier = Modifier.height(8.dp))
                        val state = downloadState as PunctuationModelManager.DownloadState.Error
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f)
                        ) {
                            Text(
                                text = state.message,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                                modifier = Modifier.padding(8.dp)
                            )
                        }
                    }
                    PunctuationModelManager.DownloadState.Complete -> {
                        Spacer(modifier = Modifier.height(8.dp))
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
                        ) {
                            Row(
                                modifier = Modifier.padding(8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Icon(
                                    Icons.Default.CheckCircle,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(16.dp)
                                )
                                Text(
                                    text = "下载完成",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                        isDownloaded = true
                        downloadState = PunctuationModelManager.DownloadState.Idle
                    }
                    PunctuationModelManager.DownloadState.Idle -> {}
                }
                
                if (isDownloaded) {
                    Spacer(modifier = Modifier.height(12.dp))
                    
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "启用智能标点",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium
                            )
                            Text(
                                text = if (isEnabled) "使用 AI 模型预测标点" else "使用简单规则添加标点",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.7f)
                            )
                        }
                        Switch(
                            checked = isEnabled,
                            onCheckedChange = {
                                isEnabled = it
                                SettingsPreferences.setPunctuationModelEnabled(context, it)
                            },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = MaterialTheme.colorScheme.primary,
                                checkedTrackColor = MaterialTheme.colorScheme.primaryContainer
                            )
                        )
                    }
                    
                    Spacer(modifier = Modifier.height(8.dp))
                    
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        OutlinedButton(
                            onClick = {
                                punctuationManager.deleteModel()
                                isDownloaded = false
                                isEnabled = false
                                SettingsPreferences.setPunctuationModelEnabled(context, false)
                            },
                            colors = ButtonDefaults.outlinedButtonColors(
                                contentColor = MaterialTheme.colorScheme.error
                            )
                        ) {
                            Icon(
                                Icons.Default.Delete,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(Modifier.width(4.dp))
                            Text("删除")
                        }
                    }
                } else {
                    Spacer(modifier = Modifier.height(12.dp))
                    
                    val isDownloading = downloadState is PunctuationModelManager.DownloadState.Downloading
                    Button(
                        onClick = {
                            scope.launch {
                                downloadState = PunctuationModelManager.DownloadState.Downloading(0f, 0L, -1L)
                                punctuationManager.downloadModel { state ->
                                    downloadState = state
                                }
                            }
                        },
                        enabled = !isDownloading,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(
                            Icons.Default.Download,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(if (isDownloading) "下载中..." else "下载标点模型")
                    }
                }
            }
        }
        
        Spacer(modifier = Modifier.height(8.dp))
        
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
            )
        ) {
            Row(
                modifier = Modifier.padding(12.dp),
                verticalAlignment = Alignment.Top,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    Icons.Default.Warning,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.size(16.dp)
                )
                Text(
                    text = "标点预测模型基于 Transformer 架构，可自动为语音识别结果添加逗号、句号、问号、感叹号等标点符号",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline.copy(alpha = 0.7f)
                )
            }
        }
    }
}

@Composable
fun ModelCard(
    modelInfo: SherpaAsrEngine.AsrModelInfo,
    isDownloaded: Boolean,
    isSelected: Boolean,
    downloadState: ModelDownloadManager.DownloadState,
    onDownload: () -> Unit,
    onDelete: () -> Unit,
    onSelect: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected)
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.8f)
            else
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(
                            if (isDownloaded)
                                MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                            else
                                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.1f)
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Storage,
                        contentDescription = null,
                        tint = if (isDownloaded)
                            MaterialTheme.colorScheme.primary
                        else
                            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                        modifier = Modifier.size(24.dp)
                    )
                }

                Spacer(modifier = Modifier.width(16.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = modelInfo.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )

                    Spacer(modifier = Modifier.height(2.dp))

                    Text(
                        text = modelInfo.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )

                    Spacer(modifier = Modifier.height(4.dp))

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Surface(
                            shape = RoundedCornerShape(4.dp),
                            color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f)
                        ) {
                            Text(
                                text = modelInfo.size,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.secondary,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                        if (isDownloaded) {
                            Surface(
                                shape = RoundedCornerShape(4.dp),
                                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
                            ) {
                                Text(
                                    text = "已下载",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                        }
                    }
                }
            }

            when (downloadState) {
                is ModelDownloadManager.DownloadState.Downloading -> {
                    Spacer(modifier = Modifier.height(12.dp))
                    val animatedProgress by animateFloatAsState(
                        targetValue = downloadState.progress.coerceIn(0f, 1f),
                        label = "progress"
                    )
                    Column {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = "下载中...",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Text(
                                text = "${(downloadState.progress * 100).toInt()}%",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        LinearProgressIndicator(
                            progress = { animatedProgress },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(6.dp)
                                .clip(RoundedCornerShape(3.dp)),
                            color = MaterialTheme.colorScheme.primary,
                            trackColor = MaterialTheme.colorScheme.surfaceVariant
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        if (downloadState.totalBytes > 0) {
                            val df = DecimalFormat("#.##")
                            val downloaded = downloadState.bytesDownloaded.toFloat() / (1024 * 1024)
                            val total = downloadState.totalBytes.toFloat() / (1024 * 1024)
                            Text(
                                text = "${df.format(downloaded)} MB / ${df.format(total)} MB",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                            )
                        }
                    }
                }
                is ModelDownloadManager.DownloadState.Error -> {
                    Spacer(modifier = Modifier.height(8.dp))
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f)
                    ) {
                        Text(
                            text = downloadState.message,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(8.dp)
                        )
                    }
                }
                ModelDownloadManager.DownloadState.Complete -> {
                    Spacer(modifier = Modifier.height(8.dp))
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
                    ) {
                        Row(
                            modifier = Modifier.padding(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(
                                Icons.Default.CheckCircle,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(16.dp)
                            )
                            Text(
                                text = "下载完成",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }
                ModelDownloadManager.DownloadState.Idle -> {}
            }

            Spacer(modifier = Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (isDownloaded) {
                    OutlinedButton(
                        onClick = onDelete,
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = MaterialTheme.colorScheme.error
                        )
                    ) {
                        Icon(
                            Icons.Default.Delete,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(Modifier.width(4.dp))
                        Text("删除")
                    }

                    Spacer(Modifier.width(8.dp))

                    Button(
                        onClick = onSelect,
                        enabled = !isSelected,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (isSelected)
                                MaterialTheme.colorScheme.primary.copy(alpha = 0.3f)
                            else
                                MaterialTheme.colorScheme.primary
                        )
                    ) {
                        Icon(
                            if (isSelected) Icons.Default.Check else Icons.Default.Check,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(if (isSelected) "使用中" else "使用")
                    }
                } else {
                    val isDownloading = downloadState is ModelDownloadManager.DownloadState.Downloading
                    Button(
                        onClick = onDownload,
                        enabled = !isDownloading
                    ) {
                        Icon(
                            Icons.Default.Download,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(if (isDownloading) "下载中..." else "下载")
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AsrProviderCardModern(
    provider: AsrProvider,
    onClick: () -> Unit,
    enabled: Boolean = true
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        onClick = { if (enabled) onClick() },
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(
                            if (provider.iconRes != null)
                                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                            else if (enabled && provider.isConfigured)
                                MaterialTheme.colorScheme.primary
                            else
                                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f)
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    if (provider.iconRes != null) {
                        Icon(
                            painter = painterResource(provider.iconRes),
                            contentDescription = null,
                            modifier = Modifier.size(32.dp)
                        )
                    } else if (provider.icon != null) {
                        Icon(
                            imageVector = provider.icon,
                            contentDescription = null,
                            tint = if (enabled && provider.isConfigured)
                                MaterialTheme.colorScheme.onPrimary
                            else
                                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.width(16.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = provider.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = if (enabled)
                            MaterialTheme.colorScheme.onSurface
                        else
                            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )

                    Spacer(modifier = Modifier.height(4.dp))

                    Text(
                        text = provider.description,
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (enabled)
                            MaterialTheme.colorScheme.onSurfaceVariant
                        else
                            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                    )
                }

                if (enabled) {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = if (provider.isConfigured)
                            MaterialTheme.colorScheme.primary
                        else
                            MaterialTheme.colorScheme.surface.copy(alpha = 0.9f)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            if (provider.isConfigured) {
                                Icon(
                                    Icons.Default.Check,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onPrimary,
                                    modifier = Modifier.size(14.dp)
                                )
                                Text(
                                    text = "已配置",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onPrimary,
                                    fontWeight = FontWeight.Medium
                                )
                            } else {
                                Icon(
                                    Icons.Default.Key,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(14.dp)
                                )
                                Text(
                                    text = "待配置",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.width(8.dp))

                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = "进入设置",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            if (provider.features.isNotEmpty() && enabled) {
                Spacer(modifier = Modifier.height(12.dp))

                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    provider.features.forEach { feature ->
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
                        ) {
                            Text(
                                text = feature,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}
