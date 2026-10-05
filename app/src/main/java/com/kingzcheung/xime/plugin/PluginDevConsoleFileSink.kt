package com.kingzcheung.xime.plugin

import android.content.Context
import com.kingzcheung.xime.plugin.core.js.sdk.PluginDevConsole
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors

/**
 * 插件 console 日志的调试落盘实现（仅 app debug 构建注册，release 零行为）。
 *
 * 写入 `files/logs/plugins/dev-console.jsonl`（短键 {t,p,l,m}），
 * 供 `xipm dev` / `xipm logs` 增量轮询实时回显——不依赖 logcat
 * （vivo 等 ROM 会拦截后台应用日志写入，导致真机调试看不到 console 输出）。
 * 单线程异步落盘，不阻塞插件执行线程；超过大小上限时重建（dev-only 数据）。
 */
object PluginDevConsoleFileSink {

    private const val MAX_FILE_BYTES = 2L * 1024 * 1024
    private const val FILE_NAME = "logs/plugins/dev-console.jsonl"

    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "xime-dev-console").apply { isDaemon = true }
    }

    fun install(context: Context) {
        val appContext = context.applicationContext
        PluginDevConsole.install { pluginId, level, message ->
            try {
                executor.execute { append(appContext, pluginId, level, message) }
            } catch (_: Throwable) {
                // 落盘失败不影响插件运行
            }
        }
    }

    private fun append(context: Context, pluginId: String, level: String, message: String) {
        try {
            val file = File(context.filesDir, FILE_NAME)
            file.parentFile?.mkdirs()
            if (file.length() > MAX_FILE_BYTES) {
                file.delete()
            }
            val line = JSONObject().apply {
                put("t", System.currentTimeMillis())
                put("p", pluginId)
                put("l", level)
                put("m", message)
            }
            file.appendText(line.toString() + "\n")
        } catch (_: Throwable) {
            // dev-only 通道：任何异常静默
        }
    }
}