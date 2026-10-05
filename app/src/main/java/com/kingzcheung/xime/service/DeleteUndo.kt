package com.kingzcheung.xime.service

/**
 * 下滑撤回「删除」的纯逻辑（2026-10-02）。
 *
 * 背景：下滑撤回（undo_clear）原先只能撤回「上滑清空」（clear_all 是
 * [XimeInputMethodService.lastClearedText] 的唯一写入点），单击退格 / 长按连删
 * 一路删除却不记账，抬手后下滑无内容可回插。
 *
 * 记账方式：删除键按下时快照光标前文本（候选栏模式再快照编码串），抬手时再读一次，
 * 两次之差即本次删掉的内容——长按连删是 30ms 级热路径，逐次读 InputConnection 会卡，
 * 故只在会话首尾各读一次。此处只放纯函数，便于 JVM 单测；Android 侧收尾逻辑见
 * [XimeInputMethodService.beginDeleteSession] / [XimeInputMethodService.finishDeleteSession]。
 */
internal object DeleteUndo {

    /**
     * before 以 after 开头时的被删后缀。
     *
     * 退格只从光标前（末尾）删，所以会话结束时读到的光标前文本应是开始时的前缀；
     * 不满足说明发生了别的事（窗口被截断、输入框被改写、光标被移动），
     * 此时返回空串由调用方放弃记账——宁可不撤回，也不回插一段错的文本。
     */
    fun removedPrefix(before: String, after: String): String =
        if (after.length < before.length && before.startsWith(after)) {
            before.substring(after.length)
        } else {
            ""
        }

    /**
     * 撤回落点校验：入账时光标前文本 [anchor] 与撤回时的 [probe] 一致才允许回插，
     * 避免用户随后打过字 / 移过光标后，旧内容被插到错误位置。
     *
     * 任一侧读不到输入框文本（部分宿主不支持 getTextBeforeCursor）时放行（true），
     * 退回改动前的行为，不因校验把原本可用的撤回挡掉。
     */
    fun anchorMatches(anchor: String?, probe: String?): Boolean =
        anchor == null || probe == null || anchor == probe
}
