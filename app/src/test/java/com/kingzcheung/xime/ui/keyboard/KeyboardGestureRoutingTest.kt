package com.kingzcheung.xime.ui.keyboard

import com.kingzcheung.xime.keyboard.OverlayRoute
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class KeyboardGestureRoutingTest {

    @Test
    fun `emoji value 映射到 Emoji 面板`() {
        assertEquals(OverlayRoute.Emoji, switchRouteOverlay("emoji"))
    }

    @Test
    fun `symbol value 映射到 Symbol 面板`() {
        assertEquals(OverlayRoute.Symbol, switchRouteOverlay("symbol"))
    }

    @Test
    fun `clipboard value 映射到剪贴板面板首页`() {
        assertEquals(OverlayRoute.Clipboard(0), switchRouteOverlay("clipboard"))
    }

    @Test
    fun `未知 value 返回 null`() {
        assertNull(switchRouteOverlay("plugin_panel"))
        assertNull(switchRouteOverlay(""))
    }
}