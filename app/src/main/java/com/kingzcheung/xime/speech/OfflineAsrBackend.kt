package com.kingzcheung.xime.speech

import android.content.Context
import com.kingzcheung.xime.service.AsrInferenceClient
import com.kingzcheung.xime.util.FileLogger
import kotlinx.coroutines.runBlocking

/**
 * 基于 :asr 独立进程的流式离线 ASR 后端。
 *
 * 模型加载与推理全部在 [com.kingzcheung.xime.service.AsrInferenceService]（:asr 进程）中完成，
 * 主进程仅负责音频采集与结果回调，不占用输入法进程内存。
 */
class OfflineAsrBackend(private val context: Context) : AsrBackend {

    companion object {
        private const val TAG = "OfflineAsrBackend"
    }

    override val name: String = "本地 Zipformer（离线）"

    private val client = AsrInferenceClient(context)
    private var initialized = false

    private var resultCallback: ((String) -> Unit)? = null
    private var partialResultCallback: ((String) -> Unit)? = null
    private var stateCallback: ((RecognitionState) -> Unit)? = null
    private var errorCallback: ((String) -> Unit)? = null

    override fun setCallbacks(
        onResult: (String) -> Unit,
        onPartialResult: ((String) -> Unit)?,
        onStateChange: (RecognitionState) -> Unit,
        onError: (String) -> Unit
    ) {
        resultCallback = onResult
        partialResultCallback = onPartialResult
        stateCallback = onStateChange
        errorCallback = onError
    }

    private val asrCallback = object : AsrInferenceClient.AsrCallback {
        override fun onPartialResult(text: String) {
            partialResultCallback?.invoke(text)
        }

        override fun onFinalResult(text: String) {
            resultCallback?.invoke(text)
        }

        override fun onError(message: String) {
            errorCallback?.invoke(message)
        }
    }

    override fun initialize(): Boolean {
        return try {
            val ok = runBlocking { client.ensureBound() }
            if (!ok) {
                FileLogger.e(TAG, "Failed to bind AsrInferenceService")
                return false
            }
            initialized = true
            syncKeepAlive()
            // 预热模型：绑定后立即创建模型句柄并驻留，避免首次语音时
            // 1s 模型加载导致开头音频（如"你觉得"）在录音缓冲中被丢弃
            try {
                val modelManager = AsrModelManager(context)
                if (modelManager.isModelReady()) {
                    val modelDir = modelManager.getSelectedModelDir().absolutePath
                    runBlocking { client.startAsr(modelDir, asrCallback) }
                    runBlocking { client.stopAsr() }
                }
            } catch (e: Exception) {
                FileLogger.w(TAG, "model warmup skipped: ${e.message}")
            }
            FileLogger.i(TAG, "initialize result=true")
            true
        } catch (e: Exception) {
            FileLogger.e(TAG, "initialize failed", e)
            false
        }
    }

    override fun start(): Boolean {
        if (!initialized) return false
        return try {
            val modelManager = AsrModelManager(context)
            if (!modelManager.isModelReady()) {
                FileLogger.e(TAG, "ASR model not downloaded")
                errorCallback?.invoke("离线语音模型未下载，请在设置中先下载")
                return false
            }
            // 每次会话开始都重新 startAsr：服务端会 nativeReset 并重设回调，
            // 否则 preload 预热时 stop() 清空的 callback 会导致 partial 结果丢失
            syncKeepAlive()
            val modelDir = modelManager.getSelectedModelDir().absolutePath
            runBlocking { client.startAsr(modelDir, asrCallback) }
        } catch (e: InterruptedException) {
            // 快速取消竞态：stopRecognition/cancelRecognition 会 interrupt 录音线程，
            // 中断正好落在等待 :asr 绑定/模型加载的 runBlocking 上——属正常取消，不算错误，
            // 不打 E 级日志（避免"启动引擎失败"误报污染排查）
            Thread.currentThread().interrupt()
            FileLogger.d(TAG, "start aborted: recording cancelled before engine started")
            false
        } catch (e: Exception) {
            FileLogger.e(TAG, "start failed", e)
            false
        }
    }

    override fun processAudioChunk(buffer: ByteArray) {
        if (!initialized) return
        client.pushAsrAudio(buffer)
    }

    override fun stop() {
        if (!initialized) return
        val text = try {
            runBlocking { client.stopAsr() }
        } catch (e: InterruptedException) {
            // 录音线程被取消 interrupt 时可能正阻塞在此（同 start 的取消竞态），属正常路径
            Thread.currentThread().interrupt()
            FileLogger.d(TAG, "stop aborted: recording cancelled")
            ""
        } catch (e: Exception) {
            FileLogger.e(TAG, "stop failed", e)
            ""
        }
        if (text.isNotEmpty()) {
            resultCallback?.invoke(text)
        }
        stateCallback?.invoke(RecognitionState.IDLE)
    }

    override fun cancel() {
        if (initialized) {
            client.cancelAsr()
        }
    }

    override fun release() {
        // 语音会话结束：重置识别状态，但保持 :asr 服务绑定与模型常驻，
        // 由 AsrSupport 统一管理（关闭开关时才真正卸载）
        initialized = false
        try {
            runBlocking { client.stopAsr() }
        } catch (_: Exception) {
        }
    }

    /** 完全释放：卸载模型并解绑服务。仅在关闭"本地识别"开关时调用。 */
    fun releaseModel() {
        initialized = false
        try {
            runBlocking { client.releaseAsr() }
        } catch (e: Exception) {
            FileLogger.e(TAG, "releaseAsr failed", e)
        }
        client.unbind()
    }

    override fun isAvailable(): Boolean = true

    /**
     * 把"保持引擎常驻"设置同步到 :asr 服务。每次绑定/会话开始都同步一次：
     * 用户中途切换设置后，下一个会话立即按新设置决定是否空闲释放模型。
     */
    private fun syncKeepAlive() {
        try {
            runBlocking {
                client.setKeepModelAlive(
                    com.kingzcheung.xime.settings.SettingsPreferences.isSttKeepEngineAlive(context)
                )
            }
        } catch (_: Exception) {
        }
    }
}
