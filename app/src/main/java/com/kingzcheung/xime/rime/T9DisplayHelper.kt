package com.kingzcheung.xime.rime

import com.kingzcheung.xime.util.PreeditMergeHelper

/** T9 模式下 UI 展示状态：包含展示文本、候选列表和 composing 标志 */
data class T9DisplayState(
    val displayText: String,
    val displayCandidates: List<String>,
    val displayComments: List<String>,
    val isComposing: Boolean,
)

/**
 * 构建 T9 模式下的 UI 展示状态。
 *
 * 三种情形：
 * 1. 无 partial commit：使用 preedit（t9_filter 转换后的拼音字母编码）；
 *    preedit 缺失**或含未转换数字**（合成切不出词时引擎回退原始输入）时
 *    显示空编码——T9 编码区无论何时都不能显示数字，字母保留由调用方传入
 * 2. RightCommit 展示态（有 partial + preedit 为空）：
 *    displayText=partialTexts 拼接，候选列表=最近一次已提交文本
 * 3. 常规：mergePartialCommitText 合并
 */
fun buildT9DisplayState(
    partialTexts: List<String>,
    preeditText: String,
    inputText: String,
    candidates: List<String>,
    comments: List<String>,
): T9DisplayState {
    if (partialTexts.isEmpty()) {
        val safePreedit = preeditText.takeUnless { preedit -> preedit.any { it.isDigit() } } ?: ""
        return T9DisplayState(
            displayText = safePreedit,
            displayCandidates = candidates,
            displayComments = comments,
            isComposing = inputText.isNotEmpty(),
        )
    }
    // RightCommit 展示态：preedit 为空（composition 已清除），input 可能为残留值
    if (preeditText.isEmpty()) {
        val last = partialTexts.last()
        return T9DisplayState(
            displayText = partialTexts.joinToString(""),
            displayCandidates = listOf(last),
            displayComments = comments.firstOrNull()?.let { listOf(it) } ?: emptyList(),
            isComposing = true,
        )
    }
    return T9DisplayState(
        displayText = PreeditMergeHelper.mergePartialCommitText(partialTexts, preeditText),
        displayCandidates = candidates,
        displayComments = comments,
        isComposing = inputText.isNotEmpty() || partialTexts.isNotEmpty(),
    )
}

/**
 * 解析 RIME 原始候选词列表中与用户选中候选词对应的 index。
 *
 * UI 候选词列表经过 filterCandidatesBySelectionHistory 过滤/重排序后，index 可能与
 * RIME 原始候选词 index 不对应。通过候选词文本查找 RIME 原始候选词的真实 index。
 *
 * @param uiIndex 原始 fallback index
 * @param selectedCandidate 用户选中的候选词文本
 * @param rawCandidates RIME 原始候选词列表（字符串文本）
 * @return 原始候选词列表中匹配的 index，未找到则返回 [uiIndex]
 */
fun resolveRimeCandidateIndex(
    uiIndex: Int,
    selectedCandidate: String?,
    rawCandidates: List<String>,
): Int {
    if (selectedCandidate == null) return uiIndex
    val rawIndex = rawCandidates.indexOf(selectedCandidate)
    return if (rawIndex >= 0) rawIndex else uiIndex
}