package com.kingzcheung.xime.plugin

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import com.kingzcheung.xime.MainActivity
import com.kingzcheung.xime.plugin.core.runtime.PluginManager
import com.kingzcheung.xime.settings.SettingsPreferences

/**
 * 插件联网授权引导：
 * - [ensureAuthorized]：使用前检测——插件声明/配置了网络域名但未授权时，引导用户先授权；
 * - [onNetworkDenied]：运行时兜底——被拒时记录待授权域名、Toast 提示原因，
 *   首次被拒自动拉起该插件的网络授权页，让用户当场授权。
 */
object PluginNetworkAuthHelper {

    private val mainHandler = Handler(Looper.getMainLooper())

    /** 使用前检测：返回 true 放行；false 表示存在未授权域名，已引导用户授权。 */
    fun ensureAuthorized(context: Context, pluginId: String): Boolean {
        val info = PluginManager.getAllInstallPlugins().firstOrNull { it.id == pluginId } ?: return true
        val authorized = SettingsPreferences.getPluginAuthorizedHosts(context, pluginId)
        val candidates = (info.declaredHosts +
            ExtensionManager.getConfiguredNetworkHosts(context, pluginId) +
            SettingsPreferences.getPluginPendingHosts(context, pluginId)).distinct()
        val unauthorized = candidates.filter { it !in authorized }
        if (unauthorized.isEmpty()) return true

        val host = unauthorized.first()
        val firstTime = SettingsPreferences.addPluginPendingHost(context, pluginId, host)
        mainHandler.post {
            if (firstTime) {
                // 仅首次断 Toast 引导（避免反复未授权时持续打扰），错误详情在插件中心/日志查看器
                Toast.makeText(
                    context,
                    "插件「${info.name}」需要授权后才能联网访问 $host\n已为您打开授权页面，请授权后重试",
                    Toast.LENGTH_LONG
                ).show()
                runCatching {
                    context.startActivity(MainActivity.buildPluginAuthIntent(context, pluginId))
                }
            }
        }
        return false
    }

    fun onNetworkDenied(
        context: Context,
        pluginId: String,
        pluginName: String?,
        host: String?,
        reason: String
    ) {
        if (host == null) return
        val firstTime = SettingsPreferences.addPluginPendingHost(context, pluginId, host)
        mainHandler.post {
            if (firstTime) {
                // 仅首次断 Toast 引导（避免插件重试/轮询时连环弹提示），后续被拒静默进错误日志
                val name = pluginName ?: pluginId
                Toast.makeText(
                    context,
                    "插件「$name」联网被拒绝：$reason\n请在插件设置中授权后重试",
                    Toast.LENGTH_LONG
                ).show()
                runCatching {
                    context.startActivity(MainActivity.buildPluginAuthIntent(context, pluginId))
                }
            }
        }
    }
}