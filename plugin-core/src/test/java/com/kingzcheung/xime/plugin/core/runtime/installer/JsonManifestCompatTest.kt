package com.kingzcheung.xime.plugin.core.runtime.installer

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class JsonManifestCompatTest {

    @Serializable
    data class M(
        val id: String,
        val name: String? = null,
        val version: String = "0.0.0",
        val network: N? = null,
        val hosts: List<String> = emptyList()
    )

    @Serializable
    data class N(val hosts: List<String> = emptyList())

    @OptIn(ExperimentalSerializationApi::class)
    private val json = Json {
        ignoreUnknownKeys = true
        allowComments = true
        allowTrailingComma = true
    }

    private fun parse(text: String): M = json.decodeFromString<M>(text)

    @Test
    fun `纯 JSON 语法可解析`() {
        val m = parse("""{"id":"a.b.c","version":"1.2.3"}""")
        assertEquals("a.b.c", m.id)
        assertEquals("1.2.3", m.version)
    }

    @Test
    fun `行注释与块注释被支持`() {
        val text = """
            // 顶层注释
            {
              "id": "a.b.c", // 行内注释
              /* 块注释 */
              "version": "1.2.3"
            }
        """.trimIndent()
        val m = parse(text)
        assertEquals("a.b.c", m.id)
        assertEquals("1.2.3", m.version)
    }

    @Test
    fun `尾逗号被容忍`() {
        val m = parse("""{"id":"a.b.c","hosts":["x.com","y.com"],}""")
        assertEquals(listOf("x.com", "y.com"), m.hosts)
    }

    @Test
    fun `字符串内的斜杠不被当作注释`() {
        val m = parse("""{"id":"a.b.c","name":"https://x.com/y"}""")
        assertEquals("https://x.com/y", m.name)
    }

    @Test
    fun `嵌套对象与数组正常`() {
        val text = """
            {
              "id": "a.b.c",
              "network": { "hosts": ["dashscope.aliyuncs.com"] }
            }
        """.trimIndent()
        val m = parse(text)
        assertTrue(m.network != null)
        assertEquals(listOf("dashscope.aliyuncs.com"), m.network?.hosts)
    }
}