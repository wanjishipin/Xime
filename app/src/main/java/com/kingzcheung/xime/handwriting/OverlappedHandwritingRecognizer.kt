package com.kingzcheung.xime.handwriting

/**
 * 叠写（连写）识别引擎：在单字识别模型之上实现多字连笔切分。
 *
 * 原理：过分割 + 动态规划组合打分。把笔画序列按所有可能的切分点切段，
 * 每段送单字模型识别，DP 求全局最优切分。段间比较用"段均分 + 切分惩罚"，
 * 解决部件成字问题（如"张" = 弓 + 长，两者都是独立汉字）：
 * 未写完时即使被切成 [弓][卜]，写完后合并段"张"的分数更高，均分比较
 * 会切回单段，上屏内容随之替换纠正。
 *
 * 缓存约定：笔画序列只允许尾部追加（前缀不变），段推理结果跨笔画复用；
 * 笔画缓冲被裁剪/清空时必须调用 [reset] 使缓存失效。
 *
 * 推理成本：每追加一笔，仅新增 ≤ [maxStrokesPerSegment] 次单字 ONNX 推理
 * （其余段全部命中缓存），后台线程调用即可。
 */
class OverlappedHandwritingRecognizer(
    /** 活动笔画最多同时叠写的字数，超过后由调用方固化最早的字。 */
    private val maxSegments: Int = DEFAULT_MAX_SEGMENTS,
    /** 单个字允许的最大笔画数（超出视为不可识别为单字）。 */
    private val maxStrokesPerSegment: Int = DEFAULT_MAX_STROKES_PER_SEGMENT,
) {
    /**
     * 单个切分段（一个字）。
     *
     * @param startStroke 段起始笔画索引（0-based，相对本次 recognize 输入）。
     * @param strokeCount 段内笔画数。
     * @param candidates 候选字列表（降序，非空）。
     */
    data class Segment(
        val startStroke: Int,
        val strokeCount: Int,
        val candidates: List<HandwritingCandidate>,
    )

    /**
     * 识别结果。
     *
     * @param segments 最优切分（时间序）：最后一段为"当前正在写的字"，
     *                 前面的段已可固化上屏。段候选非空。
     * @param totalScore 最优切分的识别分总和。
     * @param avgScore 段均分（切分比较依据）。
     */
    data class Result(
        val segments: List<Segment>,
        val totalScore: Float,
        val avgScore: Float,
    )

    private val segCache = HashMap<Long, List<HandwritingCandidate>>()

    /**
     * 对笔画序列做叠写识别。
     *
     * @param strokes 笔画序列（时间序，前缀不可变；超长时自动裁剪并清缓存）。
     * @param gaps 笔间时间间隔（毫秒）：gaps[j] = 第 j 笔起笔与第 j-1 笔收笔的
     *             间隔（gaps[0] 恒为 0）。分割信号：停顿处倾向切分、连笔处压制切分。
     *             与 strokes 长度一致或为空（视为无间隔信息）。
     * @param predictFn 单字识别函数，生产环境用 [HandwritingEngine.predict]；
     *                  测试注入假模型。
     */
    fun recognize(
        strokes: List<List<Pair<Float, Float>>>,
        gaps: List<Long> = emptyList(),
        predictFn: (List<List<Pair<Float, Float>>>, Int) -> List<HandwritingCandidate> =
            { s, k -> HandwritingEngine.predict(s, k) }
    ): Result {
        val bounded = if (strokes.size > maxSegments * maxStrokesPerSegment) {
            reset()
            strokes.takeLast(maxSegments * maxStrokesPerSegment)
        } else {
            strokes
        }
        val n = bounded.size
        if (n == 0) return Result(emptyList(), 0f, 0f)

        val neg = Float.NEGATIVE_INFINITY
        // best[k][i]：前 i 笔切成 k 段的最大识别分总和（含时间间隔偏置）
        val best = Array(maxSegments + 1) { FloatArray(n + 1) { neg } }
        val back = Array(maxSegments + 1) { IntArray(n + 1) { -1 } }
        best[0][0] = 0f

        for (k in 1..maxSegments) {
            for (i in 1..n) {
                val minJ = (i - maxStrokesPerSegment).coerceAtLeast(0)
                for (j in minJ until i) {
                    val prev = best[k - 1][j]
                    if (prev == neg) continue
                    val candidates = segmentCandidates(j, i, bounded, predictFn)
                    if (candidates.isEmpty()) continue
                    // 时间偏置：j>0 时新段起笔与上一段收笔的间隔影响切分倾向
                    val gapBias = if (j > 0 && j < gaps.size) gapBias(gaps[j]) else 0f
                    val score = prev + candidates[0].score + gapBias
                    if (best[k][i] == neg || score > best[k][i]) {
                        best[k][i] = score
                        back[k][i] = j
                    }
                }
            }
        }

        // 段间比较：段均分 - 每多切一段的惩罚。均分避免"两段低分字之和
        // 虚高压过单段高分字"；惩罚避免"多一刀白赚分数"。
        var bestK = 0
        var bestNorm = neg
        for (k in 1..maxSegments) {
            val total = best[k][n]
            if (total == neg) continue
            val norm = total / k - SEGMENT_PENALTY * (k - 1)
            if (norm > bestNorm) {
                bestNorm = norm
                bestK = k
            }
        }
        if (bestK == 0) return Result(emptyList(), 0f, 0f)

        // "一个字"先验：DP 选出多段时，必须存在"显著换字停顿"才信任切分。
        // 显著性用相对阈值判定——边界间隔显著高于窗口内笔间间隔中位数
        // （慢写者字内间隔本身高，绝对阈值会把匀速书写的复杂字拆开；
        //   快写者字间间隔虽小但相对突出，仍能正确检出）。
        // 无显著停顿时按单字处理（合并段识别），输入框始终只显示一个字；
        // 连笔写多字词时合并段分数必然极低（跨字笔迹不成字），仍尊重 DP 切分。
        if (bestK >= 2 && !hasPauseBoundary(gaps)) {
            val merged = segmentCandidates(0, n, bounded, predictFn)
            val mergedScore = merged.firstOrNull()?.score ?: 0f
            if (mergedScore >= MERGED_CHAR_MIN_SCORE) {
                return Result(listOf(Segment(0, n, merged)), mergedScore, mergedScore)
            }
        }

        val segments = mutableListOf<Segment>()
        var i = n
        for (k in bestK downTo 1) {
            val j = back[k][i]
            segments.add(0, Segment(j, i - j, segmentCandidates(j, i, bounded, predictFn)))
            i = j
        }
        val total = best[bestK][n]
        return Result(segments, total, total / bestK)
    }

    /** 笔画缓冲被裁剪或清空时调用，使段缓存失效。 */
    fun reset() {
        segCache.clear()
    }

    /**
     * 窗口滑窗：头部裁剪 k 笔后调用。段缓存 key 平移（(j,i)→(j-k,i-k)），
     * 被裁笔画之外的段全部复用，无需重新推理。
     */
    fun onStrokesTrimmed(k: Int) {
        if (k <= 0) return
        val shifted = HashMap<Long, List<HandwritingCandidate>>()
        for ((key, candidates) in segCache) {
            val j = (key / 1000L).toInt()
            val i = (key % 1000L).toInt()
            if (i > k) shifted[(j - k).toLong() * 1000L + (i - k)] = candidates
        }
        segCache.clear()
        segCache.putAll(shifted)
    }

    private fun segmentCandidates(
        j: Int,
        i: Int,
        strokes: List<List<Pair<Float, Float>>>,
        predictFn: (List<List<Pair<Float, Float>>>, Int) -> List<HandwritingCandidate>
    ): List<HandwritingCandidate> {
        val key = j.toLong() * 1000L + i
        segCache[key]?.let { return it }
        val segment = strokes.subList(j, i).map { stroke ->
            stroke.map { Pair(it.first, it.second) }
        }
        val candidates = predictFn(segment, SEGMENT_TOP_K).take(SEGMENT_TOP_K)
        segCache[key] = candidates
        return candidates
    }

    /**
     * 切分点时间偏置：笔间停顿是分字的最强信号——
     * 明显停顿（≥500ms）倾向在此切分（加分）；
     * 连笔（≤150ms）压制切分（减分），避免单字中途被拆出部件字。
     */
    private fun gapBias(gapMs: Long): Float = when {
        gapMs >= GAP_SPLIT_MS -> GAP_SPLIT_BONUS
        gapMs <= GAP_JOIN_MS -> GAP_JOIN_MALUS
        else -> 0f
    }

    /**
     * 显著换字停顿检测（相对阈值）：
     * - 窗口内笔间间隔样本少（<3 个）时用绝对阈值 [GAP_SPLIT_MS]（无中位数参照）；
     * - 否则边界间隔 ≥ max(中位数×2, [GAP_PAUSE_MIN_MS]) 才算换字。
     * 匀速慢写的复杂字（字内间隔均匀偏高）无显著边界 → 判定单字；
     * 快写多字词（字间间隔相对突出）→ 正确检出边界。
     */
    private fun hasPauseBoundary(gaps: List<Long>): Boolean {
        val inner = gaps.drop(1)
        if (inner.isEmpty()) return false
        val threshold = if (inner.size < 3) {
            GAP_SPLIT_MS
        } else {
            val median = inner.sorted()[inner.size / 2]
            maxOf(median * 2, GAP_PAUSE_MIN_MS)
        }
        return inner.any { it >= threshold }
    }

    companion object {
        /**
         * 活动笔画最多同时叠写的字数。
         * 5 段上限保证单字书写中途的临时多段切分（如"在"写到 3 笔时的
         * [一][丿][丨]）不会被误当作多字而触发固化——写完后合并段分数
         * 更高会自动切回单段替换。
         */
        const val DEFAULT_MAX_SEGMENTS = 5
        const val DEFAULT_MAX_STROKES_PER_SEGMENT = 20
        private const val SEGMENT_PENALTY = 0.02f
        private const val SEGMENT_TOP_K = 8

        /** 笔间停顿达到此值视为"换字"切分点（加分）。 */
        const val GAP_SPLIT_MS = 500L
        /** 笔间间隔小于此值视为连笔（压制切分）。 */
        const val GAP_JOIN_MS = 150L
        private const val GAP_SPLIT_BONUS = 0.15f
        private const val GAP_JOIN_MALUS = -0.08f

        /**
         * 合并段（全窗口单字识别）分数达到此值视为"笔迹整体像一个字"：
         * 无显著停顿时强制按单字处理（上屏 1 个字），防止单字中途被拆成多字上屏。
         */
        private const val MERGED_CHAR_MIN_SCORE = 0.35f

        /** 相对停顿检测的绝对下限（ms）：中位数极低（极快连写）时防止阈值过低。 */
        private const val GAP_PAUSE_MIN_MS = 250L
    }
}
