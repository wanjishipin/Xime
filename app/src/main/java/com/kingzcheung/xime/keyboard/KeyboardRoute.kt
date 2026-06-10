package com.kingzcheung.xime.keyboard

sealed class KeyboardRoute {
    data object Keyboard : KeyboardRoute()
    data object Menu : KeyboardRoute()
    data class Clipboard(val tab: Int = 0) : KeyboardRoute()
    data object Emoji : KeyboardRoute()
    data object Symbol : KeyboardRoute()
    data object SchemaList : KeyboardRoute()
    data object CandidatePage : KeyboardRoute()
    data object ToolbarCustomize : KeyboardRoute()
    data class SplitWords(val text: String) : KeyboardRoute()
}
