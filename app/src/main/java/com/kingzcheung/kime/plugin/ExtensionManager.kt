package com.kingzcheung.kime.plugin

import android.content.Context
import android.util.Log
import com.kingzcheung.kime.plugin.core.api.EmojiPlugin
import com.kingzcheung.kime.plugin.core.api.PredictionPlugin
import com.kingzcheung.kime.plugin.core.api.SpeechPlugin
import com.kingzcheung.kime.plugin.core.model.PluginInfo
import com.kingzcheung.kime.plugin.core.runtime.PluginManager
import com.kingzcheung.kime.settings.SettingsPreferences
import com.kingzcheung.kime.ui.EmojiCategory
import com.kingzcheung.kime.ui.EmojiData
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

object ExtensionManager {
    private const val TAG = "ExtensionManager"
    
    private var initialized = false
    private val _emojiCategoriesFlow = MutableStateFlow<List<EmojiCategory>>(EmojiData.categories)
    val emojiCategoriesFlow: StateFlow<List<EmojiCategory>> = _emojiCategoriesFlow.asStateFlow()
    
    fun initialize(context: Context) {
        if (initialized) {
            Log.d(TAG, "Already initialized")
            return
        }
        Log.d(TAG, "Initialized")
        initialized = true
        
        kotlinx.coroutines.GlobalScope.launch(Dispatchers.IO) {
            PluginManager.pluginInstancesFlow.collect { instances ->
                Log.d(TAG, "Plugin instances changed: ${instances.size} instances")
                // 无论是增加还是减少，都重新加载表情数据
                loadEmojiDataFromPlugins(context)
            }
        }
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
                    val emojiItems = plugin.getEmojis(category = null, searchText = null, topK = 100)
                    if (emojiItems.isNotEmpty()) {
                        pluginCategories.add(
                            EmojiCategory(
                                name = pluginInfo?.name ?: "表情",
                                icon = "🎭",
                                emojis = emptyList(),
                                isPlugin = true,
                                pluginId = pluginId,
                                emojiItems = emojiItems
                            )
                        )
                        Log.d(TAG, "Preloaded ${emojiItems.size} from ${pluginInfo?.name}")
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
            kotlinx.coroutines.GlobalScope.launch(Dispatchers.IO) {
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
    
    fun getSpeechPlugins(): List<SpeechPlugin> {
        val all = PluginManager.getAllPluginInstances()
        Log.d(TAG, "All plugin instances: ${all.keys}")
        val speech = all.values.mapNotNull { instance ->
            Log.d(TAG, "Checking instance: ${instance::class.simpleName}, interfaces: ${instance::class.java.interfaces.map { it.simpleName }}")
            if (instance is SpeechPlugin) instance else null
        }
        Log.d(TAG, "Speech plugins found: ${speech.size}")
        return speech
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
    
    fun getPredictionPlugins(): List<PredictionPlugin> {
        val all = PluginManager.getAllPluginInstances()
        Log.d(TAG, "All plugin instances: ${all.keys}")
        val prediction = all.values.mapNotNull { instance ->
            Log.d(TAG, "Checking instance: ${instance::class.simpleName}, interfaces: ${instance::class.java.interfaces.map { it.simpleName }}")
            if (instance is PredictionPlugin) instance else null
        }
        Log.d(TAG, "Prediction plugins found: ${prediction.size}")
        return prediction
    }
    
    fun getEnabledEmojiPlugins(context: Context): List<Pair<String, EmojiPlugin>> {
        return getEmojiPlugins().mapNotNull { plugin ->
            val pluginId = getPluginId(plugin)
            if (pluginId.isNotEmpty() && SettingsPreferences.isPluginEnabled(context, pluginId)) {
                Pair(pluginId, plugin)
            } else null
        }
    }
    
    fun getEnabledPredictionPlugins(context: Context): List<Pair<String, PredictionPlugin>> {
        return getPredictionPlugins().mapNotNull { plugin ->
            val pluginId = getPluginId(plugin)
            if (pluginId.isNotEmpty() && SettingsPreferences.isPluginEnabled(context, pluginId)) {
                Pair(pluginId, plugin)
            } else null
        }
    }
    
    fun getEnabledSpeechPlugins(context: Context): List<Pair<String, SpeechPlugin>> {
        return getSpeechPlugins().mapNotNull { plugin ->
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
    
    suspend fun predict(context: Context, inputText: String, topK: Int = 5): List<String> =
        withContext(Dispatchers.Default) {
            getEnabledPredictionPlugins(context).flatMap { (_, plugin) ->
                try { plugin.predict(inputText, topK).map { it.text } }
                catch (e: Exception) { Log.e(TAG, "Prediction failed", e); emptyList() }
            }.distinct().take(topK)
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
    
    fun hasSpeechPlugins(context: Context): Boolean = getEnabledSpeechPlugins(context).isNotEmpty()
    fun hasEmojiPlugins(context: Context): Boolean = getEnabledEmojiPlugins(context).isNotEmpty()
    fun hasPredictionPlugins(context: Context): Boolean = getEnabledPredictionPlugins(context).isNotEmpty()
    
    fun release() { initialized = false }
}