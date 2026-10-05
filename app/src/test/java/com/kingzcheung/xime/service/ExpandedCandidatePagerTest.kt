package com.kingzcheung.xime.service

import com.kingzcheung.xime.rime.RimeCandidate
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * ExpandedCandidatePager 纯逻辑单测：展开页数据过滤、与候选栏的口径衔接。
 *
 * 展开页已改为可滚动列表（无本地分页），此处只守护 filterIndices 的过滤与
 * 偏移语义——它是点选/长按换算跨页全局索引的基准。
 */
class ExpandedCandidatePagerTest {

    private fun candidate(text: String, comment: String = "") =
        RimeCandidate(text = text, comment = comment)

    // ── filterIndices ──

    @Test
    fun `不过滤时返回全部索引`() {
        val all = listOf(candidate("你"), candidate("你好"), candidate("世界"))
        assertEquals(listOf(0, 1, 2), ExpandedCandidatePager.filterIndices(all, singleCharOnly = false))
    }

    @Test
    fun `单字过滤只保留长度为1的候选`() {
        val all = listOf(candidate("你"), candidate("你好"), candidate("世"), candidate("世界", "shij"))
        assertEquals(listOf(0, 2), ExpandedCandidatePager.filterIndices(all, singleCharOnly = true))
    }

    @Test
    fun `fromIndex偏移跳过候选栏已显示的候选`() {
        val all = listOf(candidate("你"), candidate("你好"), candidate("世"), candidate("们好"))
        // 候选栏已显示前 2 个，展开页从索引 2 开始
        val filtered = ExpandedCandidatePager.filterIndices(all, singleCharOnly = false, fromIndex = 2)
        assertEquals(listOf(2, 3), filtered)
        // 偏移作用于过滤后列表：跳过 1 个单字"你"后接"世"
        val single = ExpandedCandidatePager.filterIndices(all, singleCharOnly = true, fromIndex = 1)
        assertEquals(listOf(2), single)
        // 偏移越界安全
        assertEquals(emptyList<Int>(), ExpandedCandidatePager.filterIndices(all, false, fromIndex = 9))
    }

    @Test
    fun `单字筛选时偏移作用于过滤后列表与候选栏单字口径衔接`() {
        // 候选栏（单字口径）显示"你""好"2 个单字后，展开页应无更多单字——
        // 旧语义（先偏移后过滤）会返回 [2]，把候选栏已显示的"好"再展示一遍
        val all = listOf(candidate("你"), candidate("你好"), candidate("好"), candidate("好的"))
        assertEquals(
            emptyList<Int>(),
            ExpandedCandidatePager.filterIndices(all, singleCharOnly = true, fromIndex = 2)
        )
        // 正向衔接：候选栏显示 2 个单字后，展开页从下一个单字接续
        val all2 = listOf(candidate("你"), candidate("你好"), candidate("好"), candidate("好人"), candidate("们"))
        assertEquals(
            listOf(4),
            ExpandedCandidatePager.filterIndices(all2, singleCharOnly = true, fromIndex = 2)
        )
    }

    // ── flowRows（展示分组：配合 LazyColumn 惰性渲染）──

    @Test
    fun `空列表返回空行组`() {
        val all = listOf(candidate("你"))
        assertEquals(emptyList<List<Int>>(), ExpandedCandidatePager.flowRows(emptyList(), all, 10f))
    }

    @Test
    fun `全部装得下时单行收纳且保持顺序`() {
        val all = listOf(candidate("你"), candidate("好"), candidate("吗"))
        val rows = ExpandedCandidatePager.flowRows(listOf(0, 1, 2), all, 100f)
        assertEquals(1, rows.size)
        assertEquals(listOf(0, 1, 2), rows[0])
    }

    @Test
    fun `行容量不足时贪心换行`() {
        // 条目"中中" = 1.2 + 2 = 3.2 单位：容量 6.5 时每行放 2 条（3.2+3.2=6.4 ≤ 6.5），
        // 第 3 条超宽 → 换行
        val all = listOf(candidate("中中"), candidate("中中"), candidate("中中"))
        val rows = ExpandedCandidatePager.flowRows(listOf(0, 1, 2), all, 6.5f)
        assertEquals(listOf(listOf(0, 1), listOf(2)), rows)
    }

    @Test
    fun `超宽单条独占一行且不被丢弃`() {
        val all = listOf(candidate("超".repeat(50)), candidate("字"))
        val rows = ExpandedCandidatePager.flowRows(listOf(0, 1), all, 5f)
        assertEquals(listOf(listOf(0), listOf(1)), rows)
    }

    @Test
    fun `行组中的全局索引指向原始列表`() {
        val all = listOf(candidate("你"), candidate("你好"), candidate("世"), candidate("们好"))
        val filtered = ExpandedCandidatePager.filterIndices(all, singleCharOnly = true)
        val rows = ExpandedCandidatePager.flowRows(filtered, all, 4f)
        // 过滤后全局索引 0、2：估算 1.2+1=2.2 单位/条，容量 4 装不下第二条 → 各占一行
        assertEquals(listOf(listOf(0), listOf(2)), rows)
    }
}
