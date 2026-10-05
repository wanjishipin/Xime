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
import com.kingzcheung.xime.plugin.ActivePluginSelection
import com.kingzcheung.xime.plugin.ExtensionManager
import com.kingzcheung.xime.settings.SettingsPreferences
import com.kingzcheung.xime.util.FileLogger

class SpeechRecognitionManager(private val context: Context) {

    companion object {
        private const val TAG = "SpeechRecognitionManager"
        private const val SAMPLE_RATE = 16000
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
        private const val BUFFER_SIZE_SECONDS = 0.1f
        private const val SPEECH_THRESHOLD = 25

        /** 停止时等待录音线程退出的超时：覆盖 backend.stop() 同步等待最终结果的耗时。 */
        private const val JOIN_TIMEOUT_MS = 5000L

        /**
         * 停止后延迟释放后端的窗口：在线插件 stop() 只发送结束信号，最终结果由
         * 服务端处理完尾点后经 WebSocket 异步回调（1~2s），需比
         * VoiceRecognitionHandler.FINISH_TIMEOUT_MS 更长，保证回调通道存活。
         */
        private const val BACKEND_RELEASE_DELAY_MS = 3500L
    }

    private var backend: AsrBackend? = null
    private var recordingThread: RecordingThread? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    // 会话序号：用于区分连续语音会话，防止旧会话的回收线程误释放新会话的后端
    private var sessionId = 0
    // 待执行的延迟释放所对应的会话序号（-1 表示无待释放）
    @Volatile
    private var pendingReleaseSession = -1
    private val pendingBackendRelease = Runnable {
        val session = pendingReleaseSession
        pendingReleaseSession = -1
        // 主线程只取引用；release() 含跨进程 IPC/断连，放后台线程执行
        val b = synchronized(preloadLock) {
            if (session != -1 && sessionId == session) {
                val tmp = backend
                backend = null
                tmp
            } else null
        }
        if (b != null) {
            Thread { b.release() }.start()
        }
    }
    // 后台加载 ASR 模型的进行中标记与取消标记
    @Volatile
    private var loadingInProgress = false
    @Volatile
    private var loadingCancelled = false

    private var resultCallback: ((String) -> Unit)? = null
    private var partialResultCallback: ((String) -> Unit)? = null
    private var stateCallback: ((RecognitionState) -> Unit)? = null
    @Volatile
    private var currentState: RecognitionState = RecognitionState.IDLE
    private var errorCallback: ((String, Boolean) -> Unit)? = null
    private var amplitudeCallback: ((Float) -> Unit)? = null
    private var spectrumCallback: ((FloatArray) -> Unit)? = null

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
        setState(RecognitionState.PROCESSING)

        if (backend == null) {
            // 按需加载：后台线程加载 ASR 模型，完成后在主线程启动录音，避免阻塞键盘 UI
            FileLogger.i(TAG, "ASR backend not loaded, loading on demand")
            synchronized(preloadLock) {
                if (loadingInProgress) {
                    // 已有加载进行中（如设置开启触发的预加载），等待其完成
                    loadingCancelled = false
                    return
                }
                loadingInProgress = true
                loadingCancelled = false
            }
            Thread {
                try {
                    val ok = preload()
                    if (!ok || synchronized(preloadLock) { backend } == null) {
                        mainHandler.post {
                            errorCallback?.invoke("无法初始化语音引擎，请检查本地模型或在线语音插件配置", true)
                            setState(RecognitionState.ERROR)
                        }
                        return@Thread
                    }
                    if (loadingCancelled) {
                        mainHandler.post {
                            setState(RecognitionState.IDLE)
                        }
                        return@Thread
                    }
                    mainHandler.post {
                        if (recordingThread == null && !loadingCancelled) {
                            startRecording()
                        }
                    }
                } finally {
                    synchronized(preloadLock) {
                        loadingInProgress = false
                        preloadLock.notifyAll()
                    }
                }
            }.start()
            return
        }

        startRecording()
    }

    private fun startRecording() {
        val currentBackend = synchronized(preloadLock) { backend } ?: return
        synchronized(preloadLock) { sessionId++ }

        // 新会话复用后端：取消上一次会话遗留的延迟释放
        mainHandler.removeCallbacks(pendingBackendRelease)
        pendingReleaseSession = -1

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

    /**
     * 延迟释放后端：给在线插件的异步最终结果留出送达窗口。窗口内新会话开始
     * （startRecording）会取消释放并复用后端；释放前校验会话序号，避免误释放
     * 新会话正在使用的后端。
     * "引擎常驻"开启时不安排释放——后端跨会话保留，闲置后再用免重建
     * （重新加载模型/重建 Lua 后端与连接正是"闲置后首次使用慢"的根源）；
     * 关闭设置后自然回退为延迟释放，无需主动清理。
     */
    private fun scheduleBackendRelease(session: Int) {
        if (SettingsPreferences.isSttKeepEngineAlive(context)) return
        pendingReleaseSession = session
        mainHandler.removeCallbacks(pendingBackendRelease)
        mainHandler.postDelayed(pendingBackendRelease, BACKEND_RELEASE_DELAY_MS)
    }

    fun stopRecognition() {
        Log.d(TAG, "Stopping recognition")
        val thread = recordingThread
        if (thread == null) {
            // 模型仍在后台加载中：标记取消，加载完成后不再启动录音
            if (loadingInProgress) {
                loadingCancelled = true
                mainHandler.post {
                    setState(RecognitionState.IDLE)
                }
            }
            return
        }
        recordingThread = null
        thread.stopRequested = true
        val session = synchronized(preloadLock) { sessionId }
        // 不能 interrupt：录音线程可能正阻塞在 backend.stop() 同步等待最终结果
        // （本地 stopAsr 的 runBlocking），中断会吞掉最终文本；forceStopAudio 释放
        // AudioRecord 使 read() 返回错误、循环自然退出后执行 stop()
        thread.forceStopAudio()
        Thread {
            try {
                // join 超时兜底：录音线程若卡在 Lua 调用中（最坏 CALL_TIMEOUT_MS=180s），
                // 不能让后端释放无限期阻塞（否则 WebSocket 与麦克风一直占着）
                thread.join(JOIN_TIMEOUT_MS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
            if (thread.isAlive) {
                // 卡死兜底：标记中断，尽快从可中断调用中退出
                thread.interrupt()
            }
            scheduleBackendRelease(session)
            mainHandler.post {
                setState(RecognitionState.IDLE)
            }
        }.start()
    }

    fun cancelRecognition() {
        Log.d(TAG, "Canceling recognition")
        val thread = recordingThread
        if (thread == null) {
            // 模型仍在后台加载中：标记取消，加载完成后不再启动录音
            if (loadingInProgress) {
                loadingCancelled = true
                mainHandler.post {
                    setState(RecognitionState.IDLE)
                }
            }
            return
        }
        recordingThread = null
        thread.stopRequested = true
        val session = synchronized(preloadLock) { sessionId }
        // 同 stopRecognition：不打断 backend.stop()，避免破坏引擎收尾状态
        thread.forceStopAudio()
        Thread {
            try {
                thread.join(JOIN_TIMEOUT_MS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
            if (thread.isAlive) {
                thread.interrupt()
            }
            scheduleBackendRelease(session)
            mainHandler.post {
                setState(RecognitionState.IDLE)
            }
        }.start()
    }

    fun getState(): RecognitionState = currentState

    private fun setState(state: RecognitionState) {
        currentState = state
        stateCallback?.invoke(state)
    }

    fun setCallbacks(
        onResult: (String) -> Unit,
        onPartialResult: ((String) -> Unit)? = null,
        onStateChange: (RecognitionState) -> Unit,
        onError: (message: String, userVisible: Boolean) -> Unit,
        onAmplitude: ((Float) -> Unit)? = null,
        onSpectrum: ((FloatArray) -> Unit)? = null
    ) {
        resultCallback = onResult
        partialResultCallback = onPartialResult
        stateCallback = onStateChange
        errorCallback = onError
        amplitudeCallback = onAmplitude
        spectrumCallback = onSpectrum
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
        mainHandler.removeCallbacks(pendingBackendRelease)
        pendingReleaseSession = -1
        cancelRecognition()
        val b = synchronized(preloadLock) {
            val tmp = backend
            backend = null
            tmp
        }
        if (b != null) {
            // release() 含跨进程 IPC，放到后台线程执行，避免阻塞主线程（onDestroy 等场景）
            Thread {
                b.release()
            }.start()
        }
    }

    private var isPreloading = false
    private val preloadLock = Object()

    fun preload(): Boolean {
        synchronized(preloadLock) {
            if (backend != null) return true
            isPreloading = true
        }
        
        val newBackend = createBackend()
        if (newBackend == null) {
            synchronized(preloadLock) {
                isPreloading = false
                preloadLock.notifyAll()
            }
            return false
        }

        newBackend.setCallbacks(
            onResult = { text -> handleResult(text) },
            onPartialResult = { text -> handlePartialResult(text) },
            onStateChange = { state -> setState(state) },
            onError = { error -> handleError(error) }
        )

        if (!newBackend.initialize()) {
            synchronized(preloadLock) {
                isPreloading = false
                preloadLock.notifyAll()
            }
            return false
        }

        synchronized(preloadLock) {
            backend = newBackend
            isPreloading = false
            preloadLock.notifyAll()
        }

        return true
    }

    private fun createBackend(): AsrBackend? {
        // 用户开启"本地识别"时才使用离线后端，否则走在线插件
        val useLocal = SettingsPreferences.isSttUseLocal(context)
        return if (useLocal) {
            AsrBackendFactory.create(context) ?: createOnlineAsrBackend()
        } else {
            createOnlineAsrBackend()
        }
    }

    private fun createOnlineAsrBackend(): AsrBackend? {
        val enabledPlugins = ExtensionManager.getEnabledAsrPlugins(context)
        if (enabledPlugins.isEmpty()) return null

        val selectedId = SettingsPreferences.getSttOnlinePluginId(context)
        // 与插件管理页共用同一判定规则（ActivePluginSelection）：偏好不可用时回退首个已启用插件，
        // 并回填偏好，避免"识别在跑、插件页显示未使用"
        val resolvedId = ActivePluginSelection.resolve(selectedId, enabledPlugins.map { it.first })
        if (resolvedId != selectedId) SettingsPreferences.setSttOnlinePluginId(context, resolvedId)
        val selected = enabledPlugins.firstOrNull { it.first == resolvedId }
            ?: enabledPlugins.firstOrNull()
        val (_, plugin) = selected ?: return null

        val backend = plugin.createBackend(context.applicationContext)
        val pluginName = ExtensionManager.getAllInstalledPlugins()
            .firstOrNull { it.id == selected?.first }?.name ?: selected?.first ?: "语音识别"
        return PluginAsrBackendAdapter(pluginName, backend)
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

        private val spectrumAnalyzer = SpectrumAnalyzer()

        /**
         * 停止请求标志：不能只依赖线程中断标志停止循环。
         *
         * processAudioChunk 会经 JsScriptRuntime.runGuarded 的 FutureTask.get 执行，
         * FutureTask.awaitDone 内部用 Thread.interrupted() 检查中断状态并**清除中断标志**，
         * 随后 JsScriptRuntime.call 吞掉 InterruptedException 正常返回 NIL——
         * 于是中断标志被消费后 while (!interrupted()) 永远为真，录音线程无法停止，
         * stopRecognition 的 join() 永不返回，后端（WebSocket）与麦克风一直后台占用。
         */
        @Volatile
        var stopRequested = false

        @Volatile
        private var audioRecord: AudioRecord? = null

        override fun run() {
            val audioRecord = preStarted ?: (createAudioRecord() ?: run {
                mainHandler.post {
                    errorCallback?.invoke("无法启动录音", false)
                    setState(RecognitionState.ERROR)
                }
                return
            })
            this.audioRecord = audioRecord

            if (!currentBackend.start()) {
                audioRecord.stop()
                audioRecord.release()
                mainHandler.post {
                    errorCallback?.invoke("启动引擎失败", false)
                    setState(RecognitionState.ERROR)
                }
                return
            }

            if (audioRecord.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                audioRecord.startRecording()
            }
            mainHandler.post {
                setState(RecognitionState.LISTENING)
            }

            val buffer = ShortArray((SAMPLE_RATE * BUFFER_SIZE_SECONDS).toInt())
            val byteBuffer = ByteArray(buffer.size * 2)
            var speechDetected = false
            // 语音前缓冲：保存检测到语音前的若干块，检测到后一起送入 ASR，
            // 避免"你/觉"等弱开头的语音块因音量低于阈值被当作静音丢弃
            val preSpeechBuffer = ArrayDeque<ByteArray>()
            val maxPreSpeechChunks = 4  // 0.4s 语音前缓冲

            try {
                while (!interrupted() && !stopRequested) {
                    val nread = audioRecord.read(buffer, 0, buffer.size)
                    if (nread > 0) {
                        var peak = 0
                        for (i in 0 until nread) {
                            val s = buffer[i].toInt()
                            byteBuffer[i * 2] = (s and 0xFF).toByte()
                            byteBuffer[i * 2 + 1] = ((s shr 8) and 0xFF).toByte()
                            val abs = if (s < 0) -s else s
                            if (abs > peak) peak = abs
                        }
                        // 归一化振幅（0~1）与频段频谱，驱动频谱可视化
                        val normalized = (peak / 32768f).coerceIn(0f, 1f)
                        val spectrum = spectrumAnalyzer.analyze(buffer, nread)
                        mainHandler.post {
                            amplitudeCallback?.invoke(normalized)
                            spectrumCallback?.invoke(spectrum)
                        }
                        val chunk = byteBuffer.copyOf(nread * 2)
                        if (!speechDetected) {
                            preSpeechBuffer.addLast(chunk)
                            // 缓冲满仍未检测到语音：放弃 VAD，直接开始识别，
                            // 保证整段弱音内容也能送入 ASR（开头静音已被缓冲丢弃）
                            if (preSpeechBuffer.size >= maxPreSpeechChunks) {
                                speechDetected = true
                                while (preSpeechBuffer.isNotEmpty()) {
                                    currentBackend.processAudioChunk(preSpeechBuffer.removeFirst())
                                }
                            } else if (isSpeech(chunk)) {
                                speechDetected = true
                                // 把语音前缓冲的块按顺序送入 ASR，保证开头不丢失
                                while (preSpeechBuffer.isNotEmpty()) {
                                    currentBackend.processAudioChunk(preSpeechBuffer.removeFirst())
                                }
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
                // forceStopAudio 可能已从外部 stop/release，这里需容错（重复释放不抛异常中断后续流程）
                try { audioRecord.stop() } catch (_: Exception) { }
                try { audioRecord.release() } catch (_: Exception) { }
            }

            currentBackend.stop()
            Log.d(TAG, "Recognition thread ended")
        }

        /** 从外部强制停止录音：录音线程阻塞在 read() 等不可中断调用时，释放 AudioRecord 使其立即返回错误并退出。 */
        fun forceStopAudio() {
            audioRecord?.let { record ->
                try { record.stop() } catch (_: Exception) { }
                try { record.release() } catch (_: Exception) { }
            }
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
            errorCallback?.invoke(error, true)
        }
    }
}
