package com.kingzcheung.xime.service

import com.kingzcheung.xime.plugin.core.js.CandidateTransformItem
import com.kingzcheung.xime.rime.RimeCandidate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 候选词变换映射（CandidateTransformCoordinator.buildDisplay 纯函数）：
 * - 引用项（engine_index）→ 平行 CandidateAction.engine + comment 覆盖
 * - 插件项（text）→ CandidateAction.plugin，点击直接上屏
 * - 语义校验：越界/重复 engine_index 丢弃、空 text 丢弃、总数上限截断
 * - 空结果 → null（不干预）
 */
class CandidateTransformCoordinatorTest {

    private val engine = listOf(
        RimeCandidate("你好", "nǐ hǎo"),
        RimeCandidate("你好吗", ""),
        RimeCandidate("拟好", "nǐ hǎo"),
    )

    @Test
    fun `引用项映射引擎候选并保留平行动作`() {
        val result = CandidateTransformCoordinator.buildDisplay(
            items = listOf(
                CandidateTransformItem(engineIndex = 2, text = null, comment = null),
                CandidateTransformItem(engineIndex = 0, text = null, comment = null),
            ),
            engineCandidates = engine,
        )!!
        assertEquals(listOf("拟好", "你好"), result.candidates.map { it.text })
        assertEquals(listOf(2, 0), result.actions.map { it.engineIndex })
        // 引用项仍显示引擎注释
        assertEquals("nǐ hǎo", result.candidates[0].comment)
        assertFalse(result.actions[0].isPluginCandidate)
    }

    @Test
    fun `引用项 comment 覆盖仅影响显示`() {
        val result = CandidateTransformCoordinator.buildDisplay(
            items = listOf(CandidateTransformItem(engineIndex = 0, text = null, comment = "覆盖注释")),
            engineCandidates = engine,
        )!!
        assertEquals("覆盖注释", result.candidates[0].comment)
        // commitText 不用于引擎引用
        assertEquals("", result.actions[0].commitText)
    }

    @Test
    fun `插件候选映射为直接上屏动作`() {
        val result = CandidateTransformCoordinator.buildDisplay(
            items = listOf(CandidateTransformItem(engineIndex = null, text = "18500000000", comment = "手机号")),
            engineCandidates = engine,
        )!!
        assertEquals("18500000000", result.candidates[0].text)
        assertEquals("手机号", result.candidates[0].comment)
        val action = result.actions[0]
        assertTrue(action.isPluginCandidate)
        assertEquals(-1, action.engineIndex)
        assertEquals("18500000000", action.commitText)
    }

    @Test
    fun `混合列表保持插件给定的顺序`() {
        val result = CandidateTransformCoordinator.buildDisplay(
            items = listOf(
                CandidateTransformItem(engineIndex = 0, text = null, comment = null),
                CandidateTransformItem(engineIndex = null, text = "快捷短语", comment = null),
                CandidateTransformItem(engineIndex = 1, text = null, comment = null),
            ),
            engineCandidates = engine,
        )!!
        assertEquals(listOf("你好", "快捷短语", "你好吗"), result.candidates.map { it.text })
        assertEquals(listOf(0, -1, 1), result.actions.map { it.engineIndex })
    }

    @Test
    fun `越界与重复 engine_index 丢弃`() {
        val result = CandidateTransformCoordinator.buildDisplay(
            items = listOf(
                CandidateTransformItem(engineIndex = 3, text = null, comment = null),
                CandidateTransformItem(engineIndex = -1, text = null, comment = null),
                CandidateTransformItem(engineIndex = 0, text = null, comment = null),
                CandidateTransformItem(engineIndex = 0, text = null, comment = null),
            ),
            engineCandidates = engine,
        )!!
        assertEquals(1, result.candidates.size)
        assertEquals(0, result.actions[0].engineIndex)
    }

    @Test
    fun `空 text 与全部非法项`() {
        val result = CandidateTransformCoordinator.buildDisplay(
            items = listOf(
                CandidateTransformItem(engineIndex = null, text = "", comment = null),
                CandidateTransformItem(engineIndex = null, text = null, comment = "x"),
                CandidateTransformItem(engineIndex = 99, text = null, comment = null),
            ),
            engineCandidates = engine,
        )
        assertNull(result)
    }

    @Test
    fun `总数上限截断`() {
        val items = (0 until 30).map { CandidateTransformItem(engineIndex = null, text = "c$it", comment = null) }
        val result = CandidateTransformCoordinator.buildDisplay(items = items, engineCandidates = engine, maxCandidates = 20)!!
        assertEquals(20, result.candidates.size)
        assertEquals(20, result.actions.size)
        assertEquals("c19", result.candidates[19].text)
    }

    @Test
    fun `空响应返回 null 不干预`() {
        assertNull(
            CandidateTransformCoordinator.buildDisplay(items = emptyList(), engineCandidates = engine)
        )
    }
}
