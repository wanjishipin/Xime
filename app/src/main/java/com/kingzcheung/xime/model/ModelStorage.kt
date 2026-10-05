package com.kingzcheung.xime.model

import android.content.Context
import com.kingzcheung.xime.util.FileLogger
import java.io.File

/**
 * 统一的模型存储目录管理。
 *
 * 所有模型统一存放到 `filesDir/models/<modelId>/`，按模型 id 区分。
 *
 * 旧版本曾把模型分散存放在 `filesDir/`（联想）、`filesDir/asr_models/<id>/`（ASR）。
 * 通过 [migrateLegacy] 在首次访问时把旧目录中的
 * 模型文件自动迁移到新目录，保证升级后已下载的模型仍可用。
 */
object ModelStorage {

    private const val TAG = "ModelStorage"

    /** 统一根目录：filesDir/models/ */
    fun getModelsRoot(context: Context): File = File(context.filesDir, "models")

    /** 某模型的目录：filesDir/models/<modelId>/ */
    fun getModelDir(context: Context, modelId: String): File {
        return File(getModelsRoot(context), modelId)
    }

    /**
     * 已知模型的旧路径迁移表。key 为模型 id，value 为旧目录下的文件名。
     * 旧目录由 [legacyDirFor] 根据模型 id 推导。
     */
    private fun legacyDirFor(context: Context, modelId: String): File? {
        return when (modelId) {
            "predictive-text-small" -> context.filesDir
            // base 联想模型是新增版本，无旧路径，无需迁移
            "predictive-text-base" -> null
            // 手写模型：旧版在 filesDir 根目录
            "ochwpro" -> context.filesDir
            else -> {
                // ASR 类：旧目录为 filesDir/asr_models/<id>/
                val asrDir = File(context.filesDir, "asr_models/$modelId")
                if (asrDir.exists() && asrDir.isDirectory) asrDir else null
            }
        }
    }

    /**
     * 按已知旧路径迁移模型到统一目录。
     * 调用方需在加载/检测模型前调用，确保已下载的旧版模型可用。
     * 联想模型（predictive-text-small）旧路径在 filesDir 根目录，仅迁移其两个模型文件，
     * 避免误移 rime 等其他数据。
     */
    fun migrateLegacyForModel(context: Context, modelId: String): Boolean {
        // 旧版联想 small 与手写模型直接放在 filesDir 根目录，仅迁移其专属文件，避免误移其他数据
        if (modelId == "predictive-text-small") {
            return migrateLegacy(
                context, modelId,
                listOf(context.filesDir),
                listOf("vocab.json", "model_int8_dynamic.onnx")
            )
        }
        if (modelId == "ochwpro") {
            return migrateLegacy(
                context, modelId,
                listOf(context.filesDir),
                listOf("ochwpro.onnx", "char_index.json")
            )
        }
        val legacyDir = legacyDirFor(context, modelId) ?: return false
        val targetDir = getModelDir(context, modelId)
        if (targetDir.listFiles()?.isNotEmpty() == true) return true
        legacyDir.listFiles()?.filter { it.isFile && it.length() > 0 }?.forEach { f ->
            val target = File(targetDir, f.name)
            if (!target.exists()) {
                try {
                    if (f.renameTo(target)) {
                        FileLogger.i(TAG, "Migrated ${f.name} to ${target.absolutePath}")
                    } else {
                        f.copyTo(target, overwrite = true); f.delete()
                    }
                } catch (e: Exception) {
                    FileLogger.e(TAG, "Failed to migrate ${f.name}: ${e.message}")
                }
            }
        }
        return true
    }

    /**
     * 迁移旧路径中的模型文件到统一目录。
     *
     * @param legacyDirs 可能存放旧模型的目录（按优先级，最先找到文件的优先）
     * @param fileNames  需要迁移的文件名（只迁移这些文件，避免误移其他数据）
     * @return true 表示找到了旧模型并迁移成功（或新目录已有模型无需迁移）
     */
    fun migrateLegacy(context: Context, modelId: String, legacyDirs: List<File>, fileNames: List<String>): Boolean {
        val targetDir = getModelDir(context, modelId)

        // 新目录已有模型文件，无需迁移
        if (targetDir.listFiles()?.isNotEmpty() == true) {
            return true
        }

        // 在旧目录中查找指定文件
        val legacyFiles = mutableListOf<Pair<File, File>>() // (source, targetName)
        for (legacyDir in legacyDirs) {
            if (!legacyDir.exists() || !legacyDir.isDirectory) continue
            for (name in fileNames) {
                val f = File(legacyDir, name)
                if (f.exists() && f.isFile && f.length() > 0) {
                    legacyFiles.add(Pair(f, File(targetDir, f.name)))
                }
            }
        }

        if (legacyFiles.isEmpty()) return false

        // 迁移到新目录
        targetDir.mkdirs()
        for ((source, target) in legacyFiles) {
            try {
                if (source.renameTo(target)) {
                    FileLogger.i(TAG, "Migrated ${source.name} to ${target.absolutePath}")
                } else {
                    source.copyTo(target, overwrite = true)
                    source.delete()
                    FileLogger.i(TAG, "Copied ${source.name} to ${target.absolutePath}")
                }
            } catch (e: Exception) {
                FileLogger.e(TAG, "Failed to migrate ${source.name}: ${e.message}")
            }
        }
        return true
    }
}
