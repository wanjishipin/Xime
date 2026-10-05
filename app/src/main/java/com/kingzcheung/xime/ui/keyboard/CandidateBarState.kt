package com.kingzcheung.xime.ui.keyboard

import com.kingzcheung.xime.clipboard.ClipboardItem

sealed interface CandidateBarState {

    data object Idle : CandidateBarState

    data class ChineseCandidates(
        val candidates: List<String> = emptyList(),
        val comments: List<String> = emptyList(),
        val inputText: String = "",
        val preeditText: String = "",
        val hasMore: Boolean = false,
        val associationCandidates: List<String> = emptyList(),
        /** 编码显示串中的光标偏移（字符，-1 = 末尾/不适用），编码编辑光标可视化用。 */
        val preeditCaretPos: Int = -1,
    ) : CandidateBarState

    data class AssociationOnly(
        val candidates: List<String> = emptyList(),
        val comments: List<String> = emptyList(),
        val hasMore: Boolean = false,
        val highlightIndex: Int = -1,
    ) : CandidateBarState

    data class EnglishCandidates(
        val candidates: List<String> = emptyList(),
        val comments: List<String> = emptyList(),
        val pendingText: String = "",
    ) : CandidateBarState

    data class ClipboardDisplay(
        val candidates: List<String> = emptyList(),
        /**
         * 与 [candidates] 等长（图片位为条目、非图片位为 null）：候选栏据此把图片候选渲染成
         * Gboard 风格的"圆角卡片 + 圆形缩略图 + 图片"芯片。
         */
        val images: List<ClipboardItem?> = emptyList(),
    ) : CandidateBarState

    data class Calculator(
        val candidates: List<String> = emptyList(),
        val comments: List<String> = emptyList(),
        val expression: String = "",
        val result: String = "",
    ) : CandidateBarState

    companion object {
        fun from(
            candidates: List<String>,
            candidateComments: List<String>,
            inputText: String,
            preeditText: String = "",
            isComposing: Boolean,
            associationCandidates: List<String>,
            isShowingRecentClipboard: Boolean,
            hasNextPage: Boolean,
            isCalculatorActive: Boolean = false,
            preeditCaretPos: Int = -1,
            /** 剪贴板展示态下与 [candidates] 等长的图片条目列表（非图片位为 null）。 */
            clipboardImages: List<ClipboardItem?> = emptyList(),
        ): CandidateBarState {
            val hasCandidates = candidates.isNotEmpty()
            val hasAssociations = associationCandidates.isNotEmpty()
            val hasInput = inputText.isNotEmpty()
            return when {
                isShowingRecentClipboard && hasCandidates ->
                    ClipboardDisplay(candidates = candidates, images = clipboardImages)
                isCalculatorActive && hasCandidates ->
                    Calculator(candidates = candidates, comments = candidateComments)
                isComposing && (hasCandidates || hasInput) ->
                    ChineseCandidates(
                        candidates = candidates,
                        comments = candidateComments,
                        inputText = inputText,
                        preeditText = preeditText,
                        hasMore = hasCandidates && hasNextPage,
                        associationCandidates = associationCandidates,
                        preeditCaretPos = preeditCaretPos,
                    )
                !isComposing && !hasInput && hasAssociations && !hasCandidates ->
                    AssociationOnly(
                        candidates = associationCandidates,
                        hasMore = hasNextPage,
                    )
                hasCandidates || hasInput ->
                    ChineseCandidates(
                        candidates = candidates,
                        comments = candidateComments,
                        inputText = inputText,
                        preeditText = preeditText,
                        hasMore = hasCandidates && hasNextPage,
                        preeditCaretPos = preeditCaretPos,
                    )
                else -> Idle
            }
        }
    }
}
