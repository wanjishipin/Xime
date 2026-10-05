package com.kingzcheung.xime.service

/**
 * 候选词变换后单个显示候选的上屏动作（与 CandidateState.candidates 平行）。
 *
 * - [engineIndex] >= 0：引擎候选引用（插件可能仅覆盖了显示注释），点击走引擎选词
 * - [engineIndex] < 0：插件自有候选，点击直接上屏 [commitText]（绕过引擎，所见即所得）
 *
 * 空列表 = 纯引擎语义（显示 index 即引擎 index），所有既有路径零影响。
 */
data class CandidateAction(
    val engineIndex: Int,
    val commitText: String,
) {
    val isPluginCandidate: Boolean get() = engineIndex < 0

    companion object {
        fun engine(index: Int) = CandidateAction(engineIndex = index, commitText = "")
        fun plugin(text: String) = CandidateAction(engineIndex = -1, commitText = text)
    }
}
