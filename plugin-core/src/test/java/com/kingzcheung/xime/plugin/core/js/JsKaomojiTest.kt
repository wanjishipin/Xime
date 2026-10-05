package com.kingzcheung.xime.plugin.core.js

import com.kingzcheung.xime.plugin.core.config.NoopPluginConfigStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * 验证 kaomoji JS 版：内置颜文字数据表情插件。
 *
 * 覆盖：
 * - emoji.listCategories 返回 ["颜文字"]
 * - emoji.query 空 keyword 返回全量数据（防迁移丢数据，174 条）
 * - keyword 子串过滤
 * - topK 截断（含默认值 100）
 *
 * 载入真实插件产物（xipm build 输出），测试与发布同源。
 */
class JsKaomojiTest {

    @get:Rule
    val tmp = TemporaryFolder()

    /** 内置数据总条数（与 manifest 描述一致）。 */
    private val totalCount = 174

    /** 载入真实插件产物（xipm build 输出），测试与发布同源。 */
    private fun writePlugin(): File {
        val dir = tmp.newFolder()
        pluginSourceFile().copyTo(File(dir, "main.js"))
        return dir
    }

    private fun pluginSourceFile(): File {
        var dir: File? = File(System.getProperty("user.dir") ?: ".")
        while (dir != null) {
            val candidate = File(dir, "build/plugin-js/kaomoji/main.js")
            if (candidate.isFile) return candidate
            dir = dir.parentFile
        }
        throw AssertionError(
            "找不到 build/plugin-js/kaomoji/main.js，" +
                "请先运行：cd tools/xime-plugin && cargo run -- build ../../plugins/kaomoji --out ../../build/plugin-js"
        )
    }

    private fun newRuntime(): JsScriptRuntime {
        val runtime = JsScriptRuntime("js-kaomoji", writePlugin(), "main.js", NoopPluginConfigStore)
        assertTrue("main.js 应能加载", runtime.load())
        return runtime
    }

    /** 调 emoji.query；topK 为 null 时不传（验证插件默认值）。 */
    private fun emojis(runtime: JsScriptRuntime, keyword: String, topK: Int? = null): List<Map<*, *>> {
        val query = HashMap<String, Any?>()
        query["keyword"] = keyword
        if (topK != null) query["topK"] = topK
        return (runtime.call("emoji.query", query) as? List<*>)
            .orEmpty()
            .mapNotNull { it as? Map<*, *> }
    }

    @Test
    fun `emoji listCategories 返回颜文字分类`() {
        val runtime = newRuntime()
        try {
            val cats = (runtime.call("emoji.listCategories") as? List<*>)?.map { it.toString() }
            assertEquals(listOf("颜文字"), cats)
        } finally {
            runtime.close()
        }
    }

    @Test
    fun `空 keyword 返回全量内置数据且 id 唯一`() {
        val runtime = newRuntime()
        try {
            val list = emojis(runtime, "", topK = 1000)
            assertEquals("应返回全部内置颜文字", totalCount, list.size)

            val ids = list.map { it["id"]?.toString() }
            assertEquals("id 应互不相同", totalCount, ids.toSet().size)

            val texts = list.map { it["text"]?.toString().orEmpty() }
            assertTrue("颜文字文本不应为空", texts.none { it.isEmpty() })
            assertTrue("首项应为数据首条", texts.first().contains("ﾟ∀ﾟ"))
        } finally {
            runtime.close()
        }
    }

    @Test
    fun `keyword 子串过滤`() {
        val runtime = newRuntime()
        try {
            // 唯一命中：ʕ⸝⸝⸝˙Ⱉ˙ʔ
            val unique = emojis(runtime, "Ⱉ", topK = 1000)
            assertEquals(1, unique.size)
            assertTrue(unique[0]["text"]?.toString().orEmpty().contains("Ⱉ"))

            // 多条命中：全部包含子串，且确实被过滤（少于全量）
            val many = emojis(runtime, "ω", topK = 1000)
            assertTrue("应有命中: ${many.size}", many.isNotEmpty())
            assertTrue("应少于全量: ${many.size}", many.size < totalCount)
            assertTrue(
                "命中项都应包含 keyword",
                many.all { it["text"]?.toString().orEmpty().contains("ω") }
            )

            // 无命中返回空列表
            assertTrue(emojis(runtime, "不存在的颜文字zzz", topK = 1000).isEmpty())
        } finally {
            runtime.close()
        }
    }

    @Test
    fun `topK 截断与默认值`() {
        val runtime = newRuntime()
        try {
            val full = emojis(runtime, "", topK = 1000)
            val truncated = emojis(runtime, "", topK = 5)
            assertEquals(5, truncated.size)
            assertEquals(listOf("kaomoji_0", "kaomoji_1", "kaomoji_2", "kaomoji_3", "kaomoji_4"),
                truncated.map { it["id"]?.toString() })
            assertEquals(full.take(5).map { it["text"] }, truncated.map { it["text"] })

            // 过滤 + topK：只截断命中项
            val filtered = emojis(runtime, "ω", topK = 3)
            assertEquals(3, filtered.size)
            assertTrue(filtered.all { it["text"]?.toString().orEmpty().contains("ω") })

            // 不传 topK 走插件默认值 100
            assertEquals(100, emojis(runtime, "").size)
        } finally {
            runtime.close()
        }
    }
}
