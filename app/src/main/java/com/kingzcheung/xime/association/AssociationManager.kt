package com.kingzcheung.xime.association

import android.content.Context
import android.util.Log
import com.kingzcheung.xime.util.FileLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

object AssociationManager {
    private const val TAG = "AssociationManager"
    
    @Volatile
    private var isInitialized = false
    private val mutex = Mutex()
    
    private lateinit var fusionEngine: NgramFusionEngine
    private var context: Context? = null
    
    suspend fun initialize(ctx: Context): Boolean = withContext(Dispatchers.IO) {
        context = ctx
        if (isInitialized) {
            FileLogger.d(TAG, "Already initialized")
            return@withContext true
        }
        
        mutex.withLock {
            if (isInitialized) {
                FileLogger.d(TAG, "Already initialized (in lock)")
                return@withContext true
            }
            
            try {
                fusionEngine = NgramFusionEngine(ctx)
                
                FileLogger.i(TAG, "Starting OnnxAssociationEngine initialization...")
                val modelInit = OnnxAssociationEngine.initialize(ctx)
                FileLogger.i(TAG, "OnnxAssociationEngine init result: $modelInit")

                if (modelInit) {
                    OnnxAssociationEngine.startWarmup()
                }

                val cacheInit = fusionEngine.initialize()
                FileLogger.i(TAG, "NgramFusionEngine init result: $cacheInit")
                
                isInitialized = modelInit
                
                FileLogger.i(TAG, "AssociationManager initialized: model=$modelInit, cache=$cacheInit")
                isInitialized
                
            } catch (e: Exception) {
                FileLogger.e(TAG, "Failed to initialize AssociationManager: ${e.message}", e)
                false
            }
        }
    }
    
    suspend fun predict(contextText: String, topK: Int = 20): List<AssociationCandidate> = withContext(Dispatchers.Default) {
        if (!isInitialized) {
            Log.d(TAG, "Not initialized, attempting to initialize...")
            val ctx = context
            if (ctx != null) {
                val initSuccess = withContext(Dispatchers.IO) {
                    initialize(ctx)
                }
                if (!initSuccess) {
                    Log.e(TAG, "Initialization failed, returning empty list")
                    return@withContext emptyList()
                }
            } else {
                Log.e(TAG, "Context is null, cannot initialize")
                return@withContext emptyList()
            }
        }
        
        try {
            val modelCandidates = OnnxAssociationEngine.predict(contextText, topK * 2)
            
            Log.d(TAG, "Model candidates: ${modelCandidates.size}, ${modelCandidates.map { it.text }}")
            
            if (modelCandidates.isEmpty()) {
                return@withContext emptyList()
            }
            
            val fusedCandidates = fusionEngine.fuseCandidates(modelCandidates, contextText)
            
            Log.d(TAG, "Fused candidates: ${fusedCandidates.size}, ${fusedCandidates.map { it.text }}")
            
            fusedCandidates.take(topK)
            
        } catch (e: Exception) {
            Log.e(TAG, "Prediction failed", e)
            emptyList()
        }
    }
    
    fun recordInput(text: String) {
        if (!isInitialized) return
        fusionEngine.recordUserInput(text)
    }
    
    suspend fun saveUserData() {
        if (!isInitialized) return
        fusionEngine.saveCache()
    }
    
    fun getCacheSize(): Int {
        return if (isInitialized) fusionEngine.getCacheSize() else 0
    }
    
    fun isInitialized(): Boolean = isInitialized
    
    fun release() {
        if (isInitialized) {
            OnnxAssociationEngine.release()
            isInitialized = false
            context = null
            Log.d(TAG, "AssociationManager released")
        }
    }
}