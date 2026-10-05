package com.kingzcheung.xime.plugin.core.js

import android.util.Log
import com.kingzcheung.xime.plugin.core.api.BackupPlugin
import com.kingzcheung.xime.plugin.core.api.BackupResult
import com.kingzcheung.xime.plugin.core.api.RemoteBackupEntry
import com.kingzcheung.xime.plugin.core.js.sdk.JsPluginContract
import com.kingzcheung.xime.plugin.core.model.PluginContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * backup 类型 JS 插件的宿主侧适配器：实现 [BackupPlugin] 接口。
 *
 * 备份包生成/恢复由宿主 BackupManager 承载，协议逻辑（WebDAV / S3 / 自建 HTTP）
 * 由插件 JS 用 `host.http` + `host.crypto` 承载。字节（zip 归档）以 Uint8Array 跨桥：
 * - backup.push({name, archive}) → 插件返回 {ok, id?, message?} 或 bool
 * - backup.pull(id)              → 插件返回 zip 字节（null/undefined → 失败）
 * - backup.list()                → {id,name,createdAt,size} 数组
 * - backup.remove(id) / backup.test()
 */
class JsBackupPluginAdapter(
    runtime: JsScriptRuntime,
    pluginContext: PluginContext
) : JsPluginAdapter(runtime, pluginContext), BackupPlugin {

    override suspend fun pushBackup(name: String, archive: ByteArray): BackupResult =
        withContext(Dispatchers.IO) {
            try {
                val result = runtime.callAsync(
                    JsPluginContract.PATH_BACKUP_PUSH,
                    mapOf("name" to name, "archive" to archive)
                )
                parseBackupResult(result)
            } catch (e: Exception) {
                Log.e("JsBackup", "pushBackup failed", e)
                BackupResult(ok = false, message = e.message ?: "pushBackup failed")
            }
        }

    override suspend fun pullBackup(id: String): ByteArray? = withContext(Dispatchers.IO) {
        try {
            val result = runtime.callAsync(JsPluginContract.PATH_BACKUP_PULL, id)
            when (val v = JsScriptRuntime.jsToKotlin(result)) {
                is ByteArray -> v
                else -> null
            }
        } catch (e: Exception) {
            Log.e("JsBackup", "pullBackup failed", e)
            null
        }
    }

    override suspend fun listBackups(): List<RemoteBackupEntry>? = withContext(Dispatchers.IO) {
        try {
            val result = runtime.callAsync(JsPluginContract.PATH_BACKUP_LIST)
            val items = JsScriptRuntime.jsToKotlin(result) as? List<*> ?: return@withContext null
            items.mapNotNull { item ->
                val map = JsScriptRuntime.jsToKotlin(item) as? Map<*, *> ?: return@mapNotNull null
                val id = map["id"]?.toString()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                RemoteBackupEntry(
                    id = id,
                    name = map["name"]?.toString() ?: id,
                    createdAt = (map["createdAt"] as? Number)?.toLong() ?: 0L,
                    size = (map["size"] as? Number)?.toLong() ?: -1L
                )
            }
        } catch (e: Exception) {
            Log.e("JsBackup", "listBackups failed", e)
            null
        }
    }

    override suspend fun deleteBackup(id: String): Boolean = withContext(Dispatchers.IO) {
        try {
            (JsScriptRuntime.jsToKotlin(runtime.callAsync(JsPluginContract.PATH_BACKUP_REMOVE, id)) as? Boolean) ?: false
        } catch (e: Exception) {
            Log.e("JsBackup", "deleteBackup failed", e)
            false
        }
    }

    override suspend fun testConnection(): String? = withContext(Dispatchers.IO) {
        try {
            val result = runtime.callAsync(JsPluginContract.PATH_BACKUP_TEST)
            val v = JsScriptRuntime.jsToKotlin(result)
            when (v) {
                null -> null
                is Boolean -> if (v) null else "连接失败"
                else -> v.toString().takeIf { it.isNotBlank() }
            }
        } catch (e: Exception) {
            e.message ?: "connection test failed"
        }
    }

    /** pushBackup 返回值兼容两种形态：bool 直接映射，对象取 {ok, id, message}。 */
    private fun parseBackupResult(result: Any?): BackupResult {
        val v = JsScriptRuntime.jsToKotlin(result)
        if (v is Map<*, *>) {
            val ok = (v["ok"] as? Boolean) ?: false
            return BackupResult(
                ok = ok,
                id = v["id"]?.toString()?.takeIf { it.isNotEmpty() },
                message = v["message"]?.toString()?.takeIf { it.isNotEmpty() }
            )
        }
        return BackupResult(ok = (v as? Boolean) ?: false)
    }
}