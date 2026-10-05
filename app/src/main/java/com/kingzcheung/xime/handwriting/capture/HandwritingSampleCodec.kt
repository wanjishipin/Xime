package com.kingzcheung.xime.handwriting.capture

import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.nio.charset.Charset

/**
 * HandwritingSample <-> JSON 编解码。
 *
 * JSON 结构（训练侧契约）：
 * ```
 * {
 *   "format": "xime-handwriting-capture",
 *   "version": 1,
 *   "normalization": "per-stroke-set bounding box: (x-minX)/rangeX, (y-minY)/rangeY",
 *   "meta": { "app_version", "device", "android", "exported_at_ms", "sample_count" },
 *   "samples": [
 *     {
 *       "target": "中",
 *       "canvas": { "w": 520.0, "h": 380.0 },
 *       "strokes": [ { "t0": 0,   "pts": [[x,y,dt], ...] }, { "t0": 430, ... } ],
 *       "model_top": "中" | 缺省,
 *       "model_top_score": 0.97 | 缺省
 *     }
 *   ]
 * }
 * ```
 * - x/y 为作画区局部像素坐标（原点左上，y 向下），未归一化；
 * - t0 为笔画起点相对样本首笔起点的毫秒数，pts 内第三列为相对 t0 的毫秒数；
 * - 归一化在训练侧执行，算法与推理侧 HandwritingInference.strokesToSequence 一致。
 */
object HandwritingSampleCodec {

    const val FORMAT = "xime-handwriting-capture"
    const val VERSION = 1

    fun samplesToJsonArray(samples: List<HandwritingSample>): JSONArray {
        val arr = JSONArray()
        for (sample in samples) arr.put(sampleToJson(sample))
        return arr
    }

    fun sampleToJson(sample: HandwritingSample): JSONObject {
        val obj = JSONObject()
        obj.put("target", sample.target)
        obj.put(
            "canvas",
            JSONObject().put("w", sample.canvasWidthPx.toDouble()).put("h", sample.canvasHeightPx.toDouble())
        )
        val strokes = JSONArray()
        for (stroke in sample.strokes) {
            val s = JSONObject()
            val t0 = stroke.firstOrNull()?.t ?: 0L
            s.put("t0", t0)
            val pts = JSONArray()
            for (p in stroke) {
                pts.put(JSONArray().put(p.x.toDouble()).put(p.y.toDouble()).put(p.t - t0))
            }
            s.put("pts", pts)
            strokes.put(s)
        }
        obj.put("strokes", strokes)
        sample.modelTop?.let { obj.put("model_top", it) }
        sample.modelTopScore?.let { obj.put("model_top_score", it.toDouble()) }
        return obj
    }

    fun buildExportJson(
        samples: List<HandwritingSample>,
        appVersion: String,
        device: String,
        androidVersion: String,
        exportedAtMs: Long,
    ): String {
        val root = JSONObject()
        root.put("format", FORMAT)
        root.put("version", VERSION)
        root.put(
            "normalization",
            "per-sample bounding box: (x-minX)/max(rangeX,1), (y-minY)/max(rangeY,1); " +
                "identical to HandwritingInference.strokesToSequence"
        )
        root.put(
            "meta",
            JSONObject()
                .put("app_version", appVersion)
                .put("device", device)
                .put("android", androidVersion)
                .put("exported_at_ms", exportedAtMs)
                .put("sample_count", samples.size)
        )
        root.put("samples", samplesToJsonArray(samples))
        return root.toString(2)
    }

    fun parseFromJson(text: String): List<HandwritingSample> {
        val root = JSONObject(text)
        if (root.optString("format") != FORMAT) return emptyList()
        val arr = root.optJSONArray("samples") ?: return emptyList()
        val out = mutableListOf<HandwritingSample>()
        for (i in 0 until arr.length()) {
            val s = arr.optJSONObject(i) ?: continue
            val target = s.optString("target")
            if (target.isEmpty()) continue
            val canvas = s.optJSONObject("canvas")
            val w = canvas?.optDouble("w")?.toFloat() ?: 0f
            val h = canvas?.optDouble("h")?.toFloat() ?: 0f
            val modelTop = s.optString("model_top").ifEmpty { null }
            val modelTopScore = if (s.has("model_top_score")) s.optDouble("model_top_score").toFloat() else null
            val strokes = mutableListOf<List<StrokePointMs>>()
            val sArr = s.optJSONArray("strokes") ?: JSONArray()
            for (j in 0 until sArr.length()) {
                val st = sArr.optJSONObject(j) ?: continue
                val t0 = st.optLong("t0")
                val ptsArr = st.optJSONArray("pts") ?: JSONArray()
                val pts = mutableListOf<StrokePointMs>()
                for (k in 0 until ptsArr.length()) {
                    val p = ptsArr.optJSONArray(k) ?: continue
                    if (p.length() < 3) continue
                    pts.add(
                        StrokePointMs(
                            x = p.optDouble(0).toFloat(),
                            y = p.optDouble(1).toFloat(),
                            t = t0 + p.optLong(2),
                        )
                    )
                }
                if (pts.isNotEmpty()) strokes.add(pts)
            }
            if (strokes.isNotEmpty()) {
                out.add(HandwritingSample(target, w, h, strokes, modelTop, modelTopScore))
            }
        }
        return out
    }
}
