package com.kingzcheung.xime.plugin.core.model

import com.kingzcheung.xime.plugin.core.api.ToolResult

/**
 * 插件能力声明（manifest.capabilities）：宿主消费能力的**唯一来源**。
 *
 * 静态能力（搜索支持、布局、结果模式、录音格式等）一律由元数据声明，
 * 插件 Lua 侧只提供运行时数据与事件处理，不再重复声明。
 */
data class PluginCapabilities(
    val emoji: EmojiCapabilities? = null,
    val speech: SpeechCapabilities? = null,
    val tool: ToolCapabilities? = null,
    @kotlinx.serialization.SerialName("clipboard_sync")
    val clipboardSync: ClipboardSyncCapabilities? = null,
    @kotlinx.serialization.SerialName("backup")
    val backup: BackupCapabilities? = null,
    /** 下行事件订阅声明（如 "input_changed"）：未声明的事件宿主不投递，通道也不建立。 */
    val events: List<String> = emptyList(),
    /** 候选词变换能力（manifest 声明 `candidate_transform: true`）：rime 返回候选后、
     *  候选栏渲染前，宿主同步调用插件 transformCandidates 修改候选；首个接入输入
     *  主流程（hotPath）的能力，硬超时 15ms + 敏感输入短路 + 连续超时熔断。 */
    @kotlinx.serialization.SerialName("candidate_transform")
    val candidateTransform: Boolean = false,
    /** 快捷发送只读能力（manifest 声明 `quick_send_read: true`）：声明后宿主注入
     *  `host.quickSend`（list()），并允许订阅 `quick_send_changed` 事件。 */
    @kotlinx.serialization.SerialName("quick_send_read")
    val quickSendRead: Boolean = false,
    /** 剪贴板只读能力（manifest 声明 `clipboard_read: true`）：声明后宿主注入
     *  `host.clipboard`（getText()）。 */
    @kotlinx.serialization.SerialName("clipboard_read")
    val clipboardRead: Boolean = false,
) {
    companion object {
        val EMPTY = PluginCapabilities()

        /** 内建能力块集合（块键与 type 同名）：capabilities 中仅允许声明与 type 对应的块。 */
        private val BUILTIN_TYPES = setOf("tool", "speech", "emoji", "clipboard_sync", "backup")

        /** 横切权限的典型适用类型（软校验：其余类型声明仅告警，合法组合不受限）。 */
        private val CROSS_CUTTING_TYPICAL = mapOf(
            "clipboard_read" to setOf("tool", "clipboard_sync"),
            "candidate_transform" to setOf("tool"),
            "quick_send_read" to setOf("tool"),
        )

        /**
         * 类型 × 能力合理性校验（安装时与 xipm check 调用）。
         *
         * - 内建能力块与 type 错配 → errors：宿主不会消费错配块，声明只会误导作者与用户；
         * - 横切权限与类型的非常见组合 → warnings：运行时本就按声明门禁（与类型正交），
         *   这里只提示"该类型通常不需要此能力"，避免复制粘贴式权限残留。
         *
         * @return (errors, warnings)，文案面向插件作者、可直接展示。
         */
        fun validateForType(type: String, caps: PluginCapabilities): Pair<List<String>, List<String>> {
            val errors = mutableListOf<String>()
            val warnings = mutableListOf<String>()
            if (type !in BUILTIN_TYPES) {
                errors += "未知插件类型: $type（允许: ${BUILTIN_TYPES.sorted().joinToString("/")}）"
                return errors to warnings
            }
            caps.declaredBuiltinBlocks().forEach { block ->
                if (block != type) {
                    errors += "capabilities.$block 与插件类型 $type 不匹配（此块仅 $block 型插件声明），宿主不会消费该块"
                }
            }
            CROSS_CUTTING_TYPICAL.forEach { (cap, typical) ->
                val declared = when (cap) {
                    "clipboard_read" -> caps.clipboardRead
                    "candidate_transform" -> caps.candidateTransform
                    "quick_send_read" -> caps.quickSendRead
                    else -> false
                }
                if (declared && type !in typical) {
                    warnings += "$type 型插件声明了 capabilities.$cap（典型用于 ${typical.sorted().joinToString("/")} 型），请确认非误报"
                }
            }
            return errors to warnings
        }
    }

    /** 已声明的内建能力块（manifest 键名）。 */
    private fun declaredBuiltinBlocks(): List<String> = buildList {
        if (emoji != null) add("emoji")
        if (speech != null) add("speech")
        if (tool != null) add("tool")
        if (clipboardSync != null) add("clipboard_sync")
        if (backup != null) add("backup")
    }

    /** emoji 表情能力声明。 */
    data class EmojiCapabilities(
        val supportsSearch: Boolean = false,
        /** 网格列数（缺省按宿主默认）。 */
        val columns: Int? = null,
        /** 单行高度 dp。 */
        val itemHeightDp: Int? = null,
    )

    /** speech 语音识别能力声明。 */
    data class SpeechCapabilities(
        val inputMode: String = "streaming",
        val supportsPartialResults: Boolean = true,
        val requiresNetwork: Boolean = true,
    )

    /** tool 工具面板能力声明。 */
    data class ToolCapabilities(
        /** 结果显示方式：DIRECT 直接上屏 / SELECT 全屏候选页面；null 宿主按结果数量兜底。 */
        val display: ToolResult? = null,
    )

    /** clipboard_sync 剪贴板同步能力声明。 */
    data class ClipboardSyncCapabilities(
        val protocols: List<String> = emptyList(),
        /**
         * 是否支持**附件**（图片 blob 传输，Phase 3 / 决策 D12）。
         *
         * 未声明的插件（含所有旧插件）恒为 false：宿主不推送图片条目、跳过拉到的图片 profile，
         * 文本同步完全不受影响——这是**优雅降级**而非静默失败。
         */
        val attachments: Boolean = false,
    )

    /** backup 备份能力声明：宿主负责备份包生成/恢复，插件只承载传输协议。 */
    data class BackupCapabilities(
        val protocols: List<String> = emptyList(),
    )
}