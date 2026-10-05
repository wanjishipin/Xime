package com.kingzcheung.xime.viewmodel

import com.kingzcheung.xime.ui.keyboard.AsciiKeyboardContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class KeyboardAsciiStateMachineTest {

    private val machine = KeyboardAsciiStateMachine()

    @Test
    fun `first attach to symbol panel defaults to english`() {
        assertEquals(true, machine.targetFor(AsciiKeyboardContext.SYMBOL_PANEL, engineAscii = false))
        assertNull(machine.targetFor(AsciiKeyboardContext.SYMBOL_PANEL, engineAscii = true))
    }

    @Test
    fun `symbol panel memory persists across sessions`() {
        // 首次进入符号面板：引擎中文，目标应为英文
        assertEquals(true, machine.targetFor(AsciiKeyboardContext.SYMBOL_PANEL, engineAscii = false))
        // 面板内切回中文
        machine.saveMemory(AsciiKeyboardContext.SYMBOL_PANEL, engineAscii = false)
        // 再次进入：记忆为中文，无需切换
        assertNull(machine.targetFor(AsciiKeyboardContext.SYMBOL_PANEL, engineAscii = false))
        // 引擎为英文时需切回中文记忆
        assertEquals(false, machine.targetFor(AsciiKeyboardContext.SYMBOL_PANEL, engineAscii = true))
    }

    @Test
    fun `exit panel restores main keyboard memory`() {
        // 主键盘中文，进入符号面板（保存主键盘记忆）
        machine.saveMemory(AsciiKeyboardContext.MAIN, engineAscii = false)
        assertEquals(true, machine.targetFor(AsciiKeyboardContext.SYMBOL_PANEL, engineAscii = false))
        // 面板内切英文
        machine.saveMemory(AsciiKeyboardContext.SYMBOL_PANEL, engineAscii = true)
        // 退出面板：目标为主键盘记忆（中文）
        assertEquals(false, machine.targetFor(AsciiKeyboardContext.MAIN, engineAscii = true))
    }

    @Test
    fun `panel switch does not affect main keyboard memory`() {
        machine.saveMemory(AsciiKeyboardContext.MAIN, engineAscii = false)
        // 面板内多次切换
        machine.saveMemory(AsciiKeyboardContext.SYMBOL_PANEL, engineAscii = true)
        machine.saveMemory(AsciiKeyboardContext.SYMBOL_PANEL, engineAscii = false)
        // 主键盘记忆仍为中文
        assertEquals(false, machine.targetFor(AsciiKeyboardContext.MAIN, engineAscii = true))
    }

    @Test
    fun `main keyboard with memory unset defaults to chinese`() {
        // MAIN 记忆缺失时 fallback 默认中文（不跟随引擎——引擎可能停留在面板态）
        assertNull(machine.targetFor(AsciiKeyboardContext.MAIN, engineAscii = false))
        assertEquals(false, machine.targetFor(AsciiKeyboardContext.MAIN, engineAscii = true))
    }

    @Test
    fun `main keyboard memory lost after reset restores chinese even if engine stuck on panel ascii`() {
        // 复现 issue：中文主键盘进符号面板（引擎被切英文）→ 发送消息触发会话刷新
        // reset 清掉记忆 → 退出面板时引擎仍为英文，应切回中文而不是沿用引擎的面板态
        machine.saveMemory(AsciiKeyboardContext.MAIN, engineAscii = false)
        machine.saveMemory(AsciiKeyboardContext.SYMBOL_PANEL, engineAscii = true)
        machine.reset()
        assertEquals(false, machine.targetFor(AsciiKeyboardContext.MAIN, engineAscii = true))
    }

    @Test
    fun `number panel follows engine`() {
        assertNull(machine.targetFor(AsciiKeyboardContext.NUMBER_PANEL, engineAscii = true))
        assertNull(machine.targetFor(AsciiKeyboardContext.NUMBER_PANEL, engineAscii = false))
        machine.saveMemory(AsciiKeyboardContext.NUMBER_PANEL, engineAscii = true)
        assertNull(machine.targetFor(AsciiKeyboardContext.NUMBER_PANEL, engineAscii = true))
    }

    @Test
    fun `reset clears all memories`() {
        machine.saveMemory(AsciiKeyboardContext.MAIN, engineAscii = false)
        machine.saveMemory(AsciiKeyboardContext.SYMBOL_PANEL, engineAscii = true)
        machine.reset()
        // MAIN 记忆缺失 fallback 默认中文；符号面板首入仍默认英文
        assertEquals(false, machine.targetFor(AsciiKeyboardContext.MAIN, engineAscii = true))
        assertEquals(true, machine.targetFor(AsciiKeyboardContext.SYMBOL_PANEL, engineAscii = false))
    }

    @Test
    fun `english main keyboard restored after symbol panel`() {
        // 主键盘英文
        machine.saveMemory(AsciiKeyboardContext.MAIN, engineAscii = true)
        // 符号面板内切中文
        machine.saveMemory(AsciiKeyboardContext.SYMBOL_PANEL, engineAscii = false)
        // 退出：恢复英文
        assertEquals(true, machine.targetFor(AsciiKeyboardContext.MAIN, engineAscii = false))
    }
}