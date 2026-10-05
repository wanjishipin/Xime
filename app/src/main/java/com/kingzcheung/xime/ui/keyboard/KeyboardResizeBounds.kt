package com.kingzcheung.xime.ui.keyboard

/**
 * 键盘调节的边界纯逻辑（无 Compose 依赖，可单测）。
 *
 * 两个可调维度：
 *  - 高度：键盘内容高度（候选栏 + 按键区），竖屏 25%~60%、横屏 30%~70% 屏高；
 *  - 左右边距：键盘两侧各自收窄的距离（宽度调节，左右独立、可整体偏移），
 *    每侧上限取「屏宽 25%」与「(屏宽 - 最小键盘宽)/2」的较小值。
 *
 * 底部手柄 = 底边跟手（顶边锚定）：高度与底部留白此消彼长、总和恒定，
 * 见 [bottomEdgeDragDelta]。
 */
object KeyboardResizeBounds {

    /** 键盘最窄总宽度（dp）：低于此值按键过小不可用。 */
    const val MIN_KEYBOARD_WIDTH_DP = 240

    /** 底部留白上限（dp）：底边最多抬离屏幕底部这么远。 */
    const val MAX_BOTTOM_PADDING_DP = 120

    /** 高度边界 [min, max]（dp）。 */
    fun heightBoundsDp(screenHeightDp: Int, isLandscape: Boolean): IntRange {
        val (minPercent, maxPercent) = if (isLandscape) (30 to 70) else (25 to 60)
        val min = (screenHeightDp * minPercent) / 100
        val max = (screenHeightDp * maxPercent) / 100
        return min..max.coerceAtLeast(min)
    }

    /** 左右边距上限（dp，每侧）。 */
    fun maxHorizontalMarginDp(screenWidthDp: Int): Int {
        val byPercent = (screenWidthDp * 25) / 100
        val byMinWidth = (screenWidthDp - MIN_KEYBOARD_WIDTH_DP) / 2
        return minOf(byPercent, byMinWidth).coerceAtLeast(0)
    }

    /**
     * 底部手柄拖动的耦合增量（dp，向下为正）：高度 += delta、底部留白 -= delta。
     * 两者总和恒定 ⇒ 顶边严格不动，底边精确跟手。
     * 边界：留白收敛到 [0, MAX_BOTTOM_PADDING_DP]，高度收敛到 [heightBounds]；
     * 任一边到界后增量归零（如留白为 0 时往下拉 = 底边已贴屏幕底部，不再动作）。
     */
    fun bottomEdgeDragDelta(
        dyDp: Float,
        heightDp: Int,
        heightBounds: IntRange,
        bottomPaddingDp: Int,
    ): Float =
        dyDp.coerceIn(
            maxOf(bottomPaddingDp - MAX_BOTTOM_PADDING_DP, heightBounds.first - heightDp).toFloat(),
            minOf(bottomPaddingDp, heightBounds.last - heightDp).toFloat(),
        )
}
