package com.kingzcheung.xime.handwriting

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.kingzcheung.xime.model.ModelStorage
import com.kingzcheung.xime.service.InferenceClient
import com.kingzcheung.xime.util.FileLogger
import kotlinx.coroutines.runBlocking
import java.io.File

data class HandwritingCandidate(
    val char: String,
    val score: Float,
)

/**
 * 手写识别客户端（主进程）：只负责模型生命周期管理与 IPC 代理。
 * 笔画预处理、ONNX 推理与 idx→汉字映射全部在 :inference 进程完成
 * （见 [HandwritingInference]），主进程不加载 ONNX 运行库、不常驻词表。
 */
object HandwritingEngine {
    private const val TAG = "HandwritingEngine"
    private const val DEFAULT_TOP_K = 10
    /** 自愈重试节流：:inference 进程回收后重载失败时，短时间内不重复 bind 空转。 */
    private const val REINIT_RETRY_INTERVAL_MS = 5_000L

    @Volatile
    private var initialized = false
    private var inferenceClient: InferenceClient? = null
    private var appContext: Context? = null

    @Volatile
    private var lastReinitAttemptMs = 0L

    fun initialize(context: Context): Boolean {
        if (initialized && inferenceClient?.isBound() == true) return true

        val ctx = context.applicationContext
        val modelDir = ModelStorage.getModelDir(ctx, "ochwpro")
        // 兼容旧版：旧版手写模型在 filesDir 根目录
        ModelStorage.migrateLegacyForModel(ctx, "ochwpro")
        val modelFile = File(modelDir, "ochwpro.onnx")
        val charIndexFile = File(modelDir, "char_index.json")

        if (!modelFile.exists() || !charIndexFile.exists()) {
            Log.w(TAG, "Model files not found: $modelFile, $charIndexFile")
            return false
        }

        try {
            val client = InferenceClient(ctx)
            // 先释放旧 client 的绑定，避免重复 initialize 泄漏 ServiceConnection
            inferenceClient?.unbind()
            inferenceClient = client
            appContext = ctx
            client.onDisconnected = {
                // 进程被回收后系统 AUTO_CREATE 自动重启只恢复 binder，模型不会自己回来：
                // 仅软重置本地"已加载"标志（不 unbind，保留自动重启），下次落笔由
                // ensureEngineReady 检测到未初始化后重载，避免"重启抢先→静默空结果"
                initialized = false
            }

            // 调用方均在后台线程（键盘 LaunchedEffect(IO)/切方案 Thread），runBlocking 桥接 IPC
            val bound = runBlocking { client.ensureBound() }
            if (!bound) {
                Log.e(TAG, "Failed to bind InferenceService for handwriting")
                return false
            }
            val loaded = runBlocking {
                client.loadModel(
                    InferenceClient.MODEL_HANDWRITING,
                    modelFile.absolutePath,
                    charIndexFile.absolutePath
                )
            }
            if (!loaded) {
                Log.e(TAG, "Failed to load handwriting model in inference process")
                return false
            }

            initialized = true
            Log.i(TAG, "Handwriting engine initialized via IPC")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "HandwritingEngine init failed: ${e.message}", e)
            FileLogger.e(TAG, "HandwritingEngine init failed: ${e.message}", e)
            return false
        }
    }

    fun isInitialized(): Boolean = initialized

    /** 手写模型是否可用（含旧版 filesDir 根目录模型迁移后检查）。 */
    fun hasModel(context: Context): Boolean {
        ModelStorage.migrateLegacyForModel(context, "ochwpro")
        val modelDir = ModelStorage.getModelDir(context, "ochwpro")
        return File(modelDir, "ochwpro.onnx").exists() && File(modelDir, "char_index.json").exists()
    }

    fun predict(
        strokes: List<List<Pair<Float, Float>>>,
        topK: Int = DEFAULT_TOP_K
    ): List<HandwritingCandidate> {
        if (strokes.isEmpty()) return emptyList()
        if (!ensureEngineReady()) return emptyList()
        val client = inferenceClient ?: return emptyList()

        // 原始笔画扁平化：points = [x0,y0,x1,y1,...]，counts = 每笔画点数
        var totalPoints = 0
        for (stroke in strokes) totalPoints += stroke.size
        val points = FloatArray(totalPoints * 2)
        val counts = IntArray(strokes.size)
        var pi = 0
        strokes.forEachIndexed { i, stroke ->
            counts[i] = stroke.size
            for (pt in stroke) {
                points[pi++] = pt.first
                points[pi++] = pt.second
            }
        }

        val candidates = runBlocking { client.recognizeHandwriting(points, counts, topK) }

        // IPC 期间服务进程被回收（客户端已吞 DeadObjectException 并重置绑定）：
        // 重置本地状态，下次 predict 由 ensureEngineReady 自愈重载
        if (!client.isBound()) {
            release()
            return emptyList()
        }
        return candidates
    }

    /**
     * 预测前确保引擎可用。:inference 进程被系统回收（设计内行为，同联想模型）
     * 后在此按需重载（重新 bind + loadModel）实现自愈。从未初始化过时直接返回
     * false，保持"无模型时 predict 返回空"的既有语义，不自动拉起服务。
     */
    private fun ensureEngineReady(): Boolean {
        val ctx = appContext ?: return false
        if (initialized && inferenceClient?.isBound() == true) return true

        release()
        val now = SystemClock.elapsedRealtime()
        if (now - lastReinitAttemptMs < REINIT_RETRY_INTERVAL_MS) return false
        lastReinitAttemptMs = now
        return initialize(ctx)
    }

    fun release() {
        if (!initialized && inferenceClient == null) return
        initialized = false
        inferenceClient?.apply {
            try {
                runBlocking { unloadModel(InferenceClient.MODEL_HANDWRITING) }
            } catch (_: Exception) {
            }
            unbind()
        }
        inferenceClient = null
    }
}
