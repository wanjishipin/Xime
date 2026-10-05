package com.kingzcheung.xime.handwriting

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import com.kingzcheung.xime.handwriting.capture.HandwritingSample
import com.kingzcheung.xime.handwriting.capture.HandwritingSampleCodec
import com.kingzcheung.xime.handwriting.capture.StrokePointMs

class HandwritingSampleCodecTest {

    private fun sample(modelTop: String? = "中") = HandwritingSample(
        target = "中",
        canvasWidthPx = 520f,
        canvasHeightPx = 380f,
        strokes = listOf(
            listOf(
                StrokePointMs(10f, 20f, 0),
                StrokePointMs(12.5f, 21f, 16),
                StrokePointMs(15f, 22f, 33),
            ),
            listOf(
                StrokePointMs(100f, 5f, 430),
                StrokePointMs(100f, 300f, 610),
            ),
        ),
        modelTop = modelTop,
        modelTopScore = if (modelTop != null) 0.97f else null,
    )

    @Test
    fun `导出JSON包含契约字段与归一化说明`() {
        val json = HandwritingSampleCodec.buildExportJson(
            samples = listOf(sample()),
            appVersion = "2.8.0-beta1",
            device = "Xiaomi 13",
            androidVersion = "Android 14 (API 34)",
            exportedAtMs = 1000L,
        )
        assertTrue(json.contains("\"format\": \"xime-handwriting-capture\""))
        assertTrue(json.contains("\"version\": 1"))
        assertTrue(json.contains("bounding box"))
        assertTrue(json.contains("\"target\": \"中\""))
        assertTrue(json.contains("\"model_top\": \"中\""))
        assertTrue(json.contains("\"sample_count\": 1"))
    }

    @Test
    fun `编码解码往返保持数据一致`() {
        val original = sample()
        val json = HandwritingSampleCodec.buildExportJson(
            listOf(original), "v", "d", "a", 0L,
        )
        val parsed = HandwritingSampleCodec.parseFromJson(json)
        assertEquals(1, parsed.size)
        val s = parsed[0]
        assertEquals(original.target, s.target)
        assertEquals(original.canvasWidthPx, s.canvasWidthPx, 0.001f)
        assertEquals(original.canvasHeightPx, s.canvasHeightPx, 0.001f)
        assertEquals(original.modelTop, s.modelTop)
        assertEquals(original.modelTopScore!!, s.modelTopScore!!, 0.0001f)
        assertEquals(2, s.strokes.size)
        assertEquals(3, s.strokes[0].size)
        assertEquals(2, s.strokes[1].size)
        for ((so, sn) in original.strokes.zip(s.strokes)) {
            assertEquals(so.size, sn.size)
            for ((po, pn) in so.zip(sn)) {
                assertEquals(po.x, pn.x, 0.001f)
                assertEquals(po.y, pn.y, 0.001f)
                assertEquals(po.t, pn.t)
            }
        }
    }

    @Test
    fun `无识别结果的样本不含model字段`() {
        val json = HandwritingSampleCodec.sampleToJson(sample(modelTop = null))
        assertTrue(!json.has("model_top"))
        assertTrue(!json.has("model_top_score"))
        val parsed = HandwritingSampleCodec.parseFromJson("{\"format\":\"xime-handwriting-capture\",\"samples\":[$json]}")
        assertNull(parsed[0].modelTop)
        assertNull(parsed[0].modelTopScore)
    }

    @Test
    fun `空笔画与空目标被过滤`() {
        val root = org.json.JSONObject()
            .put(
                "samples",
                org.json.JSONArray()
                    .put(org.json.JSONObject().put("target", "").put("strokes", org.json.JSONArray()))
                    .put(
                        org.json.JSONObject()
                            .put("target", "人")
                            .put("strokes", org.json.JSONArray().put(org.json.JSONObject().put("pts", org.json.JSONArray())))
                    )
            )
        val parsed = HandwritingSampleCodec.parseFromJson(
            root.put("format", "xime-handwriting-capture").toString()
        )
        assertTrue(parsed.isEmpty())
    }
}
