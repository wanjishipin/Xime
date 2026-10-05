package com.kingzcheung.xime.plugin.ws

import android.content.Context
import com.kingzcheung.xime.plugin.core.js.ws.WsHostListener
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyNoInteractions
import java.io.IOException

/**
 * 连接生命周期回归：终态回调必须清空 socket，且只认当前连接的回调。
 *
 * 回归背景：`webSocket` 原先只在 [WsHostApiImpl.close] 里置空，`onFailure`/`onClosed` 只改 state。
 * 于是一次传输层失败之后，下一次 `connect` 会命中"Already connected, reusing"而复用一个**死连接**：
 * 不再有 onOpen，插件状态机永远停在未就绪（音频只能进 prebuffer，最终无结果）。
 *
 * OkHttp 的 [WebSocketListener] 只能由真实连接触发，这里用反射取出 `wsListener` 与注入
 * `webSocket`/`listener` 字段直接驱动回调（无需真实网络）。
 */
class WsHostApiImplTest {

    private fun newApi() = WsHostApiImpl(mock<Context>(), "com.kingzcheung.xime.test")

    private fun field(name: String) =
        WsHostApiImpl::class.java.getDeclaredField(name).apply { isAccessible = true }

    private fun setSocket(api: WsHostApiImpl, socket: WebSocket?) = field("webSocket").set(api, socket)

    private fun socketOf(api: WsHostApiImpl): WebSocket? = field("webSocket").get(api) as WebSocket?

    private fun setListener(api: WsHostApiImpl, listener: WsHostListener?) =
        field("listener").set(api, listener)

    private fun wsListenerOf(api: WsHostApiImpl): WebSocketListener =
        field("wsListener").get(api) as WebSocketListener

    @Test
    fun `连接失败后应清空 socket 并通知监听器`() {
        val api = newApi()
        val socket = mock<WebSocket>()
        val listener = mock<WsHostListener>()
        setSocket(api, socket)
        setListener(api, listener)

        wsListenerOf(api).onFailure(socket, IOException("boom"), null)

        assertNull("失败后必须清空 socket，否则下次 connect 会复用死连接", socketOf(api))
        assertEquals("失败后状态应为已关闭", 3, api.getState())
        verify(listener).onError("boom")
    }

    @Test
    fun `服务端关闭后应清空 socket 并通知监听器`() {
        val api = newApi()
        val socket = mock<WebSocket>()
        val listener = mock<WsHostListener>()
        setSocket(api, socket)
        setListener(api, listener)

        wsListenerOf(api).onClosed(socket, 1000, "bye")

        assertNull("关闭后必须清空 socket，否则下次 connect 会复用死连接", socketOf(api))
        assertEquals("关闭后状态应为已关闭", 3, api.getState())
        verify(listener).onClose()
    }

    @Test
    fun `旧连接的迟到回调不得清掉新连接`() {
        val api = newApi()
        val old = mock<WebSocket>()
        val fresh = mock<WebSocket>()
        val listener = mock<WsHostListener>()
        setSocket(api, old)
        val wsListener = wsListenerOf(api)

        // 新会话已建立（旧连接的 close 回调此刻才迟到）
        setSocket(api, fresh)
        setListener(api, listener)

        wsListener.onClosed(old, 1000, "bye")

        assertSame("旧连接的迟到回调不得清掉新连接", fresh, socketOf(api))
        verifyNoInteractions(listener)
    }
}
