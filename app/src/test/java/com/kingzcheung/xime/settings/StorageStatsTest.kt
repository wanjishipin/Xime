package com.kingzcheung.xime.settings

import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * StorageStats 纯逻辑单测：目录递归求和与总计（不依赖 Android Context）。
 */
class StorageStatsTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `目录不存在返回0`() {
        assertEquals(0L, StorageStats.directorySize(File(tmp.root, "missing")))
        assertEquals(0L, StorageStats.directorySize(null))
    }

    @Test
    fun `单文件按文件大小统计`() {
        val f = tmp.newFile("a.bin").apply { writeText("12345") }
        assertEquals(5L, StorageStats.directorySize(f))
    }

    @Test
    fun `递归统计多级子目录求和`() {
        val sub1 = tmp.newFolder("sub1")
        val sub2 = tmp.newFolder("sub1", "sub2")
        File(sub1, "a").writeText("123")        // 3
        File(sub2, "b").writeText("1234567")    // 7
        File(sub2, "c").writeText("12")         // 2
        assertEquals(12L, StorageStats.directorySize(tmp.root))
    }

    @Test
    fun `空目录大小为0`() {
        tmp.newFolder("empty")
        assertEquals(0L, StorageStats.directorySize(tmp.newFolder("empty2")))
    }

    @Test
    fun `总大小为各类目求和`() {
        val categories = listOf(
            category(100L),
            category(250L, clearable = false),
            category(50L),
        )
        assertEquals(400L, StorageStats.totalSize(categories))
    }

    private fun category(size: Long, clearable: Boolean = true) = StorageStats.Category(
        id = "test",
        title = "测试",
        description = "",
        sizeBytes = size,
        clearable = clearable,
    )
}
