package com.kingzcheung.xime.model

import android.content.Context
import com.kingzcheung.xime.util.FileLogger
import java.io.File

object ModelManager {

    private const val TAG = "ModelManager"

    private var initialized = false
    private val _modelsFlow = kotlinx.coroutines.flow.MutableStateFlow<List<ModelInfo>>(emptyList())

    /** 可观察的模型清单（远程 index 加载后自动更新） */
    val modelsFlow: kotlinx.coroutines.flow.StateFlow<List<ModelInfo>> = _modelsFlow

    fun initialize() {
        if (initialized) return
        initialized = true
        FileLogger.i(TAG, "ModelManager initialized")
    }

    /** 模型清单完全来自远程 index（xime_index.base_urls 指向的 models/index.yaml）。
     *  以 StateFlow 内的不可变列表为唯一状态源：读方拿到的是发布时的快照，
     *  与后续刷新互不干扰（历史上共享可变列表曾被遍历方并发 clear/addAll，
     *  连点刷新触发 ConcurrentModificationException 闪退）。 */
    private fun allModels(): List<ModelInfo> = _modelsFlow.value

    fun getAllModels(): List<ModelInfo> = allModels()

    fun getModel(id: String): ModelInfo? = allModels().find { it.id == id }

    fun getModelsByCategory(category: ModelCategory): List<ModelInfo> =
        allModels().filter { it.category == category }

    suspend fun loadFromRemote(context: Context) {
        val remote = ModelIndexLoader.loadFromRemote(context)
        if (remote.isNotEmpty()) {
            FileLogger.i(TAG, "Loaded ${remote.size} models from remote index")
        } else {
            FileLogger.w(TAG, "Remote index returned empty, no models available")
        }
        _modelsFlow.value = remote
    }

    fun isUsingRemoteIndex(): Boolean = _modelsFlow.value.isNotEmpty()

    fun isModelDownloaded(context: Context, id: String): Boolean {
        val model = getModel(id) ?: return false
        return isModelDownloaded(context, model)
    }

    fun isModelDownloaded(context: Context, model: ModelInfo): Boolean {
        // 检测前先尝试迁移旧路径模型到统一目录，保证已下载的旧版可用
        ModelStorage.migrateLegacyForModel(context, model.id)
        val dir = getModelStorageDir(context, model) ?: return false
        if (!dir.exists()) return false

        return model.files.all { fileInfo ->
            val file = if (model.archiveUrl != null) {
                findFileInDir(dir, fileInfo.name)
            } else {
                File(dir, fileInfo.name)
            }
            file.exists() && file.length() > 0
        }
    }

    fun getModelStorageDir(context: Context, model: ModelInfo): File? {
        // 统一规则：所有模型一律存 filesDir/models/<id>/
        return ModelStorage.getModelDir(context, model.id)
    }

    fun getModelStorageDir(context: Context, id: String): File? {
        val model = getModel(id) ?: return null
        return getModelStorageDir(context, model)
    }

    fun getModelSizeOnDisk(context: Context, id: String): Long {
        val model = getModel(id) ?: return 0
        val dir = getModelStorageDir(context, model) ?: return 0
        if (!dir.exists()) return 0

        return model.files.sumOf { fileInfo ->
            val file = if (model.archiveUrl != null) {
                findFileInDir(dir, fileInfo.name)
            } else {
                File(dir, fileInfo.name)
            }
            if (file.exists()) file.length() else 0
        }
    }

    fun getDownloadedModels(context: Context): List<ModelInfo> {
        return allModels().filter { isModelDownloaded(context, it) }
    }

    suspend fun downloadModel(
        context: Context,
        id: String,
        onProgress: (ModelDownloadState) -> Unit
    ) {
        val model = getModel(id)
        if (model == null) {
            onProgress(ModelDownloadState.Error("未知模型: $id"))
            return
        }
        downloadModel(context, model, onProgress)
    }

    suspend fun downloadModel(
        context: Context,
        model: ModelInfo,
        onProgress: (ModelDownloadState) -> Unit,
        version: ModelVersion? = null
    ) {
        ModelDownloader.downloadModel(context, model, onProgress, version)
    }

    fun deleteModel(context: Context, id: String): Boolean {
        val model = getModel(id) ?: return false
        return deleteModel(context, model)
    }

    fun deleteModel(context: Context, model: ModelInfo): Boolean {
        val dir = getModelStorageDir(context, model) ?: return false
        if (!dir.exists()) return false

        var success = true
        for (fileInfo in model.files) {
            val file = if (model.archiveUrl != null) {
                findFileInDir(dir, fileInfo.name)
            } else {
                File(dir, fileInfo.name)
            }
            if (file.exists() && !file.delete()) {
                success = false
            }
        }
        if (success) {
            com.kingzcheung.xime.settings.MarketVersionStore.removeModelVersion(context, model.id)
        }

        return success
    }

    private fun findFileInDir(dir: File, fileName: String): File {
        val direct = File(dir, fileName)
        if (direct.exists()) return direct

        dir.listFiles()?.forEach { child ->
            if (child.isDirectory) {
                val found = findFileInDir(child, fileName)
                if (found.exists()) return found
            }
        }

        return direct
    }
}
