package com.kingzcheung.xime.plugin.core.js

import com.kingzcheung.xime.plugin.core.config.NoopPluginConfigStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class JsEncodePathTest {

    @get:Rule
    val tmp = TemporaryFolder()

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

    private fun newRuntime(): JsScriptRuntime {
        val runtime = JsScriptRuntime(
            "js-webdav-backup-encode",
            writePlugin(),
            "main.js",
            NoopPluginConfigStore
        )
        assertTrue("main.js 应能加载", runtime.load())
        return runtime
    }

    private fun encodePath(raw: String): String {
        val runtime = newRuntime()
        return try {
            runtime.call("_encodePath", raw)?.toString() ?: ""
        } finally {
            runtime.close()
        }
    }

    @Test
    fun `encodes chinese filename to utf-8 percent escapes`() {
        assertEquals(
            "Xime%E9%85%8D%E7%BD%AE-2026-09-06.zip",
            encodePath("Xime配置-2026-09-06.zip")
        )
    }

    @Test
    fun `keeps unreserved and path chars intact`() {
        assertEquals(
            "https://dav.jianguoyun.com/dav/xime_backup",
            encodePath("https://dav.jianguoyun.com/dav/xime_backup")
        )
        assertEquals("a-b._~/z:9", encodePath("a-b._~/z:9"))
    }

    @Test
    fun `encodes space and percent`() {
        assertEquals("my%20file%2520.zip", encodePath("my file%20.zip"))
    }
}
