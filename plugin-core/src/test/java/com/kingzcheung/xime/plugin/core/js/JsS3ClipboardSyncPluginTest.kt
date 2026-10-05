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
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * S3 剪贴板同步插件在**生产 JS 桥**上的集成测试（跑真实插件产物）。
 *
 * 与 `xipm test`（mock host）互为双保险：这里用真实 [JsScriptRuntime]（QuickJS）加载
 * `build/plugin-js/s3-clipboard-sync/main.js`，crypto 用 JCE **真算** sha256/hmac
 * （不是 mock 固定值），因此能覆盖"mock 绿、真机挂"的那一类问题：
 * - SigV4 在生产桥接层上依然命中独立实现（Python）的固定签名向量；
 * - 附件字节双向都是 Uint8Array（图片不被 UTF-8 文本化破坏）。
 *
 * 前置：`xipm build`（产出 build/plugin-js/s3-clipboard-sync/main.js）。
 */
class JsS3ClipboardSyncPluginTest {

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
        /** 原始字节：附件必须按字节断言（UTF-8 文本化会丢信息） */
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

    /** 真算 crypto（等价于生产的 CryptoHostApiImpl），时钟固定 ⇒ 签名可做固定向量断言。 */
    private class JceCryptoHostApi : CryptoHostApi {
        override fun sha256(data: ByteArray): ByteArray =
            MessageDigest.getInstance("SHA-256").digest(data)

        override fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray {
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(key, "HmacSHA256"))
            return mac.doFinal(data)
        }

        override fun hmacSha1(key: ByteArray, data: ByteArray): ByteArray {
            val mac = Mac.getInstance("HmacSHA1")
            mac.init(SecretKeySpec(key, "HmacSHA1"))
            return mac.doFinal(data)
        }

        override fun hex(data: ByteArray): String = data.joinToString("") { "%02x".format(it) }

        override fun base64(data: ByteArray): String =
            java.util.Base64.getEncoder().encodeToString(data)

        override fun utcTime(format: String): String = when (format) {
            "YYYYMMDDTHHMMSSZ" -> "20260930T120000Z"
            "YYYYMMDD" -> "20260930"
            else -> format
        }

        override fun epochSeconds(): Long = 1790769600
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

    private fun pluginSourceFile(): File {
        var dir: File? = File(System.getProperty("user.dir") ?: ".")
        while (dir != null) {
            val candidate = File(dir, "build/plugin-js/s3-clipboard-sync/main.js")
            if (candidate.isFile) return candidate
            dir = dir.parentFile
        }
        throw AssertionError(
            "找不到 build/plugin-js/s3-clipboard-sync/main.js，请先在仓库根运行：xipm build"
        )
    }

    private fun newAdapter(store: PluginConfigStore, http: MockHttpHostApi): JsClipboardSyncPluginAdapter {
        val dir = tmp.newFolder()
        pluginSourceFile().copyTo(File(dir, "main.js"))
        val runtime = JsScriptRuntime(
            "js-s3-clipboard-sync",
            dir,
            "main.js",
            store,
            hostApi = DebugHostApi(store),
            httpHostApi = http,
            cryptoHostApi = JceCryptoHostApi()
        )
        assertTrue("main.js 应能加载", runtime.load())
        val info = PluginInfo(
            id = "com.kingzcheung.xime.plugin.s3_clipboard_sync", name = "S3 剪贴板同步",
            description = "测试", iconResId = 0, versionCode = 1, versionName = "1.0.0",
            path = File(dir, "main.js").absolutePath, type = "clipboard_sync"
        )
        return JsClipboardSyncPluginAdapter(
            runtime, PluginContext(application = Application(), pluginInfo = info, configStore = store)
        )
    }

    /** 与 xipm 单测同一组配置（R2 路径式 + region=auto）。 */
    private fun s3Config(store: PluginConfigStore, bucket: String, dir: String = "xime") {
        store.set("endpoint", "https://acc123.r2.cloudflarestorage.com")
        store.set("region", "auto")
        store.set("bucket", bucket)
        store.set("dir", dir)
        store.set("accessKeyId", "AKIAEXAMPLE1234567890")
        store.set("secretAccessKey", "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY")
    }

    // ============================================================
    // 契约与配置
    // ============================================================

    @Test
    fun `main js loads and exposes sync contract`() {
        val store = InMemoryConfigStore()
        val adapter = newAdapter(store, MockHttpHostApi())
        val schema = adapter.getSettingsSchema()
        assertTrue("应导出 settings.schema", schema.isNotEmpty())
        val keys = schema.mapNotNull { it.key }
        for (required in listOf(
            "endpoint", "region", "bucket", "dir",
            "accessKeyId", "secretAccessKey"
        )) {
            assertTrue("schema 应声明 $required", keys.contains(required))
        }
        // 拉取间隔由宿主引擎消费（configStore key 契约），插件 schema 必须声明同名 NUMBER 字段
        assertEquals(
            "pull_interval_seconds",
            schema.first { it.label?.contains("拉取最小间隔") == true }.key
        )
    }

    @Test
    fun `push returns false and pull returns null when not configured`() {
        val adapter = newAdapter(InMemoryConfigStore(), MockHttpHostApi())
        val http = MockHttpHostApi()
        val adapter2 = newAdapter(InMemoryConfigStore(), http)
        assertFalse(
            "未配置时应失败",
            runBlocking { adapter.push(ClipboardProfile(text = "hello", hash = "abc", size = 5)) }
        )
        assertNull("未配置时应返回 null", runBlocking { adapter2.pull() })
        assertTrue("未配置时不应发请求", http.requests.isEmpty())
    }

    // ============================================================
    // SigV4：生产桥 + JCE 真 crypto 下命中固定向量
    // 向量由独立 Python 实现（hashlib/hmac）算出，期望值硬编码在断言里
    // ============================================================

    @Test
    fun `push text signs with aws sigv4 fixed vector`() {
        val store = InMemoryConfigStore()
        s3Config(store, "xime-test-auth")
        val http = MockHttpHostApi()
        http.responseQueue.addLast(HttpResponse(200, mapOf("ETag" to "\"etag-1\"")))
        val adapter = newAdapter(store, http)

        val ok = runBlocking { adapter.push(ClipboardProfile(text = "hi", hash = "h1", size = 2)) }

        assertTrue("push 应成功", ok)
        assertEquals(1, http.requests.size)
        val (method, url, headers) = http.requests[0]
        assertEquals("PUT", method)
        assertEquals("https://acc123.r2.cloudflarestorage.com/xime-test-auth/xime/clipboard.json", url)
        assertEquals("首次推送应要求远端不存在", "*", headers["If-None-Match"])
        assertEquals("amz-date 取宿主 UTC 时钟", "20260930T120000Z", headers["x-amz-date"])
        assertEquals(
            "payload 哈希",
            "79d828785f2ce626782166f8811677f70ee32b859adb67d27d50c30ac276c20a",
            headers["x-amz-content-sha256"]
        )
        assertEquals(
            "wire JSON 为 snake_case、与 webdav 插件同构",
            """{"type":"text","hash":"h1","text":"hi","has_data":false,"data_name":null,"size":2,"source":null}""",
            http.requestBodies[0]
        )
        assertEquals(
            "SigV4 签名必须与独立实现一致",
            "AWS4-HMAC-SHA256 Credential=AKIAEXAMPLE1234567890/20260930/auto/s3/aws4_request, " +
                "SignedHeaders=host;x-amz-content-sha256;x-amz-date, " +
                "Signature=5ce7456f6440041636f31b6911baacba72ce62d700311c8e639524a57dcf4495",
            headers["Authorization"]
        )
        assertEquals("成功后缓存 ETag 供乐观锁", "\"etag-1\"", store.get("lastEtag"))
    }

    @Test
    fun `pull sends conditional GET driven by cached etag`() {
        val store = InMemoryConfigStore()
        s3Config(store, "xime-test-auth")
        store.set("lastEtag", "\"etag-9\"")
        val http = MockHttpHostApi()
        http.responseQueue.addLast(HttpResponse(304))
        val adapter = newAdapter(store, http)

        val result = runBlocking { adapter.pull() }

        assertNull("304 应视为无变更", result)
        val (method, url, headers) = http.requests[0]
        assertEquals("GET", method)
        assertEquals("https://acc123.r2.cloudflarestorage.com/xime-test-auth/xime/clipboard.json", url)
        assertEquals("\"etag-9\"", headers["If-None-Match"])
        assertEquals(
            "空 body GET 的签名必须与独立实现一致",
            "AWS4-HMAC-SHA256 Credential=AKIAEXAMPLE1234567890/20260930/auto/s3/aws4_request, " +
                "SignedHeaders=host;x-amz-content-sha256;x-amz-date, " +
                "Signature=11f849f468feafc4ee20b39e267385c351c00deca98a684af5e2e285644b5f7a",
            headers["Authorization"]
        )
    }

    // ============================================================
    // 附件（图片原图）：跨桥必须是 Uint8Array，字节零损耗
    // ============================================================

    private fun imageBytes() = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 1, 2, 3, 4)

    @Test
    fun `push image uploads raw blob before metadata`() {
        val store = InMemoryConfigStore()
        s3Config(store, "xime-test-img")
        val http = MockHttpHostApi()
        http.responseQueue.addLast(HttpResponse(200)) // blob
        http.responseQueue.addLast(HttpResponse(200, mapOf("ETag" to "\"img\""))) // metadata
        val adapter = newAdapter(store, http)
        val bytes = imageBytes()
        val profile = ClipboardProfile.fromImage(bytes, "png", source = "device-a")

        val ok = runBlocking { adapter.push(profile) }

        assertTrue("push 应成功", ok)
        assertEquals("恰好两个请求", 2, http.requests.size)
        assertEquals("附件必须先写 blob", "PUT", http.requests[0].first)
        assertEquals(
            "blobs/<sha256>.<ext> 内容寻址路径",
            "https://acc123.r2.cloudflarestorage.com/xime-test-img/xime/blobs/${profile.dataName}",
            http.requests[0].second
        )
        assertEquals("image/png", http.requests[0].third["Content-Type"])
        assertTrue(
            "附件字节必须原样过桥（Uint8Array 未被文本化）",
            http.requestBodyBytes[0]!!.contentEquals(bytes)
        )
        assertEquals(
            "JS 侧算出的 payload 哈希应与 JCE 独立计算一致",
            ClipboardProfile.sha256Hex(bytes),
            http.requests[0].third["x-amz-content-sha256"]
        )
        val json = http.requestBodies[1]
        assertTrue("metadata 应声明 has_data: $json", json.contains("\"has_data\":true"))
        assertTrue("metadata 应带附件名: $json", json.contains(profile.dataName!!))
        assertFalse("附件字节不应写进 JSON: $json", json.contains("\"data\""))
    }

    @Test
    fun `pull image returns raw bytes through the bridge`() {
        val store = InMemoryConfigStore()
        s3Config(store, "xime-test-pullimg")
        val bytes = imageBytes()
        val hash = ClipboardProfile.sha256Hex(bytes)
        val metadata = """{"type":"image","hash":"$hash","has_data":true,""" +
            """"data_name":"$hash.png","size":${bytes.size},"source":"device-a"}"""
        val http = MockHttpHostApi()
        http.responseQueue.addLast(
            HttpResponse(200, mapOf("ETag" to "\"img-etag\""), metadata.toByteArray())
        )
        http.responseQueue.addLast(
            HttpResponse(200, mapOf("Content-Type" to "image/png"), bytes)
        )
        val adapter = newAdapter(store, http)

        val profile = runBlocking { adapter.pull() }

        assertNotNull("图片条目不能被判成无变更（text 为空必须有 hasData 兜底）", profile)
        assertEquals("image", profile!!.type)
        assertTrue(profile.hasData)
        assertEquals("$hash.png", profile.dataName)
        assertNotNull("附件字节必须跨桥取回", profile.data)
        assertTrue("图片字节逐字节一致", profile.data!!.contentEquals(bytes))
        assertEquals(hash, profile.hash)
        // 先元数据、后附件
        assertEquals(2, http.requests.size)
        assertEquals(
            "https://acc123.r2.cloudflarestorage.com/xime-test-pullimg/xime/blobs/$hash.png",
            http.requests[1].second
        )
        assertEquals("\"img-etag\"", store.get("lastEtag"))
    }

    // ============================================================
    // 测试连接
    // ============================================================

    @Test
    fun `testConnection reports missing config and auth failure`() {
        val missing = newAdapter(InMemoryConfigStore(), MockHttpHostApi())
        assertTrue(
            "未配置应报告端点缺失",
            runBlocking { missing.testConnection() }.orEmpty().contains("未配置 S3 端点")
        )

        val store = InMemoryConfigStore()
        s3Config(store, "xime-test-badsign")
        val http = MockHttpHostApi()
        // 真机 HEAD 响应没有 body（HTTP 层不为 HEAD 解析 body）：这里也不能造 body，
        // 否则断言的是只有 mock 才有的错误码，比真机乐观
        http.responseQueue.addLast(HttpResponse(403))
        val adapter = newAdapter(store, http)
        val error = runBlocking { adapter.testConnection() }
        assertTrue("应报告鉴权失败: $error", error.orEmpty().contains("鉴权失败"))
        assertTrue("应提示检查密钥: $error", error.orEmpty().contains("Secret Access Key"))
    }

    @Test
    fun `testConnection success path probes bucket then write permission`() {
        val store = InMemoryConfigStore()
        s3Config(store, "xime-test-ok")
        val http = MockHttpHostApi()
        http.responseQueue.addLast(HttpResponse(200)) // HEAD bucket
        http.responseQueue.addLast(HttpResponse(200)) // PUT 探针
        http.responseQueue.addLast(HttpResponse(204)) // DELETE 探针
        http.responseQueue.addLast(HttpResponse(404)) // HEAD 元数据
        val adapter = newAdapter(store, http)

        val error = runBlocking { adapter.testConnection() }

        assertNull("应报告成功: $error", error)
        assertEquals(4, http.requests.size)
        assertEquals("HEAD", http.requests[0].first)
        assertEquals("https://acc123.r2.cloudflarestorage.com/xime-test-ok", http.requests[0].second)
        assertEquals("PUT", http.requests[1].first)
        assertEquals(
            "探针须落在对象键同目录，避免污染业务目录",
            "https://acc123.r2.cloudflarestorage.com/xime-test-ok/xime/.xime-probe",
            http.requests[1].second
        )
        assertEquals("DELETE", http.requests[2].first)
        assertEquals("HEAD", http.requests[3].first)
    }

    @Test
    fun `probe path follows configured directory`() {
        val store = InMemoryConfigStore()
        s3Config(store, "xime-test-dir", dir = "xime/archive")
        val http = MockHttpHostApi()
        http.responseQueue.addLast(HttpResponse(200))
        http.responseQueue.addLast(HttpResponse(200))
        http.responseQueue.addLast(HttpResponse(204))
        http.responseQueue.addLast(HttpResponse(404))
        val adapter = newAdapter(store, http)

        assertNull(runBlocking { adapter.testConnection() })
        assertEquals(
            "https://acc123.r2.cloudflarestorage.com/xime-test-dir/xime/archive/.xime-probe",
            http.requests[1].second
        )
    }
}