package com.kingzcheung.xime.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import com.kingzcheung.xime.model.ModelManager
import com.kingzcheung.xime.speech.AsrBackendFactory
import com.kingzcheung.xime.speech.AsrModelManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 集成在语音转文本设置页内的离线模型状态卡片。
 * 模型由"模型中心"下载（filesDir/models/<id>/），本卡片展示安装状态，
 * 并提供已安装模型间的切换（切换即写偏好并卸载常驻引擎，下次语音生效）。
 * 未安装模型时整卡可点跳转扩展商店的模型页（[onNavigateToDownload]）。
 */
@Composable
internal fun OfflineModelCard(
    onNavigateToDownload: () -> Unit = {}
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val modelManager = remember { AsrModelManager(context) }

    var selectedModelId by remember {
        mutableStateOf(modelManager.getSelectedModelId())
    }
    var showModelPicker by remember { mutableStateOf(false) }

    // 模型信息来自"模型中心"远程索引；进入本页时确保索引已加载
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            ModelManager.loadFromRemote(context)
        }
    }

    val model = modelManager.getSelectedModelInfo()
        ?: AsrModelManager.DEFAULT_MODEL
    val downloaded = modelManager.isModelReady()

    // 已安装模型数 >1 时整卡可点，弹单选切换（与"在线服务"一致的交互）。
    // 不用 remember：索引异步加载完成后下次重组即能刷新。
    val models = modelManager.getAsrModels()
    val switchable = models.count { modelManager.isModelInstalled(it.id) } > 1

    Card(
        onClick = {
            when {
                switchable -> showModelPicker = true
                // 未安装：点击跳扩展商店下载（原来此处无反应，是张死卡片）
                !downloaded -> onNavigateToDownload()
            }
        },
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = if (downloaded)
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                    else
                        MaterialTheme.colorScheme.surfaceVariant
                ) {
                    Icon(
                        Icons.Default.Memory,
                        contentDescription = null,
                        tint = if (downloaded)
                            MaterialTheme.colorScheme.primary
                        else
                            MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(12.dp).size(24.dp)
                    )
                }

                Spacer(modifier = Modifier.width(12.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "离线语音识别",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = "${model.name} · ${model.size}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                if (downloaded) {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.primary
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Icon(
                                Icons.Default.Check,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onPrimary,
                                modifier = Modifier.size(14.dp)
                            )
                            Text(
                                text = "已安装",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onPrimary,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                }

                when {
                    switchable -> {
                        Text(
                            text = "切换",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Icon(
                            Icons.AutoMirrored.Filled.KeyboardArrowRight,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    // 未安装：右侧给出去下载入口，与整卡点击一致
                    !downloaded -> {
                        Text(
                            text = "去下载",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Icon(
                            Icons.AutoMirrored.Filled.KeyboardArrowRight,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            Text(
                text = if (downloaded)
                    "本地 Zipformer 流式识别，无网络也能用，识别在独立进程运行。"
                else
                    "尚未安装模型，点击前往「扩展商店」下载「${model.name}」。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            // 已安装模型切换：与"在线服务"一致的单选弹窗交互。
            // 切换后卸载常驻引擎，下一次语音会话即按新模型加载。
            if (showModelPicker) {
                SettingsSingleChoiceDialog(
                    title = "选择离线语音模型",
                    options = models
                        .filter { modelManager.isModelInstalled(it.id) }
                        .map {
                            SettingsChoiceOption(
                                id = it.id,
                                title = it.name,
                                subtitle = "${it.size} · ${it.language}"
                            )
                        },
                    selectedId = selectedModelId,
                    onSelect = { id ->
                        showModelPicker = false
                        if (id != selectedModelId) {
                            modelManager.setModel(id)
                            selectedModelId = id
                            scope.launch(Dispatchers.IO) {
                                AsrBackendFactory.releaseModel()
                            }
                        }
                    },
                    onDismiss = { showModelPicker = false }
                )
            }
        }
    }
}
