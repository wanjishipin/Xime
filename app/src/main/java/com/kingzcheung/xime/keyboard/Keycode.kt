package com.kingzcheung.xime.keyboard

import android.view.KeyEvent

/**
 * 按键名 → Android KeyCode / 修饰键映射。
 *
 * 仿照 Trime 的 Keycode 枚举设计，提供 [parseSend] 方法解析 "Control+c"、"Escape"
 * 等字符串并提取 [keyCode] + [metaState]。
 */
object Keycode {

    // ── 按键名 → Android KeyCode 映射 ──

    private val keyNameToCode: Map<String, Int> = mapOf(
        // 功能键
        "Escape" to KeyEvent.KEYCODE_ESCAPE,
        "Tab" to KeyEvent.KEYCODE_TAB,
        "Return" to KeyEvent.KEYCODE_ENTER,
        "BackSpace" to KeyEvent.KEYCODE_DEL,
        "Delete" to KeyEvent.KEYCODE_FORWARD_DEL,
        "space" to KeyEvent.KEYCODE_SPACE,
        "Insert" to KeyEvent.KEYCODE_INSERT,

        // 方向键
        "Up" to KeyEvent.KEYCODE_DPAD_UP,
        "Down" to KeyEvent.KEYCODE_DPAD_DOWN,
        "Left" to KeyEvent.KEYCODE_DPAD_LEFT,
        "Right" to KeyEvent.KEYCODE_DPAD_RIGHT,

        // 导航键
        "Home" to KeyEvent.KEYCODE_MOVE_HOME,
        "End" to KeyEvent.KEYCODE_MOVE_END,
        "Page_Up" to KeyEvent.KEYCODE_PAGE_UP,
        "Page_Down" to KeyEvent.KEYCODE_PAGE_DOWN,

        // F 功能键
        "F1" to KeyEvent.KEYCODE_F1,
        "F2" to KeyEvent.KEYCODE_F2,
        "F3" to KeyEvent.KEYCODE_F3,
        "F4" to KeyEvent.KEYCODE_F4,
        "F5" to KeyEvent.KEYCODE_F5,
        "F6" to KeyEvent.KEYCODE_F6,
        "F7" to KeyEvent.KEYCODE_F7,
        "F8" to KeyEvent.KEYCODE_F8,
        "F9" to KeyEvent.KEYCODE_F9,
        "F10" to KeyEvent.KEYCODE_F10,
        "F11" to KeyEvent.KEYCODE_F11,
        "F12" to KeyEvent.KEYCODE_F12,

        // 修饰键本身（可直接发送）
        "Shift_L" to KeyEvent.KEYCODE_SHIFT_LEFT,
        "Shift_R" to KeyEvent.KEYCODE_SHIFT_RIGHT,
        "Control_L" to KeyEvent.KEYCODE_CTRL_LEFT,
        "Control_R" to KeyEvent.KEYCODE_CTRL_RIGHT,
        "Alt_L" to KeyEvent.KEYCODE_ALT_LEFT,
        "Alt_R" to KeyEvent.KEYCODE_ALT_RIGHT,
        "Meta_L" to KeyEvent.KEYCODE_META_LEFT,
        "Meta_R" to KeyEvent.KEYCODE_META_RIGHT,
        "Sym" to KeyEvent.KEYCODE_SYM,

        // 数字键
        "0" to KeyEvent.KEYCODE_0,
        "1" to KeyEvent.KEYCODE_1,
        "2" to KeyEvent.KEYCODE_2,
        "3" to KeyEvent.KEYCODE_3,
        "4" to KeyEvent.KEYCODE_4,
        "5" to KeyEvent.KEYCODE_5,
        "6" to KeyEvent.KEYCODE_6,
        "7" to KeyEvent.KEYCODE_7,
        "8" to KeyEvent.KEYCODE_8,
        "9" to KeyEvent.KEYCODE_9,

        // 字母键（用于组合键场景）
        "a" to KeyEvent.KEYCODE_A, "b" to KeyEvent.KEYCODE_B,
        "c" to KeyEvent.KEYCODE_C, "d" to KeyEvent.KEYCODE_D,
        "e" to KeyEvent.KEYCODE_E, "f" to KeyEvent.KEYCODE_F,
        "g" to KeyEvent.KEYCODE_G, "h" to KeyEvent.KEYCODE_H,
        "i" to KeyEvent.KEYCODE_I, "j" to KeyEvent.KEYCODE_J,
        "k" to KeyEvent.KEYCODE_K, "l" to KeyEvent.KEYCODE_L,
        "m" to KeyEvent.KEYCODE_M, "n" to KeyEvent.KEYCODE_N,
        "o" to KeyEvent.KEYCODE_O, "p" to KeyEvent.KEYCODE_P,
        "q" to KeyEvent.KEYCODE_Q, "r" to KeyEvent.KEYCODE_R,
        "s" to KeyEvent.KEYCODE_S, "t" to KeyEvent.KEYCODE_T,
        "u" to KeyEvent.KEYCODE_U, "v" to KeyEvent.KEYCODE_V,
        "w" to KeyEvent.KEYCODE_W, "x" to KeyEvent.KEYCODE_X,
        "y" to KeyEvent.KEYCODE_Y, "z" to KeyEvent.KEYCODE_Z,
    )

    // ── 修饰键名 → metaState 掩码 ──

    private val modifiers: Map<String, Int> = mapOf(
        "Shift" to KeyEvent.META_SHIFT_ON,
        "Control" to KeyEvent.META_CTRL_ON,
        "Alt" to KeyEvent.META_ALT_ON,
        "Meta" to KeyEvent.META_META_ON,
        "Super" to KeyEvent.META_SYM_ON,
    )

    /**
     * 解析 "Control+c"、"Escape" 这类字符串，返回 (keyCode, metaState)。
     *
     * @param str 按键表达式，如 "Control+Shift+z"、"Left"、"Escape"
     * @return [keyCode] 为 Android KeyCode，[metaState] 为修饰键掩码
     */
    fun parseSend(str: String): Pair<Int, Int> {
        if (str.isEmpty()) return Pair(0, 0)
        val keys = str.split('+')
        val keyCode = keyNameToCode[keys.last()] ?: 0
        val metaState = keys
            .filter { it in modifiers }
            .fold(0) { acc, key -> acc or (modifiers[key] ?: 0) }
        return Pair(keyCode, metaState)
    }
}
