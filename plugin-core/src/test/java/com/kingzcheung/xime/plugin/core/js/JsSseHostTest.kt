package com.kingzcheung.xime.plugin.core.js

import com.kingzcheung.xime.plugin.core.config.PluginConfigStore
import com.kingzcheung.xime.plugin.core.js.http.HttpHostApi
import com.kingzcheung.xime.plugin.core.js.http.HttpResponse
import com.kingzcheung.xime.plugin.core.js.http.SseHostApi
import com.kingzcheung.xime.plugin.core.js.http.SseHostListener
import com.kingzcheung.xime.plugin.core.js.sdk.JsHostApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * 验证宿主 SSE 流式原语在 JS 侧的注入与桥接（v3 TS 范式：async）：
 * - host.http.stream 发起会话（await；URL/headers 透传，resolve 会话 id）
 * - 流事件（onData/onDone/onError）投递到导出对象的回调槽 plugin.sse.onData/onSseDone/onSseError
 * - host.http.closeStream 主动关停会话
 * - host.http.request 透传 timeoutMillis
 */
class JsSseHostTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private class InMemoryConfigStore : PluginConfigStore {
        private val map = HashMap<String, String>()
        override fun get(key: String): String? = map[key]
        override fun set(key: String, value: String) { map[key] = value }
        override fun remove(key: String) { map.remove(key) }
        override fun keys(): Set<String> = map.keys.toSet()
    }

    private class MockHttpHostApi : HttpHostApi {
        var lastTimeoutMillis: Int? = null
        var lastUrl: String? = null
        override fun request(
            method: String,
            url: String,
            headers: Map<String, String>,
            body: ByteArray?,
            timeoutMillis: Int?
        ): HttpResponse? {
            lastTimeoutMillis = timeoutMillis
            lastUrl = url
            return null
        }
        override fun lastError(): String? = null
    }

    private class MockSseHostApi : SseHostApi {
        var connectedUrl: String? = null
        var connectedHeaders: Map<String, String> = emptyMap()
        var listener: SseHostListener? = null
        var closedIds = mutableListOf<Int>()
        var returnId = 100

        override fun connect(
            url: String,
            headers: Map<String, String>,
            listener: SseHostListener,
            timeoutMillis: Int?,
            method: String,
            body: ByteArray?
        ): Int {
            connectedUrl = url
            connectedHeaders = headers
            this.listener = listener
            return returnId
        }
        override fun close(sessionId: Int) { closedIds.add(sessionId) }
        override fun lastError(): String? = null
    }

    private fun awaitUntil(timeoutMs: Long = 5000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(50)
        }
        assertTrue("等待条件超时", condition())
    }

    private fun buildRuntime(sseMock: MockSseHostApi?, httpMock: MockHttpHostApi?): JsScriptRuntime {
        val dir = tempFolder.newFolder("sse_test")
        File(dir, "main.js").writeText(
            """
            globalThis.plugin = {
              received: [],
              doneText: "",
              errorMsg: "",
              sid: -1,

              open_stream: async function() {
                this.sid = await host.http.stream("https://api.example.com/v1/chat/completions", {
                  Authorization: "Bearer test-key",
                  "Content-Type": "application/json",
                  Accept: "text/event-stream"
                });
                return this.sid;
              },

              get_received: function() { return this.received.slice(); },
              get_done: function() { return this.doneText; },
              get_error: function() { return this.errorMsg; },

              close_stream: async function() { await host.http.closeStream(this.sid); },

              sync_request: async function() {
                return host.http.request("POST", "https://api.example.com/v1/chat", {
                  "Content-Type": "application/json"
                }, "{}", 120000);
              },

              sse: {
                onData: function(sessionId, text) { globalThis.plugin.received.push(text); },
                onDone: function(sessionId, fullText) { globalThis.plugin.doneText = fullText; },
                onError: function(sessionId, message) { globalThis.plugin.errorMsg = message; }
              }
            }
            """.trimIndent()
        )
        return JsScriptRuntime(
            pluginId = "com.kingzcheung.xime.plugin.sse_test",
            pluginDir = dir,
            entryScript = "main.js",
            configStore = InMemoryConfigStore(),
            httpHostApi = httpMock,
            sseHostApi = sseMock
        )
    }

    @Test
    fun `stream 回调投递与关停`() {
        val sse = MockSseHostApi()
        val runtime = buildRuntime(sse, MockHttpHostApi())
        try {
            assertTrue(runtime.load())

            val sid = (runtime.callAsync("open_stream") as? Number)?.toInt()
            assertEquals("会话 id 透传", 100, sid)
            assertEquals("URL 透传", "https://api.example.com/v1/chat/completions", sse.connectedUrl)
            assertEquals("Authorization 透传", "Bearer test-key", sse.connectedHeaders["Authorization"])
            assertEquals("Accept 透传", "text/event-stream", sse.connectedHeaders["Accept"])

            // 流事件 → JS 回调槽
            sse.listener?.onData("你好")
            sse.listener?.onData("世界")
            val received = runtime.call("get_received") as? List<*>
            assertEquals("onData 逐条累积", listOf("你好", "世界"), received)

            sse.listener?.onDone("你好世界")
            awaitUntil { runtime.call("get_done")?.toString() == "你好世界" }
            assertEquals("onDone 交付拼接文本", "你好世界", runtime.call("get_done")?.toString())

            sse.listener?.onError("服务端限流")
            awaitUntil { runtime.call("get_error")?.toString() == "服务端限流" }
            assertEquals("onError 交付错误", "服务端限流", runtime.call("get_error")?.toString())

            // 主动关停
            runtime.callAsync("close_stream")
            assertEquals("closeStream 携带会话 id", listOf(100), sse.closedIds)
        } finally {
            runtime.close()
        }
    }

    @Test
    fun `stream 失败时返回 null`() {
        val sse = MockSseHostApi().apply { returnId = -1 }
        val runtime = buildRuntime(sse, MockHttpHostApi())
        try {
            assertTrue(runtime.load())
            // v3：连接失败 throw XimeError（宿主吞并返回 null），不再同步返回 -1
            assertNull("失败返回 null", runtime.callAsync("open_stream"))
        } finally {
            runtime.close()
        }
    }

    @Test
    fun `request 透传 timeoutMillis 覆盖默认超时`() {
        val http = MockHttpHostApi()
        val runtime = buildRuntime(MockSseHostApi(), http)
        try {
            assertTrue(runtime.load())

            assertNull("未传 timeout 时为 null", http.lastTimeoutMillis)
            runtime.callAsync("sync_request")
            assertEquals("timeoutMillis 透传", 120000, http.lastTimeoutMillis)
            assertEquals("URL 透传", "https://api.example.com/v1/chat", http.lastUrl)
        } finally {
            runtime.close()
        }
    }

    @Test
    fun `回调槽缺失时宿主不崩溃`() {
        val sse = MockSseHostApi()
        val dir = tempFolder.newFolder("sse_no_callbacks")
        File(dir, "main.js").writeText(
            """
            globalThis.plugin = {
              open_no_cb: async function() {
                return host.http.stream("https://api.example.com/stream", {});
              }
            }
            """.trimIndent()
        )
        val debugHost = object : JsHostApi {
            override val sdkVersion = "1.0.0"
            override fun log(message: String) { System.out.println("JS_LOG: $message") }
            override fun logError(message: String) { System.err.println("JS_ERR: $message") }
            override fun configGet(key: String) = null
            override fun configSet(key: String, value: String) {}
            override fun configRemove(key: String) {}
            override fun configKeys() = emptySet<String>()
            override fun resourcePath(name: String) = null
            override fun resourceList(dir: String) = emptyList<String>()
            override fun uuid() = "uuid"
        }
        val runtime = JsScriptRuntime(
            pluginId = "no_cb",
            pluginDir = dir,
            entryScript = "main.js",
            configStore = InMemoryConfigStore(),
            hostApi = debugHost,
            httpHostApi = MockHttpHostApi(),
            sseHostApi = sse
        )
        try {
            assertTrue(runtime.load())
            val ret = runtime.callAsync("open_no_cb")
            assertEquals("无回调槽仍返回会话 id", 100, (ret as? Number)?.toInt())

            // 未定义回调槽时，宿主流事件不应崩溃
            sse.listener?.onData("x")
            sse.listener?.onDone("x")
            sse.listener?.onError("x")
        } finally {
            runtime.close()
        }
    }
}