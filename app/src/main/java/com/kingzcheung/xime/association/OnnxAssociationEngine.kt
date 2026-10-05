package com.kingzcheung.xime.association

import android.content.Context
import com.kingzcheung.xime.model.ModelRuntime
import com.kingzcheung.xime.model.ModelStorage
import com.kingzcheung.xime.service.InferenceClient
import com.kingzcheung.xime.settings.SettingsPreferences
import com.kingzcheung.xime.util.FileLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.runBlocking
import java.io.File

object OnnxAssociationEngine {
    private const val TAG = "OnnxAssociationEngine"

    @Volatile
    private var isInitialized = false
    private var warmupStarted = false
    private val warmupScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var inferenceClient: InferenceClient? = null

    suspend fun initialize(context: Context): Boolean {
        if (isInitialized) {
            FileLogger.d(TAG, "Already initialized")
            return true
        }

        ModelRuntime.register(
            id = "predictive_text",
            loader = { initialize(context) },
            releaser = { release() },
            label = "智能联想模型"
        )

        try {
            // 模型 id 由设置决定，支持 small/base 等多版本共存切换
            val modelId = SettingsPreferences.getPredictionSelectedModel(context)
            val modelDir = ModelStorage.getModelDir(context, modelId)
            modelDir.mkdirs()

            // 兼容旧版：把旧路径模型迁移到统一目录
            ModelStorage.migrateLegacyForModel(context, modelId)

            val filesToCheck = listOf("vocab.json", "model_int8_dynamic.onnx")
            for (fileName in filesToCheck) {
                val file = File(modelDir, fileName)
                if (!file.exists()) {
                    FileLogger.e(TAG, "$fileName not found at ${file.absolutePath}")
                    return false
                }
                FileLogger.d(TAG, "$fileName exists: ${file.length()} bytes")
            }

            val modelFile = File(modelDir, "model_int8_dynamic.onnx")
            val vocabFile = File(modelDir, "vocab.json")
            FileLogger.d(TAG, "Using model: ${modelFile.name} (${modelFile.length()} bytes)")

            val client = InferenceClient(context)
            // 先释放旧 client 的绑定，避免每次 initialize 泄漏一个 ServiceConnection
            // （ServiceConnectionLeaked：切换预测设置时反复 initialize 累积泄漏）
            inferenceClient?.unbind()
            inferenceClient = client
            client.onDisconnected = {
                // 服务进程被回收后 AUTO_CREATE 自动重启只恢复 binder，模型不会自己回来：
                // 软重置本地状态（不 unbind，保留系统自动重启与连接），下次预测由
                // AssociationManager.predict 的引擎状态守卫检测并重新 load，
                // 避免"重启抢先→服务端空返回→联想静默失效"
                isInitialized = false
                ModelRuntime.markUnloaded("predictive_text")
            }

            if (!client.ensureBound()) {
                FileLogger.e(TAG, "Failed to bind to InferenceService")
                return false
            }
            val ok = client.loadModel(
                InferenceClient.MODEL_PREDICTION,
                modelFile.absolutePath,
                vocabFile.absolutePath
            )
            if (!ok) {
                FileLogger.e(TAG, "Failed to load prediction model in inference process")
                return false
            }

            isInitialized = true
            FileLogger.i(TAG, "Prediction model loaded via IPC")
            ModelRuntime.markLoaded("predictive_text")
            return true
        } catch (e: Exception) {
            FileLogger.e(TAG, "Failed to initialize prediction: ${e.message}", e)
            return false
        }
    }

    suspend fun predict(inputText: String, topK: Int = 20): List<AssociationCandidate> = withContext(Dispatchers.Default) {
        val client = inferenceClient
        if (!isInitialized || client == null) {
            FileLogger.e(TAG, "Engine not initialized")
            return@withContext emptyList()
        }

        // 服务进程被系统回收（设计内行为）后 binder 失效：这里必须重置本地
        // "已初始化"假象，否则联想将永远空转（无法重新 bind + loadModel）。
        // 重置后由 AssociationManager.predict 的引擎状态守卫检测到并按需
        // 重新加载（ModelRuntime.load → 本类 initialize），实现自愈。
        if (!client.isBound()) {
            FileLogger.w(TAG, "InferenceService not bound, resetting engine state for recovery")
            release()
            return@withContext emptyList()
        }

        try {
            client.predict(inputText, topK)
        } catch (e: Exception) {
            FileLogger.e(TAG, "Prediction failed: ${e.message}", e)
            emptyList()
        }
    }

    fun startWarmup() {
        if (!isInitialized || warmupStarted) return
        warmupStarted = true
        warmupScope.launch {
            try {
                val client = inferenceClient ?: return@launch
                client.predict("，", 5)
            } catch (e: Exception) {
                FileLogger.w(TAG, "Warmup prediction failed (non-fatal): ${e.message}")
            }
        }
    }

    fun release() {
        isInitialized = false
        inferenceClient?.apply {
            runBlocking { runCatching { unloadModel(InferenceClient.MODEL_PREDICTION) } }
            unbind()
        }
        inferenceClient = null
        ModelRuntime.markUnloaded("predictive_text")
        FileLogger.d(TAG, "Prediction model released")
    }

    fun isInitialized(): Boolean = isInitialized
}
