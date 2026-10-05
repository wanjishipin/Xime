package com.kingzcheung.xime.plugin.core.js

import android.app.Application
import com.kingzcheung.xime.plugin.core.api.ClipboardProfile
import com.kingzcheung.xime.plugin.core.config.NoopPluginConfigStore
import com.kingzcheung.xime.plugin.core.model.PluginContext
import com.kingzcheung.xime.plugin.core.model.PluginInfo
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * clipboardSync 契约的**字节映射**与**空值判定**（Phase 3 / 决策 D12）。
 *
 * 用一个内联 JS 桩插件直接测适配器（不依赖任何真实插件产物），锁定两件事：
 * 1. 附件字节双向过桥：Kotlin `ByteArray` → JS `Uint8Array` → Kotlin `ByteArray`（不是 base64 字符串）；
 * 2. 图片 profile 的 `text` 是空串，**不能**拿"text 非空"当有内容的判据——
 *    早期实现会让纯图片条目永远被当作"无变更"而静默丢弃。
 */
class JsClipboardSyncProfileMappingTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val probeJs = """
        globalThis.plugin = {
          clipboardSync: {
            push: function (p) {
              globalThis.__probe = {
                type: p.type,
                hash: p.hash,
                text: p.text,
                hasData: p.hasData,
                dataName: p.dataName,
                size: p.size,
                isBytes: (p.data instanceof Uint8Array),
                len: p.data ? p.data.length : -1,
                first: (p.data && p.data.length) ? p.data[0] : -1
              };
              return true;
            },
            pull: function () { return globalThis.__pull; },
            test: function () { return null; }
          },
          probe: function () { return globalThis.__probe; },
          setPull: function (v) { globalThis.__pull = v; return true; }
        };
    """.trimIndent()

    private fun newAdapter(js: String): Pair<JsClipboardSyncPluginAdapter, JsScriptRuntime> {
        val dir = tmp.newFolder("plugin")
        File(dir, "main.js").writeText(js)
        val runtime = JsScriptRuntime("js-clipboard-map", dir, "main.js", NoopPluginConfigStore)
        assertTrue("main.js 应能加载", runtime.load())
        val info = PluginInfo(
            id = "com.test.clipboard_map",
            name = "契约映射测试",
            description = "测试",
            iconResId = 0,
            versionCode = 1,
            versionName = "1.0.0",
            path = File(dir, "main.js").absolutePath,
            type = "clipboard_sync"
        )
        val adapter = JsClipboardSyncPluginAdapter(
            runtime,
            PluginContext(
                application = Application(),
                pluginInfo = info,
                configStore = NoopPluginConfigStore
            )
        )
        return adapter to runtime
    }

    @Test
    fun `push 把附件字节作为 Uint8Array 传给插件`() {
        val (adapter, runtime) = newAdapter(probeJs)
        val bytes = byteArrayOf(10, 20, 30, 40)
        val ok = runBlocking {
            adapter.push(ClipboardProfile.fromImage(bytes, "png", source = "dev-a"))
        }
        assertTrue("push 应成功", ok)

        val probe = JsScriptRuntime.jsToKotlin(runtime.call("probe")) as? Map<*, *>
        assertTrue("应拿到桩插件的探针结果", probe != null)
        assertEquals("image", probe!!["type"])
        assertEquals("", probe["text"])
        assertEquals(true, probe["hasData"])
        assertEquals("${ClipboardProfile.sha256Hex(bytes)}.png", probe["dataName"])
        assertEquals(4L, (probe["size"] as Number).toLong())
        assertEquals("附件应过桥为 Uint8Array", true, probe["isBytes"])
        assertEquals(4, (probe["len"] as Number).toInt())
        assertEquals(10, (probe["first"] as Number).toInt())
        runtime.close()
    }

    @Test
    fun `pull 接受 text 为空但有附件的图片 profile 并带回字节`() {
        val (adapter, runtime) = newAdapter(probeJs)
        val bytes = byteArrayOf(7, 8, 9)
        runtime.call(
            "setPull",
            mapOf(
                "type" to "image",
                "hash" to "",
                "text" to "",
                "hasData" to true,
                "dataName" to "abc.png",
                "data" to bytes,
                "size" to 3,
                "source" to "dev-b"
            )
        )

        val profile = runBlocking { adapter.pull() }
        assertTrue("text 为空但有附件时不能判为无变更", profile != null)
        assertEquals("image", profile!!.type)
        assertEquals("", profile.text)
        assertTrue(profile.hasData)
        assertEquals("abc.png", profile.dataName)
        assertEquals("dev-b", profile.source)
        assertEquals(3L, profile.size)
        assertEquals("hash 缺失时按附件字节补算", ClipboardProfile.sha256Hex(bytes), profile.hash)
        assertTrue("附件字节应回到 Kotlin ByteArray", profile.data!!.contentEquals(bytes))
        runtime.close()
    }

    @Test
    fun `pull 在 text 为空且无附件时仍返回 null`() {
        val (adapter, runtime) = newAdapter(probeJs)
        runtime.call(
            "setPull",
            mapOf("text" to "", "hash" to "", "hasData" to false, "dataName" to null, "size" to 0)
        )
        assertNull("无内容即无变更", runBlocking { adapter.pull() })
        runtime.close()
    }

    @Test
    fun `pull 的文本路径行为不变`() {
        val (adapter, runtime) = newAdapter(probeJs)
        runtime.call(
            "setPull",
            mapOf("text" to "hello", "hash" to "", "hasData" to false, "size" to 5)
        )
        val profile = runBlocking { adapter.pull() }
        assertEquals("text", profile!!.type)
        assertEquals("hello", profile.text)
        assertEquals("hash 缺失时按文本补算", ClipboardProfile.sha256Hex("hello".toByteArray()), profile.hash)
        assertNull(profile.data)
        runtime.close()
    }
}