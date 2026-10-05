package com.kingzcheung.xime.plugin.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 类型 × 能力合理性校验（纯 Kotlin，无引擎依赖）：
 * - 内建能力块与 type 错配 → errors（宿主不消费错配块）；
 * - 横切权限的非常见组合 → warnings（软提示，不阻断）。
 * 规则与 tools/xime-plugin/src/manifest.rs 的 validate_capabilities 保持一致。
 */
class PluginCapabilitiesValidateTest {

    @Test
    fun `内建块与类型匹配时无错误`() {
        val caps = PluginCapabilities(tool = PluginCapabilities.ToolCapabilities(display = null))
        val (errors, warnings) = PluginCapabilities.validateForType("tool", caps)
        assertTrue(errors.isEmpty())
        assertTrue(warnings.isEmpty())
    }

    @Test
    fun `无任何内建块的合法类型零告警`() {
        // 只有横切权限的 tool 型（如 typing-stats）
        val caps = PluginCapabilities(events = listOf("input_changed"))
        val (errors, warnings) = PluginCapabilities.validateForType("tool", caps)
        assertTrue(errors.isEmpty())
        assertTrue(warnings.isEmpty())
    }

    @Test
    fun `内建块错配报 error`() {
        val caps = PluginCapabilities(
            emoji = PluginCapabilities.EmojiCapabilities(),
            tool = PluginCapabilities.ToolCapabilities(),
        )
        val (errors, _) = PluginCapabilities.validateForType("emoji", caps)
        assertEquals(1, errors.size)
        assertTrue(errors[0].contains("capabilities.tool"))
        assertTrue(errors[0].contains("emoji"))
    }

    @Test
    fun `clipboard_read 由非典型类型声明时告警`() {
        val caps = PluginCapabilities(clipboardRead = true)
        val (errors, warnings) = PluginCapabilities.validateForType("emoji", caps)
        assertTrue("emoji+clipboard_read 是合法但可疑组合，仅告警", errors.isEmpty())
        assertEquals(1, warnings.size)
        assertTrue(warnings[0].contains("clipboard_read"))
    }

    @Test
    fun `candidate_transform 与 quick_send_read 仅 tool 型免告警`() {
        val toolCaps = PluginCapabilities(candidateTransform = true, quickSendRead = true)
        val (_, toolWarnings) = PluginCapabilities.validateForType("tool", toolCaps)
        assertTrue(toolWarnings.isEmpty())

        val speechCaps = PluginCapabilities(candidateTransform = true)
        val (errors, warnings) = PluginCapabilities.validateForType("speech", speechCaps)
        assertTrue(errors.isEmpty())
        assertEquals(1, warnings.size)
        assertTrue(warnings[0].contains("candidate_transform"))
    }

    @Test
    fun `典型组合不告警`() {
        val caps = PluginCapabilities(clipboardRead = true)
        val (_, warnings) = PluginCapabilities.validateForType("clipboard_sync", caps)
        assertTrue(warnings.isEmpty())
    }

    @Test
    fun `未知类型报 error`() {
        val (errors, warnings) = PluginCapabilities.validateForType("widget", PluginCapabilities.EMPTY)
        assertEquals(1, errors.size)
        assertTrue(errors[0].contains("widget"))
        assertTrue(warnings.isEmpty())
    }
}
