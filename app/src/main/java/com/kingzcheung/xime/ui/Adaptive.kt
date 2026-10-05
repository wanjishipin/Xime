package com.kingzcheung.xime.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 自适应布局公共工具。
 *
 * 项目未引入 material3-window-size-class / androidx.window，这里用最稳定的
 * [LocalConfiguration.smallestScreenWidthDp] 作为判定依据：它对应 Android 的
 * sw600dp 资源限定符约定，且不受横竖屏切换与多窗口尺寸变化影响，适合做
 * “手机 / 平板”的粗粒度布局分叉。
 */

/** 判定为平板/大屏的最小宽度阈值（dp，对应 sw600dp）。 */
const val TABLET_SMALLEST_WIDTH_DP: Int = 600

/**
 * 双栏布局断点：当前窗口宽度 ≥ 此值时用双栏（左导航 + 右内容），否则单栏。
 *
 * 与 [isTablet] 的区别：isTablet 按 sw600dp 设备分档、不随方向变化；
 * 此处按当前窗口实际宽度分档——手机横屏（640dp+）应得双栏，竖屏（~400dp）保持单栏。
 * 取 600 与 Material 窗口宽度 Medium 档对齐，且保证双栏后内容区仍有足够宽度
 * （左栏 260dp + 内容）。
 */
val TwoPaneMinWidth: Dp = 600.dp

/** 宽屏下正文/表单内容的最大宽度，避免行长过长与控件被拉伸。 */
val ContentMaxWidth: Dp = 560.dp

/**
 * 当前设备是否为平板/大屏。
 *
 * 以最小宽度（而非当前窗口宽度）判定，横竖屏切换时结果保持稳定。
 */
@Composable
@ReadOnlyComposable
fun isTablet(): Boolean =
    LocalConfiguration.current.smallestScreenWidthDp >= TABLET_SMALLEST_WIDTH_DP
