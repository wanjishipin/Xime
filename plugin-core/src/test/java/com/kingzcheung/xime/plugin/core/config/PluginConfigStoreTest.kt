package com.kingzcheung.xime.plugin.core.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InMemoryPluginConfigStoreTest {

    class InMemoryPluginConfigStore : PluginConfigStore {
        private val map = mutableMapOf<String, String>()

        override fun get(key: String): String? = map[key]

        override fun set(key: String, value: String) {
            map[key] = value
        }

        override fun remove(key: String) {
            map.remove(key)
        }

        override fun keys(): Set<String> = map.keys.toSet()
    }

    @Test
    fun `store get returns null for missing key`() {
        val store = InMemoryPluginConfigStore()
        assertNull(store.get("apiKey"))
    }

    @Test
    fun `store set then get returns value`() {
        val store = InMemoryPluginConfigStore()
        store.set("apiKey", "sk-123")
        assertEquals("sk-123", store.get("apiKey"))
    }

    @Test
    fun `store set overwrites existing value`() {
        val store = InMemoryPluginConfigStore()
        store.set("apiKey", "old")
        store.set("apiKey", "new")
        assertEquals("new", store.get("apiKey"))
    }

    @Test
    fun `store remove deletes key`() {
        val store = InMemoryPluginConfigStore()
        store.set("apiKey", "sk-123")
        store.remove("apiKey")
        assertNull(store.get("apiKey"))
        assertTrue(store.keys().isEmpty())
    }

    @Test
    fun `store keys returns only stored keys`() {
        val store = InMemoryPluginConfigStore()
        store.set("a", "1")
        store.set("b", "2")
        assertEquals(setOf("a", "b"), store.keys())
    }
}

class NoopPluginConfigStoreTest {

    @Test
    fun `noop store is read-only and empty`() {
        val store = NoopPluginConfigStore
        assertNull(store.get("any"))
        store.set("any", "value")
        assertNull(store.get("any"))
        assertTrue(store.keys().isEmpty())
    }
}

class UiNodeTest {

    @Test
    fun `UiNode has correct defaults`() {
        val field = UiNode(key = "apiKey", label = "API Key", type = UiNodeType.SECRET)

        assertEquals("apiKey", field.key)
        assertEquals("API Key", field.label)
        assertEquals(UiNodeType.SECRET, field.type)
        assertNull(field.placeholder)
        assertNull(field.defaultValue)
        assertTrue(field.options.isEmpty())
        assertNull(field.helpText)
        assertNull(field.section)
        assertNull(field.unit)
        assertNull(field.style)
    }

    @Test
    fun `UiNode can be required`() {
        val field = UiNode(
            key = "appKey",
            label = "App Key",
            type = UiNodeType.SECRET,
            required = true
        )

        assertTrue(field.required)
    }

    @Test
    fun `UiNode can specify options for SELECT`() {
        val field = UiNode(
            key = "region",
            label = "区域",
            type = UiNodeType.SELECT,
            options = listOf("cn", "intl")
        )

        assertEquals(listOf("cn", "intl"), field.options)
    }

    @Test
    fun `UiNode supports panel display types`() {
        val section = UiNode(type = UiNodeType.SECTION, label = "统计")
        val metric = UiNode(type = UiNodeType.METRIC, label = "字数", value = "1024", unit = "字")
        val divider = UiNode(type = UiNodeType.DIVIDER)
        val action = UiNode(type = UiNodeType.BUTTON, key = "reset", label = "清零")

        assertEquals("统计", section.label)
        assertEquals("1024", metric.value)
        assertEquals("字", metric.unit)
        assertNull(divider.label)
        assertEquals("reset", action.key)
        // 面板展示节点无需 configStore 绑定
        assertNull(section.key)
    }

    @Test
    fun `UiNode can have defaultValue`() {
        val field = UiNode(
            key = "vad",
            label = "静音判停",
            type = UiNodeType.SWITCH,
            defaultValue = "true"
        )

        assertEquals("true", field.defaultValue)
    }

    @Test
    fun `UiNode can be MULTI_SELECT with options`() {
        val field = UiNode(
            key = "languageHints",
            label = "语言提示",
            type = UiNodeType.MULTI_SELECT,
            options = listOf("zh", "en", "ja")
        )

        assertEquals(UiNodeType.MULTI_SELECT, field.type)
        assertEquals(listOf("zh", "en", "ja"), field.options)
    }

    @Test
    fun `UiNode can have section`() {
        val field = UiNode(
            key = "vadSensitivity",
            label = "静音灵敏度",
            type = UiNodeType.NUMBER,
            section = "高级"
        )

        assertEquals("高级", field.section)
        assertNull(UiNode(type = UiNodeType.TEXT, key = "a").section)
    }

    @Test
    fun `PluginConfigurable default schema is empty`() {
        val configurable = object : IPluginConfigurable {}
        assertTrue(configurable.getSettingsSchema().isEmpty())
        assertNull(configurable.getOptions("model"))
    }

    @Test
    fun `PluginConfigurable custom schema is returned`() {
        val configurable = object : IPluginConfigurable {
            override fun getSettingsSchema(): List<UiNode> =
                listOf(UiNode(key = "apiKey", label = "API Key", type = UiNodeType.SECRET))
        }

        assertFalse(configurable.getSettingsSchema().isEmpty())
        assertEquals("apiKey", configurable.getSettingsSchema().first().key)
    }

    @Test
    fun `PluginConfigurable getOptions returns dynamic list`() {
        val configurable = object : IPluginConfigurable {
            override fun getOptions(key: String): List<String>? =
                if (key == "model") listOf("fun-asr-realtime", "fun-asr") else null
        }

        assertEquals(listOf("fun-asr-realtime", "fun-asr"), configurable.getOptions("model"))
        assertNull(configurable.getOptions("other"))
    }
}
