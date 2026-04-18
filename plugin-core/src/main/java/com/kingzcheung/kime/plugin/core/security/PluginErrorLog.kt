package com.kingzcheung.kime.plugin.core.security

import android.util.Log
import java.util.concurrent.ConcurrentHashMap

object PluginErrorLog {
    
    private const val TAG = "PluginErrorLog"
    private const val MAX_ERRORS_PER_PLUGIN = 10
    
    private val errorLogs = ConcurrentHashMap<String, MutableList<PluginError>>()
    
    data class PluginError(
        val timestamp: Long = System.currentTimeMillis(),
        val pluginId: String,
        val operation: String,
        val message: String,
        val stackTrace: String? = null
    )
    
    fun logError(pluginId: String, operation: String, message: String, throwable: Throwable? = null) {
        Log.e(TAG, "[$pluginId] $operation: $message", throwable)
        
        val error = PluginError(
            pluginId = pluginId,
            operation = operation,
            message = message,
            stackTrace = throwable?.stackTraceToString()
        )
        
        val errors = errorLogs.getOrPut(pluginId) { mutableListOf() }
        
        if (errors.size >= MAX_ERRORS_PER_PLUGIN) {
            errors.removeAt(0)
        }
        errors.add(error)
    }
    
    fun getErrors(pluginId: String): List<PluginError> {
        return errorLogs[pluginId]?.toList() ?: emptyList()
    }
    
    fun getAllErrors(): Map<String, List<PluginError>> {
        return errorLogs.mapValues { it.value.toList() }
    }
    
    fun clearErrors(pluginId: String) {
        errorLogs.remove(pluginId)
    }
    
    fun clearAllErrors() {
        errorLogs.clear()
    }
    
    fun hasErrors(pluginId: String): Boolean {
        return errorLogs[pluginId]?.isNotEmpty() ?: false
    }
    
    fun getLastError(pluginId: String): PluginError? {
        return errorLogs[pluginId]?.lastOrNull()
    }
}