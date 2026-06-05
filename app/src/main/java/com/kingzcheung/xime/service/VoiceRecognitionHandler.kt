package com.kingzcheung.xime.service

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.inputmethod.InputConnection
import com.kingzcheung.xime.speech.RecognitionState
import com.kingzcheung.xime.speech.SpeechRecognitionManager
import com.kingzcheung.xime.speech.punctuation.PunctuationInference
import com.kingzcheung.xime.speech.punctuation.PunctuationModelManager
import com.kingzcheung.xime.speech.sherpa.SherpaAsrEngine
import com.kingzcheung.xime.settings.SettingsPreferences
import com.kingzcheung.xime.util.FileLogger

class VoiceRecognitionHandler(
    private val context: Context,
    private val onStateChanged: (InputUIState) -> Unit,
    private val getState: () -> InputUIState,
    private val getInputConnection: () -> InputConnection?
) {
    companion object {
        private const val TAG = "VoiceRecognition"
    }

    private lateinit var speechRecognitionManager: SpeechRecognitionManager
    private var punctuationInitialized = false

    var textBeforeVoiceInput = ""
    var textLengthBeforeVoiceInput = 0

    fun initialize() {
        FileLogger.i(TAG, "Initializing speech recognition system")

        speechRecognitionManager = SpeechRecognitionManager(context)

        speechRecognitionManager.setCallbacks(
            onResult = { text ->
                handleSpeechResult(text)
            },
            onPartialResult = { text ->
                handlePartialResult(text)
            },
            onStateChange = { state ->
                handleSpeechStateChange(state)
            },
            onError = { error ->
                handleSpeechError(error)
            },
            onAmplitude = { amplitude ->
                handleAmplitudeUpdate(amplitude)
            }
        )

        val useLocal = SettingsPreferences.isSttUseLocal(context)
        val providerName = if (useLocal) {
            val sherpaEngine = SherpaAsrEngine(context)
            sherpaEngine.getSelectedModelInfo()?.name ?: "本地模型"
        } else {
            val apiKey = SettingsPreferences.getFunAsrApiKey(context)
            if (apiKey.isNotEmpty()) "阿里百炼" else "未配置"
        }

        onStateChanged(getState().copy(voicePluginName = providerName))
        FileLogger.i(TAG, "STT provider: ${if (useLocal) "local" else "funasr"}")

        if (useLocal && SettingsPreferences.isSttEnabled(context)) {
            Thread {
                try {
                    speechRecognitionManager.preload()
                    initPunctuationModel()
                } catch (_: Exception) { }
            }.start()
        }
    }
    
    private fun initPunctuationModel() {
        if (punctuationInitialized) return
        
        val punctuationEnabled = SettingsPreferences.isPunctuationModelEnabled(context)
        if (!punctuationEnabled) {
            FileLogger.i(TAG, "Punctuation model not enabled in settings")
            return
        }
        
        val punctuationManager = PunctuationModelManager(context)
        if (!punctuationManager.isModelDownloaded()) {
            FileLogger.i(TAG, "Punctuation model not downloaded")
            return
        }
        
        val modelFile = punctuationManager.getModelFile()
        val vocabFile = punctuationManager.getVocabFile()
        if (PunctuationInference.initialize(context, modelFile.absolutePath, vocabFile.absolutePath)) {
            punctuationInitialized = true
            FileLogger.i(TAG, "Punctuation model initialized successfully")
        } else {
            FileLogger.e(TAG, "Failed to initialize punctuation model")
        }
    }

    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }
    private val delayedPreStartRunnable = Runnable {
        if (::speechRecognitionManager.isInitialized) {
            speechRecognitionManager.startPreStart()
        }
    }

    fun startDelayedPreStart(delayMs: Long = 150) {
        mainHandler.removeCallbacks(delayedPreStartRunnable)
        mainHandler.postDelayed(delayedPreStartRunnable, delayMs)
    }

    fun cancelPreStart() {
        mainHandler.removeCallbacks(delayedPreStartRunnable)
        if (::speechRecognitionManager.isInitialized) {
            speechRecognitionManager.cancelPreStart()
        }
    }

    fun startRecognition() {
        if (!::speechRecognitionManager.isInitialized) {
            Log.e(TAG, "speechRecognitionManager not initialized")
            onStateChanged(getState().copy(
                isVoiceMode = false,
                voiceRecognitionState = RecognitionState.ERROR
            ))
            return
        }

        textBeforeVoiceInput = getInputConnection()?.getTextBeforeCursor(1000, 0)?.toString() ?: ""
        textLengthBeforeVoiceInput = textBeforeVoiceInput.length
        Log.d("VoiceButtons", "Saved text before voice: length=$textLengthBeforeVoiceInput")

        val useLocal = SettingsPreferences.isSttUseLocal(context)
        val providerName = if (useLocal) {
            val sherpaEngine = SherpaAsrEngine(context)
            sherpaEngine.getSelectedModelInfo()?.name ?: "本地模型"
        } else {
            val apiKey = SettingsPreferences.getFunAsrApiKey(context)
            if (apiKey.isNotEmpty()) "阿里百炼" else "未配置"
        }
        onStateChanged(getState().copy(voicePluginName = providerName))

        speechRecognitionManager.startRecognition()
        Log.d("VoiceButtons", "Speech recognition starting")
    }

    fun stopRecognition() {
        if (::speechRecognitionManager.isInitialized) {
            speechRecognitionManager.stopRecognition()
        }
        // handleFinalResult is now called from within handleSpeechResult
        // when the final stopRecognition result arrives
    }

    fun release() {
        if (::speechRecognitionManager.isInitialized) {
            speechRecognitionManager.release()
        }
        if (punctuationInitialized) {
            PunctuationInference.release()
            punctuationInitialized = false
        }
    }

    fun isInitialized(): Boolean = ::speechRecognitionManager.isInitialized

    private var lastPartialText = ""
    private var lastAmplitudeUpdate = 0L

    private fun handleSpeechResult(text: String) {
        Log.d(TAG, "Speech result (final): $text")
        lastPartialText = ""

        val cleanText = text.replace(" ", "")
        if (cleanText.isNotEmpty() && !cleanText.startsWith("错误:")) {
            val ic = getInputConnection()
            if (ic != null) {
                val punctuatedText = addPunctuation(cleanText)
                ic.commitText(punctuatedText, 1)
            }
            onStateChanged(getState().copy(voiceRecognizedText = ""))
        }
    }
    
    private fun addPunctuation(text: String): String {
        val useLocal = SettingsPreferences.isSttUseLocal(context)
        if (!useLocal) return text
        
        val sherpaEngine = SherpaAsrEngine(context)
        val needsAutoPunctuation = sherpaEngine.getSelectedModelInfo()?.needsAutoPunctuation ?: true
        if (!needsAutoPunctuation) return text
        
        val cleanText = text.trim().replace(" ", "")
        if (cleanText.isEmpty()) return text
        
        val punctuationEnabled = SettingsPreferences.isPunctuationModelEnabled(context)
        if (punctuationEnabled && punctuationInitialized) {
            try {
                val result = PunctuationInference.predict(cleanText)
                FileLogger.d(TAG, "Punctuation model result: '$cleanText' -> '$result'")
                return result
            } catch (e: Exception) {
                FileLogger.e(TAG, "Punctuation model failed: ${e.message}")
            }
        }
        
        return "$cleanText${heuristicPunctuation(cleanText)}"
    }

    private fun heuristicPunctuation(text: String): String {
        return when {
            text.any { it in "吗呢么吧" } || text.contains("什么") || text.contains("怎么") || text.contains("为什么") || text.contains("如何") || text.contains("哪") -> "？"
            text.length < 4 -> "，"
            else -> "。"
        }
    }

    private fun handlePartialResult(text: String) {
        if (text == lastPartialText) return
        lastPartialText = text
        Log.d(TAG, "Speech result (partial): $text")
        
        // 过滤掉空格，避免显示空白
        val cleanText = text.replace(" ", "")
        if (cleanText.isEmpty()) return
        
        val ic = getInputConnection()
        if (ic != null) {
            ic.setComposingText(cleanText, 1)
        }
        onStateChanged(getState().copy(voiceRecognizedText = cleanText))
    }

    private fun handleSpeechStateChange(state: RecognitionState) {
        Log.d(TAG, "Speech state changed: $state")
        if (state == RecognitionState.LISTENING) {
            lastPartialText = ""
        }
        onStateChanged(getState().copy(voiceRecognitionState = state))
    }

    private fun handleSpeechError(error: String) {
        Log.e(TAG, "Speech error: $error")
        FileLogger.e(TAG, "Speech error: $error")
        lastPartialText = ""
        onStateChanged(getState().copy(
            isVoiceMode = false,
            voiceButtonState = VoiceButtonState(),
            voiceRecognitionState = RecognitionState.ERROR,
            voiceRecognizedText = "",
            voiceAmplitude = 0f
        ))
    }

    private fun handleAmplitudeUpdate(amplitude: Float) {
        val now = System.currentTimeMillis()
        if (now - lastAmplitudeUpdate < 80) return
        lastAmplitudeUpdate = now
        onStateChanged(getState().copy(voiceAmplitude = amplitude))
    }
}