package com.kingzcheung.xime.speech

import android.Manifest
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import android.util.Log

import androidx.annotation.RequiresPermission
import com.kingzcheung.xime.settings.SettingsPreferences
import com.kingzcheung.xime.speech.funasr.FunAsrAsrBackend
import com.kingzcheung.xime.speech.sherpa.SherpaAsrBackend
import com.kingzcheung.xime.util.FileLogger

class SpeechRecognitionManager(private val context: Context) {

    companion object {
        private const val TAG = "SpeechRecognitionManager"
        private const val SAMPLE_RATE = 16000
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
        private const val BUFFER_SIZE_SECONDS = 0.1f
        private const val SPEECH_THRESHOLD = 100
    }

    private var backend: AsrBackend? = null
    private var recordingThread: RecordingThread? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    private var resultCallback: ((String) -> Unit)? = null
    private var partialResultCallback: ((String) -> Unit)? = null
    private var stateCallback: ((RecognitionState) -> Unit)? = null
    private var errorCallback: ((String) -> Unit)? = null
    private var amplitudeCallback: ((Float) -> Unit)? = null

    // 预启动的 AudioRecord：手指按下 150ms 后启动，语音激活时直接交给录音线程
    private var preStartedRecord: AudioRecord? = null
    private val preStartTimeoutRunnable = Runnable { cancelPreStart() }

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    fun startRecognition() {
        synchronized(preloadLock) {
            while (isPreloading) {
                try {
                    preloadLock.wait()
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return
                }
            }
        }
        
        if (recordingThread != null) {
            FileLogger.w(TAG, "Recognition already running, ignoring start request")
            return
        }

        FileLogger.i(TAG, "Starting speech recognition")
        stateCallback?.invoke(RecognitionState.PROCESSING)

        if (backend == null) {
            val useLocal = SettingsPreferences.isSttUseLocal(context)
            FileLogger.i(TAG, "Creating ASR backend: ${if (useLocal) "Sherpa (local)" else "FunAsr (online)"}")
            
            val newBackend = createBackend()
            if (newBackend == null) {
                FileLogger.e(TAG, "Failed to create ASR backend")
                errorCallback?.invoke("无法创建 ASR 引擎")
                stateCallback?.invoke(RecognitionState.ERROR)
                return
            }
            backend = newBackend

            newBackend.setCallbacks(
                onResult = { text -> handleResult(text) },
                onPartialResult = { text -> handlePartialResult(text) },
                onStateChange = { state -> stateCallback?.invoke(state) },
                onError = { error -> handleError(error) }
            )

            if (!newBackend.initialize()) {
                val msg = when {
                    newBackend is SherpaAsrBackend -> "本地模型未下载或引擎未编译"
                    newBackend is FunAsrAsrBackend -> "初始化在线引擎失败，请检查 API Key"
                    else -> "引擎初始化失败"
                }
                FileLogger.e(TAG, "Backend initialization failed: $msg")
                errorCallback?.invoke(msg)
                stateCallback?.invoke(RecognitionState.ERROR)
                backend = null
                return
            }
            
            FileLogger.i(TAG, "ASR backend initialized successfully")
        }

        val currentBackend = backend!!

        // 预启动的 AudioRecord 已运行 ~250ms，直接交给录音线程
        var preStarted: AudioRecord? = null
        synchronized(this) {
            preStarted = preStartedRecord
            preStartedRecord = null
        }
        mainHandler.removeCallbacks(preStartTimeoutRunnable)

        recordingThread = RecordingThread(currentBackend, preStarted)
        recordingThread!!.start()
    }

    fun stopRecognition() {
        Log.d(TAG, "Stopping recognition")
        val thread = recordingThread ?: return
        recordingThread = null
        thread.interrupt()
        Thread {
            try {
                thread.join()
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
            mainHandler.post {
                stateCallback?.invoke(RecognitionState.IDLE)

                if (!SettingsPreferences.isSttKeepModelInRam(context)) {
                    Log.d(TAG, "Release mode: freeing backend resources")
                    backend?.release()
                    backend = null
                }
            }
        }.start()
    }

    fun cancelRecognition() {
        Log.d(TAG, "Canceling recognition")
        val thread = recordingThread ?: return
        recordingThread = null
        thread.interrupt()
        Thread {
            try {
                thread.join()
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
            mainHandler.post {
                stateCallback?.invoke(RecognitionState.IDLE)

                if (!SettingsPreferences.isSttKeepModelInRam(context)) {
                    backend?.release()
                    backend = null
                }
            }
        }.start()
    }

    fun setCallbacks(
        onResult: (String) -> Unit,
        onPartialResult: ((String) -> Unit)? = null,
        onStateChange: (RecognitionState) -> Unit,
        onError: (String) -> Unit,
        onAmplitude: ((Float) -> Unit)? = null
    ) {
        resultCallback = onResult
        partialResultCallback = onPartialResult
        stateCallback = onStateChange
        errorCallback = onError
        amplitudeCallback = onAmplitude
    }

    fun startPreStart() {
        cancelPreStart()
        val record = createAudioRecord() ?: return
        record.startRecording()
        synchronized(this) {
            preStartedRecord = record
        }
        mainHandler.removeCallbacks(preStartTimeoutRunnable)
        mainHandler.postDelayed(preStartTimeoutRunnable, 2000)
    }

    fun cancelPreStart() {
        mainHandler.removeCallbacks(preStartTimeoutRunnable)
        synchronized(this) {
            val record = preStartedRecord
            preStartedRecord = null
            if (record != null) {
                try { record.stop() } catch (_: Exception) { }
                record.release()
            }
        }
    }

    fun release() {
        Log.d(TAG, "Releasing speech recognition")
        cancelPreStart()
        cancelRecognition()
        backend?.release()
        backend = null
    }

    private var isPreloading = false
    private val preloadLock = Object()

    fun getState(): RecognitionState {
        return backend?.getState() ?: RecognitionState.IDLE
    }

    fun preload() {
        synchronized(preloadLock) {
            if (backend != null) return
            isPreloading = true
        }
        
        val newBackend = createBackend()
        if (newBackend == null) {
            synchronized(preloadLock) {
                isPreloading = false
                preloadLock.notifyAll()
            }
            return
        }

        newBackend.setCallbacks(
            onResult = { text -> handleResult(text) },
            onPartialResult = { text -> handlePartialResult(text) },
            onStateChange = { state -> stateCallback?.invoke(state) },
            onError = { error -> handleError(error) }
        )

        if (!newBackend.initialize()) {
            synchronized(preloadLock) {
                isPreloading = false
                preloadLock.notifyAll()
            }
            return
        }

        synchronized(preloadLock) {
            backend = newBackend
            isPreloading = false
            preloadLock.notifyAll()
        }

        newBackend.start()
        newBackend.stop()
    }

    private fun createBackend(): AsrBackend {
        return if (SettingsPreferences.isSttUseLocal(context)) {
            SherpaAsrBackend(context)
        } else {
            FunAsrAsrBackend(context)
        }
    }

    private fun createAudioRecord(bufferSecs: Float = 2.0f): AudioRecord? {
        val bufferSize = (SAMPLE_RATE * bufferSecs).toInt()
        return try {
            val record = AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                SAMPLE_RATE,
                CHANNEL_CONFIG,
                AUDIO_FORMAT,
                bufferSize * 2
            )
            if (record.state != AudioRecord.STATE_INITIALIZED) {
                record.release()
                null
            } else {
                record
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create AudioRecord", e)
            null
        }
    }

    private inner class RecordingThread(
        private val currentBackend: AsrBackend,
        private val preStarted: AudioRecord? = null
    ) : Thread("AsrRecording") {

        override fun run() {
            val audioRecord = preStarted ?: (createAudioRecord() ?: run {
                mainHandler.post {
                    errorCallback?.invoke("无法启动录音")
                    stateCallback?.invoke(RecognitionState.ERROR)
                }
                return
            })

            if (!currentBackend.start()) {
                audioRecord.stop()
                audioRecord.release()
                mainHandler.post {
                    errorCallback?.invoke("启动引擎失败")
                    stateCallback?.invoke(RecognitionState.ERROR)
                }
                return
            }

            if (audioRecord.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                audioRecord.startRecording()
            }
            mainHandler.post {
                stateCallback?.invoke(RecognitionState.LISTENING)
            }

            val buffer = ShortArray((SAMPLE_RATE * BUFFER_SIZE_SECONDS).toInt())
            val byteBuffer = ByteArray(buffer.size * 2)
            var speechDetected = false

            try {
                while (!interrupted()) {
                    val nread = audioRecord.read(buffer, 0, buffer.size)
                    if (nread > 0) {
                        for (i in 0 until nread) {
                            val s = buffer[i].toInt()
                            byteBuffer[i * 2] = (s and 0xFF).toByte()
                            byteBuffer[i * 2 + 1] = ((s shr 8) and 0xFF).toByte()
                        }
                        val chunk = byteBuffer.copyOf(nread * 2)
                        if (!speechDetected) {
                            if (isSpeech(chunk)) {
                                speechDetected = true
                                currentBackend.processAudioChunk(chunk)
                            }
                        } else {
                            currentBackend.processAudioChunk(chunk)
                        }
                    } else if (nread < 0) {
                        break
                    }
                }
            } catch (_: Exception) {
            } finally {
                audioRecord.stop()
                audioRecord.release()
            }

            currentBackend.stop()
            Log.d(TAG, "Recognition thread ended")
        }

        private fun isSpeech(chunk: ByteArray): Boolean {
            var peak = 0
            for (i in 0 until chunk.size / 2) {
                val low = chunk[i * 2].toInt() and 0xFF
                val high = chunk[i * 2 + 1].toInt()
                val sample = ((high shl 8) or low).toShort().toInt()
                val abs = kotlin.math.abs(sample)
                if (abs > peak) peak = abs
            }
            return peak > SPEECH_THRESHOLD
        }
    }

    private fun handleResult(text: String) {
        mainHandler.post {
            resultCallback?.invoke(text)
        }
    }

    private fun handlePartialResult(text: String) {
        mainHandler.post {
            if (text.isNotEmpty()) {
                partialResultCallback?.invoke(text)
            }
        }
    }

    private fun handleError(error: String) {
        Log.e(TAG, "Recognition error: $error")
        mainHandler.post {
            errorCallback?.invoke(error)
        }
    }
}
