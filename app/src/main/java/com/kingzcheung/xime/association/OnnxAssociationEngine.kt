package com.kingzcheung.xime.association

import android.content.Context
import com.kingzcheung.xime.util.FileLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

object OnnxAssociationEngine {
    private const val TAG = "OnnxAssociationEngine"
    
    private var vocab: Map<String, Int> = emptyMap()
    private var id2word: Map<Int, String> = emptyMap()
    private var isInitialized = false
    private var warmupStarted = false
    private val warmupScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    fun initialize(context: Context): Boolean {
        if (isInitialized) {
            FileLogger.d(TAG, "Already initialized")
            return true
        }

        try {
            val modelDir = context.filesDir
            
            modelDir.mkdirs()
            
            val filesToCheck = listOf("vocab.json", "model_int8_dynamic.onnx")
            for (fileName in filesToCheck) {
                val file = File(modelDir, fileName)
                if (!file.exists()) {
                    FileLogger.e(TAG, "$fileName not found at ${file.absolutePath}")
                    return false
                }
                FileLogger.d(TAG, "$fileName exists: ${file.length()} bytes")
            }

            val vocabFile = File(modelDir, "vocab.json")
            val vocabText = vocabFile.readText()
            FileLogger.d(TAG, "vocab.json content preview: ${vocabText.take(200)}")

            val vocabJson = JSONObject(vocabText)
            val vocabMap = when {
                vocabJson.has("model") -> {
                    vocabJson.getJSONObject("model").getJSONObject("vocab")
                }
                vocabJson.has("vocab") -> {
                    vocabJson.getJSONObject("vocab")
                }
                else -> {
                    vocabJson
                }
            }
            vocab = vocabMap.keys().asSequence().associateWith { vocabMap.getInt(it) }
            id2word = vocab.entries.associate { it.value to it.key }
            FileLogger.i(TAG, "Vocabulary loaded: ${vocab.size} words")
            
            FileLogger.d(TAG, "id2word mapping check: id=308='${id2word[308]}', id=81='${id2word[81]}', id=9='${id2word[9]}', id=5='${id2word[5]}', id=11='${id2word[11]}'")


            val modelFile = File(modelDir, "model_int8_dynamic.onnx")
            FileLogger.d(TAG, "Using model: ${modelFile.name} (${modelFile.length()} bytes)")

            val success = NativeOnnxEngine.initialize(context, modelFile.absolutePath)
            if (success) {
                isInitialized = true
                FileLogger.i(TAG, "ONNX Runtime initialized successfully")
                return true
            } else {
                FileLogger.e(TAG, "Failed to initialize ONNX Runtime - NativeOnnxEngine.initialize returned false")
                return false
            }
        } catch (e: Exception) {
            FileLogger.e(TAG, "Failed to initialize ONNX Runtime: ${e.message}", e)
            return false
        }
    }

    suspend fun predict(inputText: String, topK: Int = 20): List<AssociationCandidate> = withContext(Dispatchers.Default) {
        FileLogger.d(TAG, "predict called with inputText='$inputText', topK=$topK")
        
        if (!isInitialized) {
            FileLogger.e(TAG, "Engine not initialized")
            return@withContext emptyList()
        }

        try {
            val inputIds = encodeText(inputText)
            FileLogger.d(TAG, "encodeText result: inputIds=$inputIds")
            if (inputIds.isEmpty()) {
                FileLogger.d(TAG, "Empty input encoding for: '$inputText'")
                return@withContext emptyList()
            }

            FileLogger.d(TAG, "Predicting for: '$inputText', tokens: $inputIds")

            val inputIdsLong = inputIds.map { it.toLong() }.toLongArray()
            val scores = NativeOnnxEngine.predict(inputIdsLong, topK)
            
            FileLogger.d(TAG, "NativeOnnxEngine.predict returned ${scores.size} scores")
            FileLogger.d(TAG, "Top 5 scores with id2word lookup: ${scores.take(5).map { (id, score) -> "id=$id -> '${id2word[id]}' (score=$score)" }}")

            val candidates = scores.mapNotNull { (id, score) ->
                id2word[id]?.let { word ->
                    AssociationCandidate(word, score)
                }
            }

            FileLogger.d(TAG, "Predicted ${candidates.size} candidates: ${candidates.map { it.text }}")
            candidates

        } catch (e: Exception) {
            FileLogger.e(TAG, "Prediction failed: ${e.message}", e)
            emptyList()
        }
    }

    private fun encodeText(text: String): List<Int> {
        val ids = mutableListOf<Int>()
        ids.add(vocab["[BOS]"] ?: 1)
        var i = 0
        while (i < text.length) {
            val char = text[i].toString()
            val id = vocab[char] ?: 3
            ids.add(id)
            i++
        }
        return ids
    }

    fun startWarmup() {
        if (!isInitialized || warmupStarted) return
        warmupStarted = true
        warmupScope.launch {
            FileLogger.d(TAG, "Starting warmup prediction...")
            val dummyIds = longArrayOf(1L, 9L)
            try {
                NativeOnnxEngine.predict(dummyIds, 5)
                FileLogger.d(TAG, "Warmup prediction completed")
            } catch (e: Exception) {
                FileLogger.w(TAG, "Warmup prediction failed (non-fatal): ${e.message}")
            }
        }
    }

    fun release() {
        NativeOnnxEngine.release()
        isInitialized = false
        FileLogger.d(TAG, "ONNX Runtime released")
    }

    fun isInitialized(): Boolean = isInitialized
}