package com.kingzcheung.xime.plugin.core.engine

import com.dokar.quickjs.evaluate
import com.dokar.quickjs.quickJs
import com.kingzcheung.xime.plugin.core.config.NoopPluginConfigStore
import com.kingzcheung.xime.plugin.core.js.JsScriptRuntime
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * TS 编译产物（rolldown → IIFE 单文件）在 QuickJS 中的兼容性冒烟。
 *
 * 两段验证：
 * 1. 裸 QuickJS（无宿主注入）：记录原生全局能力基线（ES2020 语法必须全部可执行）
 * 2. JsScriptRuntime（宿主完整环境）：验证 polyfill（console/TextEncoder/TextDecoder/atob/btoa）与数据往返
 *
 * 前置：`xipm build test-fixture --out build/plugin-js`（在 tools/xime-plugin 下执行）。
 */
class JsTsBundleSmokeTest {

    private fun bundleFile(): File {
        var dir: File? = File(System.getProperty("user.dir") ?: ".")
        while (dir != null) {
            val candidate = File(dir, "build/plugin-js/test-fixture/main.js")
            if (candidate.isFile) return candidate
            dir = dir.parentFile
        }
        throw AssertionError("找不到 build/plugin-js/test-fixture/main.js，请先运行：cd tools/xime-plugin && xipm build test-fixture --out ../../build/plugin-js")
    }

    @Test(timeout = 60_000)
    fun `TS bundle runs on QuickJS with ES2020 syntax`() = runBlocking {
        val code = bundleFile().readText()
        assertTrue("产物应为 IIFE 单文件（无顶层 import/export）", !code.contains("\nimport "))

        // 第一段：裸 QuickJS 基线（polyfill 之前的原生能力）
        quickJs {
            evaluate<Any?>(code, filename = "main.js")

            // 关键契约验证：TS 模块（export default）产物的 `var plugin = (...)()`，
            // 在 QuickJS 脚本模式下必须成为 globalThis 属性（宿主读取 globalThis.plugin）
            assertTrue("产物应为 IIFE 单文件（无顶层 import/export）", !code.contains("\nimport "))
            assertTrue("产物应为 `var plugin = (...)()` 形态", code.contains("var plugin = (function"))
            assertEquals("var plugin 应成为全局变量", "object", evaluate<String>("typeof plugin"))
            assertEquals("globalThis.plugin 与 plugin 为同一引用", true, evaluate<Boolean>("globalThis.plugin === plugin"))
            assertEquals("globalThis 应包含 plugin 属性", true, evaluate<Boolean>("'plugin' in globalThis"))

            // 重复执行（插件重载场景）：全局 var 重复声明应覆盖而不报错
            evaluate<Any?>(code, filename = "main.js")
            assertEquals("重复执行后契约仍成立", true, evaluate<Boolean>("globalThis.plugin === plugin"))
            assertEquals("重复执行后 snapshot 可用", "function", evaluate<String>("typeof globalThis.plugin.snapshot"))

            val rawProbe = evaluate<String>("JSON.stringify(globalThis.plugin.snapshot().envProbe)")
            println("[env-probe-raw] 裸 QuickJS 全局 API: $rawProbe")
            assertEquals("BigInt 为语言内建", "bigint", evaluate<String>("typeof BigInt(1)"))
            assertEquals("Symbol 为语言内建", "symbol", evaluate<String>("typeof Symbol()"))
            assertEquals("Promise 为语言内建", "function", evaluate<String>("typeof Promise"))
            assertEquals("Proxy 为语言内建", "function", evaluate<String>("typeof Proxy"))
        }

        // 第二段：宿主运行时（引导脚本 + polyfill + host 注入）
        val runtime = JsScriptRuntime(
            pluginId = "js-ts-bundle-smoke",
            pluginDir = bundleFile().parentFile,
            entryScript = "main.js",
            configStore = NoopPluginConfigStore
        )
        assertTrue("TS 产物应能加载", runtime.load())
        try {
            val snapshot = runtime.call("snapshot") as? Map<*, *>
            assertNotNull("snapshot() 应返回对象", snapshot)

            assertEquals("多文件 import 内联 + 模板字符串", "hello, Xime!", snapshot!!["greet"]?.toString())
            assertEquals("class fields + getter", 2, (snapshot["count"] as? Number)?.toInt())
            assertEquals("Set + 展开 + reduce", 12, (snapshot["sum"] as? Number)?.toInt())
            assertEquals("Map", 1, (snapshot["mapSize"] as? Number)?.toInt())
            assertEquals("BigInt（2^64）", "18446744073709551616", snapshot["big"]?.toString())
            assertEquals("Uint8Array", 6, (snapshot["byteSum"] as? Number)?.toInt())
            assertEquals("可选链 + 空值合并", "unknown", snapshot["opt"]?.toString())
            assertEquals("模板字符串", "sum=12", snapshot["template"]?.toString())
            assertEquals("宿主 polyfill：UTF-8 往返", "你好，Xime", snapshot["utf8Roundtrip"]?.toString())
            assertEquals("宿主 polyfill：base64 往返", "hello xime", snapshot["base64Roundtrip"]?.toString())

            @Suppress("UNCHECKED_CAST")
            val env = snapshot["envProbe"] as? Map<*, *>
            println("[env-probe-host] 宿主运行时全局 API: $env")
            assertEquals("console 已注入", "object", env?.get("console")?.toString())
            assertEquals("TextEncoder 已注入", "function", env?.get("TextEncoder")?.toString())
            assertEquals("TextDecoder 已注入", "function", env?.get("TextDecoder")?.toString())
            assertEquals("atob 已注入", "function", env?.get("atob")?.toString())
            assertEquals("btoa 已注入", "function", env?.get("btoa")?.toString())
        } finally {
            runtime.close()
        }
    }
}