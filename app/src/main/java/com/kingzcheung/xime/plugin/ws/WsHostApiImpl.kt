package com.kingzcheung.xime.plugin.ws

import android.content.Context
import android.net.Uri
import android.util.Log
import com.kingzcheung.xime.plugin.ExtensionManager
import com.kingzcheung.xime.plugin.core.js.ws.NetworkPolicy
import com.kingzcheung.xime.plugin.core.js.ws.WsHostApi
import com.kingzcheung.xime.plugin.core.js.ws.WsHostListener
import com.kingzcheung.xime.settings.SettingsPreferences
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString.Companion.toByteString
import java.util.concurrent.TimeUnit

/**
 * 宿主通用 WebSocket 白名单 API。
 *
 * - 连接 URL 域名须通过 [NetworkPolicy]：插件已声明且经用户授权，否则拒绝
 *   （插件无法静默发起任意网络请求，自定义服务器域名需用户在插件中心手动授权）
 * - 协议无关：只提供收发原语，prebuffer/协议组装/结果解析全部由插件 Lua 承载
 */
class WsHostApiImpl(
    private val context: Context,
    private val pluginId: String
) : WsHostApi {

    companion object {
        private const val TAG = "WsHostApi"

        private const val STATE_IDLE = 0
        private const val STATE_CONNECTING = 1
        private const val STATE_OPEN = 2
        private const val STATE_CLOSED = 3
    }

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .build()

    @Volatile
    private var listener: WsHostListener? = null

    @Volatile
    private var webSocket: WebSocket? = null

    @Volatile
    private var state = STATE_IDLE

    @Volatile
    private var lastErrorMsg: String? = null

    override fun connect(url: String, headers: Map<String, String>, listener: WsHostListener): Boolean {
        val pluginInfo = com.kingzcheung.xime.plugin.core.runtime.PluginManager.getAllInstallPlugins()
            .firstOrNull { it.id == pluginId }
        val declaredHosts = pluginInfo?.declaredHosts ?: emptyList()
        val authorizedHosts = SettingsPreferences.getPluginAuthorizedHosts(context, pluginId)
        val customHosts = ExtensionManager.getConfiguredNetworkHosts(context, pluginId).toSet()

        val reason = NetworkPolicy.check(url, emptySet(), declaredHosts, authorizedHosts, customHosts)
        if (reason != null) {
            lastErrorMsg = reason
            Log.w(TAG, "[$pluginId] 联网被拒绝: $reason")
            com.kingzcheung.xime.plugin.core.security.PluginErrorLog.logError(
                pluginId,
                "联网被拒绝",
                reason,
                category = com.kingzcheung.xime.plugin.core.security.ErrorCategory.NETWORK_DENIED
            )
            com.kingzcheung.xime.plugin.PluginNetworkAuthHelper.onNetworkDenied(
                context, pluginId, pluginInfo?.name,
                NetworkPolicy.extractHost(url), reason
            )
            return false
        }
        lastErrorMsg = null

        synchronized(this) {
            if (webSocket != null) {
                Log.w(TAG, "Already connected, reusing")
                return true
            }
            val requestBuilder = Request.Builder().url(url)
            headers.forEach { (k, v) -> requestBuilder.addHeader(k, v) }
            this.listener = listener
            webSocket = client.newWebSocket(requestBuilder.build(), wsListener)
            state = STATE_CONNECTING
            Log.d(TAG, "[$pluginId] Connecting to ${NetworkPolicy.extractHost(url)}")
        }
        return true
    }

    override fun sendText(message: String): Boolean {
        val socket = webSocket
        if (socket == null) {
            lastErrorMsg = "连接未建立"
            return false
        }
        return try {
            val accepted = socket.send(message)
            if (!accepted) lastErrorMsg = "发送队列已满"
            accepted
        } catch (e: Exception) {
            lastErrorMsg = "发送失败: ${e.message}"
            Log.e(TAG, "sendText failed", e)
            false
        }
    }

    override fun sendBinary(data: ByteArray): Boolean {
        val socket = webSocket
        if (socket == null) {
            lastErrorMsg = "连接未建立"
            return false
        }
        return try {
            val accepted = socket.send(data.toByteString())
            if (!accepted) lastErrorMsg = "发送队列已满"
            accepted
        } catch (e: Exception) {
            lastErrorMsg = "发送失败: ${e.message}"
            Log.e(TAG, "sendBinary failed", e)
            false
        }
    }

    override fun close() {
        synchronized(this) {
            webSocket?.close(1000, "closed")
            webSocket = null
            state = STATE_CLOSED
            listener = null
        }
    }

    override fun getState(): Int = state

    override fun lastError(): String? = lastErrorMsg

    private fun extractServerError(body: String): String? {
        if (body.isBlank()) return null
        return try {
            org.json.JSONObject(body).optString("error").takeIf { it.isNotBlank() }
        } catch (e: Exception) {
            null
        }
    }

    private val wsListener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            state = STATE_OPEN
            listener?.onOpen()
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            listener?.onMessage(text)
        }

        override fun onMessage(webSocket: WebSocket, bytes: okio.ByteString) {
            listener?.onBinary(bytes.toByteArray())
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            val serverMsg = response?.let { r ->
                val body = try { r.body?.string() ?: "" } catch (e: Exception) { "" }
                val reason = extractServerError(body) ?: "HTTP ${r.code}"
                "HTTP ${r.code}: $reason"
            } ?: ""
            Log.e(TAG, "WS failure: ${t.message} | $serverMsg")
            com.kingzcheung.xime.plugin.core.security.PluginErrorLog.logError(
                pluginId,
                "WS 连接失败",
                serverMsg.ifEmpty { t.message ?: "连接失败" },
                t,
                category = com.kingzcheung.xime.plugin.core.security.ErrorCategory.STREAM_ERROR
            )
            if (!markClosed(webSocket)) return
            listener?.onError(serverMsg.ifEmpty { t.message ?: "连接失败" })
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            if (!markClosed(webSocket)) return
            listener?.onClose()
        }
    }

    /**
     * 当前连接已终止：置空 socket 并复位状态。
     *
     * 必须置空：否则下一次 [connect] 会命中"Already connected, reusing"而返回一个**死连接**——
     * 不再有 onOpen，插件的状态机永远停在未就绪（音频只能进 prebuffer）。
     *
     * @return 事件是否属于当前连接；false = 已被替换的旧连接的迟到回调（[close] 主动关闭后
     *         OkHttp 仍会回调），此时不得清掉新连接、也不得通知新会话的监听器。
     */
    private fun markClosed(socket: WebSocket): Boolean {
        synchronized(this) {
            if (webSocket !== socket) return false
            webSocket = null
            state = STATE_CLOSED
        }
        return true
    }
}
