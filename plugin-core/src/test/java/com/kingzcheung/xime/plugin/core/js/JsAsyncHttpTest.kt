package com.kingzcheung.xime.plugin.core.js

import com.kingzcheung.xime.plugin.core.config.NoopPluginConfigStore
import com.kingzcheung.xime.plugin.core.js.http.HttpHostApi
import com.kingzcheung.xime.plugin.core.js.http.HttpResponse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * async 服务 API 端到端（TS 范式）：插件 `await host.http.request(...)`。
 *
 * 验证链路：AsyncFunctionBinding 原生桥（判别式结果）→ bootstrap 的 host.http.request
 * wrapper（Promise / throw XimeError）→ 插件 await / try-catch → 宿主 callAsync 取值。
 *
 * 前置：`xipm build test-fixture --out ../../build/plugin-js`（tools/xime-plugin 下执行）。
 */
class JsAsyncHttpTest {

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

    /** 假 HTTP 能力：/hello 返回 200，其余失败（lastError 提供原因）。 */
    private class FakeHttpApi : HttpHostApi {
        override fun request(
            method: String,
            url: String,
            headers: Map<String, String>,
            body: ByteArray?,
            timeoutMillis: Int?
        ): HttpResponse? = when {
            url.endsWith("/hello") -> HttpResponse(
                status = 200,
                headers = mapOf("Content-Type" to "text/plain; charset=utf-8"),
                body = "你好 hello".toByteArray(Charsets.UTF_8)
            )
            else -> null
        }

        override fun lastError(): String? = "连接被拒绝（fake）"
    }

    private fun newRuntime(): JsScriptRuntime = JsScriptRuntime(
        pluginId = "js-async-http",
        pluginDir = fixtureDir(),
        entryScript = "main.js",
        configStore = NoopPluginConfigStore,
        httpHostApi = FakeHttpApi()
    )

    @Test(timeout = 60_000)
    fun `plugin awaits async http request and receives response`() {
        val runtime = newRuntime()
        assertTrue("TS 产物应能加载", runtime.load())
        try {
            assertEquals(
                "成功路径：await resolve 响应对象（status + text）",
                "200:你好 hello",
                runtime.callAsync("httpProbe")
            )
        } finally {
            runtime.close()
        }
    }

    @Test(timeout = 60_000)
    fun `plugin catches XimeError with code on failure`() {
        val runtime = newRuntime()
        assertTrue(runtime.load())
        try {
            assertEquals(
                "失败路径：reject XimeError（code/message 完整且继承 Error）",
                "E_NETWORK:连接被拒绝（fake）:xime-error:is-error",
                runtime.callAsync("httpProbeFail")
            )
        } finally {
            runtime.close()
        }
    }

    @Test(timeout = 60_000)
    fun `callAsync works for sync probe methods too`() {
        val runtime = newRuntime()
        assertTrue(runtime.load())
        try {
            val snapshot = runtime.callAsync("snapshot") as? Map<*, *>
            assertNotNull("同步方法经 callAsync 应正常返回", snapshot)
            assertEquals("hello, Xime!", snapshot!!["greet"]?.toString())
        } finally {
            runtime.close()
        }
    }
}
