package com.kingzcheung.xime.handwriting

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 叠写识别器单测：注入假单字模型（按段内笔画编号/段长返回可控结果），
 * 验证 DP 切分逻辑、部件成字纠正、缓存复用与超限防御。
 *
 * 笔画编码约定：第 idx 笔的两个点 y 坐标均为 idx（假模型据此识别"笔画起点"）。
 */
class OverlappedHandwritingRecognizerTest {

    private fun strokesOf(count: Int): List<List<Pair<Float, Float>>> =
        (0 until count).map { idx ->
            listOf(Pair(0f, idx.toFloat()), Pair(1f, idx.toFloat()))
        }

    private fun cand(ch: String, score: Float) = listOf(HandwritingCandidate(ch, score))

    @Test
    fun `单字未分裂时切为一段`() {
        // 假模型：任意段都识别为"张"（单段分数最高，无更优切分）
        val recognizer = OverlappedHandwritingRecognizer()
        val result = recognizer.recognize(strokesOf(5), predictFn = { _, _ -> cand("张", 0.95f) })
        assertEquals(1, result.segments.size)
        assertEquals("张", result.segments[0].candidates[0].char)
    }

    @Test
    fun `两字连写切成两段`() {
        // 前 5 笔识别"你"，后 5 笔识别"好"，其余段（含 10 笔合并段）识别不出
        val recognizer = OverlappedHandwritingRecognizer()
        val fake: (List<List<Pair<Float, Float>>>, Int) -> List<HandwritingCandidate> = { seg, _ ->
            val start = seg[0][0].second.toInt()
            val size = seg.size
            when {
                start == 0 && size == 5 -> cand("你", 0.9f)
                start == 5 && size == 5 -> cand("好", 0.85f)
                else -> emptyList()
            }
        }
        val result = recognizer.recognize(strokesOf(10), predictFn = fake)
        assertEquals(2, result.segments.size)
        assertEquals("你", result.segments[0].candidates[0].char)
        assertEquals("好", result.segments[1].candidates[0].char)
    }

    @Test
    fun `部件成字写完后切回单段`() {
        // 模拟"张" = 弓(3笔) + 长(4笔)：写到 3 笔时[弓]成段；
        // 7 笔写完时合并段"张"分数更高，均分比较应切回单段
        val recognizer = OverlappedHandwritingRecognizer()
        val fake: (List<List<Pair<Float, Float>>>, Int) -> List<HandwritingCandidate> = { seg, _ ->
            when (seg.size) {
                3 -> cand("弓", 0.9f)
                4 -> cand("卜", 0.6f)
                7 -> cand("张", 0.95f)
                else -> emptyList()
            }
        }
        // 3 笔：单段"弓"
        val partial = recognizer.recognize(strokesOf(3), predictFn = fake)
        assertEquals(1, partial.segments.size)
        assertEquals("弓", partial.segments[0].candidates[0].char)
        // 7 笔：写完，合并段"张"（0.95/1）应胜过 [弓][卜]（(0.9+0.6)/2-0.02）
        val done = recognizer.recognize(strokesOf(7), predictFn = fake)
        assertEquals(1, done.segments.size)
        assertEquals("张", done.segments[0].candidates[0].char)
    }

    @Test
    fun `候选栏取当前字多候选`() {
        // 最后一段返回多候选（按分数降序）
        val recognizer = OverlappedHandwritingRecognizer()
        val fake: (List<List<Pair<Float, Float>>>, Int) -> List<HandwritingCandidate> = { seg, _ ->
            if (seg.size == 4) listOf(
                HandwritingCandidate("张", 0.95f),
                HandwritingCandidate("账", 0.8f),
            ) else emptyList()
        }
        val result = recognizer.recognize(strokesOf(4), predictFn = fake)
        assertEquals(1, result.segments.size)
        assertEquals(2, result.segments[0].candidates.size)
        assertEquals("张", result.segments[0].candidates[0].char)
        assertEquals("账", result.segments[0].candidates[1].char)
    }

    @Test
    fun `段缓存跨笔画复用`() {
        // 尾部追加 3 笔后重新识别：新增推理只发生在含新笔画的段
        // （i=8,9,10 的 k=1 轮 27 段 + 可行前缀段的少量 k=2 尾段），
        // 远小于全量重算的段数；且相同输入再次识别时零新增推理。
        var calls = 0
        val fake: (List<List<Pair<Float, Float>>>, Int) -> List<HandwritingCandidate> = { seg, _ ->
            calls++
            if (seg.size == 5 && seg[0][0].second.toInt() == 0) cand("你", 0.9f) else emptyList()
        }
        val recognizer = OverlappedHandwritingRecognizer()
        recognizer.recognize(strokesOf(7), predictFn = fake)
        val afterFirst = calls
        recognizer.recognize(strokesOf(10), predictFn = fake)
        // 全量重算 10 笔约 60 段（k=1 轮 55 段 + k=2 尾段），带缓存增量应远小于它
        assertTrue("缓存未生效：新增推理 ${calls - afterFirst} 段", calls - afterFirst <= 30)
        val afterSecond = calls
        recognizer.recognize(strokesOf(10), predictFn = fake)
        assertEquals("相同输入不应产生新推理", afterSecond, calls)
    }

    @Test
    fun `笔画超限自动裁剪尾部`() {
        val recognizer = OverlappedHandwritingRecognizer()
        val result = recognizer.recognize(strokesOf(101), predictFn = { _, _ -> cand("字", 0.9f) })
        assertTrue(result.segments.isNotEmpty())
        assertTrue(result.segments.size <= OverlappedHandwritingRecognizer.DEFAULT_MAX_SEGMENTS)
    }

    @Test
    fun `停顿间隔强化切分`() {
        // 两段均分 0.9-0.02=0.88 与单段 0.9 打平偏弱：
        // 大停顿(+0.15) → 两段胜出；连笔(-0.08) → 单段胜出
        val recognizer = OverlappedHandwritingRecognizer()
        val fake: (List<List<Pair<Float, Float>>>, Int) -> List<HandwritingCandidate> = { seg, _ ->
            cand(if (seg.size == 1) "一" else "双", 0.9f)
        }
        val strokes = strokesOf(2)
        val split = recognizer.recognize(strokes, listOf(0L, 600L), fake)
        assertEquals(2, split.segments.size)
        val joined = recognizer.recognize(strokes, listOf(0L, 80L), fake)
        assertEquals(1, joined.segments.size)
    }

    @Test
    fun `慢写复杂字匀速笔迹不拆分`() {
        // 模拟 17 笔复杂字匀速慢写（每笔间隔 450ms，无显著停顿边界）：
        // 部件都是高分简单字（DP 多段均分高），但相对阈值检测无显著停顿
        // → 强制单字（合并段"赢"分数尚可），输入框只显示 1 个字
        val recognizer = OverlappedHandwritingRecognizer()
        val fake: (List<List<Pair<Float, Float>>>, Int) -> List<HandwritingCandidate> = { seg, _ ->
            when (seg.size) {
                17 -> cand("赢", 0.6f)
                else -> cand("部", 0.85f)
            }
        }
        val gaps = List(17) { if (it == 0) 0L else 450L }
        val result = recognizer.recognize(strokesOf(17), gaps, fake)
        assertEquals(1, result.segments.size)
        assertEquals("赢", result.segments[0].candidates[0].char)
    }

    @Test
    fun `慢写多字词的长停顿边界仍被检出`() {
        // 慢写者（字内 400ms）写完"你"后停 1.2s 再写"好"：
        // 1.2s 显著高于中位数（×2=800ms）→ 边界有效 → 多段
        val recognizer = OverlappedHandwritingRecognizer()
        val fake: (List<List<Pair<Float, Float>>>, Int) -> List<HandwritingCandidate> = { seg, _ ->
            when {
                seg.size == 5 && seg[0][0].second.toInt() == 0 -> cand("你", 0.9f)
                seg.size == 5 && seg[0][0].second.toInt() == 5 -> cand("好", 0.85f)
                else -> emptyList()
            }
        }
        val gaps = List(10) { idx ->
            when {
                idx == 0 -> 0L
                idx == 5 -> 1200L
                else -> 400L
            }
        }
        val result = recognizer.recognize(strokesOf(10), gaps, fake)
        assertEquals(2, result.segments.size)
    }

    @Test
    fun `连笔单字中途不被拆分`() {
        // 模拟"在"6 笔连写（笔间 80ms）：中途多段（每笔都是高分简单字）
        // 被连笔减分压制，写完 6 笔后合并段"在"应胜出
        val recognizer = OverlappedHandwritingRecognizer()
        val fake: (List<List<Pair<Float, Float>>>, Int) -> List<HandwritingCandidate> = { seg, _ ->
            when (seg.size) {
                1 -> cand("一", 0.9f)
                6 -> cand("在", 0.95f)
                else -> cand("部", 0.7f)
            }
        }
        val gaps = List(6) { if (it == 0) 0L else 80L }
        val result = recognizer.recognize(strokesOf(6), gaps, fake)
        assertEquals(1, result.segments.size)
        assertEquals("在", result.segments[0].candidates[0].char)
    }

    @Test
    fun `连笔无停顿时单字中途强制按单字处理`() {
        // 模拟"在"写到 2 笔：DP 多段 [一][一]（0.88）胜过合并段"彐"（0.5），
        // 但无停顿 + 合并段分数尚可（0.5 ≥ 0.35）→ 强制单字，输入框只显示 1 个字
        val recognizer = OverlappedHandwritingRecognizer()
        val fake: (List<List<Pair<Float, Float>>>, Int) -> List<HandwritingCandidate> = { seg, _ ->
            when (seg.size) {
                1 -> cand("一", 0.9f)
                2 -> cand("彐", 0.5f)
                else -> emptyList()
            }
        }
        val result = recognizer.recognize(strokesOf(2), listOf(0L, 80L), fake)
        assertEquals(1, result.segments.size)
        assertEquals("彐", result.segments[0].candidates[0].char)
    }

    @Test
    fun `有换字停顿时尊重多段切分`() {
        // 同上假模型，但段边界有 600ms 停顿（真换字）→ 不强制单字，多段上屏
        val recognizer = OverlappedHandwritingRecognizer()
        val fake: (List<List<Pair<Float, Float>>>, Int) -> List<HandwritingCandidate> = { seg, _ ->
            when (seg.size) {
                1 -> cand("一", 0.9f)
                2 -> cand("彐", 0.5f)
                else -> emptyList()
            }
        }
        val result = recognizer.recognize(strokesOf(2), listOf(0L, 600L), fake)
        assertEquals(2, result.segments.size)
    }

    @Test
    fun `连笔写多字词时合并分数极低仍切多段`() {
        // 连笔写"你好"：合并段（跨字笔迹）分数极低（0.2 < 0.35）→ 尊重 DP 多段
        val recognizer = OverlappedHandwritingRecognizer()
        val fake: (List<List<Pair<Float, Float>>>, Int) -> List<HandwritingCandidate> = { seg, _ ->
            when {
                seg.size == 5 && seg[0][0].second.toInt() == 0 -> cand("你", 0.9f)
                seg.size == 5 && seg[0][0].second.toInt() == 5 -> cand("好", 0.85f)
                seg.size == 10 -> cand("伪", 0.2f)
                else -> emptyList()
            }
        }
        val gaps = List(10) { if (it == 0) 0L else 80L }
        val result = recognizer.recognize(strokesOf(10), gaps, fake)
        assertEquals(2, result.segments.size)
        assertEquals("你", result.segments[0].candidates[0].char)
        assertEquals("好", result.segments[1].candidates[0].char)
    }

    @Test
    fun `空输入返回空结果`() {
        val recognizer = OverlappedHandwritingRecognizer()
        val result = recognizer.recognize(emptyList()) { _, _ -> cand("字", 0.9f) }
        assertTrue(result.segments.isEmpty())
    }

    @Test
    fun `reset后缓存失效重新推理`() {
        var calls = 0
        val fake: (List<List<Pair<Float, Float>>>, Int) -> List<HandwritingCandidate> = { _, _ ->
            calls++
            cand("字", 0.9f)
        }
        val recognizer = OverlappedHandwritingRecognizer()
        recognizer.recognize(strokesOf(3), predictFn = fake)
        val before = calls
        recognizer.recognize(strokesOf(3), predictFn = fake)
        assertEquals(before, calls)
        recognizer.reset()
        recognizer.recognize(strokesOf(3), predictFn = fake)
        assertTrue(calls > before)
    }
}
