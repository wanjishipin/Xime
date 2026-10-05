package com.kingzcheung.xime.plugin.core.js

import com.kingzcheung.xime.plugin.core.config.NoopPluginConfigStore
import com.kingzcheung.xime.plugin.core.js.ws.WsHostApi
import com.kingzcheung.xime.plugin.core.js.ws.WsHostListener
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * async 服务 API 端到端（批次 1）：zlib / resource.list / ws。
 *
 * 与 [JsAsyncHttpTest] 同链路（判别式原生桥 + bootstrap wrapper + callAsync），
 * 覆盖非网络 IO（zlib 压缩、目录列出）与 ws 连接/发送/失败。
 *
 * 前置：`xipm build test-fixture --out ../../build/plugin-js`（tools/xime-plugin 下执行；
 * 夹具 `resources/probe/` 提供目录列出数据）。
 */
class JsAsyncServicesTest {

    private fun fixtureDir(): File {
        var dir: File? = File(System.getProperty("user.dir") ?: ".")
        while (dir != null) {
            val candidate = File(dir, "build/plugin-js/test-fixture/main.js")
            if (candidate.isFile) return candidate.parentFile
            dir = dir.parentFile
        }
        throw AssertionError(
            "找不到 build/plugin-js/test-fixture/main.js，请先运行：" +
                "cd tools/xime-plugin && xipm build test-fixture --out ../../build/plugin-js"
        )
    }

    /**
     * 假 WebSocket 能力。
     *
     * 注意：connect/send 内**不得同步回调 listener**（真实 OkHttp 实现为异步回调）；
     * 同步回调会与插件执行线程互等（回调投递走同一 executor）。
     */
    private class FakeWsHostApi : WsHostApi {
        var connected = false
            private set
        val sentTexts = mutableListOf<String>()
        private var state = 0

        override fun connect(url: String, headers: Map<String, String>, listener: WsHostListener): Boolean {
            if (url.contains("denied")) return false
            connected = true
            state = 2
            return true
        }

        override fun sendText(message: String): Boolean {
            if (!connected) return false
            sentTexts.add(message)
            return true
        }

        override fun sendBinary(data: ByteArray): Boolean = connected

        override fun close() {
            connected = false
            state = 3
        }

        override fun getState(): Int = state

        override fun lastError(): String? = "域名未授权（fake）"
    }

    private fun newRuntime(wsApi: WsHostApi? = null): JsScriptRuntime = JsScriptRuntime(
        pluginId = "js-async-services",
        pluginDir = fixtureDir(),
        entryScript = "main.js",
        configStore = NoopPluginConfigStore,
        wsHostApi = wsApi
    )

    @Test(timeout = 60_000)
    fun `zlib gzip and gunzip roundtrip via await`() {
        val runtime = newRuntime()
        assertTrue(runtime.load())
        try {
            assertEquals("你好，zlib", runtime.callAsync("zlibProbe"))
        } finally {
            runtime.close()
        }
    }

    @Test(timeout = 60_000)
    fun `gunzip invalid data rejects XimeError`() {
        val runtime = newRuntime()
        assertTrue(runtime.load())
        try {
            assertEquals("E_IO", runtime.callAsync("zlibFailProbe"))
        } finally {
            runtime.close()
        }
    }

    @Test(timeout = 60_000)
    fun `resource list resolves names via await`() {
        val runtime = newRuntime()
        assertTrue(runtime.load())
        try {
            assertEquals("a.txt,b.txt", runtime.callAsync("resourceProbe"))
        } finally {
            runtime.close()
        }
    }

    @Test(timeout = 60_000)
    fun `ws connect send and state via await`() {
        val wsApi = FakeWsHostApi()
        val runtime = newRuntime(wsApi)
        assertTrue(runtime.load())
        try {
            assertEquals("true:true:2", runtime.callAsync("wsProbe"))
            assertEquals("发送内容应到达宿主", listOf("ping"), wsApi.sentTexts)
        } finally {
            runtime.close()
        }
    }

    @Test(timeout = 60_000)
    fun `ws connect failure rejects XimeError`() {
        val runtime = newRuntime(FakeWsHostApi())
        assertTrue(runtime.load())
        try {
            assertEquals(
                "E_NETWORK:域名未授权（fake）",
                runtime.callAsync("wsFailProbe")
            )
        } finally {
            runtime.close()
        }
    }
}
