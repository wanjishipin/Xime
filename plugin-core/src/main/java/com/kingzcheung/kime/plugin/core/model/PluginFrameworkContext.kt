package com.kingzcheung.kime.plugin.core.model

import android.app.Application
import com.kingzcheung.kime.plugin.core.api.IPluginEntryClass
import com.kingzcheung.kime.plugin.core.runtime.installer.InstallerManager
import com.kingzcheung.kime.plugin.core.runtime.installer.XmlManager
import com.kingzcheung.kime.plugin.core.runtime.lifecycle.PluginLifecycleManager
import com.kingzcheung.kime.plugin.core.runtime.loader.DependencyManager
import com.kingzcheung.kime.plugin.core.runtime.loader.LoadedPluginInfo
import com.kingzcheung.kime.plugin.core.runtime.proxy.ProxyManager
import com.kingzcheung.kime.plugin.core.runtime.resource.PluginResourcesManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.ConcurrentHashMap

internal class PluginFrameworkContext(val application: Application) {

    val initState = MutableStateFlow(InitState.NOT_INITIALIZED)

    val xmlManager = XmlManager(application)
    val installerManager = InstallerManager(application, xmlManager)
    val resourcesManager = PluginResourcesManager(application)
    val dependencyManager = DependencyManager(this)
    val proxyManager = ProxyManager(application)

    val classIndex = ConcurrentHashMap<String, String>()
    val loadedPlugins = ConcurrentHashMap<String, LoadedPluginInfo>()
    val pluginInstances = ConcurrentHashMap<String, IPluginEntryClass>()

    val loadedPluginsFlow: StateFlow<Map<String, LoadedPluginInfo>> =
        MutableStateFlow(loadedPlugins.toMap())
    val pluginInstancesFlow: StateFlow<Map<String, IPluginEntryClass>> =
        MutableStateFlow(pluginInstances.toMap())

    lateinit var lifecycleManager: PluginLifecycleManager

    fun initializeLifecycleManager() {
        lifecycleManager = PluginLifecycleManager(
            application = application,
            xmlManager = xmlManager,
            installerManager = installerManager,
            dependencyManager = dependencyManager,
            proxyManager = proxyManager,
            classIndex = classIndex,
            loadedPlugins = loadedPlugins,
            pluginInstances = pluginInstances
        )
    }
}

enum class InitState {
    NOT_INITIALIZED,
    INITIALIZING,
    INITIALIZED
}