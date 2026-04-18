package com.kingzcheung.kime.plugin.core.runtime

import android.app.Application
import android.util.Log
import com.kingzcheung.kime.plugin.core.api.IPluginEntryClass
import com.kingzcheung.kime.plugin.core.model.InitState
import com.kingzcheung.kime.plugin.core.model.PluginFrameworkContext
import com.kingzcheung.kime.plugin.core.model.PluginInfo
import com.kingzcheung.kime.plugin.core.runtime.loader.LoadedPluginInfo
import com.kingzcheung.kime.plugin.core.runtime.proxy.ProxyManager
import com.kingzcheung.kime.plugin.core.security.crash.PluginCrashHandler
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

    val installerManager: com.kingzcheung.kime.plugin.core.runtime.installer.InstallerManager
        get() = requireContext().installerManager

    val resourcesManager: com.kingzcheung.kime.plugin.core.runtime.resource.PluginResourcesManager
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
            val success = result is com.kingzcheung.kime.plugin.core.runtime.installer.InstallerManager.InstallResult.Success
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
        return installerManager.scanAndInstallSystemPlugins()
    }
}