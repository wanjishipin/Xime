package com.kingzcheung.kime.plugin.core.model

import android.app.Application

data class PluginContext(
    val application: Application,
    val pluginInfo: PluginInfo,
    val pluginId: String = pluginInfo.id
)

data class PluginInfo(
    val id: String,
    val name: String,
    val iconResId: Int,
    val versionCode: Long,
    val versionName: String,
    val path: String,
    val entryClass: String,
    val description: String,
    val type: String = "unknown",
    val enabled: Boolean = true,
    val installTime: Long = System.currentTimeMillis(),
    val nativeLibPath: String? = null,
    val providers: List<ProviderInfo> = emptyList()
) {
    val version: String get() = versionName
}