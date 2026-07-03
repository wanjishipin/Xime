package com.kingzcheung.xime.ui.keyboard

import com.kingzcheung.xime.keyboard.MainType
import com.kingzcheung.xime.keyboard.OverlayRoute
import com.kingzcheung.xime.keyboard.PanelType

/**
 * 统一键盘视图状态 — 替代原有的 [KeyboardPage] + [KeyboardLayoutState] 双轴管理。
 *
 * 编码了所有键盘导航层级和内容选择，所有状态转移通过 [KeyboardDispatchAction] 驱动，
 * 由 ViewModel 的单⼀ dispatch() 入口处理。
 */
sealed interface KeyboardViewState {

    // ── Level 0: 主键盘（无返回按钮） ──

    /** 中文全键盘 */
    data object ChineseFull : KeyboardViewState

    /** 英文全键盘 */
    data object EnglishFull : KeyboardViewState

    /** 拼音九键 */
    data object T9PinyinFull : KeyboardViewState

    /** 笔画键盘 */
    data object StrokeFull : KeyboardViewState

    /** 手写键盘 */
    data object Handwriting : KeyboardViewState

    /** 语音键盘 */
    data object Voice : KeyboardViewState

    // ── Level 1: 面板（显示返回按钮） ──

    data class NumberPanel(val returnTo: MainType) : KeyboardViewState

    data class CommonSymbolPanel(val returnTo: MainType) : KeyboardViewState

    // ── Level 2: 覆盖层（叠在任意状态之上） ──

    data class Overlay(
        val route: OverlayRoute,
        val backStack: List<OverlayRoute>,
        val behind: KeyboardViewState,
    ) : KeyboardViewState

    // ── 便捷属性 ──

    /** 是否属于全键盘类（ChineseFull / EnglishFull / T9PinyinFull） */
    val isFullKeyboard: Boolean get() = this is ChineseFull || this is EnglishFull || this is T9PinyinFull
}

/**
 * 键盘调度动作 — 所有状态转移走单一入口。
 */
sealed interface KeyboardDispatchAction {

    // ── 布局切换 ──

    /** 中/英切换 */
    data object ToggleChineseEnglish : KeyboardDispatchAction

    /** 进⼊数字面板 */
    data object ShowNumber : KeyboardDispatchAction

    /** 进⼊常用符号面板 */
    data object ShowCommonSymbol : KeyboardDispatchAction

    /** 退出面板，回到主键盘 */
    data object ExitPanel : KeyboardDispatchAction

    /** 切换到拼音九键 */
    data object SwitchToT9Pinyin : KeyboardDispatchAction

    /** 切换到笔画键盘 */
    data object SwitchToStroke : KeyboardDispatchAction

    /** 切换到手写键盘 */
    data object SwitchToHandwriting : KeyboardDispatchAction

    // ── 外部事件 ──

    /** ?123 切换（按当前 modeChangeTarget 走） */
    data class ModeChange(val targetIsNumber: Boolean, val isAsciiMode: Boolean) : KeyboardDispatchAction

    /** ASCII 模式由外部（Rime）改变 */
    data class AsciiModeChanged(val isAsciiMode: Boolean, val schemaId: String) : KeyboardDispatchAction

    /** 输⼊会话开始 */
    data class InputSessionStarted(val isAsciiMode: Boolean, val schemaId: String) : KeyboardDispatchAction

    // ── Overlay ──

    data class ShowOverlay(val route: OverlayRoute, val backStack: List<OverlayRoute> = emptyList()) : KeyboardDispatchAction

    data object CloseOverlay : KeyboardDispatchAction

    data class PushOverlay(val route: OverlayRoute) : KeyboardDispatchAction

    data object PopOverlay : KeyboardDispatchAction
}
