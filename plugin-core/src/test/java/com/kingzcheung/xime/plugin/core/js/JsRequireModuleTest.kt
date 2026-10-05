package com.kingzcheung.xime.plugin.core.js

import com.kingzcheung.xime.plugin.core.config.PluginConfigStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

private class RequireTestConfigStore : PluginConfigStore {
    private val map = LinkedHashMap<String, String>()
    override fun get(key: String): String? = map[key]
    override fun set(key: String, value: String) { map[key] = value }
    override fun remove(key: String) { map.remove(key) }
    override fun keys(): Set<String> = map.keys
}

class JsRequireModuleTest {

    private fun newDir(name: String): File = File("build/$name").apply {
        deleteRecursively()
        mkdirs()
    }

    private fun write(dir: File, rel: String, text: String) {
        File(dir, rel).apply {
            parentFile?.mkdirs()
            writeText(text)
        }
    }

    private fun runtime(pluginId: String, dir: File, main: String): JsScriptRuntime {
        write(dir, "main.js", main)
        return JsScriptRuntime(pluginId, dir, "main.js", RequireTestConfigStore())
    }

    @Test
    fun `require 加载 libs 模块并按 module exports 使用`() {
        val dir = newDir("js-require-basic")
        write(dir, "libs/greet.js", "module.exports = { hello: function (n) { return 'hi ' + n; } };")
        val rt = runtime(
            "js-require-basic", dir,
            """
            var greet = require('libs/greet.js');
            globalThis.plugin = { run: function () { return greet.hello('xime'); } };
            """.trimIndent()
        )
        assertTrue("插件应能加载", rt.load())
        assertEquals("hi xime", rt.call("run")?.toString())
        rt.close()
    }

    @Test
    fun `相同模块被缓存只执行一次`() {
        val dir = newDir("js-require-cache")
        write(
            dir, "libs/counter.js",
            """
            var n = (globalThis.__requireCounter || 0) + 1;
            globalThis.__requireCounter = n;
            module.exports = function () { return n; };
            """.trimIndent()
        )
        val rt = runtime(
            "js-require-cache", dir,
            """
            var counter = require('libs/counter.js');
            require('libs/counter.js');
            globalThis.plugin = { run: function () { return counter(); } };
            """.trimIndent()
        )
        assertTrue(rt.load())
        assertEquals("模块应只执行一次", "1", rt.call("run")?.toString())
        rt.close()
    }

    @Test
    fun `模块内相对路径 require 解析正确`() {
        val dir = newDir("js-require-relative")
        write(dir, "libs/b.js", "module.exports = { name: 'b' };")
        write(
            dir, "libs/a.js",
            "var b = require('./b.js');\nmodule.exports = { viaB: 'a->' + b.name };"
        )
        val rt = runtime(
            "js-require-relative", dir,
            """
            var a = require('libs/a.js');
            globalThis.plugin = { run: function () { return a.viaB; } };
            """.trimIndent()
        )
        assertTrue(rt.load())
        assertEquals("a->b", rt.call("run")?.toString())
        rt.close()
    }

    @Test
    fun `模块内上级目录 require 解析正确`() {
        val dir = newDir("js-require-updir")
        write(dir, "libs/d.js", "module.exports = { name: 'd' };")
        write(
            dir, "libs/sub/c.js",
            "var d = require('../d.js');\nmodule.exports = { viaD: 'c->' + d.name };"
        )
        val rt = runtime(
            "js-require-updir", dir,
            """
            var c = require('libs/sub/c.js');
            globalThis.plugin = { run: function () { return c.viaD; } };
            """.trimIndent()
        )
        assertTrue(rt.load())
        assertEquals("c->d", rt.call("run")?.toString())
        rt.close()
    }

    @Test
    fun `自动补全 js 后缀`() {
        val dir = newDir("js-require-noext")
        write(dir, "libs/noext.js", "module.exports = { ok: true };")
        val rt = runtime(
            "js-require-noext", dir,
            """
            var m = require('libs/noext');
            globalThis.plugin = { run: function () { return m.ok ? 'ok' : 'no'; } };
            """.trimIndent()
        )
        assertTrue(rt.load())
        assertEquals("ok", rt.call("run")?.toString())
        rt.close()
    }

    @Test
    fun `路径穿越的 require 被拒绝且不中断插件`() {
        val dir = newDir("js-require-escape")
        File(dir.parentFile, "evil.js").writeText("module.exports = 1;")
        val rt = runtime(
            "js-require-escape", dir,
            """
            globalThis.plugin = {
              run: function () {
                try { require('../evil.js'); return 'loaded'; }
                catch (e) { return 'blocked'; }
              }
            };
            """.trimIndent()
        )
        assertTrue(rt.load())
        assertEquals("blocked", rt.call("run")?.toString())
        rt.close()
    }

    @Test
    fun `模块不存在时抛错可被捕获`() {
        val dir = newDir("js-require-missing")
        val rt = runtime(
            "js-require-missing", dir,
            """
            globalThis.plugin = {
              run: function () {
                try { require('libs/nope.js'); return 'loaded'; }
                catch (e) { return 'missing:' + (e.message.indexOf('nope.js') >= 0); }
              }
            };
            """.trimIndent()
        )
        assertTrue(rt.load())
        assertEquals("missing:true", rt.call("run")?.toString())
        rt.close()
    }

    @Test
    fun `循环依赖返回半成品导出不挂死`() {
        val dir = newDir("js-require-cycle")
        write(
            dir, "libs/x.js",
            "exports.name = 'x';\nvar y = require('./y.js');\nexports.yName = y.name;"
        )
        write(
            dir, "libs/y.js",
            "exports.name = 'y';\nvar x = require('./x.js');\nexports.xName = x.name;"
        )
        val rt = runtime(
            "js-require-cycle", dir,
            """
            var x = require('libs/x.js');
            globalThis.plugin = { run: function () { return x.name + '/' + x.yName; } };
            """.trimIndent()
        )
        assertTrue(rt.load())
        assertEquals("x/y", rt.call("run")?.toString())
        rt.close()
    }

    @Test
    fun `模块内可访问宿主注入的 host`() {
        val dir = newDir("js-require-host")
        write(
            dir, "libs/store.js",
            "module.exports = { save: function (v) { host.config.set('k', v); } };"
        )
        val store = RequireTestConfigStore()
        write(
            dir, "main.js",
            """
            var store = require('libs/store.js');
            globalThis.plugin = { run: function () { store.save('v'); return 'done'; } };
            """.trimIndent()
        )
        val rt = JsScriptRuntime("js-require-host", dir, "main.js", store)
        assertTrue(rt.load())
        assertEquals("done", rt.call("run")?.toString())
        assertEquals("模块内 host 调用应生效", "v", store.get("k"))
        rt.close()
    }

    @Test
    fun `模块语法错误导致 require 抛错且带模块路径`() {
        val dir = newDir("js-require-syntax-error")
        write(dir, "libs/bad.js", "module.exports = {;")
        val rt = runtime(
            "js-require-syntax-error", dir,
            """
            globalThis.plugin = {
              run: function () {
                try { require('libs/bad.js'); return 'loaded'; }
                catch (e) { return 'error:' + (e.message.indexOf('libs/bad.js') >= 0); }
              }
            };
            """.trimIndent()
        )
        assertTrue(rt.load())
        assertEquals("error:true", rt.call("run")?.toString())
        rt.close()
    }

    @Test
    fun `模块路径解析与读取的安全边界`() {
        val dir = newDir("js-require-paths")
        write(dir, "libs/a.js", "module.exports = {};")
        val rt = runtime("js-require-paths", dir, "globalThis.plugin = {};")
        assertTrue(rt.load())

        assertEquals("libs/a.js", rt.resolveModulePath("./a.js", "libs"))
        assertEquals("libs/a.js", rt.resolveModulePath("a.js", "libs"))
        assertNull("绝对路径拒绝", rt.resolveModulePath("/etc/passwd", ""))
        assertNull("越出插件根目录拒绝", rt.resolveModulePath("../../evil.js", "libs"))
        assertNull("不存在的模块返回 null", rt.resolveModulePath("libs/nope.js", ""))
        assertNull("非 .js 文件不被模块加载", rt.resolveModulePath("resources/data.json", ""))

        assertNull("读取穿越路径拒绝", rt.readModuleText("../main.js"))
        assertNull("读取非 .js 拒绝", rt.readModuleText("manifest.json"))
        assertEquals(File(dir, "libs/a.js").readText(), rt.readModuleText("libs/a.js"))
        rt.close()
    }
}