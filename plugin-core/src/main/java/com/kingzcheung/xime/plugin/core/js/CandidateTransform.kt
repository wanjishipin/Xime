package com.kingzcheung.xime.plugin.core.js

/**
 * 候选词变换（capabilities.candidate_transform）的请求/响应模型。
 *
 * 交互语义：PULL + SYNC（宿主在 key-processing 线程同步调用插件 transformCandidates，
 * 硬超时 15ms，超时/报错回退原始候选；敏感输入框不产生请求）。
 * 这是首个接入输入主流程（hotPath）的能力，见 docs/plugin-capability-registry.md §5。
 */

/** 引擎候选快照（与宿主 app 层 RimeCandidate 解耦的最小结构）。 */
data class CandidateTransformCandidate(
    val text: String,
    val comment: String,
)

/** 变换请求：当前编码与引擎候选快照。 */
data class CandidateTransformRequest(
    /** 原始键入串（无分隔符）。 */
    val inputText: String,
    /** 引擎回显（带分隔符，如 ni'hao），可能为空。 */
    val preedit: String,
    /** 当前引擎候选（ascii 过滤前的原始数组，宿主在非 ascii + 非 T9 场景才发起请求）。 */
    val candidates: List<CandidateTransformCandidate>,
    val asciiMode: Boolean,
)

/** 插件响应中的单个候选项：引用引擎候选（engineIndex）或插件自有文本（text）二选一。 */
data class CandidateTransformItem(
    /** 引用引擎候选的 0 基索引；null 表示插件自有候选。 */
    val engineIndex: Int?,
    /** 插件候选的上屏文本（engineIndex == null 时有效）。 */
    val text: String?,
    /** 可选注释；engineIndex 项用于覆盖显示注释。 */
    val comment: String?,
)

/** 变换调用结果。 */
sealed interface CandidateTransformOutcome {
    /** 插件不干预（未导出函数 / 返回 nil / 返回空列表）。 */
    data object NoResponse : CandidateTransformOutcome

    /** 调用失败（超时 / Lua 报错 / 响应格式错误）：宿主回退原始候选；调用方累计失败次数熔断。 */
    data object Failed : CandidateTransformOutcome

    /** 变换成功（结构解析通过；语义校验——越界/重复 engineIndex——由宿主映射层处理）。 */
    data class Success(val items: List<CandidateTransformItem>) : CandidateTransformOutcome
}
