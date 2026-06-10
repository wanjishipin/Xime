package com.kingzcheung.xime.ui

import com.kingzcheung.xime.util.CharInfo
import com.kingzcheung.xime.util.SubcharHelper
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SubcharHelperTest {

    @After
    fun tearDown() {
        setAvailableSvgs(emptySet())
    }

    @Test
    fun `hasSvg and getSvgPath return expected values`() {
        setAvailableSvgs(setOf("我", "你"))

        assertTrue(SubcharHelper.hasSvg("我"))
        assertFalse(SubcharHelper.hasSvg("他"))
        assertEquals("subchar/我.svg", SubcharHelper.getSvgPath("我"))
        assertNull(SubcharHelper.getSvgPath("他"))
    }

    @Test
    fun `parseSwipeDownText marks chars with svg`() {
        setAvailableSvgs(setOf("你", "好"))

        val result = SubcharHelper.parseSwipeDownText("你好啊")

        assertEquals(3, result.size)
        assertEquals(CharInfo("你", true), result[0])
        assertEquals(CharInfo("好", true), result[1])
        assertEquals(CharInfo("啊", false), result[2])
    }

    @Test
    fun `getCharsWithSvg and getCharsWithoutSvg split correctly`() {
        setAvailableSvgs(setOf("中", "文"))

        val withSvg = SubcharHelper.getCharsWithSvg("中文输入")
        val withoutSvg = SubcharHelper.getCharsWithoutSvg("中文输入")

        assertEquals(listOf("中", "文"), withSvg)
        assertEquals("输入", withoutSvg)
    }

    private fun setAvailableSvgs(values: Set<String>) {
        val field = SubcharHelper::class.java.getDeclaredField("availableSvgs")
        field.isAccessible = true
        field.set(SubcharHelper, values)
    }
}
