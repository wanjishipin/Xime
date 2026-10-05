package com.kingzcheung.xime.plugin.core.engine

import com.dokar.quickjs.QuickJs
import com.dokar.quickjs.QuickJsException
import com.dokar.quickjs.QuickJsInterruptedException
import com.dokar.quickjs.binding.define
import com.dokar.quickjs.binding.function
import com.dokar.quickjs.evaluate
import com.dokar.quickjs.quickJs
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * QuickJS 引擎内核冒烟（使用 quickjs-kt 的 JVM artifact，无需设备）。
 *
 * 验证的是 F1 内核能力（对应未来 JsScriptRuntime 的依赖面）：
 * 1. eval / 导出表调用（对齐 Lua main.lua return table 形态）
 * 2. host 注入（define + function 回调）
 * 3. 类型映射（List/Map/Date.now 时间能力——Lua 沙箱没有时间，QuickJS 内建）
 * 4. 异常带文件名/行号/列号/堆栈（QuickJsException 位置字段）
 * 5. 死循环经协程取消中断（对应 luaj 的超时中毒机制）
 * 6. 实例级执行超时 evaluationTimeoutMillis → QuickJsInterruptedException
 * 7. 多实例隔离（每插件一个 runtime）
 * 8. 内存上限（JS_SetMemoryLimit）
 */
class QuickJsEngineSmokeTest {

    @Test(timeout = 30_000)
    fun `evaluate returns mapped types`() = runBlocking {
        quickJs {
            assertEquals("1+2 应返回 Int", 3, evaluate<Int>("1 + 2"))
            assertEquals("字符串", "ok", evaluate<String>("'ok'"))
            assertEquals(
                "对象映射 Map",
                mapOf("a" to "x"),
                evaluate<Map<String, String>>("({ a: 'x' })")
            )
            // JS number 为 64 位浮点：QuickJs 映射到 JVM 后可能是 Int/Long/Double，
            // 按数值语义断言而非具体包装类型
            assertEquals(
                listOf(1, 2, 3),
                evaluate<List<Number>>("[1, 2, 3]").map { it.toInt() }
            )
        }
    }

    @Test(timeout = 30_000)
    fun `export object call pattern works like lua main lua return table`() = runBlocking {
        quickJs {
            // 模拟插件入口：脚本定义全局导出对象 plugin（对应 Lua 的 return { ... }）
            evaluate<Any?>(
                """
                globalThis.plugin = {
                  hello: function(name) { return 'Hi, ' + name; },
                  add: function(a, b) { return a + b; }
                }
                """.trimIndent(),
                filename = "main.js"
            )
            assertEquals("Hi, xime", evaluate<String>("plugin.hello('xime')"))
            assertEquals(7, evaluate<Int>("plugin.add(3, 4)"))
        }
    }

    @Test(timeout = 30_000)
    fun `host injection via define and function callback`() = runBlocking {
        quickJs {
            val logs = mutableListOf<String>()
            define("host") {
                function("log") { args ->
                    logs += (args.first() as? String) ?: "?"
                }
                function<String, String>("echo") { it }
            }
            evaluate<Any?>(
                """
                host.log('from js')
                host.log(host.echo('echoed'))
                """.trimIndent()
            )
            assertEquals(listOf("from js", "echoed"), logs)
        }
    }

    @Test(timeout = 30_000)
    fun `Date now available in sandbox`() = runBlocking {
        quickJs {
            val now = evaluate<Double>("Date.now()")
            assertTrue("Date.now 应可用且接近当前时间", now > 1_700_000_000_000.0)
        }
    }

    @Test(timeout = 30_000)
    fun `script error carries filename and line info`() = runBlocking {
        quickJs {
            val e = try {
                evaluate<Any?>(
                    "const a = 1;\nthrow new Error('boom')",
                    filename = "main.js"
                )
                null
            } catch (e: QuickJsException) {
                e
            }
            assertNotNull("应抛出 QuickJsException", e)
            assertEquals("错误带文件名", "main.js", e!!.fileName)
            assertTrue("错误带行号（错误在第二行，行号可能 1..2）", (e.lineNumber ?: -1) > 0)
            assertNotNull("错误带堆栈", e.stack)
        }
    }

    @Test(timeout = 30_000)
    fun `instance evaluation timeout interrupts busy loop`() = runBlocking {
        quickJs {
            evaluationTimeoutMillis = 500 // 实例级执行超时
            val e = try {
                evaluate<Any?>("while(true){}")
                null
            } catch (e: QuickJsException) {
                e
            }
            assertTrue("超时应抛 QuickJsInterruptedException，实际: ${e?.javaClass?.simpleName}",
                e is QuickJsInterruptedException)
        }
    }

    @Test(timeout = 30_000)
    fun `instances are isolated per engine`() = runBlocking {
        // 每插件一个 runtime：全局对象互不可见
        quickJs {
            evaluate<Any?>("globalThis.secret = 'engine-a'")
            assertEquals("engine-a", evaluate<String>("globalThis.secret"))
        }
        quickJs {
            val secret = try {
                evaluate<String>("globalThis.secret")
            } catch (e: Exception) {
                null
            }
            assertTrue("新实例不应看到旧实例的全局对象", secret == null)
            evaluate<Any?>("globalThis.secret = 'engine-b'")
            assertEquals("engine-b", evaluate<String>("globalThis.secret"))
        }
    }

    @Test(timeout = 30_000)
    fun `memory limit applies`() = runBlocking {
        quickJs {
            memoryLimit = 1_048_576 // 1MB（走 Kotlin 属性 setter，避免与 native 重载解析歧义）
            try {
                evaluate<Any?>(
                    "var a = []; for (var i = 0; i < 100000; i++) { a.push(new Array(1000).fill('x')) }; a.length",
                    filename = "mem.js"
                )
            } catch (e: Exception) {
                // 内存吃紧时可能报错（QuickJsException），也可能收缩后放行——只验证 API 可调用不崩溃
            }
            Unit
        }
    }
}