package com.kingzcheung.xime.plugin

import android.content.Context
import com.kingzcheung.xime.clipboard.ClipboardManager
import com.kingzcheung.xime.plugin.core.js.ClipboardHostApi

/**
 * 剪贴板只读 API 实现：读系统剪贴板当前文本。
 * 仅对 manifest 声明 `clipboard_read` 的插件由生命周期管理器注入。
 */
class ClipboardHostApiImpl(context: Context) : ClipboardHostApi {

    private val clipboardManager = ClipboardManager.getInstance(context)

    override fun getText(): String? {
        return try {
            clipboardManager.getCurrentClipboardText()?.takeIf { it.isNotEmpty() }
        } catch (_: Exception) {
            null
        }
    }
}
