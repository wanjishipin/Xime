package com.kingzcheung.xime.speech.models

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AsrModelRegistryTest {

    @Test
    fun `profiles have unique ids`() {
        val ids = AsrModelRegistry.profiles.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
    }

    @Test
    fun `first profile is the default model`() {
        assertEquals("zipformer-zh-int8", AsrModelRegistry.default.id)
        assertEquals(AsrModelRegistry.profiles.first(), AsrModelRegistry.default)
    }

    @Test
    fun `findById returns registered profile`() {
        val profile = AsrModelRegistry.findById("zipformer-zh-int8")
        assertNotNull(profile)
        assertEquals("encoder.int8.onnx", profile!!.encoderFile)
        assertEquals("decoder.onnx", profile.decoderFile)
        assertEquals("joiner.int8.onnx", profile.joinerFile)
        assertEquals("tokens.txt", profile.tokensFile)
    }

    @Test
    fun `findById returns null for unknown id`() {
        assertEquals(null, AsrModelRegistry.findById("not-registered"))
    }

    @Test
    fun `profileOrDefault keeps known profile unchanged`() {
        val known = AsrModelRegistry.findById("zipformer-zh-int8")!!
        assertEquals(known, AsrModelRegistry.profileOrDefault("zipformer-zh-int8"))
    }

    @Test
    fun `profileOrDefault falls back to default layout for unknown id`() {
        val fallback = AsrModelRegistry.profileOrDefault("index-only-model")
        assertEquals("index-only-model", fallback.id)
        // 与旧版硬编码布局一致，保证索引先行、App 未更新时模型仍可加载
        assertEquals("encoder.int8.onnx", fallback.encoderFile)
        assertEquals("decoder.onnx", fallback.decoderFile)
        assertEquals("joiner.int8.onnx", fallback.joinerFile)
        assertEquals("tokens.txt", fallback.tokensFile)
    }

    @Test
    fun `all profiles declare required inference files`() {
        AsrModelRegistry.profiles.forEach { profile ->
            assertTrue(profile.encoderFile.isNotBlank())
            assertTrue(profile.decoderFile.isNotBlank())
            assertTrue(profile.joinerFile.isNotBlank())
            assertTrue(profile.tokensFile.isNotBlank())
            assertTrue(profile.downloadUrl.isNotBlank())
        }
    }

    @Test
    fun `x-asr punct profile declares its package layout`() {
        val profile = AsrModelRegistry.findById("x-asr-480ms-zh-en-punct-int8")
        assertNotNull(profile)
        assertEquals("encoder.int8.onnx", profile!!.encoderFile)
        assertEquals("decoder.onnx", profile.decoderFile)
        assertEquals("joiner.int8.onnx", profile.joinerFile)
        assertEquals("tokens.txt", profile.tokensFile)
        assertEquals("zh-en", profile.language)
        // 该包为带标点变体：词表含标点 token，描述须注明
        assertTrue(profile.description.contains("标点"))
    }
}
