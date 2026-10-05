package com.kingzcheung.xime.plugin

import android.content.Context
import com.kingzcheung.xime.clipboard.ClipboardManager
import com.kingzcheung.xime.plugin.core.js.QuickSendHostApi
import com.kingzcheung.xime.plugin.core.js.QuickSendItem

/**
 * 快捷发送只读 API 实现：同步读 [ClipboardManager] 进程单例的内存缓存
 * （Room `clipboard_entries` 表 isQuickSend 子集的 StateFlow），零 IO。
 * 仅对 manifest 声明 `quick_send_read` 的插件由生命周期管理器注入。
 */
class QuickSendHostApiImpl(context: Context) : QuickSendHostApi {

    private val clipboardManager = ClipboardManager.getInstance(context)

    override fun list(): List<QuickSendItem> {
        return clipboardManager.quickSendItems.value.map {
            QuickSendItem(
                id = it.id,
                text = it.text,
                code = it.code,
                timestamp = it.timestamp,
                isPinned = it.isPinned,
            )
        }
    }
}
