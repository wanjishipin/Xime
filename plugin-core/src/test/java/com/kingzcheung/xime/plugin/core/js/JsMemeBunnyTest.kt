package com.kingzcheung.xime.plugin.core.js

import com.kingzcheung.xime.plugin.core.config.NoopPluginConfigStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * 验证 meme-bunny JS 版：图片资源插件（resources/ 枚举 + host.resource.path）。
 * 载入真实插件产物（xipm build 输出），测试与发布同源；
 * 表情图片为测试自备 fixture。
 */
class JsMemeBunnyTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun newPlugin(emojiNames: List<String>): File {
        val dir = tmp.newFolder("plugin")
        pluginSourceFile().copyTo(File(dir, "main.js"))
        File(dir, "resources").mkdirs()
        File(dir, "resources/emojis").mkdirs()
        File(dir, "resources/icon.webp").writeText("fake-icon")
        for (name in emojiNames) {
            File(dir, "resources/emojis/$name").writeText("fake-emoji")
        }
        return dir
    }

    private fun pluginSourceFile(): File {
        var dir: File? = File(System.getProperty("user.dir") ?: ".")
        while (dir != null) {
            val candidate = File(dir, "build/plugin-js/meme-bunny/main.js")
            if (candidate.isFile) return candidate
            dir = dir.parentFile
        }
        throw AssertionError(
            "找不到 build/plugin-js/meme-bunny/main.js，" +
                "请先运行：cd tools/xime-plugin && cargo run -- build ../../plugins/meme-bunny --out ../../build/plugin-js"
        )
    }

    @Test
    fun `meme bunny js plugin serves emoji image resources`() {
        val names = listOf("立刻走.jpg", "你给老子爬.jpg", "你竟然赶我走.jpg", "你走.jpg")
        val dir = newPlugin(names)
        val runtime = JsScriptRuntime(
            "com.kingzcheung.xime.plugin.emoji", dir, "main.js", NoopPluginConfigStore
        )
        try {
            assertTrue("main.js 应能加载", runtime.load())
            // 资源目录列表在 onLoad 预加载（async），与宿主加载路径一致
            runtime.callOnLoad()

            // 分类
            val cats = (runtime.call("emoji.listCategories") as? List<*>)?.map { it.toString() }
            assertEquals(listOf("恶搞兔"), cats)

            // 表情：4 张图片，imageUrl 指向 resources/emojis/ 下真实文件
            val emojis = (runtime.call(
                "emoji.query",
                mapOf("keyword" to "", "topK" to 10)
            ) as? List<*>).orEmpty()
            assertEquals("应返回全部表情", 4, emojis.size)

            val seenImages = mutableSetOf<String>()
            for (item in emojis) {
                val map = item as? Map<*, *>
                val imageUrl = map?.get("imageUrl")?.toString()
                assertTrue("imageUrl 非空", !imageUrl.isNullOrEmpty())
                assertTrue("imageUrl 指向真实文件: $imageUrl", File(imageUrl!!).exists())
                seenImages.add(imageUrl)
            }
            assertEquals("图片应互不相同", 4, seenImages.size)

            // insertText 与 displayText 区分（[表情xx] 插入文本）
            val first = emojis.first() as Map<*, *>
            val display = first["text"]?.toString() ?: ""
            val insert = first["insertText"]?.toString() ?: ""
            assertTrue("insertText 应带[表情]前缀", insert.startsWith("[表情") && insert.endsWith("]"))
            assertTrue("displayText 与 insertText 不同", insert != display)

            // 图标
            val icon = runtime.call("emoji.icon") as? Map<*, *>
            assertEquals("icon.webp", icon?.get("assetName")?.toString())
        } finally {
            runtime.close()
        }
    }
}