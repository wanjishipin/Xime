package com.kingzcheung.xime.speech

import android.content.Context
import com.kingzcheung.xime.model.ModelCategory
import com.kingzcheung.xime.model.ModelManager
import com.kingzcheung.xime.model.ModelStorage
import com.kingzcheung.xime.speech.models.AsrModelProfile
import com.kingzcheung.xime.speech.models.AsrModelRegistry
import java.io.File

/**
 * ASR 模型管理与选择。
 *
 * 模型推理由自研的 streaming zipformer2 实现（libasr_jni.so）负责。
 * 模型清单与描述来自「扩展商店」远程索引（[ModelManager]，category=asr），
 * 文件角色映射以内置适配注册表（[AsrModelRegistry]）为准；
 * 索引未加载时回退到注册表（内置默认模型 zipformer-zh-int8）。
 */
class AsrModelManager(private val context: Context) {

    companion object {
        /** 内置默认 ASR 模型（远程索引加载前/失败时的兜底）。 */
        val DEFAULT_MODEL = AsrModelRegistry.default.toAsrModelInfo()

        private const val DEFAULT_ID = "zipformer-zh-int8"

        /** 把索引里的 ModelInfo 转换为 ASR 专用模型信息（文件映射按 id 查注册表）。 */
        private fun toAsrModelInfo(info: com.kingzcheung.xime.model.ModelInfo): AsrModelInfo {
            val version = info.resolvedVersion()
            val fileNames = info.files.map { it.name }
            val profile = AsrModelRegistry.profileOrDefault(info.id)
            return profile.toAsrModelInfo(
                name = info.name,
                description = info.description,
                // 旧索引版本节点没有 size 字段（空串），回退到模型级 size
                size = version?.size?.takeIf { it.isNotBlank() } ?: info.size,
                downloadUrl = info.archiveUrl ?: profile.downloadUrl,
                files = fileNames
            )
        }

        private fun AsrModelProfile.toAsrModelInfo(
            name: String = this.name,
            description: String = this.description,
            size: String = this.size,
            downloadUrl: String = this.downloadUrl,
            files: List<String> = listOf(encoderFile, decoderFile, joinerFile, tokensFile)
        ): AsrModelInfo = AsrModelInfo(
            id = id,
            name = name,
            description = description,
            language = language,
            size = size,
            downloadUrl = downloadUrl,
            modelType = "transducer",
            files = files,
            encoderFile = encoderFile,
            decoderFile = decoderFile,
            joinerFile = joinerFile
        )
    }

    data class AsrModelInfo(
        val id: String,
        val name: String,
        val description: String = "",
        val language: String,
        val size: String,
        val downloadUrl: String,
        val modelType: String = "transducer",
        val files: List<String>,
        val encoderFile: String = "",
        val decoderFile: String = "",
        val joinerFile: String = "",
        val needsAutoPunctuation: Boolean = true
    )

    /**
     * ASR 分类的模型清单：索引条目 + 未被索引覆盖的内置适配
     * （后者保证索引上架前选中内置模型也能解析出正确的文件映射）。
     */
    fun getAsrModels(): List<AsrModelInfo> {
        val fromIndex = ModelManager.getModelsByCategory(ModelCategory.ASR)
            .map { toAsrModelInfo(it) }
        if (fromIndex.isEmpty()) return AsrModelRegistry.profiles.map { it.toAsrModelInfo() }
        val indexedIds = fromIndex.map { it.id }.toSet()
        val builtInOnly = AsrModelRegistry.profiles
            .filter { it.id !in indexedIds }
            .map { it.toAsrModelInfo() }
        return fromIndex + builtInOnly
    }

    /** 所有 ASR 模型 id，用于判断某个 id 是否为已知 ASR 模型。 */
    fun getAsrModelIds(): Set<String> = getAsrModels().map { it.id }.toSet()

    fun isModelReady(): Boolean {
        val modelDir = getSelectedModelDir()
        if (!modelDir.exists()) return false
        val files = modelDir.listFiles()
        return files != null && files.isNotEmpty()
    }

    /** 指定模型（含未选中模型）的模型目录是否已有文件。 */
    fun isModelInstalled(modelId: String): Boolean {
        val dir = ModelStorage.getModelDir(context, modelId)
        return dir.isDirectory && dir.listFiles()?.isNotEmpty() == true
    }

    fun getSelectedModelDir(): File {
        val modelId = getSelectedModelId()
        val dir = ModelStorage.getModelDir(context, modelId)
        // 兼容旧版：自动迁移 asr_models/<id>/ 下的模型文件
        ModelStorage.migrateLegacyForModel(context, modelId)
        return dir
    }

    fun getSelectedModelId(): String {
        val sharedPrefs = context.getSharedPreferences("asr_model", Context.MODE_PRIVATE)
        return sharedPrefs.getString("selected_model", DEFAULT_ID) ?: DEFAULT_ID
    }

    /** 当前选中模型的完整信息（索引优先，兜底内置默认）。 */
    fun getSelectedModelInfo(): AsrModelInfo? {
        val modelId = getSelectedModelId()
        return getAsrModels().find { it.id == modelId } ?: DEFAULT_MODEL
    }

    fun setModel(modelId: String) {
        val sharedPrefs = context.getSharedPreferences("asr_model", Context.MODE_PRIVATE)
        sharedPrefs.edit().putString("selected_model", modelId).apply()
    }

    fun findFile(dir: File, fileName: String): File? {
        val direct = File(dir, fileName)
        if (direct.exists()) return direct
        dir.listFiles()?.forEach { child ->
            if (child.isDirectory) {
                val found = findFile(child, fileName)
                if (found != null) return found
            }
        }
        return null
    }
}

