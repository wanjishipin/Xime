package com.kingzcheung.xime.speech

import android.content.Context
import android.util.Log
import com.kingzcheung.xime.settings.SettingsPreferences
import com.kingzcheung.xime.speech.punctuation.PunctuationInference
import com.kingzcheung.xime.speech.punctuation.PunctuationModelManager
import com.kingzcheung.xime.speech.sherpa.SherpaAsrEngine
import com.kingzcheung.xime.util.FileLogger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 听录管理器：持续语音识别，累积文本，支持实时字幕和语音转文字记录。
 * 与 VoiceRecognitionHandler 不同，此处不做 InputConnection 提交，
 * 而是将识别结果累积到 [transcriptText] 供 UI 展示。
 */
class TranscriptionManager(private val context: Context) {

    companion object {
        private const val TAG = "TranscriptionManager"
    }

    private var speechManager: SpeechRecognitionManager? = null
    private var punctuationInitialized = false
    private var punctuationInitRequested = false

    private val _transcriptText = MutableStateFlow("")
    val transcriptText: StateFlow<String> = _transcriptText.asStateFlow()

    private val _partialText = MutableStateFlow("")
    val partialText: StateFlow<String> = _partialText.asStateFlow()

    private val _isRunning = MutableStateFlow(false)
    val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

    private val _amplitude = MutableStateFlow(0f)
    val amplitude: StateFlow<Float> = _amplitude.asStateFlow()

    private val _state = MutableStateFlow(RecognitionState.IDLE)
    val state: StateFlow<RecognitionState> = _state.asStateFlow()

    private val _providerName = MutableStateFlow("")
    val providerName: StateFlow<String> = _providerName.asStateFlow()

    private val rawSegments = mutableListOf<String>()

    fun start() {
        if (_isRunning.value) return

        val manager = SpeechRecognitionManager(context)
        speechManager = manager

        manager.setCallbacks(
            onResult = { text -> handleResult(text) },
            onPartialResult = { text -> handlePartialResult(text) },
            onStateChange = { state -> _state.value = state },
            onError = { error -> handleError(error) },
            onAmplitude = { amp -> _amplitude.value = amp }
        )

        updateProviderName()
        _isRunning.value = true
        _partialText.value = ""

        // 标点模型在后台线程加载，不影响识别启动
        Thread {
            try {
                initPunctuationModel()
                // 标点模型加载完成后重新推理已有文本
                reprocessTranscript()
            } catch (e: Exception) {
                FileLogger.e(TAG, "Punctuation init error: ${e.message}")
            }
        }.start()

        manager.startRecognition()
        FileLogger.i(TAG, "Transcription started")
    }

    fun stop() {
        if (!_isRunning.value) return
        speechManager?.stopRecognition()
        _isRunning.value = false
        _amplitude.value = 0f
        _state.value = RecognitionState.IDLE

        if (_partialText.value.isNotEmpty()) {
            val partial = _partialText.value.replace(" ", "")
            if (partial.isNotEmpty()) {
                addRawSegment(partial)
            }
            _partialText.value = ""
        }
        // 停止时重新推理全部文本
        reprocessTranscript()
        FileLogger.i(TAG, "Transcription stopped")
    }

    fun clear() {
        _transcriptText.value = ""
        _partialText.value = ""
        rawSegments.clear()
    }

    fun appendManualText(text: String) {
        if (text.isNotEmpty()) {
            _transcriptText.value = _transcriptText.value + text
        }
    }

    fun release() {
        stop()
        speechManager?.release()
        speechManager = null
        if (punctuationInitialized) {
            PunctuationInference.release()
            punctuationInitialized = false
        }
    }

    private fun handleResult(text: String) {
        val cleanText = text.replace(" ", "")
        if (cleanText.isNotEmpty() && !cleanText.startsWith("错误:")) {
            addRawSegment(cleanText)
            reprocessTranscript()
        }
        _partialText.value = ""
    }

    private fun handlePartialResult(text: String) {
        val cleanText = text.replace(" ", "")
        if (cleanText.isEmpty()) {
            _partialText.value = ""
            return
        }
        _partialText.value = cleanText
    }

    private fun handleError(error: String) {
        FileLogger.e(TAG, "Recognition error: $error")
        _state.value = RecognitionState.ERROR
        _isRunning.value = false
        _amplitude.value = 0f
    }

    private fun addRawSegment(text: String) {
        if (text.isNotEmpty()) {
            rawSegments.add(text)
        }
    }

    private fun reprocessTranscript() {
        if (rawSegments.isEmpty()) {
            _transcriptText.value = ""
            return
        }
        val fullRaw = rawSegments.joinToString("")
        _transcriptText.value = addPunctuation(fullRaw)
    }

    private fun addPunctuation(text: String): String {
        if (text.isEmpty()) return text

        val useLocal = SettingsPreferences.isSttUseLocal(context)

        // 在线引擎（阿里百炼）通常自带标点
        if (!useLocal) {
            return text
        }

        val sherpaEngine = SherpaAsrEngine(context)
        val needsAutoPunctuation = sherpaEngine.getSelectedModelInfo()?.needsAutoPunctuation ?: true
        if (!needsAutoPunctuation) return text

        val cleanText = text.trim().replace(" ", "")
        if (cleanText.isEmpty()) return text

        val punctuationEnabled = SettingsPreferences.isPunctuationModelEnabled(context)
        if (punctuationEnabled && punctuationInitialized) {
            try {
                val result = PunctuationInference.predict(cleanText)
                if (result.isNotEmpty()) return result
            } catch (e: Exception) {
                FileLogger.e(TAG, "Punctuation model failed: ${e.message}")
            }
        }

        // 标点模型未就绪时用启发式规则为每句加标点
        return addHeuristicPunctuationToFullText(cleanText)
    }

    /**
     * 对整段文本用启发式规则加标点：
     * 按 ASR 返回的 segment 边界加标点，每个 segment 末尾加句号/问号/逗号。
     */
    private fun addHeuristicPunctuationToFullText(text: String): String {
        if (rawSegments.isEmpty()) return text
        val sb = StringBuilder()
        for (segment in rawSegments) {
            val seg = segment.replace(" ", "")
            if (seg.isEmpty()) continue
            sb.append(seg)
            sb.append(heuristicPunctuation(seg))
        }
        return sb.toString()
    }

    private fun heuristicPunctuation(text: String): String {
        return when {
            text.any { it in "吗呢么吧" } || text.contains("什么") || text.contains("怎么") || text.contains("为什么") || text.contains("如何") || text.contains("哪") -> "？"
            text.length < 4 -> "，"
            else -> "。"
        }
    }

    private fun initPunctuationModel() {
        if (punctuationInitialized || punctuationInitRequested) return
        punctuationInitRequested = true

        val punctuationEnabled = SettingsPreferences.isPunctuationModelEnabled(context)
        if (!punctuationEnabled) return

        val punctuationManager = PunctuationModelManager(context)
        if (!punctuationManager.isModelDownloaded()) return

        val modelFile = punctuationManager.getModelFile()
        val vocabFile = punctuationManager.getVocabFile()
        if (PunctuationInference.initialize(context, modelFile.absolutePath, vocabFile.absolutePath)) {
            punctuationInitialized = true
            FileLogger.i(TAG, "Punctuation model initialized")
        }
    }

    private fun updateProviderName() {
        val useLocal = SettingsPreferences.isSttUseLocal(context)
        _providerName.value = if (useLocal) {
            val sherpaEngine = SherpaAsrEngine(context)
            sherpaEngine.getSelectedModelInfo()?.name ?: "本地模型"
        } else {
            val apiKey = SettingsPreferences.getFunAsrApiKey(context)
            if (apiKey.isNotEmpty()) "阿里百炼" else "未配置"
        }
    }
}
