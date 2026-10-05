package com.kingzcheung.xime.plugin.core.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class PluginErrorLogTest {

    private fun error(category: ErrorCategory, pluginId: String = "test.plugin") =
        PluginErrorLog.PluginError(
            timestamp = 1700000000000,
            pluginId = pluginId,
            operation = "HTTP 请求失败 (POST https://dav.example.com/file)",
            message = "timeout",
            stackTrace = "stack",
            category = category
        )

    @Before
    fun setUp() {
        // 每个用例独立：清 store（initialize 仅首次生效）+ 清内存
        PluginErrorLog.resetForTest()
    }

    @Test
    fun `userMessage covers every category`() {
        ErrorCategory.entries.forEach { category ->
            val msg = PluginErrorLog.userMessage(error(category))
            assertTrue("$category 应有可读文案", msg.isNotBlank())
            // 面向用户：不应把原始异常文本/URL 直接当原因
            assertTrue("$category 文案不应含原始异常细节", !msg.contains("EXAMPLE_URL"))
        }
    }

    @Test
    fun `userHint covers every category`() {
        ErrorCategory.entries.forEach { category ->
            assertTrue("$category 应有处理建议", PluginErrorLog.userHint(error(category)).isNotBlank())
        }
    }

    @Test
    fun `network denied hint points user to authorization`() {
        val hint = PluginErrorLog.userHint(error(ErrorCategory.NETWORK_DENIED))
        assertTrue(hint.contains("网络访问"))
    }

    @Test
    fun `timeout poisoned hint suggests reload`() {
        val hint = PluginErrorLog.userHint(error(ErrorCategory.TIMEOUT_POISONED))
        assertTrue(hint.contains("重新加载"))
    }

    @Test
    fun `script error message mentions script operation`() {
        val msg = PluginErrorLog.userMessage(error(ErrorCategory.SCRIPT_ERROR))
        assertTrue(msg.contains("脚本"))
    }

    @Test
    fun `initialize restores persisted errors and trims to capacity`() {
        val store = object : PluginErrorLog.PluginErrorStore {
            val stored = ArrayDeque<PluginErrorLog.PluginError>()
            override fun load(): List<PluginErrorLog.PluginError> =
                (1..50).map { error(ErrorCategory.HTTP_ERROR, "restored.plugin").copy(message = "m$it") }
            override fun append(pluginId: String, err: PluginErrorLog.PluginError) { stored.addLast(err) }
            override fun clearAll(pluginId: String) { }
        }

        @Suppress("UNCHECKED_CAST")
        PluginErrorLog.initialize(store)
        val restored = PluginErrorLog.getErrors("restored.plugin")
        assertEquals(20, restored.size)
        assertEquals("m50", restored.last().message)
    }

    @Test
    fun `initialize ignores second store registration`() {
        val first = object : PluginErrorLog.PluginErrorStore {
            override fun load() = listOf(error(ErrorCategory.OTHER, "first.plugin"))
            override fun append(pluginId: String, err: PluginErrorLog.PluginError) {}
            override fun clearAll(pluginId: String) {}
        }
        val second = object : PluginErrorLog.PluginErrorStore {
            override fun load() = listOf(error(ErrorCategory.OTHER, "second.plugin"))
            override fun append(pluginId: String, err: PluginErrorLog.PluginError) {}
            override fun clearAll(pluginId: String) {}
        }

        PluginErrorLog.initialize(first)
        PluginErrorLog.initialize(second)

        assertTrue(PluginErrorLog.hasErrors("first.plugin"))
        assertTrue(!PluginErrorLog.hasErrors("second.plugin"))
    }

    @Test
    fun `errors are trimmed per plugin at capacity`() {
        for (i in 1..30) {
            PluginErrorLog.logError(
                "cap.plugin",
                "op",
                "m$i",
                category = ErrorCategory.HTTP_ERROR
            )
        }
        val errors = PluginErrorLog.getErrors("cap.plugin")
        assertEquals(20, errors.size)
        assertEquals("m11", errors.first().message)
        assertEquals("m30", errors.last().message)
    }
}