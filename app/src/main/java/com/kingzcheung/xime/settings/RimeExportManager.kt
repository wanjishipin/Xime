package com.kingzcheung.xime.settings

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

enum class ExportMode(val label: String) {
    CONFIG_ONLY("仅配置文件"),
    FULL_BACKUP("完整备份")
}

data class ExportResult(
    val uri: Uri?,
    val fileName: String,
    val savedToDownloads: Boolean
)

object RimeExportManager {

    private const val TAG = "RimeExportManager"

    fun shareSingleFile(context: Context, file: File) {
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file
        )
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = inferMimeType(file)
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            clipData = android.content.ClipData.newRawUri(null, uri)
        }
        val chooser = Intent.createChooser(intent, "分享文件")
        context.startActivity(chooser)
    }

    fun exportArchive(context: Context, mode: ExportMode): Result<ExportResult> {
        try {
            val (fileName, zipBytes) = buildArchive(context, mode).getOrElse { return Result.failure(it) }
            val tempZip = File(context.cacheDir, fileName)
            tempZip.writeBytes(zipBytes)
            val savedToDownloads = saveToDownloads(context, tempZip, fileName)
            tempZip.delete()

            val resultUri = if (savedToDownloads) {
                resolveDownloadsUri(context, fileName)
            } else {
                null
            }

            return Result.success(ExportResult(resultUri, fileName, savedToDownloads))
        } catch (e: Exception) {
            android.util.Log.e(TAG, "exportArchive failed", e)
            return Result.failure(e)
        }
    }

    /**
     * 生成备份包字节流（不落盘、不保存到 Downloads），供本地导出与云备份（BackupManager）共用。
     *
     * 包内容（v1 格式，`_xime_backup/` 前缀外均为 rime 目录相对路径）：
     * - rime 目录文件（含 userdb / t9_digit.userdb 自造词，过滤规则见 [shouldInclude]）
     * - 设置项、插件配置、plugins.xml（两种模式都含）
     * - 插件包 filesDir/plugins/ 下全部文件（仅完整备份，包体可能数 MB）
     *
     * @return Pair(文件名, zip 字节流)
     */
    fun buildArchive(context: Context, mode: ExportMode): Result<Pair<String, ByteArray>> {
        try {
            val dateStr = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
            val fileName = "Xime配置-$dateStr.zip"
            val rimeDir = File(context.filesDir, "rime")
            if (!rimeDir.exists()) {
                return Result.failure(Exception("Rime 目录不存在"))
            }

            val bos = java.io.ByteArrayOutputStream()
            ZipOutputStream(bos).use { zos ->
                rimeDir.walkTopDown().forEach { file ->
                    if (file.isDirectory) return@forEach
                    val relativePath = file.relativeTo(rimeDir).path.replace('\\', '/')
                    if (!shouldInclude(relativePath, mode)) return@forEach
                    zos.putNextEntry(ZipEntry(relativePath))
                    file.inputStream().use { it.copyTo(zos) }
                    zos.closeEntry()
                }
                BackupManager.collectMetaEntries(context, mode).forEach { (entryName, bytes) ->
                    zos.putNextEntry(ZipEntry(entryName))
                    zos.write(bytes)
                    zos.closeEntry()
                }
                if (mode == ExportMode.FULL_BACKUP) {
                    val pluginsDir = File(context.filesDir, "plugins")
                    pluginsDir.walkTopDown().forEach { file ->
                        if (file.isDirectory) return@forEach
                        val entryName = BackupManager.META_PREFIX + "plugins/" +
                            file.relativeTo(pluginsDir).path.replace('\\', '/')
                        zos.putNextEntry(ZipEntry(entryName))
                        file.inputStream().use { it.copyTo(zos) }
                        zos.closeEntry()
                    }
                }
            }

            val bytes = bos.toByteArray()
            if (bytes.isEmpty()) {
                return Result.failure(Exception("没有可导出的文件"))
            }
            return Result.success(fileName to bytes)
        } catch (e: Exception) {
            android.util.Log.e(TAG, "buildArchive failed", e)
            return Result.failure(e)
        }
    }

    /**
     * 保存任意 zip 字节流到 Downloads（词库同步快照包由 SyncManager 打包后经此落盘），
     * 返回文件名。
     */
    fun saveSyncArchive(context: Context, fileName: String, bytes: ByteArray): Result<String> {
        return try {
            val tempZip = File(context.cacheDir, fileName)
            tempZip.writeBytes(bytes)
            val ok = saveToDownloads(context, tempZip, fileName)
            tempZip.delete()
            if (ok) Result.success(fileName)
            else Result.failure(IllegalStateException("保存到下载目录失败"))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun saveToDownloads(context: Context, zipFile: File, fileName: String): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                    put(MediaStore.Downloads.MIME_TYPE, "application/zip")
                    put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                }
                val uri = context.contentResolver.insert(
                    MediaStore.Downloads.EXTERNAL_CONTENT_URI, values
                )
                if (uri == null) return false
                context.contentResolver.openOutputStream(uri)?.use { output ->
                    zipFile.inputStream().use { it.copyTo(output) }
                }
                true
            } else {
                @Suppress("DEPRECATION")
                val destDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                if (!destDir.exists()) destDir.mkdirs()
                val dest = File(destDir, fileName)
                zipFile.copyTo(dest, overwrite = true)
                true
            }
        } catch (e: Exception) {
            android.util.Log.e(TAG, "saveToDownloads failed", e)
            false
        }
    }

    private fun resolveDownloadsUri(context: Context, fileName: String): Uri? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
            val projection = arrayOf(MediaStore.Downloads._ID)
            val selection = "${MediaStore.Downloads.DISPLAY_NAME} = ?"
            val selectionArgs = arrayOf(fileName)
            context.contentResolver.query(collection, projection, selection, selectionArgs, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val id = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Downloads._ID))
                    return Uri.withAppendedPath(collection, id.toString())
                }
            }
        }
        return null
    }

    private fun shouldInclude(relativePath: String, mode: ExportMode): Boolean {
        if (mode == ExportMode.FULL_BACKUP) return true
        return when {
            relativePath.startsWith("build/") -> false
            // 用户词典一律不走配置备份：leveldb 文件级快照有一致性风险，
            // 且恢复=整体覆盖会吃掉其他设备后打的词——词典统一走词库同步快照（合并语义）
            relativePath.contains(".userdb") -> false
            // 词库同步快照有自己的传输通道（SyncManager）：
            // 混进备份包会让旧快照随恢复回滚、已删词条借合并复活
            relativePath == "sync" || relativePath.startsWith("sync/") -> false
            relativePath.startsWith("opencc/") -> true
            relativePath.endsWith(".bin") -> false
            relativePath.endsWith(".gram") -> false
            relativePath.endsWith(".db") -> false
            relativePath.endsWith(".db-wal") -> false
            relativePath.endsWith(".db-shm") -> false
            else -> true
        }
    }

    private fun inferMimeType(file: File): String {
        return when {
            file.name.endsWith(".yaml") -> "text/vnd.yaml"
            file.name.endsWith(".txt") -> "text/plain"
            file.name.endsWith(".bin") -> "application/octet-stream"
            file.name.endsWith(".gram") -> "application/octet-stream"
            file.name.endsWith(".zip") -> "application/zip"
            else -> "application/octet-stream"
        }
    }
}
