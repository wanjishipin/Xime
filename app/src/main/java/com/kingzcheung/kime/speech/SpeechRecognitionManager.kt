package com.kingzcheung.kime.speech

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import com.kingzcheung.kime.plugin.ExtensionManager
import com.kingzcheung.kime.plugin.core.api.AudioConfig
import com.kingzcheung.kime.plugin.core.api.RecognitionState
import com.kingzcheung.kime.plugin.core.api.SpeechPlugin
import com.kingzcheung.kime.plugin.core.api.SpeechResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

class SpeechRecognitionManager(private val context: Context) {
    
    companion object {
        private const val TAG = "SpeechRecognitionManager"
        private const val SAMPLE_RATE = 16000
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
        private const val BUFFER_SIZE_FACTOR = 2
    }
    
    private val bufferSize = AudioRecord.getMinBufferSize(
        SAMPLE_RATE,
        CHANNEL_CONFIG,
        AUDIO_FORMAT
    ) * BUFFER_SIZE_FACTOR
    
    private var audioRecord: AudioRecord? = null
    private var currentPlugin: SpeechPlugin? = null
    private val isRecording = AtomicBoolean(false)
    private val coroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var recordingJob: Job? = null
    
    private var resultCallback: ((String) -> Unit)? = null
    private var stateCallback: ((RecognitionState) -> Unit)? = null
    private var errorCallback: ((String) -> Unit)? = null
    
    fun getAvailablePlugins(): List<Pair<String, SpeechPlugin>> {
        return ExtensionManager.getEnabledSpeechPlugins(context)
    }
    
    fun getCurrentPlugin(): SpeechPlugin? = currentPlugin
    
    fun setCurrentPlugin(plugin: SpeechPlugin?) {
        currentPlugin = plugin
    }
    
    fun setCallbacks(
        onResult: (String) -> Unit,
        onStateChange: (RecognitionState) -> Unit,
        onError: (String) -> Unit
    ) {
        resultCallback = onResult
        stateCallback = onStateChange
        errorCallback = onError
    }
    
    fun startRecognition(pluginId: String? = null): Boolean {
        val plugins = getAvailablePlugins()
        
        if (plugins.isEmpty()) {
            Log.e(TAG, "No speech plugins available")
            errorCallback?.invoke("没有可用的语音识别插件")
            return false
        }
        
        val selectedPair = if (pluginId != null) {
            plugins.find { it.first == pluginId }
        } else {
            plugins.firstOrNull()
        }
        
        if (selectedPair == null) {
            Log.e(TAG, "Plugin not found: $pluginId")
            errorCallback?.invoke("插件未找到")
            return false
        }
        
        currentPlugin = selectedPair.second
        val pluginInfo = ExtensionManager.getAllInstalledPlugins().firstOrNull { it.id == selectedPair.first }
        Log.d(TAG, "Using plugin: ${pluginInfo?.name ?: pluginId}")
        
        val plugin = currentPlugin!!
        
        if (!plugin.supportsRealtime) {
            Log.e(TAG, "Plugin does not support realtime recognition")
            errorCallback?.invoke("该插件不支持实时识别")
            return false
        }
        
        if (!startAudioRecording()) {
            Log.e(TAG, "Failed to start audio recording")
            errorCallback?.invoke("无法启动录音")
            return false
        }
        
        val config = AudioConfig(
            sampleRate = SAMPLE_RATE,
            encoding = "pcm16",
            channels = 1
        )
        
        val started = plugin.startRecognition(config) { result ->
            handleRecognitionResult(result)
        }
        
        if (!started) {
            Log.e(TAG, "Plugin failed to start recognition")
            stopAudioRecording()
            errorCallback?.invoke("插件启动识别失败")
            return false
        }
        
        isRecording.set(true)
        stateCallback?.invoke(RecognitionState.LISTENING)
        
        recordingJob = coroutineScope.launch {
            try {
                while (isRecording.get()) {
                    val buffer = ByteArray(bufferSize)
                    val bytesRead = audioRecord?.read(buffer, 0, bufferSize) ?: -1
                    
                    if (bytesRead > 0) {
                        val audioChunk = buffer.copyOf(bytesRead)
                        plugin.sendAudioChunk(audioChunk)
                    } else if (bytesRead < 0) {
                        Log.e(TAG, "Audio read error: $bytesRead")
                        break
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Recording error", e)
                withContext(Dispatchers.Main) {
                    errorCallback?.invoke("录音错误: ${e.message}")
                }
            }
        }
        
        Log.d(TAG, "Recognition started with plugin: ${pluginInfo?.name ?: "unknown"}")
        return true
    }
    
    fun stopRecognition() {
        Log.d(TAG, "Stopping recognition")
        
        isRecording.set(false)
        
        recordingJob?.cancel()
        recordingJob = null
        
        stopAudioRecording()
        
        currentPlugin?.stopRecognition()
        
        stateCallback?.invoke(RecognitionState.IDLE)
        
        Log.d(TAG, "Recognition stopped")
    }
    
    fun cancelRecognition() {
        Log.d(TAG, "Canceling recognition")
        
        isRecording.set(false)
        
        recordingJob?.cancel()
        recordingJob = null
        
        stopAudioRecording()
        
        currentPlugin?.cancelRecognition()
        currentPlugin = null
        
        stateCallback?.invoke(RecognitionState.IDLE)
        
        Log.d(TAG, "Recognition canceled")
    }
    
    fun getState(): RecognitionState {
        return currentPlugin?.getState() ?: RecognitionState.IDLE
    }
    
    private fun startAudioRecording(): Boolean {
        try {
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                CHANNEL_CONFIG,
                AUDIO_FORMAT,
                bufferSize
            )
            
            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "AudioRecord not initialized")
                return false
            }
            
            audioRecord?.startRecording()
            
            if (audioRecord?.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                Log.e(TAG, "AudioRecord not recording")
                return false
            }
            
            Log.d(TAG, "Audio recording started")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start audio recording", e)
            return false
        }
    }
    
    private fun stopAudioRecording() {
        try {
            audioRecord?.stop()
            audioRecord?.release()
            audioRecord = null
            Log.d(TAG, "Audio recording stopped")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to stop audio recording", e)
        }
    }
    
    private fun handleRecognitionResult(result: SpeechResult) {
        Log.d(TAG, "Recognition result: ${result.text}, isFinal: ${result.isFinal}")
        
        CoroutineScope(Dispatchers.Main).launch {
            if (result.text.startsWith("错误:")) {
                errorCallback?.invoke(result.text)
            } else {
                resultCallback?.invoke(result.text)
            }
            
            if (result.isFinal) {
                stateCallback?.invoke(RecognitionState.IDLE)
            } else {
                stateCallback?.invoke(RecognitionState.PROCESSING)
            }
        }
    }
    
    fun release() {
        cancelRecognition()
        coroutineScope.cancel()
        Log.d(TAG, "SpeechRecognitionManager released")
    }
}