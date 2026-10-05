package com.kingzcheung.xime.service

import com.kingzcheung.xime.rime.RimeCandidate

/**
 * 展开候选数据过滤与展示分行（纯逻辑，可 JVM 单测）。
 * 展开页只渲染可见行，行分组按字符当量估算、仅作展示分组。
 */
object ExpandedCandidatePager {

    /** 条目固定开销：水平 padding(8dp) + 两侧间隙分摊(6dp)，以 18sp 字宽为 1 单位近似 */
    const val ITEM_FIXED_UNITS = 1.2f

    /** 注释字号(11sp)相对主字号(18sp)的宽度系数 */
    private const val COMMENT_WIDTH_FACTOR = 0.62f

    /** 单字符宽度（字符当量）：CJK 约 1 个字宽，其余（拉丁/数字/标点）约 0.55 */
    fun charUnits(c: Char): Float = if (c.code > 0x2E80) 1.0f else 0.55f

    /** 条目估算宽度：候选词全字宽 + 注释按小字号折算 + 固定开销 */
    fun estimateItemUnits(candidate: RimeCandidate): Float {
        var units = ITEM_FIXED_UNITS
        candidate.text.forEach { units += charUnits(it) }
        candidate.comment.forEach { units += charUnits(it) * COMMENT_WIDTH_FACTOR }
        return units
    }

    /**
     * 展开区行容量（字符当量）：可用宽度 ≈ 屏宽 - 左右栏/分隔/内边距合计约 140dp，
     * 以 18sp 主字号为 1 单位。
     */
    fun rowWidthUnits(screenWidthPx: Float, density: Float, scaledDensity: Float): Float =
        ((screenWidthPx - 140f * density) / (18f * scaledDensity)).coerceAtLeast(8f)

    /**
     * 按字符当量估算贪心分行。分行仅作展示分组：估算偏差只改变每行词数，
     * 行内条目宽度自适应拉伸，不会溢出。
     */
    fun flowRows(
        filtered: List<Int>,
        all: List<RimeCandidate>,
        rowWidthUnits: Float,
    ): List<List<Int>> {
        if (filtered.isEmpty()) return emptyList()
        val rows = mutableListOf<List<Int>>()
        var current = mutableListOf<Int>()
        var currentUnits = 0f
        for (index in filtered) {
            val itemUnits = estimateItemUnits(all[index])
            val withItem = if (current.isEmpty()) itemUnits else currentUnits + itemUnits
            if (current.isNotEmpty() && withItem > rowWidthUnits) {
                rows.add(current)
                current = mutableListOf(index)
                currentUnits = itemUnits
            } else {
                current.add(index)
                currentUnits = withItem
            }
        }
        if (current.isNotEmpty()) rows.add(current)
        return rows
    }

    /**
     * 过滤后的全局索引列表。
     * @param singleCharOnly true 时只保留单字候选（筛选单字功能）
     * @param fromIndex 起始偏移：跳过候选栏已显示的前若干个候选，避免展开页重复
     */
    fun filterIndices(
        all: List<RimeCandidate>,
        singleCharOnly: Boolean,
        fromIndex: Int = 0,
    ): List<Int> {
        val filtered = if (!singleCharOnly) all.indices.toList()
        else all.indices.filter { all[it].text.length == 1 }
        return filtered.drop(fromIndex.coerceIn(0, filtered.size))
    }
}
