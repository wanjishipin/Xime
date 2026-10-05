package com.kingzcheung.xime.plugin.core.security.crash

import android.app.Application
import android.util.Log
import com.kingzcheung.xime.plugin.core.model.PluginCrashInfo
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

object PluginCrashHandler : Thread.UncaughtExceptionHandler {

    const val EXTRA_CRASH_INFO = "CRASH_INFO"
    private const val TAG = "PluginCrashHandler"

    private lateinit var context: Application
    private var defaultHandler: Thread.UncaughtExceptionHandler? = null
    private var globalCallback: IPluginCrashCallback? = null
    private val pluginCallbacks = ConcurrentHashMap<String, IPluginCrashCallback>()

    fun initialize(context: Application) {
        if (this::context.isInitialized) {
            Log.w(TAG, "PluginCrashHandler already initialized")
            return
        }
        this.context = context
        this.defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler(this)
        Log.i(TAG, "Plugin crash handler registered")
    }

    override fun uncaughtException(thread: Thread, throwable: Throwable) {
        try {
            val wasHandled = handlePluginRelatedException(throwable)
            if (!wasHandled) {
                Log.d(TAG, "Exception not plugin-related, delegating to default handler")
                defaultHandler?.uncaughtException(thread, throwable)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in PluginCrashHandler", e)
            defaultHandler?.uncaughtException(thread, throwable)
        }
    }

    /**
     * Lua 插件在沙箱内执行，脚本错误不会传播到宿主进程；且无法通过栈帧类名归属
     * （Lua 无 class），故统一视为非插件异常，交给默认处理器。保留本方法以便未来
     * 扩展按调用方注册的 callback 处理插件相关崩溃。
     */
    private fun handlePluginRelatedException(throwable: Throwable): Boolean {
        val culpritPluginId = findCulpritPluginId(throwable)
        if (culpritPluginId == null) return false
        val crashInfo = PluginCrashInfo(
            throwable = throwable,
            culpritPluginId = culpritPluginId,
            defaultMessage = buildDefaultMessage(throwable, culpritPluginId)
        )
        pluginCallbacks[culpritPluginId]?.let { callback ->
            if (callback.onOtherPluginException(crashInfo) == true) return true
        }
        globalCallback?.let { callback ->
            if (callback.onOtherPluginException(crashInfo) == true) return true
        }
        return false
    }

    /**
     * Lua 插件在沙箱内执行，脚本错误不会传播到宿主进程；
     * 已加载插件列表无法通过栈帧类名归属（Lua 无 class），故固定返回 null。
     */
    private fun findCulpritPluginId(throwable: Throwable?): String? = null

    private fun buildDefaultMessage(throwable: Throwable, pluginId: String): String =
        "Plugin '$pluginId' crashed: ${throwable.message}"

    @OptIn(DelicateCoroutinesApi::class)
    fun setGlobalCrashCallback(callback: IPluginCrashCallback?) {
        GlobalScope.launch {
            globalCallback = callback
        }
    }

    @OptIn(DelicateCoroutinesApi::class)
    fun setCrashCallback(pluginId: String, callback: IPluginCrashCallback?) {
        GlobalScope.launch {
            if (callback == null) {
                pluginCallbacks.remove(pluginId)
            } else {
                pluginCallbacks[pluginId] = callback
            }
        }
    }

    fun clearCallbacks() {
        globalCallback = null
        pluginCallbacks.clear()
    }
}