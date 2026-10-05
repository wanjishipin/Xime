package com.kingzcheung.xime.plugin.core.js.http

/**
 * 宿主提供的通用 HTTP 白名单 API（协议无关，WebDAV / S3 / ximed 等同步插件使用）。
 *
 * 设计原则（与 [com.kingzcheung.xime.plugin.core.js.ws.WsHostApi] 一致）：
 * - 宿主只提供"发起请求 + 返回响应"原语，**不含任何业务协议逻辑**（PUT/GET 语义、
 *   认证头、SigV4 签名、ETag 缓存全由插件 Lua 承载）
 * - URL 必须命中宿主侧域名白名单（宿主实现强制校验），插件无法发起任意网络请求
 * - 本 API 为**同步阻塞**调用：宿主实现在调用线程上同步执行 HTTP 请求。
 *   宿主侧必须在 IO 线程调用（同步引擎在 Dispatchers.IO 运行），避免阻塞主线程。
 *
 * Lua 侧注入为 `host.http`：
 *   host.http.request(method, url, headers, body, timeoutMillis) -> {status, headers, body}
 *   host.http.lastError()
 *
 * @see com.kingzcheung.xime.plugin.core.js.ws.WsHostApi
 */
interface HttpHostApi {

    /**
     * 发起同步 HTTP 请求。
     *
     * @param method  HTTP 方法（GET/PUT/POST/DELETE/HEAD，大写）
     * @param url     完整 URL，宿主校验域名白名单（未授权返回 null）
     * @param headers 请求头（如 Authorization、Content-Type、If-None-Match）
     * @param body    请求体（文本转 UTF-8 字节，二进制原始字节；GET 可为 null）
     * @param timeoutMillis 覆盖默认超时（毫秒）。AI 长生成等场景可声明更长
     *                      超时；null 使用宿主默认（connect 10s / read 30s / write 30s）
     * @return 响应；URL 被拒绝或请求失败时返回 null（用 [lastError] 读取原因）
     */
    fun request(
        method: String,
        url: String,
        headers: Map<String, String> = emptyMap(),
        body: ByteArray? = null,
        timeoutMillis: Int? = null
    ): HttpResponse?

    /** 最近一次拒绝/失败原因（request 返回 null 时 JS 侧 XimeError.message 数据源）。 */
    fun lastError(): String?

    /**
     * 最近一次失败的结构化错误码（`E_*`，JS 侧 XimeError.code 数据源）。
     * 未提供结构化码时返回 null（桥层回退到 `E_NETWORK`）。
     */
    fun lastErrorCode(): String? = null
}

/**
 * HTTP 响应。
 *
 * @param status  HTTP 状态码（200/304/401/404…）
 * @param headers 响应头（含 ETag / Last-Modified 等，插件 Lua 用于条件拉取）
 * @param body    响应体（文本按 UTF-8 解码，二进制原始字节）
 */
data class HttpResponse(
    val status: Int,
    val headers: Map<String, String> = emptyMap(),
    val body: ByteArray = ByteArray(0)
) {
    /** 取响应头（大小写不敏感）。 */
    fun header(name: String): String? {
        return headers.entries.firstOrNull {
            it.key.equals(name, ignoreCase = true)
        }?.value
    }
}
