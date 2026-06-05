package com.kingzcheung.xime.ui

import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.EmojiEmotions
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.Quickreply
import androidx.compose.material.icons.twotone.ContentPaste
import androidx.compose.material.icons.twotone.EmojiEmotions
import androidx.compose.material.icons.twotone.Keyboard
import androidx.compose.material.icons.twotone.KeyboardAlt
import androidx.compose.material.icons.twotone.Quickreply

enum class ToolbarButton(
    val id: String,
    val label: String,
    val icon: ImageVector
) {
    EMOJI("emoji", "表情", Icons.TwoTone.EmojiEmotions),
    CLIPBOARD("clipboard", "剪贴板", Icons.TwoTone.ContentPaste),
    SCHEMA("schema", "方案选择", Icons.TwoTone.KeyboardAlt),
    QUICK_PHRASE("quick_phrase", "快捷发送", Icons.TwoTone.Quickreply);

    companion object {
        val DEFAULT_VISIBLE = emptySet<ToolbarButton>()

        fun fromId(id: String): ToolbarButton? =
            entries.find { it.id == id }
    }
}

data class ToolbarAction(
    val button: ToolbarButton,
    val onClick: () -> Unit
)
