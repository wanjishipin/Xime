package com.kingzcheung.xime.plugin.core.runtime.installer

import com.kingzcheung.xime.plugin.core.model.PluginToolbarButton
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ManifestParseTest {

    @Test
    fun `正常 manifest 解析成功且字段正确`() {
        val content = """
            {
              "id": "kaomoji",
              "name": "颜文字",
              "version": "2.1.0",
              "description": "内置颜文字",
              "type": "emoji",
              "entry": "main.js",
              "minHostVersion": "2.6.0",
              "maxHostVersion": "3.0.0",
              "network": {
                "hosts": ["dashscope.aliyuncs.com"],
                "allowCustomHosts": true
              }
            }
        """.trimIndent()

        val result = InstallerManager.parseManifestContent(content)
        val config = (result as PluginParseResult.Success).config
        assertEquals("kaomoji", config.id)
        assertEquals("颜文字", config.name)
        assertEquals("2.1.0", config.version)
        assertEquals("emoji", config.type)
        assertEquals("main.js", config.entryScript)
        assertEquals("2.6.0", config.minHostVersion)
        assertEquals("3.0.0", config.maxHostVersion)
        assertEquals(listOf("dashscope.aliyuncs.com"), config.declaredHosts)
        assertTrue("allowCustomHosts 应解析为 true", config.allowCustomHosts)
    }

    @Test
    fun `可选字段缺省时使用默认值`() {
        val content = """{ "id": "mini" }"""

        val config = (InstallerManager.parseManifestContent(content) as PluginParseResult.Success).config
        assertEquals("mini", config.id)
        assertEquals("mini", config.name)
        assertEquals("0.0.0", config.version)
        assertEquals("unknown", config.type)
        assertEquals("main.js", config.entryScript)
        assertEquals("", config.description)
        assertEquals(emptyList<String>(), config.declaredHosts)
    }

    @Test
    fun `缺少必填 id 字段时返回可读错误`() {
        val content = """{ "name": "没有 id" }"""

        val result = InstallerManager.parseManifestContent(content)
        val reason = (result as PluginParseResult.Failure).reason
        assertTrue("错误应说明解析失败, 实际: $reason", reason.startsWith("manifest.json 解析失败"))
        assertTrue("错误应提到 id, 实际: $reason", reason.contains("id"))
    }

    @Test
    fun `字段类型错误时返回可读错误`() {
        val content = """
            {
              "id": "demo",
              "type": ["a", "b"]
            }
        """.trimIndent()

        val result = InstallerManager.parseManifestContent(content)
        val reason = (result as PluginParseResult.Failure).reason
        assertTrue("错误应说明解析失败, 实际: $reason", reason.startsWith("manifest.json 解析失败"))
    }

    @Test
    fun `JSON 语法错误时返回可读错误`() {
        val content = """{ "id": """

        val result = InstallerManager.parseManifestContent(content)
        val reason = (result as PluginParseResult.Failure).reason
        assertTrue("错误应说明解析失败, 实际: $reason", reason.startsWith("manifest.json 解析失败"))
    }

    @Test
    fun `宽松语法支持行注释与块注释`() {
        val content = """
            // 顶层注释
            {
              "id": "demo", // 行内注释
              /* 块注释 */
              "version": "1.0.0"
            }
        """.trimIndent()

        val config = (InstallerManager.parseManifestContent(content) as PluginParseResult.Success).config
        assertEquals("demo", config.id)
        assertEquals("1.0.0", config.version)
    }

    @Test
    fun `clipboard_sync 声明的 attachments 被解析为支持图片附件`() {
        val content = """
            {
              "id": "webdav_sync",
              "type": "clipboard_sync",
              "capabilities": {
                "clipboard_sync": { "protocols": ["webdav"], "attachments": true }
              }
            }
        """.trimIndent()

        val config = (InstallerManager.parseManifestContent(content) as PluginParseResult.Success).config
        val clipboardSync = config.capabilities?.clipboardSync
        assertEquals(listOf("webdav"), clipboardSync?.protocols)
        assertTrue("应解析出 attachments", clipboardSync?.attachments == true)
    }

    @Test
    fun `未声明 attachments 时缺省 false（旧插件自动降级为文本同步）`() {
        val content = """
            {
              "id": "legacy_sync",
              "type": "clipboard_sync",
              "capabilities": { "clipboard_sync": { "protocols": ["webdav"] } }
            }
        """.trimIndent()

        val config = (InstallerManager.parseManifestContent(content) as PluginParseResult.Success).config
        assertEquals(false, config.capabilities?.clipboardSync?.attachments)
    }

    @Test
    fun `宽松语法支持尾逗号`() {
        val content = """
            {
              "id": "demo",
              "network": { "hosts": ["api.openai.com"], },
            }
        """.trimIndent()

        val config = (InstallerManager.parseManifestContent(content) as PluginParseResult.Success).config
        assertEquals("demo", config.id)
        assertEquals(listOf("api.openai.com"), config.declaredHosts)
    }

    @Test
    fun `字符串中的斜杠不被当作注释`() {
        val content = """{ "id": "demo", "description": "https://example.com/a" }"""

        val config = (InstallerManager.parseManifestContent(content) as PluginParseResult.Success).config
        assertEquals("https://example.com/a", config.description)
    }

    @Test
    fun `多余字段在非严格模式下被忽略`() {
        val content = """
            {
              "id": "demo",
              "extraField": 123,
              "custom": ["a"]
            }
        """.trimIndent()

        val config = (InstallerManager.parseManifestContent(content) as PluginParseResult.Success).config
        assertEquals("demo", config.id)
    }

    @Test
    fun `capabilities 的 snake_case 键应解析为 clipboardSync protocols`() {
        // 回归：clipboardSync 缺 SerialName("clipboard_sync") 时解析器静默忽略该键，
        // 宿主因 protocols 为空拒绝启动剪贴板同步插件
        val content = """
            {
              "id": "sync",
              "type": "clipboard_sync",
              "capabilities": {
                "clipboard_sync": { "protocols": ["webdav"] }
              }
            }
        """.trimIndent()

        val config = (InstallerManager.parseManifestContent(content) as PluginParseResult.Success).config
        val protocols = config.capabilities?.clipboardSync?.protocols
        assertEquals(listOf("webdav"), protocols)
    }

    @Test
    fun `插件 id 支持反域名命名空间`() {
        assertTrue("反域名 id 应合法", InstallerManager.isValidPluginId("com.kingzcheung.xime.plugin.funasr_asr"))
        assertTrue("下划线/连字符 id 应合法", InstallerManager.isValidPluginId("webdav-clipboard_sync"))
    }

    @Test
    fun `非法插件 id 被拒绝`() {
        assertTrue("空 id 非法", !InstallerManager.isValidPluginId(""))
        assertTrue("含点号连续出现非法", !InstallerManager.isValidPluginId("a..b"))
        assertTrue("点号开头非法", !InstallerManager.isValidPluginId(".abc"))
        assertTrue("点号结尾非法", !InstallerManager.isValidPluginId("abc."))
        assertTrue("含斜杠非法", !InstallerManager.isValidPluginId("a/b"))
        assertTrue("超过 64 非法", !InstallerManager.isValidPluginId("a".repeat(65)))
        assertTrue("含中文非法", !InstallerManager.isValidPluginId("插件a"))
    }

    @Test
    fun `toolbarButtons manifest 解析为按钮列表`() {
        val content = """
            {
              "id": "ai_reply",
              "name": "AI 智能回复",
              "type": "tool",
              "toolbarButtons": [
                { "id": "ai_reply", "label": "AI 回复", "icon": "ai_reply.png" },
                { "id": "ai_write", "label": "AI 帮写", "action": "custom_action" }
              ]
            }
        """.trimIndent()

        val config = (InstallerManager.parseManifestContent(content) as PluginParseResult.Success).config
        assertEquals("tool", config.type)
        assertEquals(2, config.toolbarButtons.size)
        val first = config.toolbarButtons[0]
        assertEquals("ai_reply", first.id)
        assertEquals("AI 回复", first.label)
        assertEquals("ai_reply.png", first.icon)
        assertEquals("open_panel", first.action)
        val second = config.toolbarButtons[1]
        assertEquals("ai_write", second.id)
        assertEquals("AI 帮写", second.label)
        assertEquals(null, second.icon)
        assertEquals("custom_action", second.action)
    }

    @Test
    fun `toolbarButtons 缺省为空列表`() {
        val config = (InstallerManager.parseManifestContent("""{ "id": "mini" }""") as PluginParseResult.Success).config
        assertEquals("toolbarButtons 缺省应为空", emptyList<PluginToolbarButton>(), config.toolbarButtons)
    }

    @Test
    fun `toolbarButtons 空白 action 回落 open_panel`() {
        val content = """
            {
              "id": "ai_reply",
              "toolbarButtons": [
                { "id": "ai_reply", "label": "AI 回复", "action": "   " }
              ]
            }
        """.trimIndent()

        val config = (InstallerManager.parseManifestContent(content) as PluginParseResult.Success).config
        assertEquals("open_panel", config.toolbarButtons.single().action)
    }

    @Test
    fun `toolbarButtons 非法 id 被过滤`() {
        val content = """
            {
              "id": "ai_reply",
              "toolbarButtons": [
                { "id": "a,b", "label": "含逗号非法" },
                { "id": "合法_按钮", "label": "合法" }
              ]
            }
        """.trimIndent()

        val buttons = (InstallerManager.parseManifestContent(content) as PluginParseResult.Success).config.toolbarButtons
        assertEquals(1, buttons.size)
        assertEquals("合法_按钮", buttons[0].id)
        assertEquals("合法", buttons[0].label)
    }

    @Test
    fun `toolbarButtons id 合法性校验`() {
        assertTrue("反域名按钮 id 合法", InstallerManager.isValidToolbarButtonId("com.kingzcheung.xime.plugin.ai_reply"))
        assertTrue("含冒号命名空间合法", InstallerManager.isValidToolbarButtonId("com.kingzcheung.xime.plugin:ai_reply"))
        assertTrue("空 id 非法", !InstallerManager.isValidToolbarButtonId(""))
        assertTrue("含逗号非法", !InstallerManager.isValidToolbarButtonId("a,b"))
        assertTrue("含空格非法", !InstallerManager.isValidToolbarButtonId("a b"))
        assertTrue("含尖括号非法", !InstallerManager.isValidToolbarButtonId("a<b"))
        assertTrue("超过 64 非法", !InstallerManager.isValidToolbarButtonId("a".repeat(65)))
    }

    @Test
    fun `manifest 顶层 icon 解析透传`() {
        val content = """
            {
              "id": "ai_translate",
              "name": "AI 翻译",
              "type": "tool",
              "icon": "译"
            }
        """.trimIndent()

        val config = (InstallerManager.parseManifestContent(content) as PluginParseResult.Success).config
        assertEquals("译", config.icon)

        val noIcon = (InstallerManager.parseManifestContent("""{ "id": "mini" }""") as PluginParseResult.Success).config
        assertEquals("缺省 icon 为 null", null, noIcon.icon)
    }

    @Test
    fun `非法网络域名声明被过滤`() {
        val content = """
            {
              "id": "demo",
              "network": {
                "hosts": [
                  "api.openai.com",
                  "*.wildcard.example.com",
                  "http://evil.example.com/path",
                  "169.254.169.254",
                  ""
                ]
              }
            }
        """.trimIndent()

        val config = (InstallerManager.parseManifestContent(content) as PluginParseResult.Success).config
        assertEquals(
            "仅合法域名/IPv4 保留",
            listOf("api.openai.com", "169.254.169.254"),
            config.declaredHosts
        )
    }

    @Test
    fun `网络域名合法性校验`() {
        assertTrue("普通域名合法", InstallerManager.isValidDeclaredHost("api.openai.com"))
        assertTrue("带连字符合法", InstallerManager.isValidDeclaredHost("my-server.example.com"))
        assertTrue("IPv4 合法", InstallerManager.isValidDeclaredHost("192.168.1.50"))
        assertTrue("通配符非法", !InstallerManager.isValidDeclaredHost("*.example.com"))
        assertTrue("带协议非法", !InstallerManager.isValidDeclaredHost("https://example.com"))
        assertTrue("带端口非法", !InstallerManager.isValidDeclaredHost("example.com:8080"))
        assertTrue("空白非法", !InstallerManager.isValidDeclaredHost(""))
        assertTrue("含下划线非法", !InstallerManager.isValidDeclaredHost("under_score.example.com"))
    }

    @Test
    fun `平台声明解析并归一化空白项`() {
        val content = """
            {
              "id": "demo",
              "platforms": ["android", " ios ", ""]
            }
        """.trimIndent()

        val config = (InstallerManager.parseManifestContent(content) as PluginParseResult.Success).config
        assertEquals(listOf("android", "ios"), config.platforms)
    }

    @Test
    fun `平台声明缺省或空数组视为 android`() {
        val absent = (InstallerManager.parseManifestContent("""{ "id": "mini" }""") as PluginParseResult.Success).config
        assertEquals(listOf("android"), absent.platforms)

        val empty = (InstallerManager.parseManifestContent("""{ "id": "mini", "platforms": [] }""") as PluginParseResult.Success).config
        assertEquals(listOf("android"), empty.platforms)
    }

    @Test
    fun `资源路径合法性校验`() {
        assertTrue("普通文件名合法", InstallerManager.isValidResourcePath("icon.png"))
        assertTrue("子目录合法", InstallerManager.isValidResourcePath("icons/ai.png"))
        assertTrue("点开头文件名合法", InstallerManager.isValidResourcePath(".hidden.png"))
        assertTrue("空路径非法", !InstallerManager.isValidResourcePath(""))
        assertTrue("路径穿越非法", !InstallerManager.isValidResourcePath("../xime.yaml"))
        assertTrue("嵌套穿越非法", !InstallerManager.isValidResourcePath("icons/../../xime.yaml"))
        assertTrue("绝对路径非法", !InstallerManager.isValidResourcePath("/etc/passwd"))
        assertTrue("反斜杠非法", !InstallerManager.isValidResourcePath("..\\xime.yaml"))
        assertTrue("空段非法", !InstallerManager.isValidResourcePath("icons//x.png"))
    }
}