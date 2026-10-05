package com.kingzcheung.xime.plugin.core.js

import com.kingzcheung.xime.plugin.core.config.NoopPluginConfigStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * 下行事件（capabilities.events + onPluginEvent）：
 * - 声明订阅的插件收到 input_changed（payload 快照）
 * - conflated 通道：连发只保最新
 * - 未订阅类型 / 未启用通道：静默丢弃不炸
 */
class JsScriptRuntimeEventTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun writePlugin(js: String): File {
        val dir = tmp.newFolder("plugin")
        File(dir, "main.js").writeText(js)
        return dir
    }

    private val recorderJs = """
        globalThis.plugin = (function () {
          var events = [];
          function onInputChanged(payload) {
            events.push({ type: 'input_changed', inputText: payload ? (payload.inputText || null) : null });
          }
          function eventCount() { return events.length; }
          function lastEvent() {
            var e = events[events.length - 1];
            if (!e) return "";
            return (e.type || "") + "|" + (e.inputText || "");
          }
          return {
            events: { onInputChanged: onInputChanged },
            eventCount: eventCount,
            lastEvent: lastEvent
          };
        })();
    """.trimIndent()

    private fun awaitUntil(timeoutMs: Long = 5000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(50)
        }
        assertTrue("等待条件超时", condition())
    }

    @Test
    fun `订阅插件收到 input_changed 事件与 payload`() {
        val runtime = JsScriptRuntime("evt-test", writePlugin(recorderJs), "main.js", NoopPluginConfigStore)
        runtime.initEvents(setOf(PluginEvent.TYPE_INPUT_CHANGED))
        assertTrue(runtime.load())

        val delivered = runtime.dispatchEvent(
            PluginEvent(PluginEvent.TYPE_INPUT_CHANGED, mapOf(PluginEvent.FIELD_INPUT_TEXT to "niha"))
        )
        assertTrue("应投递成功", delivered)
        awaitUntil { runtime.call("eventCount")?.toString() == "1" }
        assertEquals("input_changed|niha", runtime.call("lastEvent")?.toString())
        runtime.close()
    }

    @Test
    fun `conflated 连发多事件只保最新`() {
        val runtime = JsScriptRuntime("evt-conflated", writePlugin(recorderJs), "main.js", NoopPluginConfigStore)
        runtime.initEvents(setOf(PluginEvent.TYPE_INPUT_CHANGED))
        assertTrue(runtime.load())

        for (i in 1..20) {
            runtime.dispatchEvent(
                PluginEvent(PluginEvent.TYPE_INPUT_CHANGED, mapOf(PluginEvent.FIELD_INPUT_TEXT to "k$i"))
            )
        }
        awaitUntil { runtime.call("lastEvent")?.toString() == "input_changed|k20" }
        val count = runtime.call("eventCount")?.toString()?.toInt() ?: 0
        assertTrue("conflated 应合并中间事件: count=$count", count in 1..19)
        runtime.close()
    }

    @Test
    fun `未订阅的事件类型不投递`() {
        val runtime = JsScriptRuntime("evt-filter", writePlugin(recorderJs), "main.js", NoopPluginConfigStore)
        runtime.initEvents(setOf(PluginEvent.TYPE_INPUT_CHANGED))
        assertTrue(runtime.load())

        val delivered = runtime.dispatchEvent(PluginEvent("other_event", mapOf("x" to "1")))
        assertFalse("未订阅类型应被过滤", delivered)
        Thread.sleep(300)
        assertEquals(0, runtime.call("eventCount")?.toString()?.toInt())
        runtime.close()
    }

    @Test
    fun `未启用通道旧插件 dispatchEvent 返回 false 不炸`() {
        val runtime = JsScriptRuntime("evt-legacy", writePlugin(recorderJs), "main.js", NoopPluginConfigStore)
        assertTrue(runtime.load())

        val delivered = runtime.dispatchEvent(
            PluginEvent(PluginEvent.TYPE_INPUT_CHANGED, mapOf(PluginEvent.FIELD_INPUT_TEXT to "x"))
        )
        assertFalse("未声明 events 的旧插件不应建立通道", delivered)
        Thread.sleep(200)
        assertEquals(0, runtime.call("eventCount")?.toString()?.toInt())
        runtime.close()
    }

    @Test
    fun `插件未导出 onPluginEvent 时事件静默丢弃`() {
        val noHandlerJs = "globalThis.plugin = { ping: function() { return 'pong'; } }"
        val runtime = JsScriptRuntime("evt-nohandler", writePlugin(noHandlerJs), "main.js", NoopPluginConfigStore)
        runtime.initEvents(setOf(PluginEvent.TYPE_INPUT_CHANGED))
        assertTrue(runtime.load())

        val delivered = runtime.dispatchEvent(
            PluginEvent(PluginEvent.TYPE_INPUT_CHANGED, mapOf(PluginEvent.FIELD_INPUT_TEXT to "x"))
        )
        assertTrue("进入通道（投递与导出无关）", delivered)
        Thread.sleep(300)
        runtime.close()
    }
}