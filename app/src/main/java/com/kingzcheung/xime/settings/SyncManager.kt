package com.kingzcheung.xime.settings

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.kingzcheung.xime.plugin.core.api.BackupPlugin
import com.kingzcheung.xime.plugin.core.api.RemoteBackupEntry
import com.kingzcheung.xime.rime.RimeEngine
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * rime 原生用户词典同步：
 *
 * - sync 目录固定在引擎用户目录下（filesDir/rime/sync/<installation_id> 下的 .userdb.txt），
 *   与桌面端 rime 的 sync 目录同构；快照为 TSV 文本，librime 以时间戳合并进 userdb，
 *   规避对 leveldb 文件做整体覆盖的一致性风险。
 * - 数据不出私有目录，无外部存储权限：导入/导出走文件选择器与 Downloads，
 *   远端走备份插件通道（快照包固定名 rime-sync-<installation_id>.zip，
 *   与云备份的配置包在同一远端目录下按前缀隔离，互不感知）。
 *
 * 与云备份（[BackupManager]）的语义分工：云备份=时间点覆盖式灾难恢复（全量），
 * 词库同步=增量合并（仅用户词典）。
 */
object SyncManager {

    /** 远端快照包文件名前缀：与配置备份条目在远端列表中区分 */
    const val REMOTE_SYNC_PREFIX = "rime-sync-"

    private const val IMPORT_DIR_PREFIX = "imported"

    /** librime 文本快照的文件头（UserDictManager 导出格式） */
    private const val SNAPSHOT_MAGIC = "Rime user dictionary export"

    // ---------- installation id ----------

    /**
     * 稳定 installation id 核心（纯 JVM 便于单测）：yaml 缺失或 id 不一致时
     * 以 [stableId] 重写最小集（仅 installation_id，其余字段由 librime 的
     * installation_update 维护并保留该 id）。
     * 场景：部署会删除 installation.yaml；云备份恢复可能带回旧 id 的文件。
     */
    internal fun ensureInstallationFile(yamlFile: File, stableId: String): String {
        val currentId = if (yamlFile.exists()) {
            yamlFile.readLines().firstOrNull { it.trimStart().startsWith("installation_id:") }
                ?.substringAfter(':')?.trim()?.trim('"', '\'')
        } else null
        if (currentId == stableId) return stableId
        yamlFile.parentFile?.mkdirs()
        yamlFile.writeText("installation_id: \"$stableId\"\n")
        return stableId
    }

    fun ensureInstallationYaml(context: Context): String =
        ensureInstallationFile(
            File(File(context.filesDir, "rime"), "installation.yaml"),
            SettingsPreferences.getRimeInstallationId(context)
        )

    // ---------- 同步 ----------

    /** 立即同步：合并 sync 目录下已有快照 + 导出本机快照。须在引擎所在进程调用。 */
    fun syncNow(context: Context): Result<Unit> {
        ensureInstallationYaml(context)
        if (!RimeEngine.isInitialized()) {
            return Result.failure(IllegalStateException("输入法引擎尚未初始化，请先在任意输入框唤起键盘一次"))
        }
        return if (RimeEngine.getInstance().syncUserData()) {
            SettingsPreferences.setLastRimeSyncAt(context, System.currentTimeMillis())
            Result.success(Unit)
        } else {
            Result.failure(IllegalStateException("同步未完成，详见应用日志"))
        }
    }

    // ---------- 打包 / 解包（纯 JVM，单测锚定） ----------

    /**
     * 打包 sync 目录：包内条目为 rime 目录相对路径（sync/...）。
     * @return zip 字节流；sync 目录不存在或为空时返回 null
     */
    internal fun packSyncDir(rimeDir: File): ByteArray? {
        val syncDir = File(rimeDir, "sync")
        if (!syncDir.isDirectory) return null
        val bos = ByteArrayOutputStream()
        var count = 0
        ZipOutputStream(bos).use { zos ->
            syncDir.walkTopDown().filter { it.isFile }.forEach { f ->
                zos.putNextEntry(ZipEntry(f.relativeTo(rimeDir).path.replace('\\', '/')))
                f.inputStream().use { it.copyTo(zos) }
                zos.closeEntry()
                count++
            }
        }
        return if (count == 0) null else bos.toByteArray()
    }

    /**
     * 解包快照 zip 到 [baseDir]：条目路径可为 "sync/<id>/..." 或 "<id>/..."
     * （两种打包来源），统一剥掉 sync/ 前缀后落盘。
     * 含路径穿越/逃逸 baseDir 条目的包整体抛出 [SecurityException]。
     * @return 落盘文件数
     */
    internal fun unpackArchive(baseDir: File, bytes: ByteArray): Int {
        val canonicalBase = baseDir.canonicalPath + File.separator
        var count = 0
        ZipInputStream(ByteArrayInputStream(bytes)).use { zis ->
            while (true) {
                val entry = zis.nextEntry ?: break
                if (entry.isDirectory) continue
                var rel = entry.name.replace('\\', '/')
                if (rel.startsWith("sync/")) rel = rel.removePrefix("sync/")
                if (rel.isEmpty()) continue
                val target = File(baseDir, rel)
                if (!target.canonicalPath.startsWith(canonicalBase)) {
                    throw SecurityException("路径穿越: ${entry.name}")
                }
                target.parentFile?.mkdirs()
                target.outputStream().use { zis.copyTo(it) }
                count++
            }
        }
        return count
    }

    private fun isZip(bytes: ByteArray): Boolean =
        bytes.size >= 2 && bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte()

    private fun isSnapshotText(bytes: ByteArray): Boolean {
        val head = bytes.take(64).toByteArray().toString(Charsets.US_ASCII).trimStart()
        return head.startsWith(SNAPSHOT_MAGIC)
    }

    private fun queryDisplayName(context: Context, uri: Uri): String? =
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }

    // ---------- 导入 ----------

    /**
     * 导入快照（文件选择器）：接受快照包 zip 与裸 .userdb.txt 文本快照
     * （其他设备/桌面端在其 sync 目录中生成），落入 sync/imported/<uuid>/，
     * 随后自动触发一次同步合并。
     * @return 导入的快照文件数
     */
    fun importSnapshots(context: Context, uris: List<Uri>): Result<Int> {
        if (uris.isEmpty()) return Result.failure(IllegalArgumentException("未选择文件"))
        val rimeDir = File(context.filesDir, "rime")
        val targetDir = File(File(rimeDir, "sync"), "$IMPORT_DIR_PREFIX/${UUID.randomUUID()}")
        return try {
            targetDir.mkdirs()
            var count = 0
            for (uri in uris) {
                val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    ?: return Result.failure(IllegalStateException("无法读取所选文件"))
                count += when {
                    isZip(bytes) -> unpackArchive(targetDir, bytes)
                    isSnapshotText(bytes) -> {
                        val raw = queryDisplayName(context, uri) ?: "snapshot.userdb.txt"
                        val safeName = raw.substringAfterLast('/').replace(Regex("[\\\\/:*?\"<>|]"), "_")
                        File(targetDir, safeName).writeBytes(bytes)
                        1
                    }
                    else -> return Result.failure(IllegalArgumentException("无法识别的文件：需要快照 zip 或 .userdb.txt 文本快照"))
                }
            }
            if (count == 0) {
                Result.failure(IllegalArgumentException("所选文件中没有快照"))
            } else {
                syncNow(context).map { count }
            }
        } catch (e: SecurityException) {
            Result.failure(e)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // ---------- 导出 ----------

    /** 将 sync 目录打包 zip 保存到 Downloads，返回文件名。 */
    fun exportToDownloads(context: Context): Result<String> {
        val bytes = packSyncDir(File(context.filesDir, "rime"))
            ?: return Result.failure(IllegalStateException("sync 目录为空，请先执行一次同步"))
        val dateStr = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
        return RimeExportManager.saveSyncArchive(context, "Xime词库快照-$dateStr.zip", bytes)
    }

    // ---------- 远端（备份插件通道） ----------

    internal fun isRemoteSyncEntry(name: String): Boolean = name.startsWith(REMOTE_SYNC_PREFIX)

    /** 远端快照条目（已按前缀过滤；获取列表失败返回 null）。 */
    suspend fun listRemoteSnapshots(plugin: BackupPlugin): List<RemoteBackupEntry>? =
        plugin.listBackups()?.filter { isRemoteSyncEntry(it.name) }

    /**
     * 远端同步：本地同步 → 拉取其他设备的快照包并解包合并 → 再次本地同步 →
     * 推送本机包（固定名覆盖，每台设备一份）。须在引擎所在进程调用。
     * @return 拉取合并的远端设备包数
     */
    suspend fun remoteSync(context: Context, plugin: BackupPlugin): Result<Int> {
        val rimeDir = File(context.filesDir, "rime")
        syncNow(context).getOrElse { return Result.failure(it) }

        val entries = plugin.listBackups()
            ?: return Result.failure(IllegalStateException("获取远端列表失败"))
        val myName = "$REMOTE_SYNC_PREFIX${SettingsPreferences.getRimeInstallationId(context)}.zip"
        val others = entries.filter { isRemoteSyncEntry(it.name) && it.name != myName }

        var pulled = 0
        for (entry in others) {
            val bytes = plugin.pullBackup(entry.id) ?: continue
            unpackArchive(File(rimeDir, "sync"), bytes)
            pulled++
        }
        if (pulled > 0) {
            syncNow(context).getOrElse { return Result.failure(it) }
        }

        val bytes = packSyncDir(rimeDir)
            ?: return Result.failure(IllegalStateException("本地快照为空"))
        val push = plugin.pushBackup(myName, bytes)
        if (!push.ok) {
            return Result.failure(IllegalStateException(push.message ?: "上传快照失败"))
        }
        return Result.success(pulled)
    }

    /** 删除远端本机快照包（解绑设备时使用）。 */
    suspend fun deleteRemoteSnapshot(context: Context, plugin: BackupPlugin): Boolean {
        val myName = "$REMOTE_SYNC_PREFIX${SettingsPreferences.getRimeInstallationId(context)}.zip"
        val entries = plugin.listBackups() ?: return false
        val mine = entries.firstOrNull { it.name == myName } ?: return true
        return plugin.deleteBackup(mine.id)
    }
}
