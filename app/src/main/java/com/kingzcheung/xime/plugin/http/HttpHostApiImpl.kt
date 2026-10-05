package com.kingzcheung.xime.plugin.http

import android.content.Context
import android.net.Uri
import android.util.Log
import com.kingzcheung.xime.plugin.ExtensionManager
import com.kingzcheung.xime.plugin.PluginNetworkAuthHelper
import com.kingzcheung.xime.plugin.core.js.http.HttpHostApi
import com.kingzcheung.xime.plugin.core.js.http.HttpResponse
import com.kingzcheung.xime.plugin.core.js.ws.NetworkPolicy
import com.kingzcheung.xime.plugin.core.runtime.PluginManager
import com.kingzcheung.xime.plugin.core.security.PluginErrorLog
import com.kingzcheung.xime.settings.SettingsPreferences
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.util.concurrent.TimeUnit

/**
 * 宿主通用 HTTP 白名单 API。
 *
 * - URL 域名须通过 [NetworkPolicy]：插件已声明且经用户授权，否则拒绝
 *   （插件无法静默发起任意网络请求，自定义服务器域名需用户在插件中心手动授权）
 * - 同步阻塞执行：必须在 IO 线程调用（宿主同步引擎在 Dispatchers.IO 运行）
 * - 协议无关：认证头/ETag/SigV4 全部由插件 Lua 组装，宿主只透传
 */
class HttpHostApiImpl(
    private val context: Context,
    private val pluginId: String
) : HttpHostApi {

    companion object {
        private const val TAG = "HttpHostApi"
    }

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .addInterceptor { chain ->
            val request = chain.request()
            Log.d(TAG, "[$pluginId] >>> ${request.method} ${request.url}")
            val start = System.currentTimeMillis()
            try {
                val resp = chain.proceed(request)
                Log.d(TAG, "[$pluginId] <<< ${resp.code} in ${System.currentTimeMillis() - start}ms")
                resp
            } catch (e: Exception) {
                Log.d(TAG, "[$pluginId] <<< FAIL ${e.message} in ${System.currentTimeMillis() - start}ms")
                throw e
            }
        }
        .build()

    @Volatile
    private var lastErrorMsg: String? = null

    override fun request(
        method: String,
        url: String,
        headers: Map<String, String>,
        body: ByteArray?,
        timeoutMillis: Int?
    ): HttpResponse? {
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
            return null
        }
        lastErrorMsg = null

        return try {
            val requestBuilder = Request.Builder().url(url)
            headers.forEach { (k, v) -> requestBuilder.addHeader(k, v) }
            // 尊重调用方显式声明的 Content-Type(如 RTF/DIP 服务的 application/json);OkHttp 的
            // post(body) 会用 RequestBody 的 mediaType 覆盖 header,这里先取 header 再建 body。
            // header 名大小写不敏感：Lua 插件可能传 "content-type"。
            val contentType = headers.entries.firstOrNull {
                it.key.equals("Content-Type", ignoreCase = true)
            }?.value ?: "application/octet-stream"
            val requestBody = (body ?: ByteArray(0)).toRequestBody(contentType.toMediaType())
            when (method.uppercase()) {
                "GET" -> requestBuilder.get()
                "DELETE" -> requestBuilder.delete()
                "HEAD" -> requestBuilder.head()
                "PUT" -> requestBuilder.put(requestBody)
                "POST" -> requestBuilder.post(requestBody)
                "PATCH" -> requestBuilder.patch(requestBody)
                "MKCOL" -> requestBuilder.method("MKCOL", null)
                "PROPFIND" -> requestBuilder.method("PROPFIND", null)
                else -> requestBuilder.get()
            }
            val effectiveClient = if (timeoutMillis != null && timeoutMillis > 0) {
                client.newBuilder()
                    .connectTimeout(timeoutMillis.toLong(), TimeUnit.MILLISECONDS)
                    .readTimeout(timeoutMillis.toLong(), TimeUnit.MILLISECONDS)
                    .writeTimeout(timeoutMillis.toLong(), TimeUnit.MILLISECONDS)
                    .build()
            } else {
                client
            }
            val call = effectiveClient.newCall(requestBuilder.build())
            if (timeoutMillis != null && timeoutMillis > 0) {
                call.timeout().timeout(timeoutMillis.toLong(), TimeUnit.MILLISECONDS)
            }
            call.execute().use { response ->
                toHttpResponse(response)
            }
        } catch (e: Exception) {
            lastErrorMsg = e.message ?: "request failed"
            Log.e(TAG, "[$pluginId] HTTP $method $url failed", e)
            PluginErrorLog.logError(
                pluginId,
                "HTTP 请求失败 ($method $url)",
                e.message ?: "request failed",
                e,
                category = com.kingzcheung.xime.plugin.core.security.ErrorCategory.HTTP_ERROR
            )
            null
        }
    }

    override fun lastError(): String? = lastErrorMsg

    private fun toHttpResponse(response: Response): HttpResponse {
        val headers = HashMap<String, String>()
        response.headers.forEach { (k, v) -> headers[k] = v }
        val bytes = response.body?.bytes() ?: ByteArray(0)
        return HttpResponse(
            status = response.code,
            headers = headers,
            body = bytes
        )
    }
}
