package com.kingzcheung.xime.plugin.core.runtime

import android.app.Application
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import com.kingzcheung.xime.plugin.core.api.IPluginEntryClass
import com.kingzcheung.xime.plugin.core.model.InitState
import com.kingzcheung.xime.plugin.core.model.PluginFrameworkContext
import com.kingzcheung.xime.plugin.core.model.PluginInfo
import com.kingzcheung.xime.plugin.core.runtime.loader.LoadedPluginInfo
import com.kingzcheung.xime.plugin.core.runtime.proxy.ProxyManager
import com.kingzcheung.xime.plugin.core.security.crash.PluginCrashHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.io.File

object PluginManager {

    private const val TAG = "PluginManager"

    private var frameworkContext: PluginFrameworkContext? = null
    private val _loadedPluginsFlow = MutableStateFlow<Map<String, LoadedPluginInfo>>(emptyMap())
    private val _pluginInstancesFlow = MutableStateFlow<Map<String, IPluginEntryClass>>(emptyMap())

    val initStateFlow: StateFlow<InitState>
        get() = requireContext().initState

    val loadedPluginsFlow: StateFlow<Map<String, LoadedPluginInfo>>
        get() = _loadedPluginsFlow

    val pluginInstancesFlow: StateFlow<Map<String, IPluginEntryClass>>
        get() = _pluginInstancesFlow

    val isInitialized: Boolean
        get() = frameworkContext?.initState?.value == InitState.INITIALIZED

    val installerManager: com.kingzcheung.xime.plugin.core.runtime.installer.InstallerManager
        get() = requireContext().installerManager

    val resourcesManager: com.kingzcheung.xime.plugin.core.runtime.resource.PluginResourcesManager
        get() = requireContext().resourcesManager

    val proxyManager: ProxyManager
        get() = requireContext().proxyManager

    internal fun getClassIndex(): Map<String, String> = requireContext().classIndex

    private val managerScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private fun requireContext(): PluginFrameworkContext {
        return frameworkContext
            ?: throw IllegalStateException("PluginManager has not been initialized.")
    }

    @Synchronized
    fun initialize(
        context: Application,
        hostProviderAuthority: String? = null,
        onSetup: (suspend () -> Unit)? = null
    ) {
        if (frameworkContext != null && frameworkContext?.initState?.value != InitState.NOT_INITIALIZED) {
            Log.d(TAG, "Already initialized, skipping")
            return
        }

        Log.d(TAG, "Starting initialization...")
        PluginCrashHandler.initialize(context)
        frameworkContext = PluginFrameworkContext(context)
        
        hostProviderAuthority?.let {
            requireContext().proxyManager.setHostProviderAuthority(it)
        }
        
        requireContext().initState.value = InitState.INITIALIZING

        requireContext().initializeLifecycleManager()
        requireContext().initState.value = InitState.INITIALIZED
        Log.d(TAG, "Framework initialized")

        managerScope.launch {
            try {
                Log.d(TAG, "Executing onSetup asynchronously...")
                onSetup?.invoke()
                updateFlows()
                Log.d(TAG, "onSetup completed successfully")
            } catch (e: Exception) {
                Log.e(TAG, "onSetup failed", e)
            }
        }
        Log.d(TAG, "Initialization complete (onSetup running in background)")
    }

    private fun updateFlows() {
        _loadedPluginsFlow.value = requireContext().loadedPlugins.toMap()
        _pluginInstancesFlow.value = requireContext().pluginInstances.toMap()
        Log.d(TAG, "Flows updated: ${_pluginInstancesFlow.value.size} instances")
    }

    suspend fun awaitInitialization() {
        if (isInitialized) return
        initStateFlow.first { it == InitState.INITIALIZED }
    }

    suspend fun launchPlugin(pluginId: String): Boolean {
        val result = requireContext().lifecycleManager.launchPlugin(pluginId)
        updateFlows()
        return result
    }

    suspend fun unloadPlugin(pluginId: String) {
        requireContext().lifecycleManager.unloadPlugin(pluginId)
        updateFlows()
    }

    suspend fun loadEnabledPlugins(): Int {
        Log.d(TAG, "loadEnabledPlugins called")
        val result = requireContext().lifecycleManager.loadEnabledPlugins()
        updateFlows()
        Log.d(TAG, "loadEnabledPlugins result: $result")
        return result
    }

    fun <T : Any> getInterface(interfaceClass: Class<T>, className: String): T? {
        try {
            val targetPluginId = requireContext().classIndex[className]
            if (targetPluginId == null) return null

            val loadedPlugin = requireContext().loadedPlugins[targetPluginId]
            if (loadedPlugin == null) return null

            return loadedPlugin.classLoader.getInterface(interfaceClass, className)
        } catch (e: Exception) {
            return null
        }
    }

    fun getPluginInstance(pluginId: String): IPluginEntryClass? {
        return requireContext().pluginInstances[pluginId]
    }

    fun getPluginInfo(pluginId: String): LoadedPluginInfo? {
        return requireContext().loadedPlugins[pluginId]
    }

    fun getAllPluginInstances(): Map<String, IPluginEntryClass> {
        return requireContext().pluginInstances.toMap()
    }

    fun getAllInstallPlugins(): List<PluginInfo> {
        return requireContext().xmlManager.getAllPlugins()
    }

    fun getPluginDependentsChain(pluginId: String): List<String> {
        return requireContext().dependencyManager.findDependentsRecursive(pluginId)
    }

    fun getPluginDependenciesChain(pluginId: String): List<String> {
        return requireContext().dependencyManager.findDependenciesRecursive(pluginId)
    }

    suspend fun setPluginEnabled(pluginId: String, enabled: Boolean): Boolean {
        return try {
            val pluginInfo = requireContext().xmlManager.getPluginById(pluginId) ?: return false
            if (pluginInfo.enabled == enabled) return true
            val updatedPluginInfo = pluginInfo.copy(enabled = enabled)
            requireContext().xmlManager.updatePlugin(updatedPluginInfo)
            requireContext().xmlManager.flushToDisk()
            true
        } catch (e: Exception) {
            false
        }
    }

    suspend fun installPluginFromAssets(assetsPath: String, forceOverwrite: Boolean = true): Boolean {
        Log.d(TAG, "installPluginFromAssets: $assetsPath")
        return try {
            val context = requireContext().application
            val pluginFile = File(context.cacheDir, "temp_plugin.apk")
            context.assets.open(assetsPath).use { input ->
                pluginFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            val result = installerManager.installPlugin(pluginFile, forceOverwrite)
            pluginFile.delete()
            val success = result is com.kingzcheung.xime.plugin.core.runtime.installer.InstallerManager.InstallResult.Success
            Log.d(TAG, "installPluginFromAssets result: $success")
            success
        } catch (e: Exception) {
            Log.e(TAG, "installPluginFromAssets failed", e)
            false
        }
    }

    suspend fun installPluginsFromAssetsForDebug(assetsDir: String = "plugins"): Int {
        Log.d(TAG, "installPluginsFromAssetsForDebug: $assetsDir")
        val context = requireContext().application
        var installedCount = 0

        try {
            val assetFiles = context.assets.list(assetsDir) ?: return 0
            Log.d(TAG, "Found ${assetFiles.size} files in assets/$assetsDir: ${assetFiles.toList()}")
            
            for (fileName in assetFiles) {
                if (fileName.endsWith(".apk")) {
                    val assetPath = "$assetsDir/$fileName"
                    Log.d(TAG, "Installing: $assetPath")
                    if (installPluginFromAssets(assetPath, forceOverwrite = true)) {
                        installedCount++
                        Log.d(TAG, "Successfully installed: $fileName")
                    } else {
                        Log.w(TAG, "Failed to install: $fileName")
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "installPluginsFromAssetsForDebug failed", e)
        }

        Log.d(TAG, "Total installed: $installedCount")
        return installedCount
    }
    
    suspend fun scanAndInstallSystemPlugins(): Int {
        Log.d(TAG, "scanAndInstallSystemPlugins")
        
        // 先清理已卸载的插件（APK 不存在）
        cleanupUninstalledPlugins()
        
        return installerManager.scanAndInstallSystemPlugins()
    }
    
    private suspend fun cleanupUninstalledPlugins() {
        Log.d(TAG, "cleanupUninstalledPlugins")
        val context = requireContext().application
        val allPlugins = requireContext().xmlManager.getAllPlugins()
        
        // 获取系统中已安装的插件包名
        val installedPackageNames = try {
            val intent = android.content.Intent("com.wanjishipin.xime.plugin.EXTENSION")
            val resolveInfos = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.queryIntentActivities(
                    intent,
                    android.content.pm.PackageManager.ResolveInfoFlags.of(0)
                )
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.queryIntentActivities(intent, 0)
            }
            resolveInfos.map { it.activityInfo.packageName }.toSet()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to query installed plugins", e)
            emptySet()
        }
        
        Log.d(TAG, "Installed plugin packages: $installedPackageNames")
        
        // 插件 ID 就是 packageName
        for (plugin in allPlugins) {
            // 如果插件 ID 不在已安装的包列表中，则移除
            if (plugin.id !in installedPackageNames && plugin.id != context.packageName) {
                Log.d(TAG, "Plugin app not installed, removing: ${plugin.id}")
                requireContext().xmlManager.removePlugin(plugin.id)
                requireContext().lifecycleManager.unloadPlugin(plugin.id)
                
                // 删除复制的文件
                try {
                    val pluginDir = File(plugin.path).parentFile
                    if (pluginDir != null && pluginDir.exists()) {
                        pluginDir.deleteRecursively()
                        Log.d(TAG, "Deleted plugin directory: ${pluginDir.absolutePath}")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to delete plugin directory", e)
                }
            }
        }
        requireContext().xmlManager.flushToDisk()
    }
}