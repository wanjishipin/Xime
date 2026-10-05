package com.kingzcheung.xime.service

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.DeadObjectException
import android.os.IBinder
import com.kingzcheung.xime.association.AssociationCandidate
import com.kingzcheung.xime.handwriting.HandwritingCandidate
import com.kingzcheung.xime.util.FileLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class InferenceClient(private val context: Context) {

    companion object {
        private const val TAG = "InferenceClient"
        const val MODEL_PREDICTION = "predictive_text"
        const val MODEL_HANDWRITING = "handwriting"
    }

    private var service: IInferenceService? = null
    private var bound = false

    /** 服务进程死亡/绑定失效时通知宿主：宿主据此重置本地"已加载"假象实现自愈
     *  （AUTO_CREATE 会自动重启服务，但新进程里模型是空的，客户端标志必须跟着复位）。 */
    @Volatile
    var onDisconnected: (() -> Unit)? = null

    @Volatile
    private var connectLatch = CountDownLatch(1)

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            service = IInferenceService.Stub.asInterface(binder)
            bound = true
            connectLatch.countDown()
            FileLogger.i(TAG, "Connected to InferenceService")
        }

        override fun onServiceDisconnected(name: ComponentName) {
            service = null
            bound = false
            onDisconnected?.invoke()
            FileLogger.w(TAG, "InferenceService disconnected (crash?)")
        }

        override fun onBindingDied(name: ComponentName) {
            service = null
            bound = false
            connectLatch.countDown()
            onDisconnected?.invoke()
            FileLogger.e(TAG, "InferenceService binding died")
        }
    }

    /** 服务是否处于可用绑定状态（进程存活且 binder 有效）。 */
    fun isBound(): Boolean = bound && service != null

    suspend fun ensureBound(): Boolean = withContext(Dispatchers.IO) {
        if (isBound()) return@withContext true

        // 服务进程可能崩溃重启：latch 是一次性的，重绑前必须重建，
        // 否则旧 latch 已 countDown 会让 await 立即假成功（实际未连接）
        synchronized(this@InferenceClient) {
            if (isBound()) return@withContext true
            connectLatch = CountDownLatch(1)
            val intent = Intent(context, InferenceService::class.java)
            val ok = context.bindService(intent, connection, Context.BIND_AUTO_CREATE)
            if (!ok) return@withContext false
        }

        val connected = connectLatch.await(3, TimeUnit.SECONDS)
        connected && isBound()
    }

    fun unbind() {
        try {
            context.unbindService(connection)
        } catch (_: Exception) {}
        bound = false
        service = null
    }

    private fun requireService(): IInferenceService {
        return service ?: throw IllegalStateException("InferenceService not bound")
    }

    /** binder 调用失败后重置绑定状态；下一次 ensureBound 会重新 bindService。 */
    private fun invalidateBinding() {
        service = null
        bound = false
    }

    suspend fun loadModel(modelId: String, modelPath: String, extraPath: String = ""): Boolean = withContext(Dispatchers.IO) {
        try {
            requireService().loadModel(modelId, modelPath, extraPath)
        } catch (e: DeadObjectException) {
            invalidateBinding()
            FileLogger.e(TAG, "loadModel($modelId) failed: service died", e)
            false
        } catch (e: Exception) {
            FileLogger.e(TAG, "loadModel($modelId) failed", e)
            false
        }
    }

    suspend fun unloadModel(modelId: String) = withContext(Dispatchers.IO) {
        try {
            requireService().unloadModel(modelId)
        } catch (_: Exception) {}
    }

    suspend fun isModelLoaded(modelId: String): Boolean = withContext(Dispatchers.IO) {
        try {
            requireService().isModelLoaded(modelId)
        } catch (_: Exception) {
            false
        }
    }

    suspend fun predict(text: String, topK: Int = 20): List<AssociationCandidate> = withContext(Dispatchers.IO) {
        try {
            val result = requireService().predict(MODEL_PREDICTION, text, topK)
            val candidates = mutableListOf<AssociationCandidate>()
            for (i in result.indices step 2) {
                val word = result[i]
                val score = result.getOrNull(i + 1)?.toFloatOrNull() ?: continue
                candidates.add(AssociationCandidate(word, score))
            }
            candidates
        } catch (e: DeadObjectException) {
            // 服务进程已死：重置绑定状态，让上层（OnnxAssociationEngine）感知失联并自愈
            invalidateBinding()
            FileLogger.e(TAG, "predict failed: service died", e)
            emptyList()
        } catch (e: Exception) {
            FileLogger.e(TAG, "predict failed", e)
            emptyList()
        }
    }

    /** 手写识别：客户端传原始笔画，服务端完成预处理与汉字映射，直接返回候选字。 */
    suspend fun recognizeHandwriting(points: FloatArray, strokePointCounts: IntArray, topK: Int): List<HandwritingCandidate> = withContext(Dispatchers.IO) {
        try {
            val result = requireService().recognizeHandwriting(MODEL_HANDWRITING, points, strokePointCounts, topK)
            val candidates = mutableListOf<HandwritingCandidate>()
            for (i in result.indices step 2) {
                val ch = result.getOrNull(i) ?: continue
                val score = result.getOrNull(i + 1)?.toFloatOrNull() ?: continue
                candidates.add(HandwritingCandidate(ch, score))
            }
            candidates
        } catch (e: DeadObjectException) {
            // 服务进程被回收：重置绑定状态，让上层（HandwritingEngine）感知失联并自愈
            invalidateBinding()
            FileLogger.e(TAG, "recognizeHandwriting failed: service died", e)
            emptyList()
        } catch (e: Exception) {
            FileLogger.e(TAG, "recognizeHandwriting failed", e)
            emptyList()
        }
    }

    suspend fun processAudioBytes(input: ByteArray, sampleRate: Int = 16000): ByteArray = withContext(Dispatchers.IO) {
        try {
            requireService().processAudioBytes(input, sampleRate)
        } catch (_: Exception) {
            input
        }
    }
}
