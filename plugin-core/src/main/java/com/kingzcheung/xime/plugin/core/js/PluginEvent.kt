package com.kingzcheung.xime.plugin.core.js

/**
 * 下行事件：宿主 → 插件。
 *
 * - 仅投递给 manifest 声明了 `capabilities.events` 且包含 [type] 的运行实例。
 * - 通道语义为"只保最新"（conflated）：插件消费慢时，中间事件合并丢弃，
 *   插件永远只收到最新的状态快照，主输入流程不受插件处理速度影响。
 * - [payload] 为不可变快照（camelCase 字段），插件线程不得反推宿主内部状态。
 */
data class PluginEvent(
    val type: String,
    val payload: Map<String, Any?> = emptyMap(),
) {
    companion object {
        /** 用户正在输入的编码变化：payload = { inputText: String }。 */
        const val TYPE_INPUT_CHANGED = "input_changed"

        /** FIELD_INPUT_TEXT：用户正在输入的编码文本（composing 快照）。 */
        const val FIELD_INPUT_TEXT = "inputText"

        /**
         * 文本上屏：payload = { committedText: String, sessionTotalChars: Long,
         * sessionTotalCommits: Long, isPaste: Boolean }。
         * 累计值为宿主进程生命周期计数（conflated 丢中间事件不影响统计，
         * 插件用前后差值做增量持久化）。敏感输入框（密码类）不投递。
         */
        const val TYPE_TEXT_COMMITTED = "text_committed"

        /** FIELD_COMMITTED_TEXT：本次上屏的文本。 */
        const val FIELD_COMMITTED_TEXT = "committedText"

        /** FIELD_SESSION_TOTAL_CHARS：宿主进程累计上屏字符数。 */
        const val FIELD_SESSION_TOTAL_CHARS = "sessionTotalChars"

        /** FIELD_SESSION_TOTAL_COMMITS：宿主进程累计上屏提交次数。 */
        const val FIELD_SESSION_TOTAL_COMMITS = "sessionTotalCommits"

        /**
         * FIELD_IS_PASTE：本次上屏是否为粘贴性质（键盘剪贴板点选/编辑面板提交）。
         * 事件语义仍为"文本上屏"（照常投递、计数器照常累计以维持差值基准），
         * 是否把粘贴计入打字量由各订阅插件自行决定（如 typing-stats 过滤）。
         */
        const val FIELD_IS_PASTE = "isPaste"

        /**
         * 快捷发送列表变更：payload = { count: Int }。
         * 只通知变更（conflated 只保最新），插件收到后调 host.quickSend.list() 重新拉取。
         */
        const val TYPE_QUICK_SEND_CHANGED = "quick_send_changed"

        /** FIELD_COUNT：变更后的快捷发送条目数。 */
        const val FIELD_COUNT = "count"
    }
}
