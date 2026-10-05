package com.kingzcheung.xime.plugin

import android.content.Context
import android.util.Log
import com.kingzcheung.xime.plugin.core.model.PluginSource
import com.kingzcheung.xime.plugin.core.runtime.PluginManager
import com.kingzcheung.xime.plugin.core.runtime.installer.InstallerManager
import org.json.JSONObject
import java.io.File

/**
 * 插件热安装公共逻辑（`xipm dev` 调试用，debug/release 通用，开关门禁见
 * [DevPluginInstallActivity]）。
 *
 * 结果双通道：
 * - logcat：`INSTALL_OK ...` / `INSTALL_FAIL ...`（tag=XipmDev；release 通道 CLI
 *   经 `logcat -s XipmDev` 轮询解析）
 * - 落盘：`files/logs/xipm-dev-result.jsonl`（stage=received/done，debug 通道 CLI
 *   经 run-as 读取）
 */
object DevPluginInstaller {

    private const val TAG = "XipmDev"

    /** release 兼容通道的暂存目录前缀（shell 0644，任何应用可读，装完即删）。 */
    private const val COMPAT_DIR = "/data/local/tmp/"

    /** 覆盖安装并重载；返回是否成功。 */
    suspend fun install(context: Context, path: String?): Boolean {
        appendResult(context, stage = "received", ok = null, pluginId = null, message = "path=$path")
        if (path.isNullOrBlank()) {
            Log.e(TAG, "INSTALL_FAIL 缺少安装路径")
            appendResult(context, "done", false, null, "缺少安装路径")
            return false
        }
        val ok = try {
            PluginManager.awaitInitialization()
            val file = File(path)
            if (!file.isFile) {
                Log.e(TAG, "INSTALL_FAIL 文件不存在或不可读: $path")
                appendResult(context, "done", false, null, "文件不存在或不可读: $path")
                false
            } else when (
                val result = PluginManager.installerManager.installPlugin(
                    file,
                    forceOverwrite = true,
                    source = PluginSource.FILE,
                )
            ) {
                is InstallerManager.InstallResult.Success -> {
                    val id = result.pluginInfo.id
                    val reloaded = PluginManager.launchPlugin(id)
                    Log.i(TAG, "INSTALL_OK $id reload=$reloaded version=${result.pluginInfo.version}")
                    appendResult(
                        context, "done", true, id,
                        "reload=$reloaded version=${result.pluginInfo.version}"
                    )
                    true
                }

                is InstallerManager.InstallResult.Failure -> {
                    Log.e(TAG, "INSTALL_FAIL ${result.reason}")
                    appendResult(context, "done", false, null, result.reason)
                    false
                }
            }
        } catch (e: Throwable) {
            Log.e(TAG, "INSTALL_FAIL 安装异常", e)
            appendResult(context, "done", false, null, "安装异常: ${e.message}")
            false
        }
        deleteCompatSource(path)
        return ok
    }

    /** release 兼容通道：清理 /data/local/tmp 暂存副本（无论成败）。 */
    private fun deleteCompatSource(path: String) {
        if (path.startsWith(COMPAT_DIR)) {
            runCatching { File(path).delete() }
        }
    }

    /** 回执落盘（debug 通道 CLI 经 run-as 读取；失败不影响主流程）。 */
    fun appendResult(
        context: Context,
        stage: String,
        ok: Boolean?,
        pluginId: String?,
        message: String,
    ) {
        try {
            val file = File(context.filesDir, "logs/xipm-dev-result.jsonl")
            file.parentFile?.mkdirs()
            val json = JSONObject().apply {
                put("t", System.currentTimeMillis())
                put("stage", stage)
                ok?.let { put("ok", it) }
                pluginId?.let { put("p", it) }
                put("m", message)
            }
            file.appendText(json.toString() + "\n")
        } catch (_: Throwable) {
            // 回执失败不影响安装
        }
    }
}
