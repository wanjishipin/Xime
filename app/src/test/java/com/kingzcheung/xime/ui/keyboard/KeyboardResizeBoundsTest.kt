package com.kingzcheung.xime.ui.keyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 键盘调节边界纯逻辑测试（[KeyboardResizeBounds]）。
 */
class KeyboardResizeBoundsTest {

    @Test
    fun `portrait height bounds are 25 to 60 percent of screen height`() {
        val bounds = KeyboardResizeBounds.heightBoundsDp(screenHeightDp = 800, isLandscape = false)
        assertEquals(200, bounds.first)
        assertEquals(480, bounds.last)
    }

    @Test
    fun `landscape height bounds are 30 to 70 percent of screen height`() {
        val bounds = KeyboardResizeBounds.heightBoundsDp(screenHeightDp = 400, isLandscape = true)
        assertEquals(120, bounds.first)
        assertEquals(280, bounds.last)
    }

    @Test
    fun `degenerate screen height keeps min at most max`() {
        val bounds = KeyboardResizeBounds.heightBoundsDp(screenHeightDp = 10, isLandscape = false)
        assertTrue(bounds.first <= bounds.last)
    }

    @Test
    fun `margin cap is 25 percent of screen width per side`() {
        // 800dp 宽：25% = 200dp，(800-240)/2 = 280dp ⇒ 取较小者 200
        assertEquals(200, KeyboardResizeBounds.maxHorizontalMarginDp(800))
    }

    @Test
    fun `margin cap keeps keyboard at least min width`() {
        // 360dp 宽（常见手机）：25% = 90dp，(360-240)/2 = 60dp ⇒ 60，键盘最窄 240dp
        assertEquals(60, KeyboardResizeBounds.maxHorizontalMarginDp(360))
        assertEquals(240, 360 - 2 * KeyboardResizeBounds.maxHorizontalMarginDp(360))
    }

    @Test
    fun `margin is zero on screens narrower than min keyboard width`() {
        assertEquals(0, KeyboardResizeBounds.maxHorizontalMarginDp(200))
    }

    @Test
    fun `bottom drag delta passes through within all bounds`() {
        val bounds = KeyboardResizeBounds.heightBoundsDp(800, isLandscape = false) // 200..480
        assertEquals(10f, KeyboardResizeBounds.bottomEdgeDragDelta(10f, 300, bounds, 40))
        assertEquals(-10f, KeyboardResizeBounds.bottomEdgeDragDelta(-10f, 300, bounds, 40))
    }

    @Test
    fun `bottom drag stops when padding is zero and finger moves down`() {
        val bounds = KeyboardResizeBounds.heightBoundsDp(800, isLandscape = false)
        // 留白已为 0（底边贴屏幕底部）：往下拉不再动作，顶边保持不动
        assertEquals(0f, KeyboardResizeBounds.bottomEdgeDragDelta(10f, 300, bounds, 0))
    }

    @Test
    fun `bottom drag downward clamps when padding runs out`() {
        val bounds = KeyboardResizeBounds.heightBoundsDp(800, isLandscape = false)
        // 留白只有 30，下拖 50 ⇒ 只走 30（底边贴回屏幕底部即停），高度同步加 30
        assertEquals(30f, KeyboardResizeBounds.bottomEdgeDragDelta(50f, 300, bounds, 30))
    }

    @Test
    fun `bottom drag upward passes through while padding headroom allows`() {
        val bounds = KeyboardResizeBounds.heightBoundsDp(800, isLandscape = false)
        // 上拖抬离：留白随上拖增加（30 → 80），增量不受留白限制
        assertEquals(-50f, KeyboardResizeBounds.bottomEdgeDragDelta(-50f, 300, bounds, 30))
    }

    @Test
    fun `bottom drag clamps at height bounds keeping top edge fixed`() {
        val bounds = KeyboardResizeBounds.heightBoundsDp(800, isLandscape = false) // 200..480
        // 高度已在下界 200：再上推（缩小）不再动作
        assertEquals(0f, KeyboardResizeBounds.bottomEdgeDragDelta(-10f, 200, bounds, 40))
        // 高度已在上界 480：上拖仍可用留白余量换缩小（480→470、留白 40→50，总和不变）
        assertEquals(-10f, KeyboardResizeBounds.bottomEdgeDragDelta(-10f, 480, bounds, 40))
        // 高度已在上界 480：下拖会突破高度上界，不再动作
        assertEquals(0f, KeyboardResizeBounds.bottomEdgeDragDelta(10f, 480, bounds, 40))
        // 下拖最多走留白余量（40）：底边只降 40 就贴底
        assertEquals(40f, KeyboardResizeBounds.bottomEdgeDragDelta(300f, 300, bounds, 40))
    }

    @Test
    fun `bottom drag never exceeds absolute padding cap`() {
        val bounds = KeyboardResizeBounds.heightBoundsDp(800, isLandscape = false)
        // 留白已在 120 上限：继续上推不再动作
        assertEquals(0f, KeyboardResizeBounds.bottomEdgeDragDelta(-10f, 300, bounds, KeyboardResizeBounds.MAX_BOTTOM_PADDING_DP))
    }
}
