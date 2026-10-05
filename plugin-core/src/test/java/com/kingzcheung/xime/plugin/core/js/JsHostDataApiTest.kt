package com.kingzcheung.xime.plugin.core.js

import com.kingzcheung.xime.plugin.core.config.NoopPluginConfigStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * 宿主数据只读 API（host.quickSend / host.clipboard）桥接：
 * - 声明式注入：构造传 API → host 表挂出；不传 → host（undefined）
 * - list() 数据映射（id/text/timestamp/isPinned）
 * - clipboard.get() 空文本返回 null
 */
class JsHostDataApiTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun writePlugin(js: String): File {
        val dir = tmp.newFolder("plugin")
        File(dir, "main.js").writeText(js)
        return dir
    }

    /** 探针插件：把 host API 的观察结果序列化为字符串返回。 */
    private val probeJs = """
        globalThis.plugin = {
          probeQuickSend: function() {
            if (typeof host.quickSend === 'undefined') return 'nil';
            var l = host.quickSend.list();
            var first = l[0];
            return l.length + '|' + first.id + '|' + first.text + '|' + (first.code || '') + '|' + first.isPinned;
          },
          probeClipboard: function() {
            if (typeof host.clipboard === 'undefined') return 'nil';
            var t = host.clipboard.get();
            return (t === null || t === undefined) ? 'nil' : t;
          }
        }
    """.trimIndent()

    private fun newRuntime(
        quickSendApi: QuickSendHostApi? = null,
        clipboardApi: ClipboardHostApi? = null,
    ): JsScriptRuntime = JsScriptRuntime(
        "hostdata-test", writePlugin(probeJs), "main.js", NoopPluginConfigStore,
        quickSendHostApi = quickSendApi,
        clipboardHostApi = clipboardApi,
    )

    @Test
    fun `注入 quickSendApi 后 list 数据映射正确`() {
        val rt = newRuntime(
            quickSendApi = object : QuickSendHostApi {
                override fun list() = listOf(
                    QuickSendItem(id = 7, text = "18500000000", code = "dh", timestamp = 1690000000000, isPinned = true),
                    QuickSendItem(id = 8, text = "北京市海淀区", code = "", timestamp = 1690000000001, isPinned = false),
                )
            }
        )
        assertTrue(rt.load())
        assertEquals("2|7|18500000000|dh|true", rt.call("probeQuickSend")?.toString())
        rt.close()
    }

    @Test
    fun `注入 clipboardApi 后 get 返回文本`() {
        val rt = newRuntime(
            clipboardApi = object : ClipboardHostApi {
                override fun getText(): String? = "剪贴板内容"
            }
        )
        assertTrue(rt.load())
        assertEquals("剪贴板内容", rt.call("probeClipboard")?.toString())
        rt.close()
    }

    @Test
    fun `剪贴板为空时 get 返回 nil`() {
        val rt = newRuntime(
            clipboardApi = object : ClipboardHostApi {
                override fun getText(): String? = ""
            }
        )
        assertTrue(rt.load())
        assertEquals("nil", rt.call("probeClipboard")?.toString())
        rt.close()
    }

    @Test
    fun `未声明能力的插件拿不到 host 表`() {
        val rt = newRuntime()
        assertTrue(rt.load())
        assertEquals("nil", rt.call("probeQuickSend")?.toString())
        assertEquals("nil", rt.call("probeClipboard")?.toString())
        rt.close()
    }
}