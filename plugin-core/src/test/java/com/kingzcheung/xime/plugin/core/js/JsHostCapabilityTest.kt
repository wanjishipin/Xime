package com.kingzcheung.xime.plugin.core.js

import com.kingzcheung.xime.plugin.core.config.PluginConfigStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * host 能力探测与配置糖（API 友好度批次 1）：
 * - host.has / host.capabilities 反映按 manifest 实际注入的子能力（has 是官方探测手段）
 * - 未声明能力保持历史契约：子表不存在（typeof === 'undefined'），可优雅降级
 * - host.config.getJson 糖方法：键不存在 / 内容非法 JSON / 空值均返回 null
 */
class JsHostCapabilityTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private class InMemoryConfigStore : PluginConfigStore {
        private val map = LinkedHashMap<String, String>()
        override fun get(key: String): String? = map[key]
        override fun set(key: String, value: String) {
            map[key] = value
        }

        override fun remove(key: String) {
            map.remove(key)
        }

        override fun keys(): Set<String> = map.keys.toSet()
    }

    private val probeJs = """
        globalThis.plugin = {
          probe: function () {
            var out = {
              caps: host.capabilities.join(','),
              hasConfig: host.has('config'),
              hasWs: host.has('ws'),
              hasClipboard: host.has('clipboard'),
              hasAsr: host.has('asr'),
              wsType: typeof host.ws,
              clipboardType: typeof host.clipboard,
              asrType: typeof host.asr,
              jsonMissing: host.config.getJson('missing'),
              jsonBad: host.config.getJson('bad'),
              jsonEmpty: host.config.getJson('empty'),
              jsonOk: JSON.stringify(host.config.getJson('good'))
            };
            if (typeof host.clipboard !== 'undefined') {
              out.clipboardValue = host.clipboard.get();
            }
            return out;
          }
        };
    """.trimIndent()

    private fun makeRuntime(clipboard: ClipboardHostApi? = null, injectAsr: Boolean = false): JsScriptRuntime {
        val dir = tmp.newFolder("cap-probe")
        File(dir, "main.js").writeText(probeJs)
        val store = InMemoryConfigStore().apply {
            set("bad", "{not valid json")
            set("empty", "")
            set("good", """{"a":1}""")
        }
        return JsScriptRuntime(
            pluginId = "cap-probe",
            pluginDir = dir,
            entryScript = "main.js",
            configStore = store,
            clipboardHostApi = clipboard,
            injectAsr = injectAsr
        )
    }

    @Test(timeout = 60_000)
    fun `未声明能力可通过 has 探测且子表保持 undefined`() {
        val runtime = makeRuntime(clipboard = null)
        try {
            assertTrue("插件应能加载", runtime.load())
            val out = runtime.call("probe") as Map<*, *>

            val caps = out["caps"].toString()
            assertTrue("恒有能力应在 caps 中: $caps", caps.contains("config"))
            // asr 上行表仅 speech 型注入（构造参数默认关）：非 speech 型不应出现
            assertFalse("非 speech 型不应注入 asr: $caps", caps.contains("asr"))
            assertFalse("未注入能力不应在 caps 中: $caps", caps.contains("ws"))
            assertFalse("未注入能力不应在 caps 中: $caps", caps.contains("clipboard"))
            assertFalse("未注入能力不应在 caps 中: $caps", caps.contains("http"))

            assertEquals(false, out["hasWs"])
            assertEquals(false, out["hasClipboard"])
            assertEquals(true, out["hasConfig"])

            assertEquals("未声明能力保持历史契约（undefined 可降级）", "undefined", out["wsType"]?.toString())
            assertEquals("未声明能力保持历史契约（undefined 可降级）", "undefined", out["clipboardType"]?.toString())

            assertNull("键不存在 → null", out["jsonMissing"])
            assertNull("非法 JSON → null", out["jsonBad"])
            assertNull("空值 → null", out["jsonEmpty"])
            assertEquals("""{"a":1}""", out["jsonOk"]?.toString())
        } finally {
            runtime.close()
        }
    }

    @Test(timeout = 60_000)
    fun `已注入能力 has 为 true 且子表可正常使用`() {
        val runtime = makeRuntime(clipboard = object : ClipboardHostApi {
            override fun getText(): String? = "剪贴板内容"
        })
        try {
            assertTrue(runtime.load())
            val out = runtime.call("probe") as Map<*, *>

            assertTrue("已注入能力应在 caps 中", out["caps"].toString().contains("clipboard"))
            assertEquals(true, out["hasClipboard"])
            assertEquals("object", out["clipboardType"]?.toString())
            assertEquals("剪贴板内容", out["clipboardValue"]?.toString())
            assertEquals("ws 仍未注入", false, out["hasWs"])
        } finally {
            runtime.close()
        }
    }

    @Test(timeout = 60_000)
    fun `speech 型注入 asr 上行表`() {
        val runtime = makeRuntime(injectAsr = true)
        try {
            assertTrue(runtime.load())
            val out = runtime.call("probe") as Map<*, *>
            assertTrue("speech 型应注入 asr: ${out["caps"]}", out["caps"].toString().contains("asr"))
            assertEquals(true, out["hasAsr"])
            assertEquals("object", out["asrType"]?.toString())
        } finally {
            runtime.close()
        }
    }
}
