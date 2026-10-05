package com.kingzcheung.xime.plugin

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 单选插件"当前使用中"判定测试（[ActivePluginSelection.resolve]）。
 *
 * 关键场景来自真机：偏好为空（用户从未在设置里点选，插件靠内置默认启用），
 * 引擎回退到首个已启用项并运行，页面按空偏好比较就显示"未使用"。
 */
class ActivePluginSelectionTest {

    @Test
    fun `偏好命中已启用项时直接使用偏好`() {
        assertEquals("b", ActivePluginSelection.resolve("b", listOf("a", "b", "c")))
    }

    @Test
    fun `偏好为空时回退首个已启用项`() {
        assertEquals("a", ActivePluginSelection.resolve("", listOf("a", "b")))
        assertEquals("a", ActivePluginSelection.resolve(null, listOf("a", "b")))
        assertEquals("a", ActivePluginSelection.resolve("   ", listOf("a", "b")))
    }

    @Test
    fun `偏好指向已卸载或已停用插件时回退首个已启用项`() {
        // 引擎与页面必须同时这样判：否则会出现"引擎在跑、页面显示未使用"
        assertEquals("a", ActivePluginSelection.resolve("missing", listOf("a", "b")))
    }

    @Test
    fun `无已启用候选时返回空串`() {
        assertEquals("", ActivePluginSelection.resolve("a", emptyList()))
        assertEquals("", ActivePluginSelection.resolve("", emptyList()))
    }
}