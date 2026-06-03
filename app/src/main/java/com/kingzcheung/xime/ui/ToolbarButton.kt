package com.kingzcheung.xime.ui

import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.EmojiEmotions
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.Quickreply

enum class ToolbarButton(
    val id: String,
    val label: String,
    val icon: ImageVector
) {
    EMOJI("emoji", "表情", Icons.Default.EmojiEmotions),
    CLIPBOARD("clipboard", "剪贴板", Icons.Default.ContentPaste),
    SCHEMA("schema", "方案选择", Icons.Default.Keyboard),
    QUICK_PHRASE("quick_phrase", "快捷发送", Icons.Default.Quickreply);

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
