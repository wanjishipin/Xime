package com.kingzcheung.xime.plugin.core.js.ws

/**
 * 宿主提供的通用 WebSocket 白名单 API（协议无关）。
 *
 * 设计原则：
 * - 宿主只提供"连接/收发文本/收发二进制/关闭"原语，**不含任何业务协议逻辑**
 * - 连接 URL 必须命中宿主侧域名白名单（app 层实现强制校验），插件无法发起任意网络请求
 * - 协议控制面（何时连接、组装什么消息、prebuffer 决策、结果解析）全部由插件承载
 *
 * JS 侧注入为 `host.ws`（async 服务，失败 throw XimeError）：
 *   await host.ws.connect(url, headers)
 *   await host.ws.sendText(text) / sendBinary(bytes)
 *   await host.ws.close()
 *   host.ws.getState()                          -- 0=IDLE 1=CONNECTING 2=OPEN 3=CLOSED（同步）
 */
interface WsHostApi {

    /**
     * 建立 WebSocket 连接（发起成功返回 true）。
     * @param url 目标地址（宿主校验域名白名单，不合法返回 false）
     * @param headers 额外请求头
     * @param listener 事件回调
     */
    fun connect(url: String, headers: Map<String, String>, listener: WsHostListener): Boolean

    /** 发送文本消息（成功入队返回 true；未连接/队列满/异常返回 false，原因经 [lastError]）。 */
    fun sendText(message: String): Boolean

    /** 发送二进制数据（成功入队返回 true；未连接/队列满/异常返回 false，原因经 [lastError]）。 */
    fun sendBinary(data: ByteArray): Boolean

    /** 关闭连接（幂等）。 */
    fun close()

    /** 连接状态（0=IDLE 1=CONNECTING 2=OPEN 3=CLOSED）。 */
    fun getState(): Int

    /** 最近一次拒绝/失败原因（connect/send 返回 false 时，JS 侧 XimeError.message 数据源）。 */
    fun lastError(): String?
}

/** 通用 WebSocket 事件回调。 */
interface WsHostListener {
    fun onOpen()
    fun onMessage(text: String)
    fun onBinary(data: ByteArray)
    fun onError(message: String)
    fun onClose()
}
