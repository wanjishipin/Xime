package com.kingzcheung.xime.service

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import com.kingzcheung.xime.util.FileLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 离线语音识别服务客户端，绑定 [AsrInferenceService]（:asr 独立进程）。
 */
class AsrInferenceClient(private val context: Context) {

    companion object {
        private const val TAG = "AsrInferenceClient"
    }

    interface AsrCallback {
        fun onPartialResult(text: String)
        fun onFinalResult(text: String)
        fun onError(message: String)
    }

    private var service: IInferenceAsrService? = null
    private var bound = false

    /** 连接等待锁：一次性 CountDownLatch，countDown 后即失效——
     *  重绑前必须在 [ensureBound] 中重建，否则断线重连后会立即假醒。 */
    @Volatile
    private var connectLatch = CountDownLatch(1)

    private val asrCallbackStub = object : IInferenceAsrCallback.Stub() {
        private var callback: AsrCallback? = null

        fun attach(cb: AsrCallback) { callback = cb }
        fun detach() { callback = null }

        override fun onPartialResult(text: String) {
            callback?.onPartialResult(text)
        }

        override fun onFinalResult(text: String) {
            callback?.onFinalResult(text)
        }

        override fun onError(message: String) {
            callback?.onError(message)
        }
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            service = IInferenceAsrService.Stub.asInterface(binder)
            bound = true
            connectLatch.countDown()
            FileLogger.i(TAG, "Connected to AsrInferenceService")
        }

        override fun onServiceDisconnected(name: ComponentName) {
            service = null
            bound = false
            FileLogger.w(TAG, "AsrInferenceService disconnected (crash?)")
        }

        override fun onBindingDied(name: ComponentName) {
            service = null
            bound = false
            connectLatch.countDown()
            FileLogger.e(TAG, "AsrInferenceService binding died")
        }
    }

    suspend fun ensureBound(): Boolean {
        if (bound && service != null) return true

        // bindService 必须在主线程调用；等待结果放到 IO 线程避免阻塞 UI
        val boundOk = withContext(Dispatchers.Main) {
            synchronized(this@AsrInferenceClient) {
                if (bound && service != null) {
                    true
                } else {
                    // 服务进程可能崩溃/被回收后重启：latch 是一次性的，重绑前必须重建，
                    // 否则旧 latch 已 countDown 会让 await 立即假醒（实际未连接），
                    // 重绑后的第一次 startAsr 必然失败（同 InferenceClient 的修复）
                    connectLatch = CountDownLatch(1)
                    val intent = Intent(context, AsrInferenceService::class.java)
                    context.bindService(intent, connection, Context.BIND_AUTO_CREATE)
                }
            }
        }
        if (!boundOk) return false
        return withContext(Dispatchers.IO) {
            connectLatch.await(5, TimeUnit.SECONDS)
            bound && service != null
        }
    }

    fun unbind() {
        try {
            context.unbindService(connection)
        } catch (_: Exception) {}
        bound = false
        service = null
    }

    private fun requireService(): IInferenceAsrService {
        return service ?: throw IllegalStateException("AsrInferenceService not bound")
    }

    suspend fun startAsr(modelDir: String, callback: AsrCallback): Boolean = withContext(Dispatchers.IO) {
        try {
            asrCallbackStub.attach(callback)
            requireService().startAsr(modelDir, asrCallbackStub)
        } catch (e: Exception) {
            FileLogger.e(TAG, "startAsr failed", e)
            asrCallbackStub.detach()
            false
        }
    }

    fun pushAsrAudio(audioData: ByteArray) {
        try {
            requireService().pushAsrAudio(audioData)
        } catch (_: Exception) {}
    }

    suspend fun stopAsr(): String = withContext(Dispatchers.IO) {
        try {
            requireService().stopAsr()
        } catch (_: Exception) {
            ""
        } finally {
            asrCallbackStub.detach()
        }
    }

    fun cancelAsr() {
        try {
            requireService().cancelAsr()
        } catch (_: Exception) {}
        asrCallbackStub.detach()
    }

    suspend fun releaseAsr() {
        try {
            requireService().releaseAsr()
        } catch (_: Exception) {}
    }

    /** 同步"保持引擎常驻"设置到 :asr 进程（服务端据此决定是否空闲自动释放模型）。 */
    suspend fun setKeepModelAlive(keepAlive: Boolean) {
        try {
            requireService().setKeepModelAlive(keepAlive)
        } catch (_: Exception) {}
    }
}
