package com.kingzcheung.xime.keyboard

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.twotone.LastPage
import androidx.compose.material.icons.twotone.ContentCopy
import androidx.compose.material.icons.twotone.ContentPaste
import androidx.compose.material.icons.twotone.EmojiEmotions
import androidx.compose.material.icons.twotone.FirstPage
import androidx.compose.material.icons.twotone.KeyboardAlt
import androidx.compose.material.icons.twotone.LastPage
import androidx.compose.material.icons.twotone.Paid
import androidx.compose.material.icons.twotone.Quickreply
import androidx.compose.material.icons.twotone.SelectAll
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.Create
import androidx.compose.material.icons.filled.PictureInPicture
import androidx.compose.material.icons.outlined.Gesture
import androidx.compose.material.icons.twotone.ContentPasteGo
import androidx.compose.ui.graphics.vector.ImageVector

enum class ToolbarButton(
    val id: String,
    val label: String,
    val icon: ImageVector
) {
    EMOJI("emoji", "表情", Icons.TwoTone.EmojiEmotions),
    CLIPBOARD("clipboard", "剪贴板", Icons.TwoTone.ContentPasteGo),
    SCHEMA("schema", "方案选择", Icons.TwoTone.KeyboardAlt),
    QUICK_PHRASE("quick_phrase", "快捷发送", Icons.TwoTone.Quickreply),
    SYMBOL("symbol", "符号", Icons.TwoTone.Paid),
    FLOAT("float", "悬浮", Icons.Default.PictureInPicture),
    SELECT_ALL("select_all", "全选", Icons.TwoTone.SelectAll),
    COPY("copy", "复制", Icons.TwoTone.ContentCopy),
    PASTE("paste", "黏贴", Icons.TwoTone.ContentPaste),
    HOME("home", "段首", Icons.TwoTone.FirstPage),
    END("end", "段尾", Icons.AutoMirrored.TwoTone.LastPage),
    HANDWRITING_LOOKUP("handwriting_lookup", "手写找字", Icons.Outlined.Gesture);

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
