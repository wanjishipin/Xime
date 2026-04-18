package com.kingzcheung.kime.plugin.funasr

import android.content.Context
import android.util.Log
import android.widget.Toast
import com.kingzcheung.kime.plugin.core.api.*
import com.kingzcheung.kime.plugin.core.model.PluginContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class FunAsrPlugin : SpeechPlugin {
    
    private var context: PluginContext? = null
    private var apiKey: String = ""
    private var wsManager: WebSocketManager? = null
    private var currentResultCallback: ((SpeechResult) -> Unit)? = null
    private var recognitionState: RecognitionState = RecognitionState.IDLE
    
    companion object {
        private const val TAG = "FunAsrPlugin"
    }
    
    override fun onLoad(context: PluginContext) {
        this.context = context
        Log.d(TAG, "Plugin loaded: ${context.pluginInfo.id}")
        
        val prefsName = "plugin_funasr_config"
        apiKey = context.application.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
            .getString("api_key", "") ?: ""
        Log.d(TAG, "API key from host SharedPreferences: length=${apiKey.length}")
        
        if (apiKey.isEmpty()) {
            Log.w(TAG, "API key not configured")
        }
    }
    
    override fun onUnload() {
        cancelRecognition()
        context = null
        Log.d(TAG, "Plugin unloaded")
    }
    
    override val supportsRealtime = true
    override val requiresNetwork = true
    
    override fun startRecognition(config: AudioConfig, onResult: (SpeechResult) -> Unit): Boolean {
        if (apiKey.isEmpty()) {
            context?.application?.let { ctx ->
                apiKey = FunAsrPreferences.forHost(ctx).getApiKey()
            }
        }
        
        if (apiKey.isEmpty()) {
            onResult(SpeechResult("错误: 未配置API Key", true, 0f))
            return false
        }
        
        if (recognitionState != RecognitionState.IDLE) return false
        
        currentResultCallback = onResult
        
        wsManager = WebSocketManager(
            apiKey = apiKey,
            onResult = { text, isFinal ->
                currentResultCallback?.invoke(SpeechResult(text, isFinal, 1.0f))
            },
            onError = { errorMsg ->
                recognitionState = RecognitionState.ERROR
                currentResultCallback?.invoke(SpeechResult("错误: $errorMsg", true, 0f))
            },
            onStateChanged = { wsState ->
                recognitionState = when (wsState) {
                    WebSocketManager.State.IDLE -> RecognitionState.IDLE
                    WebSocketManager.State.CONNECTING -> RecognitionState.PROCESSING
                    WebSocketManager.State.CONNECTED -> RecognitionState.PROCESSING
                    WebSocketManager.State.LISTENING -> RecognitionState.LISTENING
                    WebSocketManager.State.PROCESSING -> RecognitionState.PROCESSING
                    WebSocketManager.State.ERROR -> RecognitionState.ERROR
                }
            }
        )
        
        if (!wsManager!!.connect()) {
            onResult(SpeechResult("错误: WebSocket连接失败", true, 0f))
            return false
        }
        
        recognitionState = RecognitionState.PROCESSING
        return true
    }
    
    override fun sendAudioChunk(data: ByteArray) {
        wsManager?.sendAudioChunk(data)
    }
    
    override fun stopRecognition() {
        wsManager?.sendFinishTask()
        recognitionState = RecognitionState.IDLE
    }
    
    override fun cancelRecognition() {
        wsManager?.cancel()
        wsManager = null
        currentResultCallback = null
        recognitionState = RecognitionState.IDLE
    }
    
    override suspend fun recognizeOnce(data: ByteArray, config: AudioConfig): String? = withContext(Dispatchers.IO) {
        if (apiKey.isEmpty()) return@withContext null
        
        var result: String? = null
        val tempWs = WebSocketManager(
            apiKey = apiKey,
            onResult = { text, isFinal -> if (isFinal) result = text },
            onError = {},
            onStateChanged = {}
        )
        
        if (!tempWs.connect()) return@withContext null
        tempWs.sendAudioChunk(data)
        tempWs.sendFinishTask()
        Thread.sleep(500)
        result
    }
    
    override fun getState(): RecognitionState = recognitionState
    
    override fun hasSettings(): Boolean = true
    
    override fun openSettings(context: Context) {
        try {
            val intent = android.content.Intent()
            intent.setClassName(
                "com.kingzcheung.kime.plugin.funasr",
                "com.kingzcheung.kime.plugin.funasr.PluginSettingsActivity"
            )
            context.startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(context, "无法打开设置: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }
}