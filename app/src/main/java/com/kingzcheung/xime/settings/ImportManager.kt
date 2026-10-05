package com.kingzcheung.xime.settings

import android.content.Context
import android.net.Uri
import com.kingzcheung.xime.plugin.core.model.PluginInfo
import com.kingzcheung.xime.plugin.core.model.PluginSource
import com.kingzcheung.xime.plugin.core.runtime.PluginManager
import com.kingzcheung.xime.plugin.core.runtime.installer.InstallerManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream

/**
 * 统一的导入入口。
 *
 * 设计原则：**路由永远按「文件名 / 内容类型」集中在这里决定**，而不是由调用方传入的
 * "方向"参数决定。这样无论是文件选择器、分享、还是浏览器导入（web /upload），都汇聚到
 * 同一个路由核心，结构上不可能出现各入口路由逻辑不同步的问题。
 *
 * 调用方（各页面）只需要关心它从哪个入口触发导入，并对返回的 [ImportResult] 给出对应的
 * 成功文案与刷新动作即可。
 */
object ImportManager {

    sealed class ImportResult {
        /** 方案 / 词典 / 背景图等，走 [SchemaManager.saveImportedFile] 的落地结果。 */
        data class Content(
            val success: Boolean,
            val installedDirect: Boolean,
            /** 与已安装方案同名不同内容的文件冲突；非空时导入被拒绝，用于向用户说明原因。 */
            val conflicts: List<FileConflictInfo> = emptyList(),
        ) : ImportResult()

        /** 插件（.xipk）安装结果。 */
        data class Plugin(val pluginInfo: PluginInfo?) : ImportResult()

        data class Failed(val reason: String) : ImportResult()
        data class Unsupported(val fileName: String) : ImportResult()
    }

    /** 判断文件名是否为插件包（仅 .xipk）。 */
    fun isPluginFile(name: String): Boolean =
        name.endsWith(".xipk", ignoreCase = true)

    /** 从 URI 导入（文件选择器、分享等）。按文件名自动路由。 */
    suspend fun import(context: Context, uri: Uri): ImportResult = withContext(Dispatchers.IO) {
        val displayName = queryDisplayName(context, uri)
            ?: return@withContext ImportResult.Failed("无法识别文件名")
        val name = SchemaManager.sanitizeDisplayName(displayName)

        if (isPluginFile(name)) {
            when (val r = PluginManager.installerManager.installPluginFromUri(uri)) {
                is InstallerManager.InstallResult.Success -> ImportResult.Plugin(r.pluginInfo)
                is InstallerManager.InstallResult.Failure -> ImportResult.Failed(r.reason)
            }
        } else {
            val stream = when (uri.scheme) {
                "file" -> java.io.FileInputStream(uri.path!!)
                else -> context.contentResolver.openInputStream(uri)
            }
            if (stream == null) {
                ImportResult.Failed("无法读取文件")
            } else {
                val r = SchemaManager.saveImportedFile(context, name, stream)
                ImportResult.Content(r.success, r.installedDirect, r.conflicts)
            }
        }
    }

    /**
     * 从本地文件导入（浏览器上传的临时 partFile 等）。按文件名自动路由。
     *
     * @param autoEnable 是否在导入 .schema.yaml 时自动加入启用方案列表。
     *                   浏览器导入沿用历史行为传 false。
     */
    suspend fun importFile(
        context: Context,
        name: String,
        file: File,
        autoEnable: Boolean = true,
    ): ImportResult = withContext(Dispatchers.IO) {
        val safe = SchemaManager.sanitizeDisplayName(name)

        if (isPluginFile(safe)) {
            when (val r = PluginManager.installerManager.installPlugin(file, source = PluginSource.FILE)) {
                is InstallerManager.InstallResult.Success -> ImportResult.Plugin(r.pluginInfo)
                is InstallerManager.InstallResult.Failure -> ImportResult.Failed(r.reason)
            }
        } else {
            val r = SchemaManager.saveImportedFile(context, safe, file.inputStream(), autoEnable = autoEnable)
            ImportResult.Content(r.success, r.installedDirect, r.conflicts)
        }
    }

    private fun queryDisplayName(context: Context, uri: Uri): String? {
        return try {
            context.contentResolver.query(
                uri,
                arrayOf(android.provider.OpenableColumns.DISPLAY_NAME),
                null, null, null
            )?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        } catch (_: Exception) {
            null
        } ?: uri.lastPathSegment
    }
}
