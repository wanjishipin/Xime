package com.kingzcheung.xime.plugin

import android.content.Context
import android.util.Log
import com.kingzcheung.xime.plugin.core.api.EmojiPlugin
import com.kingzcheung.xime.plugin.core.api.PluginIcon
import com.kingzcheung.xime.plugin.core.model.PluginInfo
import com.kingzcheung.xime.plugin.core.runtime.PluginManager
import com.kingzcheung.xime.settings.SettingsPreferences
import com.kingzcheung.xime.ui.EmojiCategory
import com.kingzcheung.xime.ui.EmojiData
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
import java.util.zip.ZipFile

object ExtensionManager {
    private const val TAG = "ExtensionManager"
    
    private var initialized = false
    private var managerJob: Job = SupervisorJob()
    private val managerScope get() = CoroutineScope(managerJob + Dispatchers.IO)
    private val _emojiCategoriesFlow = MutableStateFlow<List<EmojiCategory>>(EmojiData.categories)
    val emojiCategoriesFlow: StateFlow<List<EmojiCategory>> = _emojiCategoriesFlow.asStateFlow()
    
    fun initialize(context: Context) {
        if (initialized) {
            Log.d(TAG, "Already initialized")
            return
        }
        if (!managerJob.isActive) {
            managerJob = SupervisorJob()
        }
        Log.d(TAG, "Initialized")
        initialized = true
        
        managerScope.launch {
            PluginManager.pluginInstancesFlow.collect { instances ->
                Log.d(TAG, "Plugin instances changed: ${instances.size} instances")
                loadEmojiDataFromPlugins(context)
            }
        }
    }
    
    private fun extractPluginIcon(context: Context, pluginId: String, plugin: EmojiPlugin, pluginInfo: PluginInfo?): PluginIcon? {
        val pluginIcon = try {
            plugin.getIcon()
        } catch (e: Exception) {
            Log.w(TAG, "getIcon not supported by ${pluginInfo?.name}")
            null
        }
        
        Log.d(TAG, "extractPluginIcon: pluginId=$pluginId, pluginIcon=$pluginIcon, pluginInfo?.path=${pluginInfo?.path}")
        
        if (pluginIcon == null) return null
        
        if (pluginIcon.text != null) {
            Log.d(TAG, "Using text icon: ${pluginIcon.text}")
            return PluginIcon(text = pluginIcon.text)
        }
        
        val assetName = pluginIcon.assetName
        if (assetName == null) {
            Log.d(TAG, "No assetName in pluginIcon")
            return null
        }
        
        Log.d(TAG, "Extracting icon asset: $assetName")
        
        val iconDir = File(context.filesDir, "plugin_icons")
        if (!iconDir.exists()) iconDir.mkdirs()
        
        val iconFile = File(iconDir, "${pluginId}_$assetName")
        
        Log.d(TAG, "Icon file path: ${iconFile.absolutePath}, exists: ${iconFile.exists()}")
        
        if (!iconFile.exists()) {
            val apkPath = pluginInfo?.path
            Log.d(TAG, "apkPath: $apkPath")
            if (apkPath != null) {
                try {
                    ZipFile(File(apkPath)).use { zip ->
                        val entries = zip.entries().asSequence().filter { it.name.startsWith("assets/") }.map { it.name }.toList()
                        Log.d(TAG, "Assets in APK: $entries")
                        
                        val entry = zip.entries().asSequence()
                            .firstOrNull { it.name == "assets/$assetName" }
                        Log.d(TAG, "Found entry: ${entry?.name}")
                        if (entry != null) {
                            zip.getInputStream(entry).use { input ->
                                iconFile.outputStream().use { output ->
                                    input.copyTo(output)
                                }
                            }
                            Log.d(TAG, "Extracted icon for $pluginId to ${iconFile.absolutePath}")
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to extract icon for $pluginId", e)
                }
            }
        }
        
        val result = if (iconFile.exists()) {
            Log.d(TAG, "Icon file exists, returning path: ${iconFile.absolutePath}")
            PluginIcon(assetName = iconFile.absolutePath)
        } else {
            Log.d(TAG, "Icon file does not exist, returning null")
            null
        }
        return result
    }
    
    suspend fun loadEmojiDataFromPlugins(context: Context) {
        Log.d(TAG, "Preloading emoji data from plugins")
        val pluginCategories = mutableListOf<EmojiCategory>()
        
        try {
            val emojiPlugins = getEnabledEmojiPlugins(context)
            Log.d(TAG, "Found ${emojiPlugins.size} emoji plugins for preload")
            
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
                            category = subCatName,
                            searchText = null,
                            topK = 100
                        )
                        if (emojiItems.isNotEmpty()) {
                            val layoutConfig = try {
                                plugin.getCategoryLayoutConfig(subCatName)
                            } catch (e: Exception) {
                                Log.w(TAG, "getCategoryLayoutConfig not supported by ${pluginInfo?.name}/$subCatName")
                                null
                            }
                            pluginCategories.add(
                                EmojiCategory(
                                    name = subCatName,
                                    icon = "🎭",
                                    pluginIcon = pluginIcon,
                                    emojis = emptyList(),
                                    isPlugin = true,
                                    pluginId = pluginId,
                                    emojiItems = emojiItems,
                                    layoutConfig = layoutConfig
                                )
                            )
                            Log.d(TAG, "Preloaded ${emojiItems.size} from ${pluginInfo?.name}/$subCatName")
                        } else {
                            Log.d(TAG, "No emoji items for ${pluginInfo?.name}/$subCatName")
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error preloading from ${pluginInfo?.name}", e)
                }
            }
            _emojiCategoriesFlow.value = pluginCategories + EmojiData.categories
            Log.d(TAG, "Emoji categories updated: ${_emojiCategoriesFlow.value.size} total")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to preload emoji data", e)
        }
    }
    
    fun reload(context: Context): Boolean {
        Log.d(TAG, "reload called")
        return try {
            managerScope.launch {
                val scanned = PluginManager.scanAndInstallSystemPlugins()
                Log.d(TAG, "Scanned $scanned new plugins")
                val loaded = PluginManager.loadEnabledPlugins()
                Log.d(TAG, "Loaded $loaded plugins")
            }
            PluginManager.isInitialized
        } catch (e: Exception) {
            Log.e(TAG, "reload failed", e)
            false
        }
    }
    
    fun getEmojiPlugins(): List<EmojiPlugin> {
        val all = PluginManager.getAllPluginInstances()
        Log.d(TAG, "All plugin instances: ${all.keys}")
        val emoji = all.values.mapNotNull { instance ->
            Log.d(TAG, "Checking instance: ${instance::class.simpleName}, interfaces: ${instance::class.java.interfaces.map { it.simpleName }}")
            if (instance is EmojiPlugin) instance else null
        }
        Log.d(TAG, "Emoji plugins found: ${emoji.size}")
        return emoji
    }
    
    fun getEnabledEmojiPlugins(context: Context): List<Pair<String, EmojiPlugin>> {
        return getEmojiPlugins().mapNotNull { plugin ->
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
                try { plugin.getEmojis(category, searchText, topK) }
                catch (e: Exception) { Log.e(TAG, "Get emojis failed", e); emptyList() }
            }.take(topK)
        }
    
    fun getAllInstalledPlugins(): List<PluginInfo> = PluginManager.getAllInstallPlugins()
    
    fun getPluginById(id: String): Any? = PluginManager.getPluginInstance(id)
    
    fun isInitialized(): Boolean = initialized && PluginManager.isInitialized
    
    fun hasEmojiPlugins(context: Context): Boolean = getEnabledEmojiPlugins(context).isNotEmpty()
    
    fun release() {
        initialized = false
        managerJob.cancel()
    }
}