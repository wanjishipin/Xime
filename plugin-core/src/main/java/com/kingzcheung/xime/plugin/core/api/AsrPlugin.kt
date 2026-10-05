package com.kingzcheung.xime.plugin.core.api

import android.content.Context
import com.kingzcheung.xime.plugin.core.config.IPluginConfigurable
import com.kingzcheung.xime.plugin.core.model.PluginCapabilities

enum class AsrPluginState {
    IDLE,
    LISTENING,
    PROCESSING,
    ERROR
}

/**
 * ASR 识别结果回调（插件 → 宿主）。
 * 输入（PCM 流）与识别周期由 [AsrPluginBackend] 驱动，结果统一回调回传。
 */
interface AsrPluginListener {
    fun onFinal(text: String)
    fun onPartial(text: String) {}
    fun onError(message: String) {}
    fun onStateChanged(state: AsrPluginState) {}
}

interface AsrPluginBackend {
    val isRunning: Boolean

    fun setListener(listener: AsrPluginListener)

    fun initialize(): Boolean

    fun start(): Boolean

    /** 宿主录音 PCM 灌入（16k/mono/pcm16le，见宿主录音实现）。 */
    fun processAudioChunk(pcm: ByteArray)

    fun stop()

    fun cancel()

    fun release()
}

interface AsrPlugin : IPluginEntryClass, IPluginConfigurable {

    /**
     * 能力声明（manifest.capabilities.speech 的镜像，设置页与服务选型消费）。
     * 原生插件与 JS 插件均由元数据提供。
     */
    fun getCapabilities(): PluginCapabilities.SpeechCapabilities

    fun isConfigured(): Boolean

    fun createBackend(context: Context): AsrPluginBackend
}