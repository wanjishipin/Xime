package com.kingzcheung.xime.settings

import com.kingzcheung.xime.keyboard.GestureAction

/**
 * 单个动作定义（对应 YAML 中一个手势槽位的取值）。
 *
 * @param action 动作类型；null 表示无动作
 * @param value 动作参数（如 commit 的上屏文本、command 的命令名）
 * @param label 显示标签
 * @param icon 图标名（标签以 `@` 前缀书写时提取）
 * @param display 标签/气泡显示位置（key：画在键面；bubble：不画键面，留给气泡；both：都画）
 * @param bubble 运行时是否弹出该手势的内容气泡（与 display 无关，display 只管静态提示位置）
 */
class KeyAction(
    val action: GestureAction? = null,
    val value: String = "",
    val label: String = "",
    val icon: String = "",
    val display: DisplayMode = DisplayMode.BOTH,
    val bubble: Boolean = true,
)

/**
 * 长按配置。
 *
 * 单动作对象 → [values] 仅一项；数组 → 气泡列表多项。
 *
 * @param display 显示模式；目前只支持 `bubble`（`key` / `both` 会在解析时回退为 `bubble` 并告警，
 *   长按候选的键面绘制尚未实现）
 * @param values 长按候选动作
 */
class LongPressAction(
    val display: DisplayMode = DisplayMode.KEY,
    val values: List<KeyAction> = emptyList(),
)

/**
 * 一个键的手势绑定表。
 *
 * @param width 布局宽度权重；null 时功能键用内置默认宽度、字母键为 1
 */
class KeyBinding(
    val tap: KeyAction? = null,
    val doubleTap: KeyAction? = null,
    val longPress: LongPressAction? = null,
    val swipeUp: KeyAction? = null,
    /** Shift 激活时的上滑动作（第二套字符，如 []{} 等扩展符号）；未配置时回退 swipeUp。 */
    val shiftSwipeUp: KeyAction? = null,
    val swipeDown: KeyAction? = null,
    val swipeLeft: KeyAction? = null,
    val swipeRight: KeyAction? = null,
    val width: Float? = null,
)
