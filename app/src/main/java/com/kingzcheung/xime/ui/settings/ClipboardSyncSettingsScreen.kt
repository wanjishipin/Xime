package com.kingzcheung.xime.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
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
import com.kingzcheung.xime.plugin.ActivePluginSelection
import com.kingzcheung.xime.plugin.ExtensionManager
import com.kingzcheung.xime.plugin.core.api.ClipboardSyncPlugin
import com.kingzcheung.xime.plugin.core.model.PluginCategory
import com.kingzcheung.xime.plugin.core.model.PluginInfo
import com.kingzcheung.xime.plugin.core.runtime.PluginManager
import com.kingzcheung.xime.settings.SettingsPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * 剪贴板同步内页：只做「选服务 + 配服务」。
 *
 * 总开关不在这里——已上提到上一级「剪贴板」页（开关属于"是否启用这个能力"，
 * 与"用哪个插件、怎么配"是两件事，混在一页会让开关藏在两层之下）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ClipboardSyncSettingsContent(
    onBack: () -> Unit,
    onNavigateToMarket: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val syncEnabled = remember { SettingsPreferences.isClipboardSyncEnabled(context) }
    val syncPlugins = remember { ExtensionManager.getEnabledClipboardSyncPlugins(context) }
    // 与引擎同一判定规则（ActivePluginSelection）：偏好为空或指向未启用插件时回退首个已启用项
    var selectedPluginId by remember {
        mutableStateOf(
            ActivePluginSelection.resolve(
                SettingsPreferences.getClipboardSyncPluginId(context),
                syncPlugins.map { it.first }
            )
        )
    }
    // 当前选中的插件实例（切换时在 IO 协程里 launch 完成后更新，驱动表单立即渲染新插件）
    var activePlugin by remember {
        mutableStateOf(
            syncPlugins.firstOrNull { it.first == selectedPluginId } ?: syncPlugins.firstOrNull()
        )
    }
    val installedPlugins = remember { ExtensionManager.getAllInstalledPlugins() }
    val clipboardPlugins = remember { installedPlugins.filter { it.category == PluginCategory.CLIPBOARD_SYNC } }
    // 切换服务走单选对话框（插件多时页面长度恒定，不随插件数量变长）
    var showServicePicker by remember { mutableStateOf(false) }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            TopAppBar(
                title = { Text("剪贴板同步") },
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
            if (!syncEnabled) {
                Text(
                    text = "同步已关闭：在上一级「剪贴板」页打开「剪贴板同步」开关后生效。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            SettingsSection(
                title = "同步服务",
                content = {
                    if (clipboardPlugins.isEmpty()) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Text(
                                text = "未安装剪贴板同步插件，请先在扩展商店安装后再配置。",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Button(
                                onClick = onNavigateToMarket,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("前往扩展商店")
                            }
                        }
                    } else {
                        // 入口始终可见：只装 1 个插件时也能点开确认候选/协议与配置，
                        // 藏起来会让"当前生效的是谁、能不能换"在页面上无处可查
                        CurrentServiceItem(
                            pluginInfo = installedPlugins.find { it.id == activePlugin?.first },
                            pluginId = activePlugin?.first,
                            plugin = activePlugin?.second,
                            onClick = { showServicePicker = true }
                        )
                    }
                }
            )

            activePlugin?.let { selected ->
                val pluginId = selected.first
                val pluginName = installedPlugins.find { it.id == pluginId }?.name ?: pluginId
                PluginConfigFormScreen(
                    pluginId = pluginId,
                    plugin = selected.second,
                    pluginName = pluginName,
                    onBack = {},
                    embedded = true,
                    // 配置卡标题带上插件名：明确"在配谁"
                    fallbackSectionTitle = "$pluginName 配置"
                )
            }
        }
    }

    if (showServicePicker) {
        // 切换服务：单选弹窗（点行即切换并关闭）
        SettingsSingleChoiceDialog(
            title = "选择同步服务",
            options = clipboardPlugins.map { plugin ->
                val capabilities = plugin.capabilities?.clipboardSync
                SettingsChoiceOption(
                    id = plugin.id,
                    title = plugin.name,
                    subtitle = buildString {
                        append(plugin.description)
                        if (!capabilities?.protocols.isNullOrEmpty()) {
                            append("\n同步协议: ")
                            append(capabilities?.protocols?.joinToString("、"))
                        }
                        if (capabilities?.attachments != true) {
                            append("\n该插件不支持图片同步（仅文本）")
                        }
                    }
                )
            },
            selectedId = selectedPluginId,
            onSelect = { pickedId ->
                showServicePicker = false
                if (pickedId != selectedPluginId) {
                    selectedPluginId = pickedId
                    SettingsPreferences.setClipboardSyncPluginId(context, pickedId)
                    scope.launch(Dispatchers.IO) {
                        // 单选激活：同一时间只运行 1 个剪贴板同步插件
                        clipboardPlugins
                            .filter { it.id != pickedId }
                            .forEach {
                                SettingsPreferences.setPluginEnabled(context, it.id, false)
                                PluginManager.unloadPlugin(it.id)
                            }
                        SettingsPreferences.setPluginEnabled(context, pickedId, true)
                        PluginManager.launchPlugin(pickedId)
                        // 重新获取已启用实例，驱动表单切换到新插件
                        activePlugin = ExtensionManager.getEnabledClipboardSyncPlugins(context)
                            .firstOrNull { it.first == pickedId }
                    }
                }
            },
            onDismiss = { showServicePicker = false }
        )
    }
}

/**
 * 当前生效的同步服务（一行）。
 *
 * 切换入口收进对话框（[SyncServicePickerDialog] 的等价内联实现）：插件多时页面长度恒定，
 * 这里只回答"现在用的是谁、能不能同步图片"。整行**始终可点**——只装一个插件时，
 * 这个入口是页面上唯一能确认候选/协议与配置的地方，藏掉它会让"当前生效的是谁"无处可查。
 */
@Composable
private fun CurrentServiceItem(
    pluginInfo: PluginInfo?,
    pluginId: String?,
    plugin: ClipboardSyncPlugin?,
    onClick: () -> Unit,
) {
    val context = LocalContext.current
    val icon = remember(pluginId, plugin) {
        if (pluginId == null || plugin == null) null
        else ExtensionManager.extractPluginIcon(context, pluginId, plugin, pluginInfo)
    }
    val capabilities = pluginInfo?.capabilities?.clipboardSync
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        PluginIconView(
            icon = icon,
            category = PluginCategory.CLIPBOARD_SYNC
        )
        Spacer(modifier = Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = pluginInfo?.name ?: pluginId ?: "未选择同步服务",
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium
            )
            if (!pluginInfo?.description.isNullOrBlank()) {
                Text(
                    text = pluginInfo.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            val ability = if (capabilities?.attachments == true) "文字与图片" else "仅文字"
            Text(
                text = buildString {
                    if (capabilities?.protocols.isNullOrEmpty()) {
                        append(ability)
                    } else {
                        append("同步协议: ")
                        append(capabilities?.protocols?.joinToString("、"))
                        append(" · ")
                        append(ability)
                    }
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary
            )
        }
        Text(
            text = if (pluginId == null) "选择" else "切换",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary
        )
        Icon(
            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
