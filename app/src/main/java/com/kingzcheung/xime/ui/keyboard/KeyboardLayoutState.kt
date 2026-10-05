package com.kingzcheung.xime.ui.keyboard

import com.kingzcheung.xime.settings.KeysConfigHelper

/**
 * 键盘布局状态 — 取代 [KeyboardMode] 枚举，
 * 将「当前显示哪个键盘布局」编码为单一 sealed class，
 * 消除 [KeyboardView] 中 [KeyboardMode] + [isAsciiMode] + 横屏检测 的复杂分支。
 *
 * 状态转移由 [KeyboardLayoutAction] 驱动，参见 [KeyboardLayoutState.transition]。
 *
 * 横竖屏（分体/正常）是渲染层根据 [isLandscape] 自动选择的，
 * 不编码在状态机中，故仅有 5 种核心状态。
 */
sealed class KeyboardLayoutState {

    /** 中文全键盘（默认模式，带 Rime 手势配置；横屏自动切换为分体键盘） */
    data object Chinese : KeyboardLayoutState()

    /** 英文全键盘（纯 QWERTY，无手势配置；横屏自动切换为分体键盘） */
    data object English : KeyboardLayoutState()

    /** 数字键盘 */
    data object Number : KeyboardLayoutState()

    /** 符号键盘 */
    data object Symbol : KeyboardLayoutState()

    /** 常用符号键盘（?123 进入的符号+数字混合键盘） */
    data object CommonSymbol : KeyboardLayoutState()

    /** 笔画键盘（stroke schema 专用 T9 九宫格布局） */
    data object Stroke : KeyboardLayoutState()

    /** 拼音九键键盘（t9_pinyin schema 专用 T9 九宫格布局） */
    data object T9Pinyin : KeyboardLayoutState()

    /** 是否为全键盘类（Chinese / English / T9Pinyin） */
    val isFullKeyboard: Boolean get() = this is Chinese || this is English || this is T9Pinyin
}

/**
 * 键盘布局动作 — 驱动 [KeyboardLayoutState] 转移的事件。
 */
sealed class KeyboardLayoutAction {

    /** 切换到数字键盘 */
    data object SwitchToNumber : KeyboardLayoutAction()

    /** 切换到符号键盘 */
    data object SwitchToSymbol : KeyboardLayoutAction()

    /** 切换到常用符号键盘 */
    data object SwitchToCommonSymbol : KeyboardLayoutAction()

    /** 从数字/符号/常用符号切回全键盘（中文或英文，取决于 isAsciiMode） */
    data object SwitchToFull : KeyboardLayoutAction()

    /** 切换到笔画键盘 */
    data object SwitchToStroke : KeyboardLayoutAction()
}

/**
 * [KeyboardLayoutState] 的纯函数转移。
 *
 * @param action 触发转移的动作
 * @param isAsciiMode 当前是否为 ASCII 模式（影响切换到 FULL 时选中文还是英文）
 * @return 转移后的新状态
 */
fun KeyboardLayoutState.transition(
    action: KeyboardLayoutAction,
    isAsciiMode: Boolean,
    schemaId: String = "",
): KeyboardLayoutState {
    return when (action) {
        KeyboardLayoutAction.SwitchToNumber -> KeyboardLayoutState.Number
        KeyboardLayoutAction.SwitchToSymbol -> KeyboardLayoutState.Symbol
        KeyboardLayoutAction.SwitchToCommonSymbol -> KeyboardLayoutState.CommonSymbol
        KeyboardLayoutAction.SwitchToStroke -> KeyboardLayoutState.Stroke
        KeyboardLayoutAction.SwitchToFull -> when {
            isAsciiMode -> KeyboardLayoutState.English
            isT9Schema(schemaId) -> KeyboardLayoutState.T9Pinyin
            else -> KeyboardLayoutState.Chinese
        }
    }
}

/**
 * 根据外部状态计算初始 [KeyboardLayoutState]。
 */
fun initialKeyboardLayoutState(
    isAsciiMode: Boolean,
    schemaId: String = "",
): KeyboardLayoutState = when {
    isT9Schema(schemaId) && !isAsciiMode -> KeyboardLayoutState.T9Pinyin
    isStrokeSchema(schemaId) && !isAsciiMode -> KeyboardLayoutState.Stroke
    isAsciiMode -> KeyboardLayoutState.English
    isStrokeSchema(schemaId) -> KeyboardLayoutState.Stroke
    isT9Schema(schemaId) -> KeyboardLayoutState.T9Pinyin
    else -> KeyboardLayoutState.Chinese
}

/**
 * 判断是否为九键（T9）方案：仅认 keyboard.t9.schemas 绑定声明
 * （xime.yaml / xime.custom.yaml），未声明的方案一律全键盘。
 */
fun isT9Schema(schemaId: String): Boolean =
    KeysConfigHelper.boundSectionForSchema(schemaId) == "t9"

/**
 * 判断是否为笔画方案：仅认 keyboard.stroke.schemas 绑定声明，
 * 未声明的方案一律全键盘。
 */
fun isStrokeSchema(schemaId: String): Boolean =
    KeysConfigHelper.boundSectionForSchema(schemaId) == "stroke"

/**
 * 判断是否为手写方案：仅认 keyboard.handwriting.schemas 绑定声明，
 * 未声明的方案一律全键盘（手写方案不经过 rime 引擎）。
 */
fun isHandwritingSchema(schemaId: String): Boolean =
    KeysConfigHelper.boundSectionForSchema(schemaId) == "handwriting"
