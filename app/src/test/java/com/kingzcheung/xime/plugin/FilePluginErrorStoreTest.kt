package com.kingzcheung.xime.plugin

import com.kingzcheung.xime.plugin.core.security.ErrorCategory
import com.kingzcheung.xime.plugin.core.security.PluginErrorLog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

class FilePluginErrorStoreTest {

    private lateinit var dir: File
    private lateinit var store: FilePluginErrorStore

    @Before
    fun setUp() {
        dir = File("build/plugin-error-store-test")
        dir.deleteRecursively()
        dir.mkdirs()
        store = FilePluginErrorStore(File(dir, "errors.jsonl"))
    }

    private fun error(
        pluginId: String,
        category: ErrorCategory = ErrorCategory.OTHER
    ): PluginErrorLog.PluginError = PluginErrorLog.PluginError(
        timestamp = 1700000000000,
        pluginId = pluginId,
        operation = "测试操作",
        message = "消息\n含换行",
        stackTrace = "stack line1\nstack line2",
        category = category
    )

    @Test
    fun `append and load roundtrip preserves all fields`() {
        val e = error("test.plugin", ErrorCategory.SCRIPT_ERROR)
        store.append("test.plugin", e)

        val loaded = store.load()
        assertEquals(1, loaded.size)
        val restored = loaded[0]
        assertEquals(e.timestamp, restored.timestamp)
        assertEquals(e.pluginId, restored.pluginId)
        assertEquals(e.operation, restored.operation)
        assertEquals(e.message, restored.message)
        assertEquals(e.stackTrace, restored.stackTrace)
        assertEquals(e.category, restored.category)
    }

    @Test
    fun `load returns empty when file missing`() {
        assertTrue(store.load().isEmpty())
    }

    @Test
    fun `unicode and quotes survive json roundtrip`() {
        val e = error("a").copy(message = "中文错误 🍵 带\"引号\"和\\反斜杠")
        store.append("a", e)

        assertEquals(e.message, store.load()[0].message)
        assertEquals(e.stackTrace, store.load()[0].stackTrace)
    }

    @Test
    fun `append drops oldest when over line limit`() {
        // FilePluginErrorStore.Companion.MAX_TOTAL_LINES = 300
        for (i in 0 until 305) {
            val pluginId = if (i % 2 == 0) "even" else "odd"
            store.append(pluginId, PluginErrorLog.PluginError(pluginId = pluginId, operation = "op", message = "m$i"))
        }

        val loaded = store.load()
        assertTrue("超限应截断到 300 条，实际 ${loaded.size}", loaded.size <= 300)
        assertTrue("应保留最新（m304）", loaded.any { it.message == "m304" })
        assertTrue("最旧（m0）应被丢弃", loaded.none { it.message == "m0" })
    }

    @Test
    fun `clearAll removes only the target plugin`() {
        store.append("a", error("a"))
        store.append("b", error("b"))

        store.clearAll("a")

        val loaded = store.load()
        assertEquals(1, loaded.size)
        assertEquals("b", loaded[0].pluginId)
    }

    @Test
    fun `malformed lines are skipped without breaking others`() {
        store.append("a", error("a"))
        File(dir, "errors.jsonl").appendText("this is not valid json\n")
        store.append("b", error("b"))

        val loaded = store.load()
        assertEquals(2, loaded.size)
        assertEquals(listOf("a", "b"), loaded.map { it.pluginId })
    }

    @Test
    fun `toLine matches fromLine exactly`() {
        val e = error("roundtrip", ErrorCategory.NETWORK_DENIED)
        val line = FilePluginErrorStore.toLine(e)
        val restored = FilePluginErrorStore.fromLine(line!!)
        assertEquals(e, restored)
    }
}