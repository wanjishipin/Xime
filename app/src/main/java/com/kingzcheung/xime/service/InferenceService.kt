package com.kingzcheung.xime.service

import android.app.Service
import android.content.Intent
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.util.Log
import com.kingzcheung.xime.association.AssociationCandidate
import com.kingzcheung.xime.association.NativeOnnxEngine
import com.kingzcheung.xime.handwriting.HandwritingInference
import com.kingzcheung.xime.handwriting.HandwritingNativeEngine
import org.json.JSONObject
import java.io.File

class InferenceService : Service() {

    companion object {
        private const val TAG = "InferenceService"
        private const val MODEL_PREDICTION = "predictive_text"
        private const val MODEL_HANDWRITING = "handwriting"
    }

    private var onnxLibsLoaded = false

    private var predictionVocab: Map<String, Int>? = null

    private val binder = object : IInferenceService.Stub() {

        override fun loadModel(modelId: String, modelPath: String, extraPath: String): Boolean {
            return try {
                when (modelId) {
                    MODEL_PREDICTION -> loadPredictionModel(modelPath, extraPath)
                    MODEL_HANDWRITING -> loadHandwritingModel(modelPath, extraPath)
                    else -> {
                        Log.e(TAG, "Unknown modelId: $modelId")
                        false
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "loadModel($modelId) failed", e)
                false
            }
        }

        override fun unloadModel(modelId: String) {
            try {
                when (modelId) {
                    MODEL_PREDICTION -> {
                        NativeOnnxEngine.release()
                        predictionVocab = null
                    }
                    MODEL_HANDWRITING -> {
                        HandwritingNativeEngine.release()
                        HandwritingInference.clearChars()
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "unloadModel($modelId) failed", e)
            }
        }

        override fun isModelLoaded(modelId: String): Boolean {
            return try {
                when (modelId) {
                    MODEL_PREDICTION -> NativeOnnxEngine.isInitialized()
                    MODEL_HANDWRITING -> HandwritingNativeEngine.isInitialized()
                    else -> false
                }
            } catch (e: Exception) {
                false
            }
        }

        override fun predict(modelId: String, text: String, topK: Int): MutableList<String> {
            if (modelId != MODEL_PREDICTION) return mutableListOf()
            // 模型未加载时（如进程重启后客户端状态未同步）直接返回空，
            // 避免在未初始化的 native 引擎上推理导致进程崩溃
            if (!NativeOnnxEngine.isInitialized()) {
                Log.w(TAG, "predict called before model loaded, returning empty")
                return mutableListOf()
            }
            val candidates = NativeOnnxEngine.predict(text, topK)
            val result = mutableListOf<String>()
            for (c in candidates) {
                result.add(c.text)
                result.add(c.score.toString())
            }
            return result
        }

        override fun recognizeHandwriting(modelId: String, points: FloatArray, strokePointCounts: IntArray, topK: Int): MutableList<String> {
            if (modelId != MODEL_HANDWRITING) return mutableListOf()
            // 模型未加载时（如进程重启后客户端状态未同步）直接返回空，
            // 避免在未初始化的 native 引擎上推理导致进程崩溃
            if (!HandwritingNativeEngine.isInitialized()) {
                Log.w(TAG, "recognizeHandwriting called before model loaded, returning empty")
                return mutableListOf()
            }
            val strokes = HandwritingInference.decodeStrokes(points, strokePointCounts)
            val candidates = HandwritingInference.recognize(strokes, topK)
            val result = mutableListOf<String>()
            for (c in candidates) {
                result.add(c.char)
                result.add(c.score.toString())
            }
            return result
        }

        override fun processAudioBytes(input: ByteArray, sampleRate: Int): ByteArray {
            var maxSample = 0
            for (i in input.indices step 2) {
                val low = input[i].toInt() and 0xFF
                val high = input[i + 1].toInt()
                val sample = (high shl 8) or low
                val absSample = kotlin.math.abs(sample)
                if (absSample > maxSample) maxSample = absSample
            }

            if (maxSample == 0) return input

            val peak = maxSample.toFloat() / 32768.0f
            val gain = if (peak < 0.5f) {
                kotlin.math.min(4.0f, 0.5f / peak)
            } else {
                1.0f
            }

            if (gain <= 1.0f) return input

            val output = ByteArray(input.size)
            for (i in input.indices step 2) {
                val low = input[i].toInt() and 0xFF
                val high = input[i + 1].toInt()
                var sample = ((high shl 8) or low).toShort().toFloat() * gain
                sample = sample.coerceIn(-32768f, 32767f)
                val s = sample.toInt()
                output[i] = (s and 0xFF).toByte()
                output[i + 1] = ((s shr 8) and 0xFF).toByte()
            }
            return output
        }
    }

    override fun onBind(intent: Intent): IBinder = binder

    override fun onDestroy() {
        NativeOnnxEngine.releaseSharedEnv()
        super.onDestroy()
    }

    private fun loadOnnxLibs(): Boolean {
        if (onnxLibsLoaded) return true
        val success = NativeOnnxEngine.loadNativeLibrary(this)
        onnxLibsLoaded = success
        return success
    }

    private fun loadPredictionModel(modelPath: String, vocabPath: String): Boolean {
        if (!loadOnnxLibs()) return false

        val vocabFile = File(vocabPath)
        if (!vocabFile.exists()) {
            Log.e(TAG, "Vocab file not found: $vocabPath")
            return false
        }
        val vocabText = vocabFile.readText()
        val vocabJson = JSONObject(vocabText)
        val vocabMap = when {
            vocabJson.has("model") -> vocabJson.getJSONObject("model").getJSONObject("vocab")
            vocabJson.has("vocab") -> vocabJson.getJSONObject("vocab")
            else -> vocabJson
        }
        val vocab = vocabMap.keys().asSequence().associateWith { vocabMap.getInt(it) }
        predictionVocab = vocab

        val modelFile = File(modelPath)
        if (!modelFile.exists()) {
            Log.e(TAG, "Model file not found: $modelPath")
            return false
        }

        // 先释放旧 session：切换联想模型（small/base）或重复加载时，
        // 避免 ORT 双 session 并存导致内存峰值过高、进程被系统查杀
        if (NativeOnnxEngine.isInitialized()) {
            NativeOnnxEngine.release()
        }
        if (!NativeOnnxEngine.initialize(this, modelPath)) {
            Log.e(TAG, "NativeOnnxEngine.initialize failed")
            return false
        }
        NativeOnnxEngine.initVocab(vocab)
        Log.i(TAG, "Prediction model loaded: ${vocab.size} vocab")
        return true
    }

    private fun loadHandwritingModel(modelPath: String, charIndexPath: String): Boolean {
        if (!loadOnnxLibs()) return false
        // 先释放旧 session：重复加载时避免 ORT 双 session 并存推高内存（同联想模型）
        if (HandwritingNativeEngine.isInitialized()) {
            HandwritingNativeEngine.release()
            HandwritingInference.clearChars()
        }
        if (!HandwritingNativeEngine.initialize(this, modelPath)) {
            Log.e(TAG, "HandwritingNativeEngine.initialize failed")
            return false
        }
        // idx→汉字词表在本进程加载，客户端只收最终候选字
        if (!HandwritingInference.loadCharIndex(charIndexPath)) {
            HandwritingNativeEngine.release()
            return false
        }
        return true
    }
}
