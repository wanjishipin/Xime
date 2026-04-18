package com.kingzcheung.kime.plugin.core.runtime.lifecycle

import android.app.Application
import android.os.Build
import android.util.Log
import com.kingzcheung.kime.plugin.core.api.IPluginEntryClass
import com.kingzcheung.kime.plugin.core.model.PluginContext
import com.kingzcheung.kime.plugin.core.model.PluginInfo
import com.kingzcheung.kime.plugin.core.runtime.installer.InstallerManager
import com.kingzcheung.kime.plugin.core.runtime.installer.XmlManager
import com.kingzcheung.kime.plugin.core.runtime.loader.DependencyManager
import com.kingzcheung.kime.plugin.core.runtime.loader.LoadedPluginInfo
import com.kingzcheung.kime.plugin.core.runtime.loader.PluginClassLoader
import com.kingzcheung.kime.plugin.core.runtime.proxy.ProxyManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap

class PluginLifecycleManager(
    private val application: Application,
    private val xmlManager: XmlManager,
    private val installerManager: InstallerManager,
    private val dependencyManager: DependencyManager,
    private val proxyManager: ProxyManager,
    private val classIndex: ConcurrentHashMap<String, String>,
    private val loadedPlugins: ConcurrentHashMap<String, LoadedPluginInfo>,
    private val pluginInstances: ConcurrentHashMap<String, IPluginEntryClass>
) {

    companion object {
        private const val TAG = "PluginLifecycle"
    }

    suspend fun launchPlugin(pluginId: String): Boolean = withContext(Dispatchers.IO) {
        try {
            if (loadedPlugins.containsKey(pluginId)) {
                return@withContext reloadPluginWithDependents(pluginId)
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

        proxyManager.unregisterProviders(pluginId)

        loadedPlugins.remove(pluginId)
        pluginInstances.remove(pluginId)
        dependencyManager.clearDependenciesFor(pluginId)
        removePluginFromIndex(pluginId)
    }

    suspend fun loadEnabledPlugins(): Int = withContext(Dispatchers.IO) {
        Log.d(TAG, "loadEnabledPlugins called")
        val allPlugins = xmlManager.getAllPlugins()
        Log.d(TAG, "All plugins from XmlManager: ${allPlugins.map { "${it.id}(enabled=${it.enabled})" }}")
        
        val enabledPlugins = allPlugins.filter { it.enabled && !loadedPlugins.containsKey(it.id) }
        Log.d(TAG, "Enabled plugins to load: ${enabledPlugins.map { it.id }}")

        if (enabledPlugins.isEmpty()) return@withContext 0

        var successCount = 0
        for (plugin in enabledPlugins) {
            Log.d(TAG, "Attempting to load plugin: ${plugin.id}")
            if (launchSinglePlugin(plugin.id)) {
                successCount++
                Log.d(TAG, "Successfully loaded: ${plugin.id}")
            } else {
                Log.w(TAG, "Failed to load: ${plugin.id}")
            }
        }
        Log.d(TAG, "loadEnabledPlugins completed: $successCount loaded")
        successCount
    }

    private suspend fun launchSinglePlugin(pluginId: String): Boolean {
        Log.d(TAG, "launchSinglePlugin: $pluginId")
        val pluginInfo = xmlManager.getPluginById(pluginId)
        if (pluginInfo == null) {
            Log.w(TAG, "Plugin info not found: $pluginId")
            return false
        }
        Log.d(TAG, "Plugin info: path=${pluginInfo.path}, entryClass=${pluginInfo.entryClass}")
        Log.d(TAG, "Plugin providers: ${pluginInfo.providers.map { it.className + ":" + it.authorities }}")

        proxyManager.registerProviders(pluginId, pluginInfo.providers)
        Log.d(TAG, "Providers registered for $pluginId")

        val loadedPlugin = loadPlugin(pluginInfo)
        if (loadedPlugin == null) {
            Log.w(TAG, "Failed to load plugin APK: $pluginId")
            proxyManager.unregisterProviders(pluginId)
            return false
        }
        loadedPlugins[pluginId] = loadedPlugin
        Log.d(TAG, "Plugin loaded into memory: $pluginId")

        val instance = instantiatePlugin(loadedPlugin)
        if (instance == null) {
            Log.w(TAG, "Failed to instantiate plugin: $pluginId")
            unloadPlugin(pluginId)
            return false
        }
        pluginInstances[pluginId] = instance
        Log.d(TAG, "Plugin instance created: $pluginId, instance type: ${instance::class.simpleName}")

        return true
    }

    private suspend fun reloadPluginWithDependents(pluginId: String): Boolean {
        val dependents = dependencyManager.findDependentsRecursive(pluginId)
        val pluginsToReloadIds = listOf(pluginId) + dependents

        pluginsToReloadIds.reversed().forEach { id ->
            if (loadedPlugins.containsKey(id)) {
                unloadPlugin(id)
            }
        }

        val pluginInfosToReload = pluginsToReloadIds.mapNotNull { xmlManager.getPluginById(it) }
        if (pluginInfosToReload.size != pluginsToReloadIds.size) {
            return false
        }

        var allSuccess = true
        for (pluginInfo in pluginInfosToReload) {
            if (!launchSinglePlugin(pluginInfo.id)) {
                allSuccess = false
                break
            }
        }
        return allSuccess
    }

    private fun loadPlugin(plugin: PluginInfo): LoadedPluginInfo? {
        return try {
            Log.d(TAG, "loadPlugin: ${plugin.id}, path=${plugin.path}")
            val pluginApkFile = File(plugin.path)
            if (!pluginApkFile.exists()) {
                Log.w(TAG, "Plugin APK not found: ${plugin.path}")
                return null
            }
            Log.d(TAG, "Plugin APK exists: ${pluginApkFile.absolutePath}")

            loadClassIndexForPlugin(plugin)

            val nativeLibPath = plugin.nativeLibPath ?: 
                determineNativeLibPath(plugin.id)
            val optimizedDirectory = installerManager.getOptimizedDirectory(plugin.id)?.absolutePath
            
            Log.d(TAG, "Creating ClassLoader for ${plugin.id}: nativeLibPath=$nativeLibPath")

            val classLoader = PluginClassLoader(
                pluginId = plugin.id,
                pluginFile = pluginApkFile,
                parent = application.classLoader,
                optimizedDirectory = optimizedDirectory,
                librarySearchPath = nativeLibPath,
                pluginFinder = dependencyManager
            )
            
            Log.d(TAG, "ClassLoader created for ${plugin.id}")

            LoadedPluginInfo(pluginInfo = plugin, classLoader = classLoader)
        } catch (e: Exception) {
            Log.e(TAG, "loadPlugin failed for ${plugin.id}", e)
            null
        }
    }

    private fun determineNativeLibPath(pluginId: String): String? {
        val pluginDir = installerManager.getPluginDirectory(pluginId)
        val abi = Build.SUPPORTED_ABIS[0]
        val nativeLibDir = File(pluginDir, "lib/$abi")
        return if (nativeLibDir.exists()) nativeLibDir.absolutePath else null
    }

    private fun instantiatePlugin(loadedPlugin: LoadedPluginInfo): IPluginEntryClass? {
        val plugin = loadedPlugin.pluginInfo
        Log.d(TAG, "Instantiating plugin: ${plugin.id}, entryClass: ${plugin.entryClass}")
        return try {
            val instance = loadedPlugin.classLoader.getInterface(
                IPluginEntryClass::class.java,
                plugin.entryClass
            )
            Log.d(TAG, "Instance result: ${instance?.let { it::class.simpleName } ?: "null"}")
            
            if (instance != null) {
                val pluginContext = PluginContext(
                    application = application,
                    pluginInfo = plugin
                )
                instance.onLoad(pluginContext)
                Log.d(TAG, "Plugin ${plugin.id} onLoad called successfully")
                instance
            } else {
                Log.w(TAG, "Failed to instantiate plugin ${plugin.id} - instance is null")
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to instantiate plugin ${plugin.id}", e)
            null
        }
    }

    private fun loadClassIndexForPlugin(plugin: PluginInfo) {
        val pluginDir = installerManager.getPluginDirectory(plugin.id)
        val indexFile = File(pluginDir, "class_index")

        if (!indexFile.exists()) return

        try {
            indexFile.forEachLine { className ->
                if (className.isNotBlank()) {
                    classIndex[className] = plugin.id
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun removePluginFromIndex(pluginId: String) {
        val iterator = classIndex.entries.iterator()
        while (iterator.hasNext()) {
            if (iterator.next().value == pluginId) {
                iterator.remove()
            }
        }
    }
}