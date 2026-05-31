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
        private const val SPEECH_THRESHOLD = 500
        private const val SILENCE_CONTEXT_CHUNKS = 3
    }

    private var backend: AsrBackend? = null
    private var recordingThread: RecordingThread? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    // 预缓冲：手指按下时立即开始录音，AudioRecord 直接传给录音线程继续用，不留缺口
    private var preBufferRecord: AudioRecord? = null
    private var preBufferThread: PreBufferThread? = null
    private var preBufferChunks = mutableListOf<ByteArray>()
    private val preBufferLock = Any()
    private var preBufferStartedRecording = false

    private var resultCallback: ((String) -> Unit)? = null
    private var partialResultCallback: ((String) -> Unit)? = null
    private var stateCallback: ((RecognitionState) -> Unit)? = null
    private var errorCallback: ((String) -> Unit)? = null
    private var amplitudeCallback: ((Float) -> Unit)? = null

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

    private var isPreloading = false
    private val preloadLock = Object()
    private val preBufferTimeoutRunnable = Runnable { cancelPreBuffer() }

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
                return
            }
            
            FileLogger.i(TAG, "ASR backend initialized successfully")
        }

        val currentBackend = backend!!

        // 取出预缓冲数据，传给录音线程在实时音频前处理
        val preData = stopPreBuffer()

        recordingThread = RecordingThread(currentBackend, preData)
        recordingThread!!.start()
    }

    fun stopRecognition() {
        Log.d(TAG, "Stopping recognition")
        recordingThread?.let { thread ->
            thread.interrupt()
            try {
                thread.join()
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
        recordingThread = null
        stateCallback?.invoke(RecognitionState.IDLE)

        if (!SettingsPreferences.isSttKeepModelInRam(context)) {
            Log.d(TAG, "Release mode: freeing backend resources")
            backend?.release()
            backend = null
        }
    }

    fun cancelRecognition() {
        Log.d(TAG, "Canceling recognition")
        recordingThread?.let { thread ->
            thread.interrupt()
            try {
                thread.join()
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
        recordingThread = null
        stateCallback?.invoke(RecognitionState.IDLE)

        if (!SettingsPreferences.isSttKeepModelInRam(context)) {
            backend?.release()
            backend = null
        }
    }

    fun startPreBuffer() {
        synchronized(preBufferLock) {
            if (preBufferThread != null) {
                preBufferThread?.interrupt()
                try {
                    preBufferThread?.join(500)
                } catch (_: InterruptedException) { }
                preBufferThread = null
                releasePreBufferRecord()
            }
            preBufferChunks.clear()
        }
        val record = createAudioRecord() ?: return
        synchronized(preBufferLock) {
            preBufferRecord = record
        }
        preBufferThread = PreBufferThread()
        preBufferThread?.start()
        mainHandler.removeCallbacks(preBufferTimeoutRunnable)
        mainHandler.postDelayed(preBufferTimeoutRunnable, 2000)
    }

    fun stopPreBuffer(): List<ByteArray> {
        mainHandler.removeCallbacks(preBufferTimeoutRunnable)
        synchronized(preBufferLock) {
            preBufferRecord?.stop()
            preBufferRecord = null
        }
        preBufferThread?.interrupt()
        try {
            preBufferThread?.join(1000)
        } catch (_: InterruptedException) { }
        preBufferThread = null
        synchronized(preBufferLock) {
            val data = preBufferChunks.toList()
            preBufferChunks.clear()
            return data
        }
    }

    private fun releasePreBufferRecord() {
        val record = preBufferRecord
        preBufferRecord = null
        if (record == null) return
        try {
            record.stop()
        } catch (_: Exception) { }
        record.release()
    }

    private inner class PreBufferThread : Thread("AsrPreBuffer") {
        override fun run() {
            val record: AudioRecord
            synchronized(preBufferLock) {
                record = preBufferRecord ?: return@run
            }
            record.startRecording()
            val buffer = ShortArray((SAMPLE_RATE * BUFFER_SIZE_SECONDS).toInt())
            val byteBuffer = ByteArray(buffer.size * 2)
            try {
                while (!interrupted()) {
                    val nread = record.read(buffer, 0, buffer.size)
                    if (nread > 0) {
                        for (i in 0 until nread) {
                            val s = buffer[i].toInt()
                            byteBuffer[i * 2] = (s and 0xFF).toByte()
                            byteBuffer[i * 2 + 1] = ((s shr 8) and 0xFF).toByte()
                        }
                        synchronized(preBufferLock) {
                            preBufferChunks.add(byteBuffer.copyOf(nread * 2))
                            if (preBufferChunks.size > 5) {
                                preBufferChunks.removeAt(0)
                            }
                        }
                    } else {
                        break
                    }
                }
            } catch (_: Exception) {
            } finally {
                try {
                    record.stop()
                } catch (_: Exception) { }
                record.release()
            }
        }
    }

    fun cancelPreBuffer() {
        mainHandler.removeCallbacks(preBufferTimeoutRunnable)
        if (preBufferThread == null) return
        preBufferThread?.interrupt()
        try {
            preBufferThread?.join(500)
        } catch (_: InterruptedException) { }
        preBufferThread = null
        synchronized(preBufferLock) {
            releasePreBufferRecord()
            preBufferChunks.clear()
        }
    }

    fun release() {
        Log.d(TAG, "Releasing speech recognition")
        cancelPreBuffer()
        cancelRecognition()
        backend?.release()
        backend = null
    }

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
                MediaRecorder.AudioSource.MIC,
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
        private val startChunks: List<ByteArray> = emptyList()
    ) : Thread("AsrRecording") {

        override fun run() {
            val audioRecord: AudioRecord
            synchronized(preBufferLock) {
                if (preBufferRecord != null) {
                    audioRecord = preBufferRecord!!
                    preBufferRecord = null
                } else {
                    audioRecord = createAudioRecord() ?: run {
                        mainHandler.post {
                            errorCallback?.invoke("无法启动录音")
                            stateCallback?.invoke(RecognitionState.ERROR)
                        }
                        return
                    }
                }
            }

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
            val silenceRing = ArrayDeque<ByteArray>()
            var speechDetected = false

            // 预缓冲数据先过静音检测：如果包含语音才喂给解码器
            for (chunk in startChunks) {
                if (!speechDetected) {
                    silenceRing.addLast(chunk)
                    if (silenceRing.size > SILENCE_CONTEXT_CHUNKS) {
                        silenceRing.removeFirst()
                    }
                }
                if (isSpeech(chunk)) {
                    speechDetected = true
                    for (c in silenceRing) {
                        currentBackend.processAudioChunk(c)
                    }
                    silenceRing.clear()
                }
            }
            if (!speechDetected) silenceRing.clear()

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
                            silenceRing.addLast(chunk)
                            if (silenceRing.size > SILENCE_CONTEXT_CHUNKS) {
                                silenceRing.removeFirst()
                            }
                            if (isSpeech(chunk)) {
                                speechDetected = true
                                for (c in silenceRing) {
                                    currentBackend.processAudioChunk(c)
                                }
                                silenceRing.clear()
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
            if (text.isNotEmpty()) {
                resultCallback?.invoke(text)
            }
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
