package com.kingzcheung.xime.plugin

import com.kingzcheung.xime.plugin.core.security.ErrorCategory
import com.kingzcheung.xime.plugin.core.security.PluginErrorLog
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.io.File

/**
 * 插件错误日志的 JSONL 落盘实现。
 *
 * - 文件路径由构造方指定（宿主传 filesDir/logs/plugins/errors.jsonl）。
 * - 每行一个 JSON 对象（kotlinx-serialization-json，无外部依赖）；stackTrace 含换行由 JSON 转义保证单行。
 * - 上限 [MAX_TOTAL_LINES] 行 / [MAX_FILE_BYTES] 字节，超限时从最旧行截断（错误频率低，重写开销可忽略）。
 * - 调用方（PluginErrorLog 的 storeExecutor）串行访问，本类无需内部加锁。
 */
class FilePluginErrorStore(private val errorFile: File) : PluginErrorLog.PluginErrorStore {

    companion object {
        private const val TAG = "FilePluginErrorStore"
        private const val MAX_TOTAL_LINES = 300
        private const val MAX_FILE_BYTES = 512 * 1024

        private val json = Json {
            encodeDefaults = true
            ignoreUnknownKeys = true
        }

        /** 单条错误 → JSON 行；无法编码时返回 null（调用方丢弃该条）。 */
        internal fun toLine(error: PluginErrorLog.PluginError): String? {
            return try {
                buildJsonObject {
                    put("t", error.timestamp)
                    put("p", error.pluginId)
                    put("o", error.operation)
                    put("m", error.message)
                    error.stackTrace?.let { put("s", it) }
                    put("c", error.category.name)
                }.toString()
            } catch (e: Exception) {
                android.util.Log.e(TAG, "encode error line failed", e)
                null
            }
        }

        /** JSON 行 → 错误；坏行返回 null（跳过，不中断恢复）。 */
        internal fun fromLine(line: String): PluginErrorLog.PluginError? {
            return try {
                val v = json.parseToJsonElement(line) as? JsonObject ?: return null
                fun str(key: String): String? = (v[key] as? JsonPrimitive)?.contentOrNull
                PluginErrorLog.PluginError(
                    timestamp = (v["t"] as? JsonPrimitive)?.longOrNull ?: System.currentTimeMillis(),
                    pluginId = str("p") ?: return null,
                    operation = str("o").orEmpty(),
                    message = str("m").orEmpty(),
                    stackTrace = str("s"),
                    category = runCatching {
                        ErrorCategory.valueOf(str("c").orEmpty())
                    }.getOrDefault(ErrorCategory.OTHER)
                )
            } catch (e: Exception) {
                android.util.Log.w(TAG, "skip malformed error line: ${line.take(120)}")
                null
            }
        }
    }

    private fun readAllLines(): List<String> {
        if (!errorFile.exists()) return emptyList()
        return try {
            errorFile.readLines()
        } catch (e: Exception) {
            android.util.Log.e(TAG, "read error file failed", e)
            emptyList()
        }
    }

    private fun writeLines(lines: List<String>) {
        try {
            errorFile.parentFile?.mkdirs()
            errorFile.writeText(lines.joinToString("\n") + if (lines.isEmpty()) "" else "\n")
        } catch (e: Exception) {
            android.util.Log.e(TAG, "write error file failed", e)
        }
    }

    override fun load(): List<PluginErrorLog.PluginError> {
        return readAllLines().mapNotNull { fromLine(it) }
    }

    override fun append(pluginId: String, error: PluginErrorLog.PluginError) {
        val line = toLine(error) ?: return
        val lines = readAllLines()
        val trimmed = if (lines.size >= MAX_TOTAL_LINES || (errorFile.length() > MAX_FILE_BYTES)) {
            lines.takeLast(MAX_TOTAL_LINES - 1)
        } else {
            lines
        }
        writeLines(trimmed + line)
    }

    override fun clearAll(pluginId: String) {
        val kept = readAllLines().filter { line ->
            fromLine(line)?.pluginId != pluginId
        }
        writeLines(kept)
    }

    init {
        android.util.Log.i(TAG, "Plugin error file: ${errorFile.absolutePath}")
    }
}