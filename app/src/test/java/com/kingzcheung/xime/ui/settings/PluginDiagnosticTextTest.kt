package com.kingzcheung.xime.ui.settings

import com.kingzcheung.xime.plugin.core.security.ErrorCategory
import com.kingzcheung.xime.plugin.core.security.PluginErrorLog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PluginDiagnosticTextTest {

    private fun error(category: ErrorCategory, index: Int) = PluginErrorLog.PluginError(
        timestamp = 1700000000000,
        pluginId = "webdav.test",
        operation = "HTTP 请求失败",
        message = "connect timeout",
        stackTrace = "java.lang.Exception\n\tat HostApi.request",
        category = category
    )

    @Test
    fun `diagnostic text contains host and plugin identity`() {
        val text = buildPluginDiagnosticText(
            hostVersionName = "2.9.0",
            pluginName = "WebDAV 同步",
            pluginId = "webdav.test",
            pluginVersion = "1.2.3",
            enabled = true,
            errors = emptyList()
        )

        assertTrue(text.contains("Xime v2.9.0"))
        assertTrue(text.contains("WebDAV 同步 (webdav.test) v1.2.3 已启用"))
        assertTrue(text.contains("0 条"))
    }

    @Test
    fun `diagnostic text lists user readable reason and technical detail`() {
        val text = buildPluginDiagnosticText(
            hostVersionName = "2.9.0",
            pluginName = "WebDAV 同步",
            pluginId = "webdav.test",
            pluginVersion = "1.2.3",
            enabled = true,
            errors = listOf(error(ErrorCategory.NETWORK_DENIED, 0))
        )

        assertTrue("应含分类徽章", text.contains("访问被拒绝"))
        assertTrue("应含用户可读原因", text.contains("未授权的服务器"))
        assertTrue("应含技术详情", text.contains("connect timeout"))
        assertTrue("应含堆栈", text.contains("HostApi.request"))
    }

    @Test
    fun `disabled plugin is reflected`() {
        val text = buildPluginDiagnosticText(
            hostVersionName = "2.9.0",
            pluginName = "P",
            pluginId = "p",
            pluginVersion = "0.0.1",
            enabled = false,
            errors = emptyList()
        )
        assertTrue(text.contains("已禁用"))
    }

    @Test
    fun `empty errors produces no numbered record`() {
        val text = buildPluginDiagnosticText(
            hostVersionName = "2.9.0",
            pluginName = "P",
            pluginId = "p",
            pluginVersion = "0.0.1",
            enabled = true,
            errors = emptyList()
        )
        assertTrue(text.contains("（无）"))
        assertEquals(0, text.split("\n").count { it.startsWith("1. ") })
    }

    @Test
    fun `error summary reflects count`() {
        assertEquals("有 1 条错误记录", pluginErrorSummary(1))
        assertEquals("有 7 条错误记录", pluginErrorSummary(7))
        assertEquals("暂无错误记录", pluginErrorSummary(0))
    }
}