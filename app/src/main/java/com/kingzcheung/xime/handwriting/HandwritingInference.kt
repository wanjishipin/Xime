package com.kingzcheung.xime.handwriting

import android.util.Log
import com.kingzcheung.xime.util.FileLogger
import org.json.JSONObject
import java.io.File
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 手写完整推理管线，运行于 :inference 进程（仅由 [com.kingzcheung.xime.service.InferenceService] 调用）。
 *
 * 主进程客户端（[HandwritingEngine]）只传原始笔画、收最终候选字；
 * 笔画预处理、ONNX 推理与 idx→汉字映射全部在本对象内完成，
 * 保证输入法主进程不加载 ONNX 运行库、不常驻词表。
 */
object HandwritingInference {
    private const val TAG = "HandwritingInference"
    private const val FIXED_LEN = 200
    private const val MAX_POINTS_PER_STROKE = 8

    private var chars: List<String> = emptyList()

    /** 加载 idx→汉字词表（char_index.json，路径由 loadModel 的 extraPath 传入）。 */
    fun loadCharIndex(charIndexPath: String): Boolean {
        return try {
            val text = File(charIndexPath).readText().trimStart('\uFEFF')
            val json = JSONObject(text)

            val extracted = mutableListOf<String>()

            if (json.has("chars")) {
                val arr = json.getJSONArray("chars")
                for (i in 0 until arr.length()) {
                    extracted.add(arr.getString(i))
                }
            } else if (json.has("char_index")) {
                val obj = json.getJSONObject("char_index")
                val keys = obj.keys()
                val indexed = mutableMapOf<Int, String>()
                while (keys.hasNext()) {
                    val ch = keys.next()
                    indexed[obj.getInt(ch)] = ch
                }
                val maxIdx = indexed.keys.maxOrNull() ?: 0
                extracted.addAll(Array(maxIdx + 1) { "" }.toList())
                for ((idx, ch) in indexed) {
                    extracted[idx] = ch
                }
            } else if (json.has("labels")) {
                val arr = json.getJSONArray("labels")
                for (i in 0 until arr.length()) {
                    extracted.add(arr.optString(i, ""))
                }
            } else {
                val keys = json.keys()
                val indexed = mutableMapOf<Int, String>()
                while (keys.hasNext()) {
                    val key = keys.next()
                    indexed[json.getInt(key)] = key
                }
                val maxIdx = indexed.keys.maxOrNull() ?: 0
                extracted.addAll(Array(maxIdx + 1) { "" }.toList())
                for ((idx, ch) in indexed) {
                    extracted[idx] = ch
                }
            }

            chars = extracted.toList()
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load char_index: ${e.message}", e)
            FileLogger.e(TAG, "Failed to load char_index: ${e.message}", e)
            false
        }
    }

    fun clearChars() {
        chars = emptyList()
    }

    /** AIDL 原始笔画解码：points = [x0,y0,x1,y1,...]，strokePointCounts = 每笔画点数。 */
    internal fun decodeStrokes(
        points: FloatArray,
        strokePointCounts: IntArray
    ): List<List<Pair<Float, Float>>> {
        val strokes = mutableListOf<List<Pair<Float, Float>>>()
        var offset = 0
        for (count in strokePointCounts) {
            val stroke = mutableListOf<Pair<Float, Float>>()
            repeat(count) {
                if (offset + 1 < points.size) {
                    stroke.add(points[offset] to points[offset + 1])
                }
                offset += 2
            }
            strokes.add(stroke)
        }
        return strokes
    }

    internal fun simplifyStrokes(
        strokes: List<List<Pair<Float, Float>>>
    ): List<List<Pair<Float, Float>>> {
        val simplified = mutableListOf<List<Pair<Float, Float>>>()
        for (stroke in strokes) {
            if (stroke.size <= MAX_POINTS_PER_STROKE) {
                simplified.add(stroke)
            } else {
                val step = (stroke.size - 1).toFloat() / (MAX_POINTS_PER_STROKE - 1)
                val indices = (0 until MAX_POINTS_PER_STROKE).map { i ->
                    (i * step).roundToInt().coerceIn(0, stroke.size - 1)
                }
                simplified.add(indices.map { stroke[it] })
            }
        }
        return simplified
    }

    internal data class SequenceData(
        val data: FloatArray,
        val originalLen: Int,
    )

    internal fun strokesToSequence(
        strokes: List<List<Pair<Float, Float>>>
    ): SequenceData {
        val allPoints = mutableListOf<Pair<Float, Float>>()
        val penDownFlags = mutableListOf<Int>()

        for (stroke in strokes) {
            for (pt in stroke) {
                allPoints.add(pt)
                penDownFlags.add(1)
            }
            if (penDownFlags.isNotEmpty()) {
                penDownFlags[penDownFlags.size - 1] = 0
            }
        }

        val T = allPoints.size
        if (T == 0) {
            return SequenceData(FloatArray(FIXED_LEN * 5) { 0f }, 0)
        }

        val xs = allPoints.map { it.first }
        val ys = allPoints.map { it.second }

        val minX = xs.min()
        val maxX = xs.max()
        val minY = ys.min()
        val maxY = ys.max()

        val rangeX = max(maxX - minX, 1.0f)
        val rangeY = max(maxY - minY, 1.0f)

        val seq = FloatArray(T * 5)
        for (i in 0 until T) {
            val xNorm = (allPoints[i].first - minX) / rangeX
            val yNorm = (allPoints[i].second - minY) / rangeY
            val dx = if (i == 0) 0f else (allPoints[i].first - allPoints[i - 1].first) / rangeX
            val dy = if (i == 0) 0f else (allPoints[i].second - allPoints[i - 1].second) / rangeY
            val base = i * 5
            seq[base] = xNorm
            seq[base + 1] = yNorm
            seq[base + 2] = dx
            seq[base + 3] = dy
            seq[base + 4] = penDownFlags[i].toFloat()
        }

        val paddedLen = min(T, FIXED_LEN)
        val padded = FloatArray(FIXED_LEN * 5) { 0f }
        System.arraycopy(seq, 0, padded, 0, paddedLen * 5)
        return SequenceData(padded, min(T, FIXED_LEN))
    }

    internal fun buildMask(seqLen: Int): ByteArray {
        val mask = ByteArray(FIXED_LEN) { 0 }
        for (i in 0 until min(seqLen, FIXED_LEN)) {
            mask[i] = 1
        }
        return mask
    }

    /** 完整推理：预处理 → native ONNX → idx→汉字映射（去重、截断）。 */
    fun recognize(
        strokes: List<List<Pair<Float, Float>>>,
        topK: Int
    ): List<HandwritingCandidate> {
        val simplified = simplifyStrokes(strokes)
        if (simplified.isEmpty()) return emptyList()

        val seqData = strokesToSequence(simplified)
        val mask = buildMask(seqData.originalLen)

        val rawResults = HandwritingNativeEngine.predict(seqData.data, mask, topK)
        if (rawResults.isEmpty()) return emptyList()

        val results = mutableListOf<HandwritingCandidate>()
        val seen = mutableSetOf<String>()

        for ((idx, score) in rawResults) {
            if (idx < 0 || idx >= chars.size) continue
            val ch = chars[idx]
            if (ch.isEmpty()) continue
            if (ch in seen) continue
            seen.add(ch)
            results.add(HandwritingCandidate(ch, score))
        }

        return results.take(topK)
    }
}
