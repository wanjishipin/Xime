package com.kingzcheung.xime.plugin.core.js.http

/**
 * 宿主提供的通用 SSE（Server-Sent Events）白名单 API（协议无关，AI 长文本流式生成等场景使用）。
 *
 * 设计原则（与 [com.kingzcheung.xime.plugin.core.js.ws.WsHostApi] 一致）：
 * - 宿主只提供"发起流式请求 + 逐条交付文本片段 + 主动断开"原语，**不含任何业务协议逻辑**
 *   （JSON 解析、消息组装、prompt 拼装、结果理解全部由插件 TS 承载）
 * - URL 必须命中宿主侧域名白名单（宿主实现强制校验），插件无法发起任意网络请求
 * - **异步模型（TS 范式）**：connect 为 async 服务调用（resolve 会话 id）；流事件经插件的
 *   `sse` 回调槽投递（sse.onData/onDone/onError，参数携带 sessionId），
 *   与单次请求 [HttpHostApi.request] 互补——SSE 无法用单次请求-响应模型承载
 * - 每个会话一个句柄：宿主返回递增会话 id，`closeStream(id)` 可主动中断，防止旧请求继续回调
 *
 * 插件侧（TS）注入为 `host.http.stream / host.http.closeStream`：
 *   const id = await host.http.stream(url, headers, timeoutMillis, method, body);
 *     // method 显式传（AI 对话流式接口通常为 POST 携带 JSON body）
 *     // body 文本或二进制（null 表示无请求体）
 *     // 失败 reject XimeError（E_NETWORK / E_DENIED 等，无 lastError()）
 *   await host.http.closeStream(id);
 *   // 流事件投递到 plugin.sse 槽：sse.onData(id, text) / sse.onDone(id, fullText) / sse.onError(id, msg)
 *
 * @see com.kingzcheung.xime.plugin.core.js.http.HttpHostApi
 * @see com.kingzcheung.xime.plugin.core.js.ws.WsHostApi
 */
interface SseHostApi {

    /**
     * 发起流式 SSE 请求，立即返回会话 id。
     *
     * @param url     完整 URL，宿主校验域名白名单（未授权等失败返回 -1）
     * @param headers 请求头（如 Authorization、Content-Type、Accept: text/event-stream）
     * @param listener 流事件回调（后台线程 invoke，插件 sse 回调槽需自行防重入/去抖）
     * @param timeoutMillis 覆盖默认超时（毫秒）；null 使用宿主默认（SSE 默认读超时为无限，见实现）
     * @param method   HTTP 方法（默认 GET；AI 对话流式接口通常为 POST）
     * @param body     请求体（文本转 UTF-8 字节，二进制原始字节；无请求体传 null）
     * @return 会话 id（>=0）；失败返回 -1（原因经 [lastError] 读取；JS 侧 bootstrap 会把
     *         -1 + lastError 包装为 reject `XimeError`）
     */
    fun connect(
        url: String,
        headers: Map<String, String> = emptyMap(),
        listener: SseHostListener,
        timeoutMillis: Int? = null,
        method: String = "GET",
        body: ByteArray? = null
    ): Int

    /** 主动中断指定会话（幂等）。中断后该会话不再回调。 */
    fun close(sessionId: Int)

    /** 最近一次拒绝/失败原因（connect 返回 -1 时 JS bootstrap 读取并包装为 XimeError）。 */
    fun lastError(): String?
}

/** 通用 SSE 流事件回调（宿主侧后台线程调用，每个会话一个 listener）。 */
interface SseHostListener {
    /** 收到一条 SSE 事件（`data:` 行的解析结果，多行 data 已拼接）。 */
    fun onData(text: String)

    /** 流正常结束（或主动 close 收尾），fullText 为该会话已交付文本的拼接。 */
    fun onDone(fullText: String)

    /** 流异常中断（网络错误 / 非 2xx / 授权拒绝等）。 */
    fun onError(message: String)
}