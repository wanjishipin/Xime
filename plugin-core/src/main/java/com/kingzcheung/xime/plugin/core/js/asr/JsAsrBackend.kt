package com.kingzcheung.xime.plugin.core.js.asr

import android.util.Log
import com.kingzcheung.xime.plugin.core.api.AsrPluginBackend
import com.kingzcheung.xime.plugin.core.api.AsrPluginListener
import com.kingzcheung.xime.plugin.core.js.JsScriptRuntime
import com.kingzcheung.xime.plugin.core.js.sdk.JsPluginContract

/**
 * JS 插件 ASR 后端适配器：**全部转发插件 JS 导出函数**。
 *
 * 连接/协议/prebuffer/音频发送决策都由 JS 承载（JS 使用宿主通用 `host.ws`），
 * 本类只做接口桥接：
 * - processAudioChunk → JS `processAudioChunk(pcm)`（Uint8Array，JS 决定缓冲还是直发）
 * - WS 结果经 JS 解析后通过 `host.asr.emit*` 回传本类 listener
 */
class JsAsrBackend(
    private val runtime: JsScriptRuntime
) : AsrPluginBackend {

    private val logTag = "JsAsrBackend"
    private var backendListener: AsrPluginListener? = null
    private var running = false

    override val isRunning: Boolean get() = running

    override fun setListener(listener: AsrPluginListener) {
        backendListener = listener
        runtime.asrResultCallback = listener
    }

    override fun initialize(): Boolean {
        return (JsScriptRuntime.jsToKotlin(runtime.callAsync(JsPluginContract.PATH_SPEECH_CONFIGURE)) as? Boolean) ?: false
    }

    override fun start(): Boolean {
        val ok = try {
            (JsScriptRuntime.jsToKotlin(runtime.callAsync(JsPluginContract.PATH_SPEECH_START)) as? Boolean) ?: false
        } catch (e: Exception) {
            Log.e(logTag, "start failed", e)
            false
        }
        if (ok) running = true
        return ok
    }

    override fun processAudioChunk(pcm: ByteArray) {
        runtime.callAsync(JsPluginContract.PATH_SPEECH_FEED, pcm)
    }

    override fun stop() {
        try {
            runtime.callAsync(JsPluginContract.PATH_SPEECH_STOP)
        } catch (e: Exception) {
            Log.e(logTag, "stop failed", e)
        }
        running = false
    }

    override fun cancel() {
        try {
            runtime.callAsync(JsPluginContract.PATH_SPEECH_CANCEL)
        } catch (e: Exception) {
            Log.e(logTag, "cancel failed", e)
        }
        running = false
    }

    override fun release() {
        cancel()
        backendListener = null
        runtime.asrResultCallback = null
    }
}