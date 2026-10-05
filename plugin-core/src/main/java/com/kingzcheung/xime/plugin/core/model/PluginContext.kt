package com.kingzcheung.xime.plugin.core.model

import android.app.Application
import com.kingzcheung.xime.plugin.core.config.NoopPluginConfigStore
import com.kingzcheung.xime.plugin.core.config.PluginConfigStore

data class PluginContext(
    val application: Application,
    val pluginInfo: PluginInfo,
    val pluginId: String = pluginInfo.id,
    val configStore: PluginConfigStore = NoopPluginConfigStore
)
