package com.kingzcheung.xime.plugin.core.js

/**
 * 宿主快捷发送只读 API（Lua `host.quickSend`）。
 *
 * 权限门禁：仅当插件 manifest 声明 `capabilities.quick_send_read: true` 时，
 * 宿主才注入本 API（未声明时 `host.quickSend` 不存在）。
 * 数据来自宿主 ClipboardManager 单例的内存缓存（Room 的 isQuickSend 子集），
 * 同步读取零 IO，可在 hotPath（候选词变换）内调用。
 */
interface QuickSendHostApi {

    /**
     * 列出快捷发送条目（timestamp 降序，最近优先；宿主上限 20 条）。
     * 只读快照，宿主数据变更时经 `quick_send_changed` 事件通知插件重新拉取。
     */
    fun list(): List<QuickSendItem>
}

/** Quick-send entry snapshot. */
data class QuickSendItem(
    /** 数据库 id。 */
    val id: Long,
    /** 上屏文本。 */
    val text: String,
    /** 触发编码（如 dh）：空 = 不参与编码匹配，仅内容命中。 */
    val code: String = "",
    /** 最近使用时间戳（毫秒）。 */
    val timestamp: Long,
    /** 是否用户置顶。 */
    val isPinned: Boolean,
)
