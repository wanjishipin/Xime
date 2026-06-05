// Source: https://github.com/k2-fsa/sherpa-onnx
// License: Apache License 2.0
package com.kingzcheung.xime.speech.sherpa

import android.content.Context
import android.util.Log
import com.k2fsa.sherpa.onnx.*
import com.kingzcheung.xime.speech.RecognitionState
import com.kingzcheung.xime.util.FileLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.File

class SherpaAsrEngine(private val context: Context) {

    companion object {
        private const val TAG = "SherpaAsrEngine"
        private const val SAMPLE_RATE = 16000

        val AVAILABLE_MODELS = listOf(
            AsrModelInfo(
                id = "zipformer-zh-int8",
                name = "中文 Zipformer int8",
                description = "Zipformer 架构，适合实时语音识别，int8 量化",
                language = "zh",
                size = "36 MB",
                downloadUrl = "https://www.modelscope.cn/models/bikeand/asr/resolve/master/sherpa-onnx-streaming-zipformer-zh-int8-2025-06-30.tar.bz2",
                modelType = "transducer",
                files = listOf("encoder.int8.onnx", "decoder.onnx", "joiner.int8.onnx", "tokens.txt"),
                encoderFile = "encoder.int8.onnx",
                decoderFile = "decoder.onnx",
                joinerFile = "joiner.int8.onnx"
            )
        )
    }
    
    data class AsrModelInfo(
        val id: String,
        val name: String,
        val description: String = "",
        val language: String,
        val size: String,
        val downloadUrl: String,
        val modelType: String = "transducer",
        val files: List<String>,
        val encoderFile: String = "",
        val decoderFile: String = "",
        val joinerFile: String = "",
        val needsAutoPunctuation: Boolean = true
    )
    
    private var recognizer: OnlineRecognizer? = null
    private var stream: OnlineStream? = null
    private val coroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    private var resultCallback: ((String) -> Unit)? = null
    private var partialResultCallback: ((String) -> Unit)? = null
    private var stateCallback: ((RecognitionState) -> Unit)? = null
    private var errorCallback: ((String) -> Unit)? = null
    
    private val accumulatedText = StringBuilder()
    private var lastPartialText = ""
    
    fun isAvailable(): Boolean {
        return try {
            System.loadLibrary("sherpa-onnx-jni")
            Log.d(TAG, "sherpa-onnx-jni loaded successfully")
            true
        } catch (e: UnsatisfiedLinkError) {
            Log.w(TAG, "sherpa-onnx-jni not loaded: ${e.message}")
            false
        }
    }
    
    fun isModelReady(): Boolean {
        val modelDir = getSelectedModelDir()
        if (!modelDir.exists()) return false
        val files = modelDir.listFiles()
        return files != null && files.isNotEmpty()
    }
    
    fun getSelectedModelDir(): File {
        val sharedPrefs = context.getSharedPreferences("sherpa_asr", Context.MODE_PRIVATE)
        val modelId = sharedPrefs.getString("selected_model", "zipformer-zh-int8") ?: "zipformer-zh-int8"
        return File(context.filesDir, "asr_models/$modelId")
    }

    fun getSelectedModelInfo(): AsrModelInfo? {
        val sharedPrefs = context.getSharedPreferences("sherpa_asr", Context.MODE_PRIVATE)
        val modelId = sharedPrefs.getString("selected_model", "zipformer-zh-int8") ?: "zipformer-zh-int8"
        return AVAILABLE_MODELS.find { it.id == modelId }
    }
    
    fun setModel(modelId: String) {
        val sharedPrefs = context.getSharedPreferences("sherpa_asr", Context.MODE_PRIVATE)
        sharedPrefs.edit().putString("selected_model", modelId).apply()
    }
    
    private fun findFile(dir: File, fileName: String): File? {
        val direct = File(dir, fileName)
        if (direct.exists()) return direct
        dir.listFiles()?.forEach { child ->
            if (child.isDirectory) {
                val found = findFile(child, fileName)
                if (found != null) return found
            }
        }
        return null
    }

    fun initialize(): Boolean {
        if (!isAvailable()) {
            FileLogger.e(TAG, "sherpa-onnx JNI library not available")
            Log.e(TAG, "sherpa-onnx JNI not available")
            return false
        }

        val modelDir = getSelectedModelDir()
        if (!modelDir.exists()) {
            FileLogger.e(TAG, "Model directory not found: ${modelDir.absolutePath}")
            Log.e(TAG, "Model directory not found: ${modelDir.absolutePath}")
            return false
        }

        val modelInfo = getSelectedModelInfo()
        if (modelInfo == null) {
            FileLogger.e(TAG, "Model info not found for selected model")
            Log.e(TAG, "Model info not found")
            return false
        }

        FileLogger.i(TAG, "Initializing local ASR model: ${modelInfo.name} (${modelInfo.id})")

        val tokensFile = findFile(modelDir, "tokens.txt")
        if (tokensFile == null) {
            FileLogger.e(TAG, "tokens.txt not found in ${modelDir.absolutePath}")
            Log.e(TAG, "tokens.txt not found in ${modelDir.absolutePath}")
            errorCallback?.invoke("模型文件不完整，缺少 tokens.txt")
            return false
        }

        if (findFile(modelDir, modelInfo.encoderFile) == null) {
            FileLogger.e(TAG, "Encoder file not found: ${modelInfo.encoderFile}")
            Log.e(TAG, "Encoder file not found in ${modelDir.absolutePath}")
            errorCallback?.invoke("模型文件不完整，请重新下载")
            return false
        }
        
        try {
            val config = createConfig(modelDir, modelInfo)
            recognizer = OnlineRecognizer(config = config)
            FileLogger.i(TAG, "Local ASR model initialized successfully: ${modelInfo.name}")
            Log.d(TAG, "Recognizer initialized from ${modelDir.absolutePath}")
            return true
        } catch (e: Exception) {
            FileLogger.e(TAG, "Failed to initialize recognizer: ${e.message}", e)
            Log.e(TAG, "Failed to initialize recognizer", e)
            errorCallback?.invoke("模型初始化失败: ${e.message}")
            return false
        }
    }
    
    private fun createConfig(modelDir: File, modelInfo: AsrModelInfo): OnlineRecognizerConfig {
        val tokens = findFile(modelDir, "tokens.txt")?.absolutePath
            ?: File(modelDir, "tokens.txt").absolutePath

        val encoder = (findFile(modelDir, modelInfo.encoderFile) ?: modelInfo.files.firstOrNull { f -> f.startsWith("encoder") }
            ?.let { findFile(modelDir, it) } ?: File(modelDir, modelInfo.encoderFile)).absolutePath
        val decoder = (findFile(modelDir, modelInfo.decoderFile) ?: modelInfo.files.firstOrNull { f -> f.startsWith("decoder") }
            ?.let { findFile(modelDir, it) } ?: File(modelDir, modelInfo.decoderFile)).absolutePath
        val joiner = (findFile(modelDir, modelInfo.joinerFile) ?: modelInfo.files.firstOrNull { f -> f.startsWith("joiner") }
            ?.let { findFile(modelDir, it) } ?: File(modelDir, modelInfo.joinerFile)).absolutePath

        val modelConfig = OnlineModelConfig(
            transducer = OnlineTransducerModelConfig(
                encoder = encoder, decoder = decoder, joiner = joiner
            ),
            tokens = tokens,
            numThreads = 2,
            provider = "cpu",
            modelType = "zipformer2"
        )

        return OnlineRecognizerConfig(
            featConfig = FeatureConfig(sampleRate = SAMPLE_RATE, featureDim = 80),
            modelConfig = modelConfig,
            endpointConfig = EndpointConfig(
                rule1 = EndpointRule(false, 0f, 0f),
                rule2 = EndpointRule(false, 0f, 0f),
                rule3 = EndpointRule(false, 0f, 60f)
            ),
            enableEndpoint = false,
            decodingMethod = "greedy_search"
        )
    }
    
    fun setCallbacks(
        onResult: (String) -> Unit,
        onPartialResult: ((String) -> Unit)? = null,
        onStateChange: (RecognitionState) -> Unit,
        onError: (String) -> Unit
    ) {
        resultCallback = onResult
        partialResultCallback = onPartialResult
        stateCallback = onStateChange
        errorCallback = onError
    }
    
    fun startRecognition(): Boolean {
        if (recognizer == null) {
            if (!initialize()) {
                return false
            }
        }
        
        stream = recognizer?.createStream()
        
        stateCallback?.invoke(RecognitionState.LISTENING)
        Log.d(TAG, "Recognition started")
        return true
    }
    
    fun processAudio(samples: FloatArray) {
        val currentStream = stream
        val currentRecognizer = recognizer
        if (currentStream == null || currentRecognizer == null) {
            return
        }
        
        currentStream.acceptWaveform(samples, SAMPLE_RATE)
        
        while (currentRecognizer.isReady(currentStream)) {
            currentRecognizer.decode(currentStream)
        }
        
        val text = currentRecognizer.getResult(currentStream).text
        if (text.isNotEmpty()) {
            Log.d(TAG, "Partial result: '$text'")
            lastPartialText = text
            coroutineScope.launch(Dispatchers.Main) {
                partialResultCallback?.invoke(text)
            }
        }
    }
    
    fun processAudioBytes(buffer: ByteArray) {
        val samples = FloatArray(buffer.size / 2)
        for (i in samples.indices) {
            val low = buffer[i * 2].toInt() and 0xFF
            val high = buffer[i * 2 + 1].toInt()
            val sample = (high shl 8) or low
            samples[i] = sample.toFloat() / 32768.0f
        }
        processAudio(samples)
    }
    
    fun stopRecognition() {
        val currentStream = stream
        val currentRecognizer = recognizer
        
        var resultText = ""
        if (currentStream != null && currentRecognizer != null) {
            val tailPaddings = FloatArray((0.6f * SAMPLE_RATE).toInt())
            currentStream.acceptWaveform(tailPaddings, SAMPLE_RATE)
            currentStream.inputFinished()
            
            while (currentRecognizer.isReady(currentStream)) {
                currentRecognizer.decode(currentStream)
            }
            
            resultText = currentRecognizer.getResult(currentStream).text
            Log.d(TAG, "Final from model: '$resultText', last partial: '$lastPartialText'")
            
            // 如果模型"反悔"（最终结果比部分结果短），用更长的部分结果
            if (resultText.length < lastPartialText.length) {
                Log.d(TAG, "Model regressed: '$resultText' < '$lastPartialText', using partial")
                resultText = lastPartialText
            }
            
            currentStream.release()
            stream = null
        }
        
        lastPartialText = ""
        
        if (resultText.isNotEmpty()) {
            val finalText = resultText
            coroutineScope.launch(Dispatchers.Main) {
                resultCallback?.invoke(finalText)
            }
        }
        
        accumulatedText.clear()
        stateCallback?.invoke(RecognitionState.IDLE)
        Log.d(TAG, "Recognition stopped")
    }
    
    fun cancelRecognition() {
        stream?.release()
        stream = null
        accumulatedText.clear()
        lastPartialText = ""
        stateCallback?.invoke(RecognitionState.IDLE)
        Log.d(TAG, "Recognition canceled")
    }
    
    fun release() {
        cancelRecognition()
        recognizer?.release()
        recognizer = null
        coroutineScope.cancel()
        Log.d(TAG, "SherpaAsrEngine released")
    }
    
    fun getState(): RecognitionState {
        return if (stream != null) RecognitionState.LISTENING else RecognitionState.IDLE
    }
}