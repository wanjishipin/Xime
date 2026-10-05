package com.kingzcheung.xime.ui.keyboard

import com.kingzcheung.xime.keyboard.OverlayRoute
import com.kingzcheung.xime.util.FileLogger

/**
 * `switch_route` 手势动作的 value → 目标面板映射（唯一实现）。
 *
 * 键盘有三个 UI 手势入口：屏幕层下发（T9/笔画等）、26 键竖屏、26 键紧凑（横屏）。
 * 三处都调用本函数，避免同一映射各抄一份导致行为漂移——历史上 `clipboard`
 * 只存在于屏幕层那一份里，26 键竖屏/横屏配置 `switch_route: clipboard` 会被静默丢弃
 * （外层 `when` 已命中 SWITCH_ROUTE 分支，不会回落到服务层动作分发）。
 *
 * @return 目标面板；`value` 不在支持范围时返回 null 并记录告警，便于排查配置错写。
 */
internal fun switchRouteOverlay(value: String): OverlayRoute? = when (value) {
    "emoji" -> OverlayRoute.Emoji
    "symbol" -> OverlayRoute.Symbol
    "clipboard" -> OverlayRoute.Clipboard(0)
    else -> {
        FileLogger.w("XimeKeyboard", "switch_route 未知 value: \"$value\"（支持 emoji / symbol / clipboard）")
        null
    }
}