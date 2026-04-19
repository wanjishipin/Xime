package com.kingzcheung.kime.association

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

object OnnxAssociationEngine {
    private const val TAG = "OnnxAssociationEngine"
    
    private var vocab: Map<String, Int> = emptyMap()
    private var id2word: Map<Int, String> = emptyMap()
    private var isInitialized = false

    fun initialize(context: Context): Boolean {
        if (isInitialized) return true

        try {
            val modelDir = context.filesDir
            
            modelDir.mkdirs()
            
            val filesToCheck = listOf("vocab.json", "model_int8_dynamic.onnx")
            for (fileName in filesToCheck) {
                val file = File(modelDir, fileName)
                if (!file.exists()) {
                    Log.d(TAG, "$fileName not found at ${file.absolutePath}")
                    return false
                }
            }

            val vocabFile = File(modelDir, "vocab.json")

            val vocabJson = JSONObject(vocabFile.readText())
            val vocabMap = vocabJson.getJSONObject("model").getJSONObject("vocab")
            vocab = vocabMap.keys().asSequence().associateWith { vocabMap.getInt(it) }
            id2word = vocab.entries.associate { it.value to it.key }
            Log.d(TAG, "Vocabulary loaded: ${vocab.size} words")

            val modelFile = File(modelDir, "model_int8_dynamic.onnx")
            Log.d(TAG, "Using model: ${modelFile.name} (${modelFile.length()} bytes)")

            val success = NativeOnnxEngine.initialize(context, modelFile.absolutePath)
            if (success) {
                isInitialized = true
                Log.i(TAG, "ONNX Runtime initialized successfully")
                return true
            } else {
                Log.e(TAG, "Failed to initialize ONNX Runtime")
                return false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize ONNX Runtime", e)
            return false
        }
    }

    suspend fun predict(inputText: String, topK: Int = 5): List<AssociationCandidate> = withContext(Dispatchers.Default) {
        if (!isInitialized) {
            Log.e(TAG, "Engine not initialized")
            return@withContext emptyList()
        }

        try {
            val inputIds = encodeText(inputText)
            if (inputIds.isEmpty()) {
                Log.d(TAG, "Empty input encoding for: '$inputText'")
                return@withContext emptyList()
            }

            Log.d(TAG, "Predicting for: '$inputText', tokens: $inputIds")

            val inputIdsLong = inputIds.map { it.toLong() }.toLongArray()
            val scores = NativeOnnxEngine.predict(inputIdsLong, topK)

            val candidates = scores.mapNotNull { (id, score) ->
                id2word[id]?.let { word ->
                    AssociationCandidate(word, score)
                }
            }

            Log.d(TAG, "Predicted ${candidates.size} candidates: ${candidates.map { it.text }}")
            candidates

        } catch (e: Exception) {
            Log.e(TAG, "Prediction failed", e)
            emptyList()
        }
    }

    private fun encodeText(text: String): List<Int> {
        val ids = mutableListOf<Int>()
        var i = 0
        while (i < text.length) {
            val char = text[i].toString()
            val id = vocab[char] ?: 3
            ids.add(id)
            i++
        }
        return ids
    }

    fun release() {
        NativeOnnxEngine.release()
        isInitialized = false
        Log.d(TAG, "ONNX Runtime released")
    }

    fun isInitialized(): Boolean = isInitialized
}