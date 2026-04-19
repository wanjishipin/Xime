package com.kingzcheung.kime.util

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object FileLogger {
    private const val TAG = "FileLogger"
    private const val MAX_LOG_SIZE = 5 * 1024 * 1024 // 5MB
    private const val MAX_LOG_FILES = 10
    
    private var logFile: File? = null
    private var logsDir: File? = null
    private var isInitialized = false
    
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault())
    private val fileDateFormat = SimpleDateFormat("yyyyMMdd", Locale.getDefault())
    
    fun init(context: Context) {
        try {
            logsDir = File(context.filesDir, "logs")
            if (!logsDir!!.exists()) {
                logsDir!!.mkdirs()
            }
            
            val today = fileDateFormat.format(Date())
            logFile = File(logsDir!!, "kime_$today.log")
            
            if (logFile!!.exists() && logFile!!.length() > MAX_LOG_SIZE) {
                val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
                val newLogFile = File(logsDir!!, "kime_$timestamp.log")
                logFile!!.renameTo(newLogFile)
                logFile = File(logsDir!!, "kime_$today.log")
            }
            
            cleanOldLogs()
            
            isInitialized = true
            i(TAG, "FileLogger initialized, log file: ${logFile!!.absolutePath}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize FileLogger", e)
        }
    }
    
    fun isInitialized(): Boolean = isInitialized
    
    fun v(tag: String, message: String) {
        Log.v(tag, message)
        writeToFile("V", tag, message)
    }
    
    fun d(tag: String, message: String) {
        Log.d(tag, message)
        writeToFile("D", tag, message)
    }
    
    fun i(tag: String, message: String) {
        Log.i(tag, message)
        writeToFile("I", tag, message)
    }
    
    fun w(tag: String, message: String) {
        Log.w(tag, message)
        writeToFile("W", tag, message)
    }
    
    fun e(tag: String, message: String, throwable: Throwable? = null) {
        Log.e(tag, message, throwable)
        val fullMessage = if (throwable != null) {
            "$message\n${throwable.stackTraceToString()}"
        } else {
            message
        }
        writeToFile("E", tag, fullMessage)
    }
    
    fun wtf(tag: String, message: String, throwable: Throwable? = null) {
        Log.wtf(tag, message, throwable)
        val fullMessage = if (throwable != null) {
            "$message\n${throwable.stackTraceToString()}"
        } else {
            message
        }
        writeToFile("F", tag, fullMessage)
    }
    
    private fun writeToFile(level: String, tag: String, message: String) {
        try {
            logFile?.let { file ->
                if (!file.exists()) {
                    file.createNewFile()
                }
                
                val timestamp = dateFormat.format(Date())
                val logLine = "$timestamp [$level] $tag: $message\n"
                
                file.appendText(logLine)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write log to file", e)
        }
    }
    
    private fun cleanOldLogs() {
        try {
            logsDir?.let { dir ->
                val logFiles = dir.listFiles()
                    ?.filter { it.name.startsWith("kime_") && it.name.endsWith(".log") }
                    ?.sortedByDescending { it.lastModified() }
                    ?: emptyList()
                
                if (logFiles.size > MAX_LOG_FILES) {
                    logFiles.drop(MAX_LOG_FILES).forEach { oldFile ->
                        oldFile.delete()
                        Log.d(TAG, "Deleted old log file: ${oldFile.name}")
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to clean old logs", e)
        }
    }
    
    fun getCurrentLogFile(): File? = logFile
    
    fun getAllLogFiles(): List<File> {
        return logsDir?.listFiles()
            ?.filter { it.name.startsWith("kime_") && it.name.endsWith(".log") }
            ?.sortedByDescending { it.lastModified() }
            ?: emptyList()
    }
    
    fun clearAllLogs() {
        try {
            logsDir?.listFiles()
                ?.filter { it.name.startsWith("kime_") }
                ?.forEach { it.delete() }
            
            val today = fileDateFormat.format(Date())
            logFile = File(logsDir!!, "kime_$today.log")
            
            i(TAG, "All logs cleared")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to clear logs", e)
        }
    }
}