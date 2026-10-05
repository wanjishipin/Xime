package com.kingzcheung.xime.viewmodel

import com.kingzcheung.xime.ui.keyboard.AsciiKeyboardContext

/**
 * 键盘 ascii 状态机 — 每个键盘上下文（主键盘/数字面板/符号面板）持有独立的 ascii 记忆。
 *
 * 引擎状态是权威源，本状态机只在键盘切换时（attach/detach）同步引擎：
 * - detach（离开键盘）：把引擎当前状态保存为该键盘的记忆
 * - attach（进入键盘）：返回该键盘的记忆作为目标模式，与引擎不一致时由调用方发起切换
 *
 * 语义：
 * - 面板内的中英切换只影响该面板的记忆，退出面板后主键盘恢复自己的记忆
 * - 面板记忆持久，再次进入面板恢复上次的状态
 * - 符号面板首次进入默认英文
 */
class KeyboardAsciiStateMachine {

    private var mainAsciiMode: Boolean? = null
    private var numberAsciiMode: Boolean? = null
    private var symbolAsciiMode: Boolean? = null

    /** detach：保存当前键盘的引擎状态为该键盘的记忆 */
    fun saveMemory(context: AsciiKeyboardContext, engineAscii: Boolean) {
        when (context) {
            AsciiKeyboardContext.MAIN -> mainAsciiMode = engineAscii
            AsciiKeyboardContext.NUMBER_PANEL -> numberAsciiMode = engineAscii
            AsciiKeyboardContext.SYMBOL_PANEL -> symbolAsciiMode = engineAscii
        }
    }

    /**
     * attach：返回目标引擎模式；与引擎一致时返回 null（无需切换）。
     *
     * fallback 语义（记忆缺失时）：
     * - MAIN → 默认中文：与「英文态不跨收起存活」的全局决策一致（onStartInput 的
     *   ascii 确定性决策同为默认中文）。记忆缺失时引擎可能仍停留在面板态
     *   （符号面板首入默认英文），不能拿它当主键盘的应有状态。
     * - NUMBER_PANEL / SYMBOL_PANEL → 引擎当前状态；符号面板首次进入默认英文。
     */
    fun targetFor(context: AsciiKeyboardContext, engineAscii: Boolean): Boolean? {
        val target = when (context) {
            AsciiKeyboardContext.MAIN -> mainAsciiMode ?: false
            AsciiKeyboardContext.NUMBER_PANEL -> numberAsciiMode ?: engineAscii
            AsciiKeyboardContext.SYMBOL_PANEL -> symbolAsciiMode ?: true
        }
        return if (target != engineAscii) target else null
    }

    /** 新输入会话：清空全部记忆，从引擎当前状态重新开始 */
    fun reset() {
        mainAsciiMode = null
        numberAsciiMode = null
        symbolAsciiMode = null
    }
}