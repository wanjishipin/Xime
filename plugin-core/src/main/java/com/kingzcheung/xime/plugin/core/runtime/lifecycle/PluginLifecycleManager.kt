package com.kingzcheung.xime.plugin.core.runtime.lifecycle

import android.app.Application
import android.util.Log
import com.kingzcheung.xime.plugin.core.api.IPluginEntryClass
import com.kingzcheung.xime.plugin.core.js.JsAsrPluginAdapter
import com.kingzcheung.xime.plugin.core.js.JsBackupPluginAdapter
import com.kingzcheung.xime.plugin.core.js.JsClipboardSyncPluginAdapter
import com.kingzcheung.xime.plugin.core.js.JsEmojiPluginAdapter
import com.kingzcheung.xime.plugin.core.js.JsPluginAdapter
import com.kingzcheung.xime.plugin.core.js.JsScriptRuntime
import com.kingzcheung.xime.plugin.core.js.JsToolPluginAdapter
import com.kingzcheung.xime.plugin.core.model.PluginCategory
import com.kingzcheung.xime.plugin.core.model.PluginContext
import com.kingzcheung.xime.plugin.core.model.PluginInfo
import com.kingzcheung.xime.plugin.core.runtime.PluginManager
import com.kingzcheung.xime.plugin.core.runtime.installer.InstallerManager
import com.kingzcheung.xime.plugin.core.runtime.installer.PluginRegistry
import com.kingzcheung.xime.plugin.core.runtime.loader.LoadedPluginInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap

class PluginLifecycleManager(
    private val application: Application,
    private val pluginRegistry: PluginRegistry,
    private val installerManager: InstallerManager,
    private val loadedPlugins: ConcurrentHashMap<String, LoadedPluginInfo>,
    private val pluginInstances: ConcurrentHashMap<String, IPluginEntryClass>
) {

    companion object {
        private const val TAG = "PluginLifecycle"
    }

    suspend fun launchPlugin(pluginId: String): Boolean = withContext(Dispatchers.IO) {
        try {
            if (loadedPlugins.containsKey(pluginId)) {
                return@withContext reloadPlugin(pluginId)
            }
            launchSinglePlugin(pluginId)
        } catch (e: Throwable) {
            if (loadedPlugins.containsKey(pluginId)) {
                unloadPlugin(pluginId)
            }
            false
        }
    }

    suspend fun unloadPlugin(pluginId: String) = withContext(Dispatchers.IO) {
        if (!loadedPlugins.containsKey(pluginId)) return@withContext

        pluginInstances[pluginId]?.let { instance ->
            try {
                instance.onUnload()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        loadedPlugins.remove(pluginId)
        pluginInstances.remove(pluginId)
    }

    suspend fun loadEnabledPlugins(): Int = withContext(Dispatchers.IO) {
        Log.d(TAG, "loadEnabledPlugins called")
        val allPlugins = pluginRegistry.getAllPlugins()
        Log.d(TAG, "All plugins from registry: ${allPlugins.map { "${it.id}(enabled=${it.enabled})" }}")

        val hostVersion = com.kingzcheung.xime.plugin.core.util.VersionUtil.getHostVersionName(application)
        val enabledPlugins = allPlugins.filter { plugin ->
            if (!plugin.enabled || loadedPlugins.containsKey(plugin.id)) return@filter false
            if (!plugin.supportsPlatform(PluginInfo.PLATFORM_ANDROID)) {
                Log.w(TAG, "Plugin ${plugin.id} 目标平台为 ${plugin.platforms}，非当前平台，跳过加载")
                return@filter false
            }
            val compatible = com.kingzcheung.xime.plugin.core.util.VersionUtil.isHostSupported(
                hostVersion ?: "", plugin.minHostVersion, plugin.maxHostVersion
            )
            if (!compatible) {
                Log.w(TAG, "Plugin ${plugin.id} 不兼容当前主应用版本，跳过加载")
            }
            compatible
        }
        Log.d(TAG, "Enabled plugins to load: ${enabledPlugins.map { it.id }}")

        if (enabledPlugins.isEmpty()) return@withContext 0

        var successCount = 0
        for (plugin in enabledPlugins) {
            if (launchSinglePlugin(plugin.id)) {
                successCount++
            } else {
                Log.w(TAG, "Failed to load: ${plugin.id}")
            }
        }
        Log.d(TAG, "loadEnabledPlugins completed: $successCount loaded")
        successCount
    }

    private suspend fun launchSinglePlugin(pluginId: String): Boolean {
        val pluginInfo = pluginRegistry.getPluginById(pluginId)
        if (pluginInfo == null) {
            Log.w(TAG, "Plugin info not found: $pluginId")
            return false
        }
        val hostVersion = com.kingzcheung.xime.plugin.core.util.VersionUtil.getHostVersionName(application)
        if (!com.kingzcheung.xime.plugin.core.util.VersionUtil.isHostSupported(
                hostVersion ?: "", pluginInfo.minHostVersion, pluginInfo.maxHostVersion
            )
        ) {
            Log.w(TAG, "Plugin $pluginId 不兼容当前主应用版本，拒绝加载")
            return false
        }
        if (!pluginInfo.supportsPlatform(PluginInfo.PLATFORM_ANDROID)) {
            Log.w(TAG, "Plugin $pluginId 目标平台为 ${pluginInfo.platforms}，拒绝加载")
            return false
        }

        val loadedPlugin = loadPlugin(pluginInfo)
        if (loadedPlugin == null) {
            Log.w(TAG, "Failed to load plugin: $pluginId")
            return false
        }
        loadedPlugins[pluginId] = loadedPlugin

        val instance = instantiatePlugin(loadedPlugin)
        if (instance == null) {
            Log.w(TAG, "Failed to instantiate plugin: $pluginId")
            unloadPlugin(pluginId)
            return false
        }
        pluginInstances[pluginId] = instance
        Log.d(TAG, "Loaded: $pluginId")

        return true
    }

    private suspend fun reloadPlugin(pluginId: String): Boolean {
        unloadPlugin(pluginId)
        return launchSinglePlugin(pluginId)
    }

    private fun loadPlugin(plugin: PluginInfo): LoadedPluginInfo? {
        return try {
            val entryFile = File(plugin.path)
            if (!entryFile.exists()) {
                Log.w(TAG, "Plugin entry script not found: ${plugin.path}")
                return null
            }
            val pluginDir = entryFile.parentFile ?: File(plugin.path).parentFile
            val runtime = JsScriptRuntime(
                pluginId = plugin.id,
                pluginDir = pluginDir,
                entryScript = plugin.entryScript ?: "main.js",
                configStore = PluginManager.configStoreFactory.create(application, plugin.id),
                wsHostApi = PluginManager.wsHostApiFactory?.invoke(plugin.id),
                httpHostApi = PluginManager.httpHostApiFactory?.invoke(plugin.id),
                cryptoHostApi = PluginManager.cryptoHostApiFactory?.invoke(),
                sseHostApi = PluginManager.sseHostApiFactory?.invoke(plugin.id),
                // 数据类 API 按 manifest 能力声明门禁注入：未声明连实例都不创建（host 表不挂）
                quickSendHostApi = if (plugin.capabilities?.quickSendRead == true) {
                    PluginManager.quickSendHostApiFactory?.invoke(plugin.id)
                } else null,
                clipboardHostApi = if (plugin.capabilities?.clipboardRead == true) {
                    PluginManager.clipboardHostApiFactory?.invoke(plugin.id)
                } else null,
                // host.asr 上行表仅 speech 型插件注入
                injectAsr = plugin.type == "speech"
            )
            // 按能力声明启用下行事件通道：未声明 events 的插件零开销、零行为变化。
            runtime.initEvents(plugin.capabilities?.events?.toSet() ?: emptySet())
            LoadedPluginInfo(pluginInfo = plugin, script = runtime)
        } catch (e: Exception) {
            Log.e(TAG, "loadPlugin failed for ${plugin.id}", e)
            null
        }
    }

    private fun instantiatePlugin(loadedPlugin: LoadedPluginInfo): IPluginEntryClass? {
        val plugin = loadedPlugin.pluginInfo
        return try {
            val pluginContext = PluginContext(
                application = application,
                pluginInfo = plugin,
                configStore = PluginManager.configStoreFactory.create(application, plugin.id)
            )
            val adapter: JsPluginAdapter = when (plugin.category) {
                PluginCategory.ASR ->
                    JsAsrPluginAdapter(
                        runtime = loadedPlugin.script ?: return null,
                        pluginContext = pluginContext
                    )
                PluginCategory.EMOJI ->
                    JsEmojiPluginAdapter(
                        runtime = loadedPlugin.script ?: return null,
                        pluginContext = pluginContext
                    )
                PluginCategory.CLIPBOARD_SYNC ->
                    JsClipboardSyncPluginAdapter(
                        runtime = loadedPlugin.script ?: return null,
                        pluginContext = pluginContext
                    )
                PluginCategory.BACKUP ->
                    JsBackupPluginAdapter(
                        runtime = loadedPlugin.script ?: return null,
                        pluginContext = pluginContext
                    )
                PluginCategory.TOOL ->
                    JsToolPluginAdapter(
                        runtime = loadedPlugin.script ?: return null,
                        pluginContext = pluginContext
                    )
                else ->
                    JsPluginAdapter(
                        runtime = loadedPlugin.script ?: return null,
                        pluginContext = pluginContext
                    )
            }
            adapter.onLoad(pluginContext)
            adapter
        } catch (e: Exception) {
            Log.e(TAG, "Failed to instantiate JS plugin ${plugin.id}", e)
            null
        }
    }
}
