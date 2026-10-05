package com.kingzcheung.xime.plugin.core.js

import android.app.Application
import com.kingzcheung.xime.plugin.core.api.ClipboardProfile
import com.kingzcheung.xime.plugin.core.config.PluginConfigStore
import com.kingzcheung.xime.plugin.core.js.crypto.CryptoHostApi
import com.kingzcheung.xime.plugin.core.js.http.HttpHostApi
import com.kingzcheung.xime.plugin.core.js.http.HttpResponse
import com.kingzcheung.xime.plugin.core.js.sdk.JsHostApi
import com.kingzcheung.xime.plugin.core.model.PluginContext
import com.kingzcheung.xime.plugin.core.model.PluginInfo
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class JsClipboardSyncPluginTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private class InMemoryConfigStore : PluginConfigStore {
        private val map = HashMap<String, String>()
        override fun get(key: String): String? = map[key]
        override fun set(key: String, value: String) { map[key] = value }
        override fun remove(key: String) { map.remove(key) }
        override fun keys(): Set<String> = map.keys.toSet()
    }

    private class MockHttpHostApi : HttpHostApi {
        val requests = mutableListOf<Triple<String, String, Map<String, String>>>()
        val responseQueue = ArrayDeque<HttpResponse>()
        var lastErrorMsg: String? = null

        override fun request(
            method: String,
            url: String,
            headers: Map<String, String>,
            body: ByteArray?,
            timeoutMillis: Int?
        ): HttpResponse? {
            requests.add(Triple(method, url, headers))
            return responseQueue.removeFirstOrNull()
        }

        override fun lastError(): String? = lastErrorMsg
    }

    private class MockCryptoHostApi : CryptoHostApi {
        override fun sha256(data: ByteArray): ByteArray = ByteArray(0)
        override fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray = ByteArray(0)
        override fun hmacSha1(key: ByteArray, data: ByteArray): ByteArray = ByteArray(20)
        override fun hex(data: ByteArray): String = ""
        override fun base64(data: ByteArray): String =
            java.util.Base64.getEncoder().encodeToString(data)
        override fun utcTime(format: String): String = ""
        override fun epochSeconds(): Long = 1767225600
    }

    private class DebugHostApi(private val store: PluginConfigStore) : JsHostApi {
        override val sdkVersion = "0.1.0"
        override fun log(message: String) { System.out.println("JS_LOG: $message") }
        override fun logError(message: String) { System.err.println("JS_ERROR: $message") }
        override fun configGet(key: String) = store.get(key)
        override fun configSet(key: String, value: String) { store.set(key, value) }
        override fun configRemove(key: String) { store.remove(key) }
        override fun configKeys(): Set<String> = store.keys()
        override fun resourcePath(name: String) = null
        override fun resourceList(dir: String) = emptyList<String>()
        override fun uuid() = "uuid"
    }

    /** 载入真实插件产物（xipm build 输出），测试与发布同源。 */
    private fun writePlugin(): File {
        val dir = tmp.newFolder()
        pluginSourceFile().copyTo(File(dir, "main.js"))
        return dir
    }

    private fun pluginSourceFile(): File {
        var dir: File? = File(System.getProperty("user.dir") ?: ".")
        while (dir != null) {
            val candidate = File(dir, "build/plugin-js/ximed-clipboard-sync/main.js")
            if (candidate.isFile) return candidate
            dir = dir.parentFile
        }
        throw AssertionError(
            "找不到 build/plugin-js/ximed-clipboard-sync/main.js，" +
                "请先运行：cd tools/xime-plugin && cargo run -- build ../../plugins/ximed-clipboard-sync --out ../../build/plugin-js"
        )
    }

    private fun newAdapter(store: PluginConfigStore, http: MockHttpHostApi): JsClipboardSyncPluginAdapter {
        val dir = writePlugin()
        val runtime = JsScriptRuntime(
            "js-ximed-clipboard-sync",
            dir,
            "main.js",
            store,
            hostApi = DebugHostApi(store),
            httpHostApi = http,
            cryptoHostApi = MockCryptoHostApi()
        )
        assertTrue("main.js 应能加载", runtime.load())
        val info = PluginInfo(
            id = "com.kingzcheung.xime.plugin.ximed_clipboard_sync", name = "ximed 剪贴板同步",
            description = "测试", iconResId = 0, versionCode = 1, versionName = "1.0.0",
            path = File(dir, "main.js").absolutePath, type = "clipboard_sync"
        )
        return JsClipboardSyncPluginAdapter(
            runtime, PluginContext(application = Application(), pluginInfo = info, configStore = store)
        )
    }

    @Test
    fun `main js loads and exposes sync contract`() {
        val store = InMemoryConfigStore()
        val adapter = newAdapter(store, MockHttpHostApi())
        val schema = adapter.getSettingsSchema()
        assertTrue("应导出 settings.schema", schema.isNotEmpty())
        assertEquals(5, schema.size)
        // 拉取间隔由宿主引擎消费（configStore key 契约），插件 schema 必须声明同名 NUMBER 字段
        assertEquals(
            "pull_interval_seconds",
            schema.first { it.label?.contains("拉取最小间隔") == true }.key
        )
    }

    @Test
    fun `push sends PUT to ximed endpoint with basic auth`() {
        val store = InMemoryConfigStore()
        store.set("serverUrl", "https://192.168.1.50:8080")
        store.set("username", "alice")
        store.set("password", "secret")
        val http = MockHttpHostApi()
        http.responseQueue.addLast(HttpResponse(200))
        val adapter = newAdapter(store, http)

        val ok = runBlocking { adapter.push(ClipboardProfile(text = "hello", hash = "abc", size = 5)) }

        assertTrue("push 应成功", ok)
        assertEquals(1, http.requests.size)
        val (method, url, headers) = http.requests[0]
        assertEquals("PUT", method)
        assertEquals("https://192.168.1.50:8080/api/clipboard", url)
        val expectedAuth = "Basic " + java.util.Base64.getEncoder()
            .encodeToString("alice:secret".toByteArray())
        assertEquals(expectedAuth, headers["Authorization"])
    }

    @Test
    fun `pull returns profile on 200 and caches etag`() {
        val store = InMemoryConfigStore()
        store.set("serverUrl", "https://192.168.1.50:8080")
        val http = MockHttpHostApi()
        val profileJson = """{"type":"text","hash":"abc123","text":"远端内容","has_data":false,"data_name":null,"size":12,"source":"desktop"}"""
        http.responseQueue.addLast(HttpResponse(200, mapOf("ETag" to "etag-1"), profileJson.toByteArray()))
        val adapter = newAdapter(store, http)

        val profile = runBlocking { adapter.pull() }

        assertNotNull("pull 应返回对象", profile)
        assertEquals("远端内容", profile?.text)
        assertEquals("abc123", profile?.hash)
        assertEquals("desktop", profile?.source)
        assertEquals("etag-1", store.get("lastEtag"))
    }

    @Test
    fun `pull returns null on 304`() {
        val store = InMemoryConfigStore()
        store.set("serverUrl", "https://192.168.1.50:8080")
        store.set("lastEtag", "etag-1")
        val http = MockHttpHostApi()
        http.responseQueue.addLast(HttpResponse(304))
        val adapter = newAdapter(store, http)

        val result = runBlocking { adapter.pull() }

        assertNull("304 应返回 null", result)
        assertEquals(1, http.requests.size)
        val headers = http.requests[0].third
        assertEquals("etag-1", headers["If-None-Match"])
    }

    @Test
    fun `testConnection reports auth failure`() {
        val store = InMemoryConfigStore()
        store.set("serverUrl", "https://192.168.1.50:8080")
        val http = MockHttpHostApi()
        http.responseQueue.addLast(HttpResponse(401))
        val adapter = newAdapter(store, http)

        val error = runBlocking { adapter.testConnection() }

        assertTrue("应报告认证失败: $error", error.orEmpty().contains("认证失败"))
    }

    @Test
    fun `testConnection reports missing config`() {
        val adapter = newAdapter(InMemoryConfigStore(), MockHttpHostApi())
        val error = runBlocking { adapter.testConnection() }
        assertTrue("未配置时应报告错误: $error", error.orEmpty().contains("未配置"))
    }

}