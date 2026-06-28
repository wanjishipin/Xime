package com.kingzcheung.xime.handwriting

import android.content.Context
import android.util.Log
import com.kingzcheung.xime.util.FileLogger
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

data class HandwritingCandidate(
    val char: String,
    val score: Float,
)

object HandwritingEngine {
    private const val TAG = "HandwritingEngine"
    private const val FIXED_LEN = 200
    private const val MAX_POINTS_PER_STROKE = 8
    private const val DEFAULT_TOP_K = 10

    private var initialized = false
    private var chars: List<String> = emptyList()
    private var modelFile: File? = null
    private var charIndexFile: File? = null

    fun initialize(context: Context): Boolean {
        if (initialized) return true

        val filesDir = context.filesDir
        modelFile = File(filesDir, "ochwpro.onnx")
        charIndexFile = File(filesDir, "char_index.json")

        if (!modelFile!!.exists() || !charIndexFile!!.exists()) {
            Log.w(TAG, "Model files not found: $modelFile, $charIndexFile")
            return false
        }

        Log.d(TAG, "Model file size: ${modelFile!!.length()}, char_index size: ${charIndexFile!!.length()}")

        try {
            if (!loadCharIndex(context)) {
                Log.e(TAG, "Failed to load char_index.json")
                return false
            }

            Log.d(TAG, "Initializing ONNX engine with: ${modelFile!!.absolutePath}")
            val ok = HandwritingNativeEngine.initialize(context, modelFile!!.absolutePath)
            if (!ok) {
                Log.e(TAG, "Failed to initialize HandwritingNativeEngine")
                return false
            }

            initialized = true
            Log.i(TAG, "Handwriting engine initialized: ${chars.size} chars")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "HandwritingEngine init failed: ${e.message}", e)
            FileLogger.e(TAG, "HandwritingEngine init failed: ${e.message}", e)
            return false
        }
    }

    fun isInitialized(): Boolean = initialized

    override fun toString(): String {
        return "HandwritingEngine(initialized=$initialized, chars=${chars.size})"
    }

    private fun loadCharIndex(context: Context): Boolean {
        return try {
            val text = charIndexFile!!.readText().trimStart('\uFEFF')
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
            Log.d(TAG, "Loaded ${chars.size} characters from char_index")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load char_index: ${e.message}", e)
            FileLogger.e(TAG, "Failed to load char_index: ${e.message}", e)
            false
        }
    }

    fun isCjk(ch: String): Boolean {
        if (ch.length != 1) return false
        val cp = ch[0].code
        return (cp in 0x4E00..0x9FFF) ||
               (cp in 0x3400..0x4DBF) ||
               (cp in 0x20000..0x2A6DF) ||
               (cp in 0x2A700..0x2B73F) ||
               (cp in 0x2B740..0x2B81F) ||
               (cp in 0x2B820..0x2CEAF) ||
               (cp in 0xF900..0xFAFF) ||
               (cp in 0x2F800..0x2FA1F)
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

    fun predict(
        strokes: List<List<Pair<Float, Float>>>,
        topK: Int = DEFAULT_TOP_K
    ): List<HandwritingCandidate> {
        if (!initialized || strokes.isEmpty()) return emptyList()

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

    fun release() {
        HandwritingNativeEngine.release()
        initialized = false
        chars = emptyList()
        Log.d(TAG, "Handwriting engine released")
    }
}
