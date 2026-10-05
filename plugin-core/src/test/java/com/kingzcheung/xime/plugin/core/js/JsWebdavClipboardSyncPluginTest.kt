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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class JsWebdavClipboardSyncPluginTest {

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
        val requestBodies = mutableListOf<String>()
        /** 原始字节：图片附件必须按字节断言（UTF-8 文本化会丢信息） */
        val requestBodyBytes = mutableListOf<ByteArray?>()
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
            requestBodies.add(body?.toString(Charsets.UTF_8) ?: "")
            requestBodyBytes.add(body)
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
            val candidate = File(dir, "build/plugin-js/webdav-clipboard-sync/main.js")
            if (candidate.isFile) return candidate
            dir = dir.parentFile
        }
        throw AssertionError(
            "找不到 build/plugin-js/webdav-clipboard-sync/main.js，" +
                "请先运行：cd tools/xime-plugin && cargo run -- build ../../plugins/webdav-clipboard-sync --out ../../build/plugin-js"
        )
    }

    private fun newAdapter(store: PluginConfigStore, http: MockHttpHostApi): JsClipboardSyncPluginAdapter {
        val dir = writePlugin()
        val runtime = JsScriptRuntime(
            "js-webdav-clipboard-sync",
            dir,
            "main.js",
            store,
            hostApi = DebugHostApi(store),
            httpHostApi = http,
            cryptoHostApi = MockCryptoHostApi()
        )
        assertTrue("main.js 应能加载", runtime.load())
        val info = PluginInfo(
            id = "com.kingzcheung.xime.plugin.webdav_clipboard_sync", name = "WebDAV 剪贴板同步",
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
        assertEquals(6, schema.size)
        // 拉取间隔由宿主引擎消费（configStore key 契约），插件 schema 必须声明同名 NUMBER 字段
        assertEquals(
            "pull_interval_seconds",
            schema.first { it.label?.contains("拉取最小间隔") == true }.key
        )
    }

    @Test
    fun `push sends PUT to webdav clipboard json with basic auth`() {
        val store = InMemoryConfigStore()
        store.set("davUrl", "https://192.168.1.50:8080/dav/")
        store.set("username", "alice")
        store.set("password", "secret")
        val http = MockHttpHostApi()
        http.responseQueue.addLast(HttpResponse(201))
        val adapter = newAdapter(store, http)

        val ok = runBlocking { adapter.push(ClipboardProfile(text = "hello", hash = "abc", size = 5)) }

        assertTrue("push 应成功", ok)
        assertEquals(1, http.requests.size)
        val (method, url, headers) = http.requests[0]
        assertEquals("PUT", method)
        assertEquals("https://192.168.1.50:8080/dav/clipboard/current.json", url)
        val expectedAuth = "Basic " + java.util.Base64.getEncoder()
            .encodeToString("alice:secret".toByteArray())
        assertEquals(expectedAuth, headers["Authorization"])
    }

    @Test
    fun `push creates missing directories via MKCOL on 409 then retries`() {
        val store = InMemoryConfigStore()
        store.set("davUrl", "https://192.168.1.50:8080/dav/")
        store.set("remotePath", "xime")
        val http = MockHttpHostApi()
        http.responseQueue.addLast(HttpResponse(409))
        http.responseQueue.addLast(HttpResponse(405))
        http.responseQueue.addLast(HttpResponse(201))
        http.responseQueue.addLast(HttpResponse(201))
        val adapter = newAdapter(store, http)

        val ok = runBlocking { adapter.push(ClipboardProfile(text = "hello", hash = "abc", size = 5)) }

        assertTrue("push 应成功", ok)
        assertEquals(4, http.requests.size)
        val methods = http.requests.map { it.first }
        assertEquals(listOf("PUT", "MKCOL", "MKCOL", "PUT"), methods)
        val mkcolUrls = http.requests.filter { it.first == "MKCOL" }.map { it.second }
        assertEquals(
            listOf(
                "https://192.168.1.50:8080/dav/xime",
                "https://192.168.1.50:8080/dav/xime/clipboard"
            ),
            mkcolUrls
        )
    }

    @Test
    fun `push creates missing directories via MKCOL on 404 then retries`() {
        val store = InMemoryConfigStore()
        store.set("davUrl", "https://192.168.1.50:8080/dav/")
        store.set("remotePath", "xime")
        val http = MockHttpHostApi()
        http.responseQueue.addLast(HttpResponse(404))
        http.responseQueue.addLast(HttpResponse(405))
        http.responseQueue.addLast(HttpResponse(201))
        http.responseQueue.addLast(HttpResponse(201))
        val adapter = newAdapter(store, http)

        val ok = runBlocking { adapter.push(ClipboardProfile(text = "hello", hash = "abc", size = 5)) }

        assertTrue("push 应成功", ok)
        val methods = http.requests.map { it.first }
        assertEquals(listOf("PUT", "MKCOL", "MKCOL", "PUT"), methods)
    }

    @Test
    fun `push honors remotePath in url`() {
        val store = InMemoryConfigStore()
        store.set("davUrl", "https://192.168.1.50:8080/dav/")
        store.set("remotePath", "xime")
        val http = MockHttpHostApi()
        http.responseQueue.addLast(HttpResponse(201))
        val adapter = newAdapter(store, http)

        val ok = runBlocking { adapter.push(ClipboardProfile(text = "hello", hash = "abc", size = 5)) }

        assertTrue("push 应成功", ok)
        assertEquals(1, http.requests.size)
        val (method, url) = http.requests[0]
        assertEquals("PUT", method)
        assertEquals("https://192.168.1.50:8080/dav/xime/clipboard/current.json", url)
    }

    @Test
    fun `push sends JSON profile body`() {
        val store = InMemoryConfigStore()
        store.set("davUrl", "https://192.168.1.50:8080/dav/")
        val http = MockHttpHostApi()
        http.responseQueue.addLast(HttpResponse(201))
        val adapter = newAdapter(store, http)

        val ok = runBlocking { adapter.push(ClipboardProfile(text = "hello", hash = "abc", size = 5)) }

        assertTrue("push 应成功", ok)
        val body = http.requestBodies[0]
        assertTrue("body 应为 JSON", body.startsWith("{"))
        assertTrue("body 应含 text", body.contains("\"hello\""))
        assertTrue("body 应含 hash", body.contains("\"abc\""))
    }

    @Test
    fun `pull returns profile on 200 json and caches etag`() {
        val store = InMemoryConfigStore()
        store.set("davUrl", "https://192.168.1.50:8080/dav/")
        val http = MockHttpHostApi()
        val profileJson = """{"type":"text","hash":"abc123","text":"远端内容","has_data":false,"data_name":null,"size":12,"source":"desktop"}"""
        http.responseQueue.addLast(
            HttpResponse(200, mapOf("ETag" to "webdav-etag-1"), profileJson.toByteArray())
        )
        val adapter = newAdapter(store, http)

        val profile = runBlocking { adapter.pull() }

        assertNotNull("pull 应返回对象", profile)
        assertEquals("远端内容", profile?.text)
        assertEquals("abc123", profile?.hash)
        assertEquals("desktop", profile?.source)
        assertEquals("webdav-etag-1", store.get("lastEtag"))
    }

    @Test
    fun `pull falls back to plain text for legacy file`() {
        val store = InMemoryConfigStore()
        store.set("davUrl", "https://192.168.1.50:8080/dav/")
        val http = MockHttpHostApi()
        http.responseQueue.addLast(HttpResponse(200, mapOf("ETag" to "etag-2"), "旧版纯文本".toByteArray()))
        val adapter = newAdapter(store, http)

        val profile = runBlocking { adapter.pull() }

        assertNotNull("pull 应返回对象", profile)
        assertEquals("旧版纯文本", profile?.text)
    }

    @Test
    fun `pull returns null on 304`() {
        val store = InMemoryConfigStore()
        store.set("davUrl", "https://192.168.1.50:8080/dav/")
        store.set("lastEtag", "webdav-etag-1")
        val http = MockHttpHostApi()
        http.responseQueue.addLast(HttpResponse(304))
        val adapter = newAdapter(store, http)

        val result = runBlocking { adapter.pull() }

        assertNull("304 应返回 null", result)
        assertEquals(1, http.requests.size)
        val headers = http.requests[0].third
        assertEquals("webdav-etag-1", headers["If-None-Match"])
    }

    @Test
    fun `pull returns null on 404`() {
        val store = InMemoryConfigStore()
        store.set("davUrl", "https://192.168.1.50:8080/dav/")
        val http = MockHttpHostApi()
        http.responseQueue.addLast(HttpResponse(404))
        val adapter = newAdapter(store, http)

        val result = runBlocking { adapter.pull() }

        assertNull("404 应返回 null", result)
    }

    @Test
    fun `testConnection uses PROPFIND on clipboard directory`() {
        val store = InMemoryConfigStore()
        store.set("davUrl", "https://192.168.1.50:8080/dav/")
        val http = MockHttpHostApi()
        http.responseQueue.addLast(HttpResponse(207))
        val adapter = newAdapter(store, http)

        val error = runBlocking { adapter.testConnection() }

        assertFalse("207 视为连接成功: $error", error.orEmpty().contains("失败"))
        assertEquals(1, http.requests.size)
        val (method, url, headers) = http.requests[0]
        assertEquals("PROPFIND", method)
        assertEquals("https://192.168.1.50:8080/dav/clipboard", url)
        assertEquals("0", headers["Depth"])
    }

    @Test
    fun `testConnection reports auth failure`() {
        val store = InMemoryConfigStore()
        store.set("davUrl", "https://192.168.1.50:8080/dav/")
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

    @Test
    fun `testConnection succeeds on 404 (server reachable, file not yet created)`() {
        val store = InMemoryConfigStore()
        store.set("davUrl", "https://192.168.1.50:8080/dav/")
        val http = MockHttpHostApi()
        http.responseQueue.addLast(HttpResponse(404))
        val adapter = newAdapter(store, http)

        val error = runBlocking { adapter.testConnection() }

        assertFalse("404 视为连接成功", error.orEmpty().contains("失败"))
    }

    @Test
    fun `push returns false when url not configured`() {
        val adapter = newAdapter(InMemoryConfigStore(), MockHttpHostApi())
        val ok = runBlocking { adapter.push(ClipboardProfile(text = "hello", hash = "abc", size = 5)) }
        assertFalse("未配置时应失败", ok)
    }

    @Test
    fun `pull returns null when url not configured`() {
        val adapter = newAdapter(InMemoryConfigStore(), MockHttpHostApi())
        val result = runBlocking { adapter.pull() }
        assertNull("未配置时应返回 null", result)
    }

    // ============================================================
    // 图片附件（Phase 3 / D12）：blob 先传后写 JSON；拉取用二进制 body
    // 这里跑的是**真实插件产物**（QuickJS），与 xipm test 互为双保险
    // ============================================================

    private fun imageBytes() = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 1, 2, 3, 4)

    @Test
    fun `push image uploads blob first then metadata json`() {
        val store = InMemoryConfigStore()
        store.set("davUrl", "https://192.168.1.50:8080/dav/")
        val http = MockHttpHostApi()
        http.responseQueue.addLast(HttpResponse(201)) // blob
        http.responseQueue.addLast(HttpResponse(201)) // metadata
        val adapter = newAdapter(store, http)
        val bytes = imageBytes()
        val profile = ClipboardProfile.fromImage(bytes, "png", source = "device-a")

        val ok = runBlocking { adapter.push(profile) }

        assertTrue("push 应成功", ok)
        assertEquals(2, http.requests.size)
        assertEquals("PUT", http.requests[0].first)
        assertEquals(
            "附件必须先写 blob",
            "https://192.168.1.50:8080/dav/clipboard/blobs/${profile.dataName}",
            http.requests[0].second
        )
        assertEquals("image/png", http.requests[0].third["Content-Type"])
        assertTrue("附件应原样按字节上传", http.requestBodyBytes[0]!!.contentEquals(bytes))
        assertEquals(
            "https://192.168.1.50:8080/dav/clipboard/current.json",
            http.requests[1].second
        )
        val json = http.requestBodies[1]
        assertTrue("metadata 应声明 has_data: $json", json.contains("\"has_data\":true"))
        assertTrue("metadata 应带附件名: $json", json.contains(profile.dataName!!))
        assertFalse("附件字节不应写进 JSON: $json", json.contains("\"data\""))
    }

    @Test
    fun `push image skips metadata json when blob upload fails`() {
        val store = InMemoryConfigStore()
        store.set("davUrl", "https://192.168.1.50:8080/dav/")
        val http = MockHttpHostApi()
        http.responseQueue.addLast(HttpResponse(500)) // blob 失败
        val adapter = newAdapter(store, http)

        val ok = runBlocking { adapter.push(ClipboardProfile.fromImage(imageBytes(), "png")) }

        assertFalse("blob 失败时 push 必须失败", ok)
        assertEquals("不应写出指向缺失附件的 JSON", 1, http.requests.size)
        assertTrue(http.requests[0].second.endsWith("/clipboard/blobs/${ClipboardProfile.fromImage(imageBytes(), "png").dataName}"))
    }

    @Test
    fun `pull image downloads blob bytes`() {
        val store = InMemoryConfigStore()
        store.set("davUrl", "https://192.168.1.50:8080/dav/")
        val http = MockHttpHostApi()
        val bytes = imageBytes()
        val hash = ClipboardProfile.sha256Hex(bytes)
        val metadata = """{"type":"image","hash":"$hash","text":"","has_data":true,""" +
            """"data_name":"$hash.png","size":${bytes.size},"source":"device-a"}"""
        http.responseQueue.addLast(HttpResponse(200, mapOf("ETag" to "img-etag"), metadata.toByteArray()))
        http.responseQueue.addLast(HttpResponse(200, mapOf("Content-Type" to "image/png"), bytes))
        val adapter = newAdapter(store, http)

        val profile = runBlocking { adapter.pull() }

        assertNotNull("应解析出图片 profile", profile)
        assertTrue("应标记有附件", profile!!.hasData)
        assertEquals("$hash.png", profile.dataName)
        assertEquals(hash, profile.hash)
        assertEquals("", profile.text)
        assertTrue("附件字节应无损回传（不能用 resp.text）", profile.data!!.contentEquals(bytes))
        assertEquals(
            "https://192.168.1.50:8080/dav/clipboard/blobs/$hash.png",
            http.requests[1].second
        )
    }
}