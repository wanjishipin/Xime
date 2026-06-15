package com.kingzcheung.xime.service

import android.content.Context
import android.util.Log
import com.kingzcheung.xime.association.AssociationManager
import com.kingzcheung.xime.association.AssociationService
import com.kingzcheung.xime.plugin.ExtensionManager
import com.kingzcheung.xime.settings.SettingsPreferences
import com.kingzcheung.xime.util.FileLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class PredictionManager(
    private val context: Context,
    private val serviceScope: CoroutineScope,
    private val onStateChanged: (InputUIState) -> Unit,
    private val getState: () -> InputUIState
) {
    companion object {
        private const val TAG = "PredictionManager"
        private const val MAX_CONTEXT_LENGTH = 25
        const val MAX_ASSOCIATION_COUNT = 20
    }
    
    private var _lastCommittedText = ""
    val lastCommittedText: String get() = _lastCommittedText
    
    fun appendCommittedText(text: String) {
        _lastCommittedText = (_lastCommittedText + text).takeLast(MAX_CONTEXT_LENGTH)
        FileLogger.d(TAG, "Context updated: '$text' -> '$lastCommittedText' (len=${lastCommittedText.length})")
    }
    
    fun clearCommittedText() {
        _lastCommittedText = ""
    }
    
    fun deleteLastChar() {
        if (_lastCommittedText.isNotEmpty()) {
            _lastCommittedText = _lastCommittedText.dropLast(1)
        }
    }
    
    fun initialize() {
        FileLogger.i(TAG, "Initializing association system")
        
        serviceScope.launch(Dispatchers.IO) {
            try {
                val trieInit = AssociationService.initialize(context)
                if (trieInit) {
                    FileLogger.i(TAG, "Trie association service initialized")
                } else {
                    FileLogger.w(TAG, "Trie association service initialization failed")
                }
                
                if (!ExtensionManager.isInitialized()) {
                    FileLogger.d(TAG, "ExtensionManager not initialized, initializing...")
                    ExtensionManager.initialize(context)
                }
                
                if (SettingsPreferences.isSmartPredictionEnabled(context)) {
                    try {
                        val initialized = AssociationManager.initialize(context)
                        if (initialized) {
                            FileLogger.i(TAG, "Smart prediction initialized")
                        } else {
                            FileLogger.w(TAG, "Smart prediction initialization failed")
                        }
                    } catch (e: Exception) {
                        FileLogger.e(TAG, "Failed to initialize smart prediction: ${e.message}")
                    }
                }
            } catch (e: Exception) {
                FileLogger.e(TAG, "Failed to initialize association system: ${e.message}")
            }
        }
    }
    
    fun getPrediction(contextText: String) {
        if (contextText.isEmpty()) {
            onStateChanged(getState().copy(associationCandidates = emptyArray()))
            return
        }
        
        if (!SettingsPreferences.isSmartPredictionEnabled(context)) {
            onStateChanged(getState().copy(associationCandidates = emptyArray()))
            return
        }
        
        serviceScope.launch {
            try {
                if (!AssociationManager.isInitialized()) {
                    Log.d(TAG, "AssociationManager not initialized, initializing...")
                    val initSuccess = withContext(Dispatchers.IO) {
                        AssociationManager.initialize(context)
                    }
                    if (!initSuccess) {
                        Log.e(TAG, "Failed to initialize AssociationManager")
                        withContext(Dispatchers.Main) {
                            onStateChanged(getState().copy(associationCandidates = emptyArray()))
                        }
                        return@launch
                    }
                }
                
                val candidates = AssociationManager.predict(contextText, MAX_ASSOCIATION_COUNT)
                
                Log.d(TAG, "Prediction candidates: ${candidates.map { it.text }}")
                
                withContext(Dispatchers.Main) {
                    onStateChanged(getState().copy(associationCandidates = candidates.map { it.text }.toTypedArray()))
                }
            } catch (e: Exception) {
                Log.e(TAG, "Prediction failed", e)
                withContext(Dispatchers.Main) {
                    onStateChanged(getState().copy(associationCandidates = emptyArray()))
                }
            }
        }
    }
    
    fun recordInput(text: String) {
        if (SettingsPreferences.isSmartPredictionEnabled(context) && AssociationManager.isInitialized()) {
            AssociationManager.recordInput(text)
        }
    }
    
    fun recordInputPair(lastChar: String, candidate: String) {
        if (SettingsPreferences.isSmartPredictionEnabled(context) && AssociationManager.isInitialized()) {
            AssociationManager.recordInput(lastChar + candidate)
        }
    }
    
    suspend fun getEnglishAssociations(text: String, limit: Int = MAX_ASSOCIATION_COUNT): Array<String> {
        return try {
            AssociationService.getAssociations(context, text, true, limit).toTypedArray()
        } catch (e: Exception) {
            Log.e(TAG, "English association failed", e)
            emptyArray()
        }
    }
    
    suspend fun getChineseAssociations(text: String, limit: Int = MAX_ASSOCIATION_COUNT): Array<String> {
        return try {
            if (!AssociationManager.isInitialized()) {
                Log.d(TAG, "AssociationManager not initialized, initializing...")
                AssociationManager.initialize(context)
            }
            
            val candidates = AssociationManager.predict(text, limit)
            candidates.map { it.text }.toTypedArray()
        } catch (e: Exception) {
            Log.e(TAG, "Chinese association failed", e)
            emptyArray()
        }
    }
}