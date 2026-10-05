package com.kingzcheung.xime.plugin.core.js

import android.util.Log
import com.kingzcheung.xime.plugin.core.api.ClipboardProfile
import com.kingzcheung.xime.plugin.core.api.ClipboardSyncPlugin
import com.kingzcheung.xime.plugin.core.js.sdk.JsPluginContract
import com.kingzcheung.xime.plugin.core.model.PluginContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * clipboard_sync 类型 JS 插件的宿主侧适配器：实现 [ClipboardSyncPlugin] 接口。
 *
 * 协议逻辑（WebDAV / S3 / ximed HTTP）全部由插件 JS 用 `host.http` + `host.crypto`
 * 承载，本类只做接口桥接：
 * - push(profile)      → JS `clipboardSync.push(profile)`，profile 字段 camelCase
 *                        （`data` 附件字节经 JS 桥转 `Uint8Array`）
 * - pull()             → JS `clipboardSync.pull()`，返回 profile 对象（null → 无变更；
 *                        图片 profile 的 `text` 为空串但 `hasData=true`，判空须两者兼顾）
 * - testConnection()   → JS `clipboardSync.test()`，返回错误消息（null/空 → 成功）
 */
class JsClipboardSyncPluginAdapter(
    runtime: JsScriptRuntime,
    pluginContext: PluginContext
) : JsPluginAdapter(runtime, pluginContext), ClipboardSyncPlugin {

    override suspend fun push(profile: ClipboardProfile): Boolean = withContext(Dispatchers.IO) {
        try {
            val result = runtime.callAsync(
                JsPluginContract.PATH_CLIPBOARD_PUSH,
                mapOf(
                    "type" to profile.type,
                    "hash" to profile.hash,
                    "text" to profile.text,
                    "hasData" to profile.hasData,
                    "dataName" to profile.dataName,
                    "data" to profile.data,
                    "size" to profile.size,
                    "source" to profile.source
                )
            )
            (JsScriptRuntime.jsToKotlin(result) as? Boolean) ?: false
        } catch (e: Exception) {
            Log.e("JsClipboardSync", "push failed", e)
            false
        }
    }

    override suspend fun pull(): ClipboardProfile? = withContext(Dispatchers.IO) {
        try {
            val result = runtime.callAsync(JsPluginContract.PATH_CLIPBOARD_PULL)
            val map = JsScriptRuntime.jsToKotlin(result) as? Map<*, *> ?: return@withContext null
            val text = map["text"]?.toString() ?: ""
            val hasData = (map["hasData"] as? Boolean) ?: false
            // 判空必须同时看 hasData：图片 profile 的 text 是空串（决策 D12），
            // 只按 "text 非空" 判空会让纯图片条目永远被当作"无变更"。
            if (text.isEmpty() && !hasData) return@withContext null
            val data = map["data"] as? ByteArray
            val hash = map["hash"]?.toString()?.takeIf { it.isNotEmpty() }
                ?: if (data != null) {
                    ClipboardProfile.sha256Hex(data)
                } else {
                    ClipboardProfile.sha256Hex(text.toByteArray(Charsets.UTF_8))
                }
            ClipboardProfile(
                type = map["type"]?.toString() ?: "text",
                hash = hash,
                text = text,
                hasData = hasData,
                dataName = map["dataName"]?.toString(),
                data = data,
                size = (map["size"] as? Number)?.toLong() ?: 0,
                source = map["source"]?.toString()
            )
        } catch (e: Exception) {
            Log.e("JsClipboardSync", "pull failed", e)
            null
        }
    }

    override suspend fun testConnection(): String? = withContext(Dispatchers.IO) {
        try {
            val result = runtime.callAsync(JsPluginContract.PATH_CLIPBOARD_TEST)
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
}