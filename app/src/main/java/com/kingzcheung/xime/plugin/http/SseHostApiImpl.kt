package com.kingzcheung.xime.plugin.http

import android.content.Context
import android.util.Log
import com.kingzcheung.xime.plugin.ExtensionManager
import com.kingzcheung.xime.plugin.PluginNetworkAuthHelper
import com.kingzcheung.xime.plugin.core.js.http.SseHostApi
import com.kingzcheung.xime.plugin.core.js.http.SseHostListener
import com.kingzcheung.xime.plugin.core.js.ws.NetworkPolicy
import com.kingzcheung.xime.plugin.core.runtime.PluginManager
import com.kingzcheung.xime.plugin.core.security.PluginErrorLog
import com.kingzcheung.xime.settings.SettingsPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * 宿主通用 SSE 流式 HTTP 白名单 API。
 *
 * - URL 域名须通过 [NetworkPolicy]（同 [HttpHostApiImpl]）：插件已声明且经用户授权，
 *   否则拒绝（插件无法静默发起任意网络请求，自定义服务器域名需用户手动授权）
 * - 异步回调模型：[SseHostListener.onData] 每条 SSE 事件回调一次，onDone 收尾，onError 报错
 * - 支持任意 HTTP 方法（AI 对话接口通常为 POST）；解析由 okhttp-sse 的 [EventSourceListener] 承载
 * - 会话句柄：[close] 中断单个会话；中断后不再回调
 */
class SseHostApiImpl(
    private val context: Context,
    private val pluginId: String
) : SseHostApi {

    companion object {
        private const val TAG = "SseHostApi"
    }

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private val sessions = ConcurrentHashMap<Int, Session>()
    private val nextId = AtomicInteger(1)

    @Volatile
    private var lastErrorMsg: String? = null

    private class Session(val id: Int, val eventSource: EventSource) {
        @Volatile
        var closed = false
        val totalText = StringBuilder()
    }

    override fun connect(
        url: String,
        headers: Map<String, String>,
        listener: SseHostListener,
        timeoutMillis: Int?,
        method: String,
        body: ByteArray?
    ): Int {
        val pluginInfo = PluginManager.getAllInstallPlugins()
            .firstOrNull { it.id == pluginId }
        val declaredHosts = pluginInfo?.declaredHosts ?: emptyList()
        val authorizedHosts = SettingsPreferences.getPluginAuthorizedHosts(context, pluginId)
        val customHosts = ExtensionManager.getConfiguredNetworkHosts(context, pluginId).toSet()

        val reason = NetworkPolicy.check(url, emptySet(), declaredHosts, authorizedHosts, customHosts)
        if (reason != null) {
            lastErrorMsg = reason
            Log.w(TAG, "[$pluginId] 联网被拒绝: $reason")
            PluginErrorLog.logError(
                pluginId,
                "联网被拒绝",
                reason,
                category = com.kingzcheung.xime.plugin.core.security.ErrorCategory.NETWORK_DENIED
            )
            PluginNetworkAuthHelper.onNetworkDenied(
                context, pluginId, pluginInfo?.name,
                NetworkPolicy.extractHost(url), reason
            )
            return -1
        }
        lastErrorMsg = null

        return try {
            val requestBuilder = Request.Builder().url(url)
            headers.forEach { (k, v) -> requestBuilder.addHeader(k, v) }
            // 与 HttpHostApiImpl 一致：尊重调用方显式声明的 Content-Type，避免 OkHttp 用
            // RequestBody 默认 mediaType 覆盖（阿里云 MaaS 网关对 octet-stream 请求挂起）。
            // header 名大小写不敏感：Lua 插件可能传 "content-type"。
            if (body != null) {
                val contentType = headers.entries.firstOrNull {
                    it.key.equals("Content-Type", ignoreCase = true)
                }?.value ?: "application/octet-stream"
                requestBuilder.method(method.uppercase(), body.toRequestBody(contentType.toMediaType()))
            } else {
                requestBuilder.method(method.uppercase(), null)
            }
            val request = requestBuilder.build()

            val effectiveClient = if (timeoutMillis != null && timeoutMillis > 0) {
                client.newBuilder()
                    .connectTimeout(timeoutMillis.toLong(), TimeUnit.MILLISECONDS)
                    .readTimeout(timeoutMillis.toLong(), TimeUnit.MILLISECONDS)
                    .build()
            } else {
                client
            }

            val id = nextId.getAndIncrement()
            var current: Session? = null
            Log.d(TAG, "[$pluginId] SSE 会话 $id 建立: $url body=${body?.toString(Charsets.UTF_8)?.take(300) ?: "<空>"}")
            val esListener = object : EventSourceListener() {
                override fun onEvent(eventSource: EventSource, id: String?, type: String?, data: String) {
                    val s = current ?: return
                    if (s.closed) return
                    s.totalText.append(data)
                    Log.d(TAG, "[$pluginId] SSE 会话 ${s.id} 收到事件: ${data.take(200)}")
                    listener.onData(data)
                }

                override fun onClosed(eventSource: EventSource) {
                    val s = current ?: return
                    if (!s.closed) {
                        listener.onDone(s.totalText.toString())
                    }
                    sessions.remove(s.id)
                    Log.d(TAG, "[$pluginId] SSE 会话 ${s.id} 正常结束")
                }

                override fun onFailure(eventSource: EventSource, t: Throwable?, response: Response?) {
                    val s = current ?: return
                    if (s.closed) {
                        // 主动 close：静默终止，不再回调
                        sessions.remove(s.id)
                        return
                    }
                    val message = if (response != null && !response.isSuccessful) {
                        val body = runCatching { response.body?.string()?.take(300) }.getOrNull()
                        "HTTP ${response.code}" + (if (body.isNullOrBlank()) "" else ": $body")
                    } else {
                        t?.message ?: "连接失败"
                    }
                    lastErrorMsg = message
                    Log.e(TAG, "[$pluginId] SSE 会话 ${s.id} 失败: $message", t)
                    PluginErrorLog.logError(
                    pluginId,
                    "SSE 会话失败",
                    message,
                    t,
                    category = com.kingzcheung.xime.plugin.core.security.ErrorCategory.STREAM_ERROR
                )
                    listener.onError(message)
                    sessions.remove(s.id)
                }
            }

            val factory = EventSources.createFactory(effectiveClient)
            val eventSource = factory.newEventSource(request, esListener)
            val session = Session(id, eventSource)
            current = session
            sessions[id] = session
            id
        } catch (e: Exception) {
            lastErrorMsg = e.message ?: "连接失败"
            Log.e(TAG, "[$pluginId] SSE connect failed", e)
            -1
        }
    }

    override fun close(sessionId: Int) {
        val session = sessions[sessionId] ?: return
        session.closed = true
        session.eventSource.cancel()
        sessions.remove(sessionId)
        Log.d(TAG, "[$pluginId] SSE 会话 $sessionId 已主动关闭")
    }

    override fun lastError(): String? = lastErrorMsg
}