package com.kingzcheung.xime.ui.keyboard

import kotlin.math.roundToInt

/**
 * 悬浮键盘卡片几何的唯一权威定义（无 Compose 依赖，可单测）。
 *
 * 卡片恒为竖屏形态：宽度 = 短边 × [CARD_SCALE]（横屏下占窗口宽的比例相应变小），
 * 高度 = 键盘内容高 × [CARD_SCALE] + 顶部拖条 + 底部留白。
 *
 * 触摸区与拖动钳制一律以 FloatingKeyboardContainer 实测上报的矩形为准
 * （XimeInputMethodService.floatingCardBounds，窗口坐标）；本对象只承载常量与
 * "首帧实测前"的兜底推算。禁止在调用处复制 0.85/18 等字面量——历史上的
 * 多处复制漂移正是悬浮卡片错位/跳位的根源。
 */
object FloatingCardGeometry {
    /** 卡片缩放系数：宽度、键盘内容高与字号统一按此比例缩小 */
    const val CARD_SCALE = 0.85f

    /** 卡片顶部拖条高度（dp），与 FloatingKeyboardContainer 的拖条渲染共用 */
    const val DRAG_BAR_HEIGHT_DP = 18

    /** 悬浮卡片矩形（窗口坐标，px），由 onGloballyPositioned 实测回传 */
    data class CardBounds(val left: Int, val top: Int, val right: Int, val bottom: Int) {
        val heightPx: Int get() = bottom - top
    }

    fun cardWidthDp(portraitScreenWidthDp: Int): Int =
        (portraitScreenWidthDp * CARD_SCALE).roundToInt()

    /** 卡片宽度相对宿主（全屏宽）的比例：竖屏 = [CARD_SCALE]，横屏按短边折算 */
    fun widthFraction(portraitScreenWidthDp: Int, windowWidthDp: Int): Float =
        if (windowWidthDp <= 0) CARD_SCALE
        else cardWidthDp(portraitScreenWidthDp) / windowWidthDp.toFloat()

    /** 卡片水平可移动半幅（dp）：offsetX ∈ [-halfMargin, +halfMargin] */
    fun halfMarginDp(windowWidthDp: Int, portraitScreenWidthDp: Int): Int =
        maxOf(0, (windowWidthDp - cardWidthDp(portraitScreenWidthDp)) / 2)

    /** 首帧实测前的兜底卡高（dp） */
    fun fallbackCardHeightDp(keyboardHeightDp: Int, bottomPaddingDp: Int): Int =
        (keyboardHeightDp * CARD_SCALE).roundToInt() + DRAG_BAR_HEIGHT_DP + bottomPaddingDp

    /**
     * 垂直位置钳制：offsetY 是"卡片底边离窗口底边的距离"，下界 [minY]（导航栏高，
     * 卡片不得压进导航栏），上界为"卡片顶边不顶出窗口顶"（屏高 - 卡高）。
     */
    fun clampOffsetY(offsetY: Int, minY: Int, screenHeightDp: Int, cardHeightDp: Int): Int {
        val maxOffsetY = (screenHeightDp - cardHeightDp).coerceAtLeast(minY)
        return offsetY.coerceIn(minY, maxOffsetY)
    }

    /**
     * 首帧实测前的兜底触摸区（窗口坐标，px）。卡片水平居中后加 offsetX，
     * 底边 = 窗口底 - offsetY，顶边 = 底边 - 卡高。
     */
    fun fallbackBounds(
        windowWidthPx: Int,
        windowHeightPx: Int,
        offsetXdp: Int,
        offsetYdp: Int,
        cardWidthDp: Int,
        cardHeightDp: Int,
        density: Float,
    ): CardBounds {
        val cardWidthPx = (cardWidthDp * density).toInt()
        val cardHeightPx = (cardHeightDp * density).toInt()
        val left = (windowWidthPx - cardWidthPx) / 2 + (offsetXdp * density).toInt()
        val bottom = windowHeightPx - (offsetYdp * density).toInt()
        return CardBounds(left, bottom - cardHeightPx, left + cardWidthPx, bottom)
    }
}
