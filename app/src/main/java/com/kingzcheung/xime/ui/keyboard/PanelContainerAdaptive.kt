package com.kingzcheung.xime.ui.keyboard

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/**
 * Overlay 面板容器自适应：面板按父容器真实宽度（BoxWithConstraints 的 maxWidth）选布局，
 * 不读屏幕方向——悬浮卡片（短边×0.85）、键盘收窄、分屏的容器宽都 ≠ 屏幕宽。
 *
 * ≥ [WIDE_CONTAINER_WIDTH] 视为"宽容器"（横屏全屏形态），否则按竖屏形态布局：
 * 手机竖屏全屏 ≤ ~480dp、悬浮卡片 ≤ ~410dp、分屏半宽 ≤ ~500dp；横屏全屏 ≥ ~640dp。
 */
internal val WIDE_CONTAINER_WIDTH = 520.dp

/**
 * 网格列数随容器宽连续适配：目标格宽 [targetCellWidth]，列数钳制在 [minColumns, maxColumns]。
 * （emoji/符号网格：竖屏 400dp→8、悬浮卡片 306dp→8、横屏 800dp→15）
 */
internal fun gridColumnCount(
    containerWidth: Dp,
    targetCellWidth: Dp,
    minColumns: Int,
    maxColumns: Int,
): Int = (containerWidth / targetCellWidth).roundToInt().coerceIn(minColumns, maxColumns)
