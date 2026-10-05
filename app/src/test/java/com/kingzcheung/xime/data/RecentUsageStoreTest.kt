package com.kingzcheung.xime.data

import org.junit.Assert.assertEquals
import org.junit.Test

class RecentUsageStoreTest {

    @Test
    fun `新值置顶`() {
        val result = RecentUsageStore.record(listOf("a", "b"), "c")
        assertEquals(listOf("c", "a", "b"), result)
    }

    @Test
    fun `已存在的值置顶去重`() {
        val result = RecentUsageStore.record(listOf("a", "b", "c"), "b")
        assertEquals(listOf("b", "a", "c"), result)
    }

    @Test
    fun `重复点击同一值不产生重复项`() {
        var list = emptyList<String>()
        repeat(5) { list = RecentUsageStore.record(list, "x") }
        assertEquals(listOf("x"), list)
    }

    @Test
    fun `超出上限截断末尾`() {
        var list = emptyList<String>()
        for (i in 0 until RecentUsageStore.MAX_COUNT + 5) {
            list = RecentUsageStore.record(list, "k$i")
        }
        assertEquals(RecentUsageStore.MAX_COUNT, list.size)
        assertEquals("k${RecentUsageStore.MAX_COUNT + 4}", list.first())
        assertEquals("k5", list.last())
    }

    @Test
    fun `空列表首次记录`() {
        assertEquals(listOf("a"), RecentUsageStore.record(emptyList(), "a"))
    }
}
