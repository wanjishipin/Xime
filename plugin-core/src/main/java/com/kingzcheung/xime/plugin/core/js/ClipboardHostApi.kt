package com.kingzcheung.xime.plugin.core.js

/**
 * 宿主剪贴板只读 API（JS `host.clipboard`）。
 *
 * 权限门禁：仅当插件 manifest 声明 `capabilities.clipboard_read: true` 时，
 * 宿主才注入本 API（未声明时 `host.clipboard` 不存在）。
 * 剪贴板内容是用户复制数据，插件外发仍受网络三重门（declaredHosts → 授权 →
 * 白名单强校验）约束；敏感输入框的候选场景由 hotPath 调用链短路兜底。
 */
interface ClipboardHostApi {

    /** 当前剪贴板文本（非文本内容/空/无权限返回 null）。 */
    fun getText(): String?
}
