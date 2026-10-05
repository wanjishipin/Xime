package com.kingzcheung.xime.plugin.core.js

import com.kingzcheung.xime.plugin.core.config.NoopPluginConfigStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * 候选词变换（transform.candidates 扩展点 + transformCandidates 桥接，v3 契约）：
 * - Success：engineIndex / text 混合项解析、comment 覆盖透传
 * - NoResponse：未导出函数 / 返回 null / 空列表 / 非法项全部丢弃
 * - Failed：报错 / 格式错误
 * - 校验：非法项丢弃、总数上限截断
 */
class JsCandidateTransformTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun writePlugin(js: String): File {
        val dir = tmp.newFolder("plugin")
        File(dir, "main.js").writeText(js)
        return dir
    }

    private fun runtime(js: String): JsScriptRuntime =
        JsScriptRuntime("cand-test", writePlugin(js), "main.js", NoopPluginConfigStore)

    private fun request(
        inputText: String = "dh",
        candidates: List<CandidateTransformCandidate> = listOf(
            CandidateTransformCandidate("的", ""),
            CandidateTransformCandidate("到", ""),
        ),
    ) = CandidateTransformRequest(
        inputText = inputText,
        preedit = inputText,
        candidates = candidates,
        asciiMode = false,
    )

    @Test
    fun `engineIndex 与 text 混合解析 comment 透传`() {
        val rt = runtime(
            """
            globalThis.plugin = {
              transform: {
                candidates: function(req) {
                  return { candidates: [
                    { engineIndex: 0, comment: '改注释' },
                    { engineIndex: 1 },
                    { text: '18500000000', comment: '手机号' },
                  ] };
                }
              }
            }
            """.trimIndent()
        )
        assertTrue(rt.load())
        val outcome = rt.transformCandidates(request())
        val items = (outcome as CandidateTransformOutcome.Success).items
        assertEquals(3, items.size)
        assertEquals(0, items[0].engineIndex)
        assertEquals("改注释", items[0].comment)
        assertEquals(1, items[1].engineIndex)
        assertEquals(null, items[1].comment)
        assertEquals(null, items[2].engineIndex)
        assertEquals("18500000000", items[2].text)
        assertEquals("手机号", items[2].comment)
        rt.close()
    }

    @Test
    fun `请求字段传递 inputText 与 candidates`() {
        val rt = runtime(
            """
            globalThis.plugin = {
              transform: {
                candidates: function(req) {
                  return { candidates: [
                    { text: req.inputText + ':' + req.candidates.length + ':' + req.asciiMode },
                  ] };
                }
              }
            }
            """.trimIndent()
        )
        assertTrue(rt.load())
        val outcome = rt.transformCandidates(request(inputText = "dh"))
        val text = (outcome as CandidateTransformOutcome.Success).items[0].text
        assertEquals("dh:2:false", text)
        rt.close()
    }

    @Test
    fun `未导出函数返回 NoResponse`() {
        val rt = runtime("globalThis.plugin = { ping: function() { return 'pong'; } }")
        assertTrue(rt.load())
        assertEquals(CandidateTransformOutcome.NoResponse, rt.transformCandidates(request()))
        rt.close()
    }

    @Test
    fun `插件返回 null 表示不干预`() {
        val rt = runtime(
            """
            globalThis.plugin = {
              transform: {
                candidates: function(req) { return null; }
              }
            }
            """.trimIndent()
        )
        assertTrue(rt.load())
        assertEquals(CandidateTransformOutcome.NoResponse, rt.transformCandidates(request()))
        rt.close()
    }

    @Test
    fun `空候选列表视为不干预`() {
        val rt = runtime(
            """
            globalThis.plugin = {
              transform: {
                candidates: function(req) { return { candidates: [] }; }
              }
            }
            """.trimIndent()
        )
        assertTrue(rt.load())
        assertEquals(CandidateTransformOutcome.NoResponse, rt.transformCandidates(request()))
        rt.close()
    }

    @Test
    fun `格式错误返回 Failed`() {
        val rt = runtime(
            """
            globalThis.plugin = {
              transform: {
                candidates: function(req) {
                  return { wrong_field: 1 };
                }
              }
            }
            """.trimIndent()
        )
        assertTrue(rt.load())
        assertEquals(CandidateTransformOutcome.Failed, rt.transformCandidates(request()))
        rt.close()
    }

    @Test
    fun `非法项丢弃（无 text 的非引用项）`() {
        val rt = runtime(
            """
            globalThis.plugin = {
              transform: {
                candidates: function(req) {
                  return { candidates: [
                    { comment: '孤立注释' },
                    { text: '有效' },
                  ] };
                }
              }
            }
            """.trimIndent()
        )
        assertTrue(rt.load())
        val items = (rt.transformCandidates(request()) as CandidateTransformOutcome.Success).items
        assertEquals(1, items.size)
        assertEquals("有效", items[0].text)
        rt.close()
    }

    @Test
    fun `空 text 项丢弃`() {
        val rt = runtime(
            """
            globalThis.plugin = {
              transform: {
                candidates: function(req) {
                  return { candidates: [
                    { text: '' },
                    { text: '有效' },
                  ] };
                }
              }
            }
            """.trimIndent()
        )
        assertTrue(rt.load())
        val items = (rt.transformCandidates(request()) as CandidateTransformOutcome.Success).items
        assertEquals(1, items.size)
        rt.close()
    }

    @Test
    fun `总数上限截断到 20`() {
        val rt = runtime(
            """
            globalThis.plugin = {
              transform: {
                candidates: function(req) {
                  var out = [];
                  for (var i = 1; i <= 30; i++) { out.push({ text: 'c' + i }); }
                  return { candidates: out };
                }
              }
            }
            """.trimIndent()
        )
        assertTrue(rt.load())
        val items = (rt.transformCandidates(request()) as CandidateTransformOutcome.Success).items
        assertEquals(20, items.size)
        assertEquals("c20", items[19].text)
        rt.close()
    }

    @Test
    fun `js 报错返回 Failed`() {
        val rt = runtime(
            """
            globalThis.plugin = {
              transform: {
                candidates: function(req) { throw new Error('boom'); }
              }
            }
            """.trimIndent()
        )
        assertTrue(rt.load())
        assertEquals(CandidateTransformOutcome.Failed, rt.transformCandidates(request()))
        rt.close()
    }
}