package com.kingzcheung.xime.plugin.core.js

import android.app.Application
import com.kingzcheung.xime.plugin.core.config.PluginConfigStore
import com.kingzcheung.xime.plugin.core.js.crypto.CryptoHostApi
import com.kingzcheung.xime.plugin.core.js.http.HttpHostApi
import com.kingzcheung.xime.plugin.core.js.http.HttpResponse
import com.kingzcheung.xime.plugin.core.js.sdk.JsHostApi
import com.kingzcheung.xime.plugin.core.model.PluginContext
import com.kingzcheung.xime.plugin.core.model.PluginInfo
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class JsWebdavBackupListTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val propfindXml = """
        <?xml version="1.0" encoding="utf-8"?>
        <D:multistatus xmlns:D="DAV:">
          <D:response>
            <D:href>/dav/xime_backup/</D:href>
            <D:propstat>
              <D:prop>
                <D:resourcetype><D:collection/></D:resourcetype>
                <D:getlastmodified>Sat, 06 Sep 2026 02:30:58 GMT</D:getlastmodified>
              </D:prop>
              <D:status>HTTP/1.1 200 OK</D:status>
            </D:propstat>
          </D:response>
          <D:response>
            <D:href>/dav/xime_backup/Xime%E9%85%8D%E7%BD%AE-2026-09-06.zip</D:href>
            <D:propstat>
              <D:prop>
                <D:resourcetype/>
                <D:getcontentlength>5598773</D:getcontentlength>
                <D:getlastmodified>Sat, 06 Sep 2026 02:30:58 GMT</D:getlastmodified>
              </D:prop>
              <D:status>HTTP/1.1 200 OK</D:status>
            </D:propstat>
          </D:response>
        </D:multistatus>
    """.trimIndent()

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

        override fun lastError(): String? = null
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
            val candidate = File(dir, "build/plugin-js/webdav-backup/main.js")
            if (candidate.isFile) return candidate
            dir = dir.parentFile
        }
        throw AssertionError(
            "找不到 build/plugin-js/webdav-backup/main.js，" +
                "请先运行：cd tools/xime-plugin && cargo run -- build ../../plugins/webdav-backup --out ../../build/plugin-js"
        )
    }

    private fun loadPlugin(): Pair<JsBackupPluginAdapter, MockHttpHostApi> {
        val dir = writePlugin()
        val store = InMemoryConfigStore()
        store.set("url", "https://dav.jianguoyun.com/dav/")
        store.set("username", "user")
        store.set("password", "pass")
        store.set("remote_path", "/xime_backup")
        val http = MockHttpHostApi()
        http.responseQueue.addLast(
            HttpResponse(207, mapOf("Content-Type" to "application/xml; charset=utf-8"), propfindXml.toByteArray())
        )
        val runtime = JsScriptRuntime(
            "js-webdav-backup-list",
            dir,
            "main.js",
            store,
            hostApi = DebugHostApi(store),
            httpHostApi = http,
            cryptoHostApi = MockCryptoHostApi()
        )
        assertTrue("main.js 应能加载", runtime.load())
        val info = PluginInfo(
            id = "com.kingzcheung.xime.plugin.webdav_backup", name = "WebDAV 备份",
            description = "测试", iconResId = 0, versionCode = 1, versionName = "2.0.0",
            path = File(dir, "main.js").absolutePath, type = "backup"
        )
        val adapter = JsBackupPluginAdapter(
            runtime, PluginContext(application = Application(), pluginInfo = info, configStore = store)
        )
        return adapter to http
    }

    @Test
    fun `listBackups parses jianguoyun 207 response`() {
        val (adapter, http) = loadPlugin()
        try {
            val items = runBlocking { adapter.listBackups() }

            assertEquals(1, items?.size)
            val item = items?.first()
            assertEquals("Xime配置-2026-09-06.zip", item?.name)
            assertEquals("/dav/xime_backup/Xime配置-2026-09-06.zip", item?.id)
            assertEquals(5598773L, item?.size)
            assertTrue("createdAt 应 > 0", (item?.createdAt ?: 0) > 0)
            // PROPFIND 打到了带 /dav 前缀的正确地址
            assertEquals("PROPFIND", http.requests[0].first)
            assertEquals("https://dav.jianguoyun.com/dav/xime_backup", http.requests[0].second)
        } finally {
            adapter.onUnload()
        }
    }
}