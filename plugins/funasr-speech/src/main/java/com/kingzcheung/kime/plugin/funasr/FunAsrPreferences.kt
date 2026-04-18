package com.kingzcheung.kime.plugin.funasr

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.util.Log

class FunAsrPreferences(private val context: Context, private val useProxy: Boolean = false) {
    
    companion object {
        private const val TAG = "FunAsrPreferences"
        private const val COLUMN_API_KEY = "api_key"
        
        val CONTENT_URI: Uri = Uri.parse("content://${ApiKeyContentProvider.AUTHORITY}/api_key")
        
        fun forHost(context: Context): FunAsrPreferences = FunAsrPreferences(context, useProxy = true)
        fun forPlugin(context: Context): FunAsrPreferences = FunAsrPreferences(context, useProxy = false)
    }
    
    fun saveApiKey(apiKey: String) {
        Log.d(TAG, "Saving API key via ContentProvider: length=${apiKey.length}, useProxy=$useProxy")
        
        val values = ContentValues().apply {
            put(COLUMN_API_KEY, apiKey)
        }
        
        try {
            if (useProxy) {
                saveViaProxy(values)
            } else {
                context.contentResolver.insert(CONTENT_URI, values)
            }
            Log.d(TAG, "API key saved successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save API key", e)
        }
    }
    
    fun getApiKey(): String {
        Log.d(TAG, "Getting API key via ContentProvider, useProxy=$useProxy")
        
        var apiKey = ""
        
        try {
            val cursor: Cursor? = if (useProxy) {
                queryViaProxy()
            } else {
                context.contentResolver.query(
                    CONTENT_URI,
                    arrayOf(COLUMN_API_KEY),
                    null,
                    null,
                    null
                )
            }
            
            cursor?.use {
                if (it.moveToFirst()) {
                    apiKey = it.getString(0) ?: ""
                }
            }
            
            Log.d(TAG, "API key retrieved: length=${apiKey.length}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get API key", e)
        }
        
        return apiKey
    }
    
    private fun saveViaProxy(values: ContentValues) {
        val hostAuthority = "com.kingzcheung.kime.plugin.proxy"
        val pluginAuthority = ApiKeyContentProvider.AUTHORITY
        val proxyUri = Uri.Builder()
            .scheme("content")
            .authority(hostAuthority)
            .path("$pluginAuthority/api_key")
            .build()
        
        context.contentResolver.insert(proxyUri, values)
    }
    
    private fun queryViaProxy(): Cursor? {
        val hostAuthority = "com.kingzcheung.kime.plugin.proxy"
        val pluginAuthority = ApiKeyContentProvider.AUTHORITY
        val proxyUri = Uri.Builder()
            .scheme("content")
            .authority(hostAuthority)
            .path("$pluginAuthority/api_key")
            .build()
        
        return context.contentResolver.query(
            proxyUri,
            arrayOf(COLUMN_API_KEY),
            null,
            null,
            null
        )
    }
    
    fun hasApiKey(): Boolean = getApiKey().isNotEmpty()
}