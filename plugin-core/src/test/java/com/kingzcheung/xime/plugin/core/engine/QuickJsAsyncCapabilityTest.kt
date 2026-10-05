package com.kingzcheung.xime.plugin.core.engine

import com.dokar.quickjs.QuickJs
import com.dokar.quickjs.binding.AsyncFunctionBinding
import com.dokar.quickjs.binding.function
import com.dokar.quickjs.evaluate
import com.dokar.quickjs.quickJs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * QuickJS（quickjs-kt）异步能力验证 —— TS 范式化的引擎层前提。
 *
 * 已验证的关键语义（quickjs-kt 1.0.15）：
 * - `AsyncFunctionBinding` 桥 → JS 侧返回 Promise，JS 内部 `await` 正常（job queue 被驱动）
 * - 但 `evaluate` **不 unwrap** 返回的 Promise 对象（拿到的是 Promise 本身而非 settle 值）
 * - 宿主获取异步结果需「全局暂存 + 二次读取」模式：先 evaluate 发起（结果 Promise 处于 pending
 *   → evaluate 等待至 settle），再 evaluate 读取暂存值
 * - 未捕获的 reject → `evaluate` 抛原始异常（宿主可感知熔断，原因保留）
 * - Kotlin 异常转 JS 后自定义属性丢失（code=undefined）→ 宿主用 XimeError wrapper 显式转换
 * - `Promise.all` 并发桥调用真并行（jobDispatcher 线程池）
 *
 * 前置参考：宿主运行时（JsScriptRuntime）的 async 扩展点调用采用同一模式（见 `callExprAwait`）。
 */
class QuickJsAsyncCapabilityTest {

    /**
     * 宿主侧「async 调用」帮助器：evaluate 发起（等待 Promise settle）→ 读取暂存结果。
     * 与宿主运行时将来的 `callExprAwait` 同构，验证该模式在此绑定版本下可靠。
     */
    private suspend fun QuickJs.evaluateAwait(code: String): String {
        evaluate<Any?>(
            "globalThis.__status = 0;" +
                "globalThis.__result = undefined;" +
                "globalThis.__error = undefined;" +
                "Promise.resolve(($code)).then(" +
                "  function (v) { globalThis.__status = 1; globalThis.__result = v; }," +
                "  function (e) { globalThis.__status = 2; globalThis.__error = String((e && e.message) || e); }" +
                ");"
        )
        val status = evaluate<Number>("globalThis.__status").toInt()
        return when (status) {
            1 -> evaluate<String>("String(globalThis.__result)")
            2 -> throw AssertionError("JS 侧 reject：" + evaluate<String>("globalThis.__error"))
            else -> throw AssertionError("Promise 未 settle（status=$status）")
        }
    }

    @Test(timeout = 30_000)
    fun `async bridge supports await and returns settled value`() = runBlocking {
        quickJs(Dispatchers.IO) {
            defineBinding("fetchText", AsyncFunctionBinding { _: Array<Any?> ->
                delay(20)
                "hello"
            })

            val result = evaluateAwait(
                "(async () => { const v = await fetchText(); return v + ' world'; })()"
            )
            assertEquals("await 桥调用应返回 settle 后的值", "hello world", result)
        }
    }

    @Test(timeout = 30_000)
    fun `bridge exception is catchable in JS`() = runBlocking {
        quickJs(Dispatchers.IO) {
            defineBinding("fail", AsyncFunctionBinding { _: Array<Any?> ->
                throw IllegalStateException("boom")
            })

            val result = evaluateAwait(
                "(async () => { try { await fail(); return 'no-throw'; }" +
                    " catch (e) { return 'caught:' + e.message; } })()"
            )
            assertEquals("桥异常应作为 reject 被 JS catch 捕获", "caught:boom", result)
        }
    }

    @Test(timeout = 30_000)
    fun `uncaught rejection surfaces as evaluate exception`() = runBlocking {
        quickJs(Dispatchers.IO) {
            defineBinding("fail", AsyncFunctionBinding { _: Array<Any?> ->
                throw IllegalStateException("kaboom")
            })

            try {
                evaluate<Any?>("(async () => { await fail(); return 'x'; })()")
                fail("未捕获的 reject 应让 evaluate 抛异常")
            } catch (e: IllegalStateException) {
                assertTrue(
                    "未捕获的 reject 应以原始异常冒泡（保留原因）：${e.message}",
                    e.message?.contains("kaboom") == true
                )
            }
        }
    }

    @Test(timeout = 30_000)
    fun `Promise all runs bridge calls concurrently`() = runBlocking {
        quickJs(Dispatchers.IO) {
            defineBinding("task", AsyncFunctionBinding { args: Array<Any?> ->
                val id = args[0]?.toString() ?: "?"
                delay(100)
                id
            })

            val start = System.currentTimeMillis()
            val result = evaluateAwait(
                "(async () => { const list = await Promise.all([task('a'), task('b'), task('c')]);" +
                    " return list.join(','); })()"
            )
            val elapsed = System.currentTimeMillis() - start

            assertEquals("a,b,c", result)
            assertTrue(
                "3 个 100ms 任务应并发完成（实测 ${elapsed}ms，串行需 >=300ms）",
                elapsed < 250
            )
        }
    }

    @Test(timeout = 30_000)
    fun `evaluate awaits Promise returned by JS function`() = runBlocking {
        quickJs(Dispatchers.IO) {
            defineBinding("fetchText", AsyncFunctionBinding { _: Array<Any?> ->
                delay(10)
                "hello"
            })
            evaluate<Any?>(
                "globalThis.pluginAsync = async function () {" +
                    " const v = await fetchText(); return v.toUpperCase(); }"
            )

            val result = evaluateAwait("pluginAsync()")
            assertEquals(
                "宿主调用返回 Promise 的 JS 函数应等到 settle 并拿到最终值",
                "HELLO",
                result
            )
        }
    }

    class XimeTestError(val code: String, override val message: String) : RuntimeException(message)

    @Test(timeout = 30_000)
    fun `kotlin exception loses custom properties in JS`() = runBlocking {
        quickJs(Dispatchers.IO) {
            defineBinding("fail", AsyncFunctionBinding { _: Array<Any?> ->
                throw XimeTestError("E_NET", "连接失败")
            })

            val shape = evaluateAwait(
                "(async () => { try { await fail(); } catch (e) {" +
                    " return ['name=' + e.name, 'code=' + e.code, 'message=' + e.message," +
                    " 'stack=' + (typeof e.stack)].join('|'); } })()"
            )
            // 契约：Kotlin 异常转 JS 后 name 为类全名、自定义属性（code）丢失、message 保留。
            // 宿主据此在 bootstrap 层用 XimeError wrapper 显式转换（判别式桥 + throw XimeError）。
            assertEquals(
                "name=" + XimeTestError::class.java.name + "|code=undefined|message=连接失败|stack=object",
                shape
            )
        }
    }

    @Test(timeout = 30_000)
    fun `sync bridge remains usable in async context`() = runBlocking {
        quickJs(Dispatchers.IO) {
            function("syncValue") { _: Array<Any?> -> "sync" }

            val result = evaluateAwait(
                "(async () => { const v = await syncValue(); return 'got:' + v; })()"
            )
            assertEquals("await 非 Promise 值应立即返回", "got:sync", result)
        }
    }
}
