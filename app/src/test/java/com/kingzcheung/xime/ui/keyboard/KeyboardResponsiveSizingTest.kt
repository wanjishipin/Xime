package com.kingzcheung.xime.ui.keyboard

import org.junit.Assert.assertEquals
import org.junit.Test

class KeyboardResponsiveSizingTest {
    private val tolerance = 0.0001f

    @Test
    fun phoneSizedKeyKeepsOriginalSizing() {
        assertEquals(1f, adaptiveKeyContentScale(keyHeightDp = 56f), tolerance)
        assertEquals(1f, adaptiveHintScale(contentScale = 1f), tolerance)
        assertEquals(14f, adaptiveHintOffsetDp(contentScale = 1f), tolerance)
    }

    @Test
    fun largerKeyScalesLabelsAndHintsWithinLimits() {
        assertEquals(1.25f, adaptiveKeyContentScale(keyHeightDp = 70f), tolerance)
        assertEquals(1.7f, adaptiveHintScale(contentScale = 1.5f), tolerance)
        assertEquals(1.5f, adaptiveBubbleScale(contentScale = 1.5f), tolerance)
        assertEquals(24f, adaptiveHintOffsetDp(contentScale = 1.5f), tolerance)
    }

    @Test
    fun smallerKeysAreNotShrunkAndLargeKeysAreClamped() {
        assertEquals(1f, adaptiveKeyContentScale(keyHeightDp = 20f), tolerance)
        assertEquals(1.5f, adaptiveKeyContentScale(keyHeightDp = 120f), tolerance)
    }
}
