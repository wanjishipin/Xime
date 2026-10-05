package com.kingzcheung.xime.plugin.core.js

import android.content.Context
import com.kingzcheung.xime.plugin.core.api.AsrPlugin
import com.kingzcheung.xime.plugin.core.api.AsrPluginBackend
import com.kingzcheung.xime.plugin.core.js.asr.JsAsrBackend
import com.kingzcheung.xime.plugin.core.model.PluginCapabilities
import com.kingzcheung.xime.plugin.core.model.PluginContext

/**
 * speech 类型 JS 插件的宿主侧适配器：实现 [AsrPlugin] 接口。
 *
 * 能力声明（getCapabilities）来自 manifest 元数据，JS 侧不再提供 getProviderId/
 * getDisplayName/getCapabilities——名称/能力/配置就绪均由宿主按元数据判定。
 */
class JsAsrPluginAdapter(
    runtime: JsScriptRuntime,
    pluginContext: PluginContext
) : JsPluginAdapter(runtime, pluginContext), AsrPlugin {

    override fun getCapabilities(): PluginCapabilities.SpeechCapabilities =
        pluginContext.pluginInfo.capabilities?.speech ?: PluginCapabilities.SpeechCapabilities()

    override fun isConfigured(): Boolean = super.isConfigured()

    override fun createBackend(context: Context): AsrPluginBackend = JsAsrBackend(runtime)
}