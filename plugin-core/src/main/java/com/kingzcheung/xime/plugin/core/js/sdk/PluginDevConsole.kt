package com.kingzcheung.xime.plugin.core.js.sdk

/**
 * 插件 console 日志的调试落盘通道（仅开发期注册；默认 Noop，release 零行为）。
 *
 * 背景：插件 `console.log/error`（host.log/logError）默认只写 logcat；部分 ROM
 * （如 vivo）对应用后台的日志写入直接拦截，导致真机调试时终端看不到输出。
 * 宿主 debug 构建注册 [Sink] 后，插件日志同时落盘（app 层实现），
 * `xipm dev` / `xipm logs` 通过增量轮询回显——不依赖 logcat。
 */
object PluginDevConsole {

    fun interface Sink {
        /** 插件日志回调（level: "log" / "error"；实现需自行保证非阻塞/异步）。 */
        fun onLog(pluginId: String, level: String, message: String)
    }

    @Volatile
    private var sink: Sink? = null

    /** 注册/解除落盘通道（app 层 debug 构建调用；传 null 解除）。 */
    fun install(sink: Sink?) {
        this.sink = sink
    }

    fun log(pluginId: String, level: String, message: String) {
        sink?.onLog(pluginId, level, message)
    }
}