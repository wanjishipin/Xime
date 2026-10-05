package com.kingzcheung.xime.plugin

import android.content.Context
import android.util.Log
import com.kingzcheung.xime.plugin.core.api.AsrPlugin
import com.kingzcheung.xime.plugin.core.api.BackupPlugin
import com.kingzcheung.xime.plugin.core.api.ClipboardSyncPlugin
import com.kingzcheung.xime.plugin.core.api.EmojiPlugin
import com.kingzcheung.xime.plugin.core.api.IPluginEntryClass
import com.kingzcheung.xime.plugin.core.api.PluginIcon
import com.kingzcheung.xime.plugin.core.js.ws.NetworkPolicy
import com.kingzcheung.xime.plugin.core.model.PluginCategory
import com.kingzcheung.xime.plugin.core.model.PluginInfo
import com.kingzcheung.xime.plugin.core.runtime.PluginManager
import com.kingzcheung.xime.settings.SettingsPreferences
import com.kingzcheung.xime.data.EmojiCategory
import com.kingzcheung.xime.data.EmojiData
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

object ExtensionManager {
    private const val TAG = "ExtensionManager"
    
    private var initialized = false
    private var managerJob: Job = SupervisorJob()
    private val managerScope get() = CoroutineScope(managerJob + Dispatchers.IO)
    private val _emojiCategoriesFlow = MutableStateFlow<List<EmojiCategory>>(EmojiData.categories)
    val emojiCategoriesFlow: StateFlow<List<EmojiCategory>> = _emojiCategoriesFlow.asStateFlow()
    
    fun initialize(context: Context) {
        if (initialized) {
            return
        }
        if (!managerJob.isActive) {
            managerJob = SupervisorJob()
        }
        initialized = true
        
        managerScope.launch {
            PluginManager.pluginInstancesFlow.collect { _ ->
                loadEmojiDataFromPlugins(context)
            }
        }
    }
    
    fun extractPluginIcon(context: Context, pluginId: String, plugin: IPluginEntryClass, pluginInfo: PluginInfo?): PluginIcon? {
        val pluginIcon = try {
            plugin.getIcon()
        } catch (e: Exception) {
            Log.w(TAG, "getIcon not supported by ${pluginInfo?.name}")
            null
        }
        
        if (pluginIcon == null) return null
        
        if (pluginIcon.text != null) {
            return PluginIcon(text = pluginIcon.text)
        }
        
        val assetName = pluginIcon.assetName
        if (assetName == null ||
            !com.kingzcheung.xime.plugin.core.runtime.installer.InstallerManager.isValidResourcePath(assetName)
        ) {
            return null
        }

        val iconDir = File(context.filesDir, "plugin_icons")
        if (!iconDir.exists()) iconDir.mkdirs()

        val iconFile = File(iconDir, "${pluginId}_$assetName")

        if (!iconFile.exists()) {
            // Lua 插件资源在 resources/ 目录（path 指向入口脚本，其父目录为插件目录）
            val resourceFile = pluginInfo?.path
                ?.let { File(it).parentFile }
                ?.let { File(it, "resources/$assetName") }
            if (resourceFile != null && resourceFile.exists()) {
                try {
                    iconFile.parentFile?.mkdirs()
                    resourceFile.copyTo(iconFile, overwrite = true)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to extract icon for $pluginId", e)
                }
            }
        }
        
        val result = if (iconFile.exists()) {
            PluginIcon(assetName = iconFile.absolutePath)
        } else {
            null
        }
        return result
    }
    
    /**
     * 提取插件工具栏按钮图标（manifest.toolbarButtons[].icon，resources/ 下相对路径）
     * 到本地 plugin_icons/ 目录并返回本地文件路径；无图标或提取失败返回 null（宿主用 label 兜底）。
     */
    fun extractToolbarButtonIcon(context: Context, pluginId: String, pluginInfo: PluginInfo, iconName: String?): PluginIcon? {
        if (iconName.isNullOrBlank() ||
            !com.kingzcheung.xime.plugin.core.runtime.installer.InstallerManager.isValidResourcePath(iconName)
        ) {
            return null
        }
        val iconDir = File(context.filesDir, "plugin_icons")
        if (!iconDir.exists()) iconDir.mkdirs()
        val iconFile = File(iconDir, "${pluginId}_tb_$iconName")

        if (!iconFile.exists()) {
            // Lua 插件资源在 resources/ 目录（path 指向入口脚本，其父目录为插件目录）
            val resourceFile = pluginInfo.path
                ?.let { File(it).parentFile }
                ?.let { File(it, "resources/$iconName") }
            if (resourceFile != null && resourceFile.exists()) {
                try {
                    iconFile.parentFile?.mkdirs()
                    resourceFile.copyTo(iconFile, overwrite = true)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to extract toolbar icon for $pluginId", e)
                }
            }
        }

        return if (iconFile.exists()) PluginIcon(assetName = iconFile.absolutePath) else null
    }

    /** manifest 顶层 icon 识别为图片文件名（常见图片扩展名）。 */
    private val IMAGE_EXTENSION_REGEX = Regex("(?i)\\.(png|jpe?g|gif|webp|svg)$")

    /**
     * 解析插件 manifest 顶层 icon（[PluginInfo.manifestIcon]）：文字（如 "译"）直接作为文本图标，
     * 图片文件名则从插件 resources/ 提取到本地 plugin_icons/。
     * 供工具栏按钮无专属图标时兜底。
     */
    fun extractPluginManifestIcon(context: Context, pluginInfo: PluginInfo): PluginIcon? {
        val manifestIcon = pluginInfo.manifestIcon ?: return null
        if (!IMAGE_EXTENSION_REGEX.containsMatchIn(manifestIcon)) {
            return PluginIcon(text = manifestIcon)
        }
        return extractToolbarButtonIcon(context, pluginInfo.id, pluginInfo, manifestIcon)
    }

    suspend fun loadEmojiDataFromPlugins(context: Context) {
        val pluginCategories = mutableListOf<EmojiCategory>()
        
        try {
            val emojiPlugins = getEnabledEmojiPlugins(context)
            
            emojiPlugins.forEach { (pluginId, plugin) ->
                val pluginInfo = getAllInstalledPlugins().firstOrNull { it.id == pluginId }
                try {
                    val subCategoryNames = try {
                        plugin.getCategories()
                    } catch (e: Exception) {
                        Log.w(TAG, "getCategories not supported by ${pluginInfo?.name}")
                        listOf(pluginInfo?.name ?: "表情")
                    }

                    if (subCategoryNames.isEmpty()) {
                        Log.w(TAG, "No categories from ${pluginInfo?.name}, skipping")
                        return@forEach
                    }

                    val pluginIcon = extractPluginIcon(context, pluginId, plugin, pluginInfo)

                    for (subCatName in subCategoryNames) {
                        val emojiItems = plugin.getEmojis(
                            com.kingzcheung.xime.plugin.core.api.EmojiQuery(
                                category = subCatName,
                                keyword = null,
                                topK = 100
                            )
                        )
                        if (emojiItems.isNotEmpty()) {
                            val emojiCap = pluginInfo?.capabilities?.emoji
                            pluginCategories.add(
                                EmojiCategory(
                                    name = subCatName,
                                    icon = "🎭",
                                    pluginIcon = pluginIcon,
                                    emojis = emptyList(),
                                    isPlugin = true,
                                    pluginId = pluginId,
                                    emojiItems = emojiItems,
                                    layoutColumns = emojiCap?.columns ?: 8,
                                    layoutItemHeightDp = emojiCap?.itemHeightDp ?: 40
                                )
                            )
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error preloading from ${pluginInfo?.name}", e)
                }
            }
            _emojiCategoriesFlow.value = pluginCategories + EmojiData.categories
        } catch (e: Exception) {
            Log.e(TAG, "Failed to preload emoji data", e)
        }
    }
    
    fun reload(context: Context): Boolean {
        return try {
            managerScope.launch {
                PluginManager.loadEnabledPlugins()
            }
            PluginManager.isInitialized
        } catch (e: Exception) {
            Log.e(TAG, "reload failed", e)
            false
        }
    }
    
    fun getEmojiPlugins(): List<EmojiPlugin> {
        val all = PluginManager.getAllPluginInstances()
        return all.values.mapNotNull { instance ->
            if (instance is EmojiPlugin) instance else null
        }
    }
    
    fun getEnabledEmojiPlugins(context: Context): List<Pair<String, EmojiPlugin>> {
        return getEmojiPlugins().mapNotNull { plugin ->
            val pluginId = getPluginId(plugin)
            if (pluginId.isNotEmpty() && SettingsPreferences.isPluginEnabled(context, pluginId)) {
                Pair(pluginId, plugin)
            } else null
        }
    }

    fun getAsrPlugins(): List<AsrPlugin> {
        val all = PluginManager.getAllPluginInstances()
        return all.values.mapNotNull { instance ->
            if (instance is AsrPlugin) instance else null
        }
    }

    fun getEnabledAsrPlugins(context: Context): List<Pair<String, AsrPlugin>> {
        return getAsrPlugins().mapNotNull { plugin ->
            val pluginId = getPluginId(plugin)
            if (pluginId.isNotEmpty() && SettingsPreferences.isPluginEnabled(context, pluginId)) {
                Pair(pluginId, plugin)
            } else null
        }
    }

    fun getClipboardSyncPlugins(): List<ClipboardSyncPlugin> {
        val all = PluginManager.getAllPluginInstances()
        return all.values.mapNotNull { instance ->
            if (instance is ClipboardSyncPlugin) instance else null
        }
    }

    fun getEnabledClipboardSyncPlugins(context: Context): List<Pair<String, ClipboardSyncPlugin>> {
        return getClipboardSyncPlugins().mapNotNull { plugin ->
            val pluginId = getPluginId(plugin)
            if (pluginId.isNotEmpty() && SettingsPreferences.isPluginEnabled(context, pluginId)) {
                Pair(pluginId, plugin)
            } else null
        }
    }

    fun getBackupPlugins(): List<BackupPlugin> {
        val all = PluginManager.getAllPluginInstances()
        return all.values.mapNotNull { instance ->
            if (instance is BackupPlugin) instance else null
        }
    }

    fun getEnabledBackupPlugins(context: Context): List<Pair<String, BackupPlugin>> {
        return getBackupPlugins().mapNotNull { plugin ->
            val pluginId = getPluginId(plugin)
            if (pluginId.isNotEmpty() && SettingsPreferences.isPluginEnabled(context, pluginId)) {
                Pair(pluginId, plugin)
            } else null
        }
    }
    
    private fun getPluginId(plugin: Any): String {
        return PluginManager.getAllPluginInstances().entries
            .firstOrNull { it.value == plugin }?.key ?: ""
    }
    
    suspend fun getEmojis(context: Context, category: String? = null, searchText: String? = null, topK: Int = 100) =
        withContext(Dispatchers.Default) {
            getEnabledEmojiPlugins(context).flatMap { (_, plugin) ->
                try {
                    plugin.getEmojis(
                        com.kingzcheung.xime.plugin.core.api.EmojiQuery(
                            category = category,
                            keyword = searchText,
                            topK = topK
                        )
                    )
                } catch (e: Exception) {
                    Log.e(TAG, "Get emojis failed", e); emptyList()
                }
            }.take(topK)
        }
    
    fun getAllInstalledPlugins(): List<PluginInfo> = PluginManager.getAllInstallPlugins()

    /** 插件配置中解析出的 HTTP(S) 域名（用户填写的自定义服务器地址），供网络授权 UI 展示。 */
    fun getConfiguredNetworkHosts(context: Context, pluginId: String): List<String> {
        return try {
            val store = PluginConfigStoreImpl(
                context.applicationContext as android.app.Application,
                pluginId
            )
            store.keys()
                .mapNotNull { key -> store.get(key)?.let { NetworkPolicy.extractHttpHost(it) } }
                .distinct()
        } catch (e: Exception) {
            Log.e(TAG, "getConfiguredNetworkHosts failed for $pluginId", e)
            emptyList()
        }
    }

    fun getPluginsByCategory(category: PluginCategory): List<PluginInfo> =
        getAllInstalledPlugins().filter { it.category == category }

    fun getEnabledPluginsByCategory(context: Context, category: PluginCategory): List<PluginInfo> =
        getPluginsByCategory(category).filter { SettingsPreferences.isPluginEnabled(context, it.id) }
    
    fun getPluginById(id: String): Any? = PluginManager.getPluginInstance(id)
    
    fun isInitialized(): Boolean = initialized && PluginManager.isInitialized
    
    fun hasEmojiPlugins(context: Context): Boolean = getEnabledEmojiPlugins(context).isNotEmpty()
    
    fun release() {
        initialized = false
        managerJob.cancel()
    }
}