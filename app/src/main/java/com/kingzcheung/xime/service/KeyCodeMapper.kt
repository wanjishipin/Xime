package com.kingzcheung.xime.service

import android.view.KeyEvent

/** 物理键码是否为左右 Shift（修饰键，不产生字符，单击语义由调用方定义）。 */
internal fun isShiftKeyCode(keyCode: Int): Boolean =
    keyCode == KeyEvent.KEYCODE_SHIFT_LEFT || keyCode == KeyEvent.KEYCODE_SHIFT_RIGHT

/** 物理键码 → 输入法按键名。 */
internal fun keyCodeToKey(keyCode: Int, isShifted: Boolean): String? {
    return when (keyCode) {
        KeyEvent.KEYCODE_A -> if (isShifted) "A" else "a"
        KeyEvent.KEYCODE_B -> if (isShifted) "B" else "b"
        KeyEvent.KEYCODE_C -> if (isShifted) "C" else "c"
        KeyEvent.KEYCODE_D -> if (isShifted) "D" else "d"
        KeyEvent.KEYCODE_E -> if (isShifted) "E" else "e"
        KeyEvent.KEYCODE_F -> if (isShifted) "F" else "f"
        KeyEvent.KEYCODE_G -> if (isShifted) "G" else "g"
        KeyEvent.KEYCODE_H -> if (isShifted) "H" else "h"
        KeyEvent.KEYCODE_I -> if (isShifted) "I" else "i"
        KeyEvent.KEYCODE_J -> if (isShifted) "J" else "j"
        KeyEvent.KEYCODE_K -> if (isShifted) "K" else "k"
        KeyEvent.KEYCODE_L -> if (isShifted) "L" else "l"
        KeyEvent.KEYCODE_M -> if (isShifted) "M" else "m"
        KeyEvent.KEYCODE_N -> if (isShifted) "N" else "n"
        KeyEvent.KEYCODE_O -> if (isShifted) "O" else "o"
        KeyEvent.KEYCODE_P -> if (isShifted) "P" else "p"
        KeyEvent.KEYCODE_Q -> if (isShifted) "Q" else "q"
        KeyEvent.KEYCODE_R -> if (isShifted) "R" else "r"
        KeyEvent.KEYCODE_S -> if (isShifted) "S" else "s"
        KeyEvent.KEYCODE_T -> if (isShifted) "T" else "t"
        KeyEvent.KEYCODE_U -> if (isShifted) "U" else "u"
        KeyEvent.KEYCODE_V -> if (isShifted) "V" else "v"
        KeyEvent.KEYCODE_W -> if (isShifted) "W" else "w"
        KeyEvent.KEYCODE_X -> if (isShifted) "X" else "x"
        KeyEvent.KEYCODE_Y -> if (isShifted) "Y" else "y"
        KeyEvent.KEYCODE_Z -> if (isShifted) "Z" else "z"
        KeyEvent.KEYCODE_SPACE -> "space"
        KeyEvent.KEYCODE_ENTER -> "enter"
        KeyEvent.KEYCODE_DEL -> "delete"
        KeyEvent.KEYCODE_0 -> "0"
        KeyEvent.KEYCODE_1 -> "1"
        KeyEvent.KEYCODE_2 -> "2"
        KeyEvent.KEYCODE_3 -> "3"
        KeyEvent.KEYCODE_4 -> "4"
        KeyEvent.KEYCODE_5 -> "5"
        KeyEvent.KEYCODE_6 -> "6"
        KeyEvent.KEYCODE_7 -> "7"
        KeyEvent.KEYCODE_8 -> "8"
        KeyEvent.KEYCODE_9 -> "9"
        KeyEvent.KEYCODE_COMMA -> ","
        KeyEvent.KEYCODE_PERIOD -> "."
        KeyEvent.KEYCODE_MINUS -> "-"
        KeyEvent.KEYCODE_EQUALS -> "="
        KeyEvent.KEYCODE_SLASH -> "/"
        KeyEvent.KEYCODE_BACKSLASH -> "\\"
        KeyEvent.KEYCODE_SEMICOLON -> ";"
        KeyEvent.KEYCODE_APOSTROPHE -> "'"
        KeyEvent.KEYCODE_LEFT_BRACKET -> "["
        KeyEvent.KEYCODE_RIGHT_BRACKET -> "]"
        KeyEvent.KEYCODE_GRAVE -> "`"
        KeyEvent.KEYCODE_TAB -> "\t"
        else -> null
    }
}
