package com.kingzcheung.kime.plugin.funasr

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.util.Log

class ApiKeyContentProvider : ContentProvider() {
    
    companion object {
        private const val TAG = "ApiKeyProvider"
        const val AUTHORITY = "com.kingzcheung.kime.plugin.funasr.config"
        
        private const val API_KEY_PATH = "api_key"
        private const val COLUMN_API_KEY = "api_key"
        
        val CONTENT_URI: Uri = Uri.parse("content://$AUTHORITY/$API_KEY_PATH")
        
        private const val PREFS_NAME = "plugin_funasr_config"
        private const val KEY_API_KEY = "api_key"
        private const val HOST_PACKAGE = "com.kingzcheung.kime"
    }
    
    private fun getHostPrefs(): android.content.SharedPreferences? {
        val ctx = context ?: return null
        
        // 检查当前进程是否是宿主进程
        val currentPackage = ctx.packageName
        Log.d(TAG, "Current package: $currentPackage")
        
        if (currentPackage == HOST_PACKAGE) {
            // 在宿主进程中，直接使用当前 context
            Log.d(TAG, "Running in host process, using direct SharedPreferences")
            return ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        } else {
            // 在插件进程中，需要使用 sharedUserId 来访问宿主的 SharedPreferences
            Log.d(TAG, "Running in plugin process, accessing host SharedPreferences via sharedUserId")
            return try {
                // 由于 sharedUserId，可以访问宿主的 context
                val hostContext = ctx.createPackageContext(HOST_PACKAGE, Context.CONTEXT_IGNORE_SECURITY)
                hostContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to get host context", e)
                // fallback: 使用当前 context（数据可能无法被宿主访问）
                ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            }
        }
    }
    
    override fun onCreate(): Boolean {
        Log.d(TAG, "ApiKeyContentProvider created")
        return true
    }
    
    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?
    ): Cursor? {
        Log.d(TAG, "query called: uri=$uri")
        
        if (context == null) {
            Log.e(TAG, "Context is null")
            return null
        }
        
        val apiKey = getHostPrefs()?.getString(KEY_API_KEY, "") ?: ""
        
        Log.d(TAG, "Returning API key: length=${apiKey.length}")
        
        val cursor = MatrixCursor(arrayOf(COLUMN_API_KEY))
        cursor.addRow(arrayOf(apiKey))
        return cursor
    }
    
    override fun getType(uri: Uri): String {
        return "vnd.android.cursor.item/$AUTHORITY.$API_KEY_PATH"
    }
    
    override fun insert(uri: Uri, values: ContentValues?): Uri? {
        Log.d(TAG, "insert called: uri=$uri")
        
        if (context == null) {
            Log.e(TAG, "Context is null")
            return null
        }
        
        val apiKey = values?.getAsString(COLUMN_API_KEY) ?: return null
        
        getHostPrefs()?.edit()?.putString(KEY_API_KEY, apiKey)?.apply()
        
        Log.d(TAG, "Saved API key: length=${apiKey.length}")
        
        context?.contentResolver?.notifyChange(CONTENT_URI, null)
        
        return uri
    }
    
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int {
        Log.d(TAG, "delete called: uri=$uri")
        
        if (context == null) return 0
        
        getHostPrefs()?.edit()?.remove(KEY_API_KEY)?.apply()
        
        context?.contentResolver?.notifyChange(CONTENT_URI, null)
        
        return 1
    }
    
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int {
        Log.d(TAG, "update called: uri=$uri")
        
        if (context == null) return 0
        
        val apiKey = values?.getAsString(COLUMN_API_KEY) ?: return 0
        
        getHostPrefs()?.edit()?.putString(KEY_API_KEY, apiKey)?.apply()
        
        Log.d(TAG, "Updated API key: length=${apiKey.length}")
        
        context?.contentResolver?.notifyChange(CONTENT_URI, null)
        
        return 1
    }
}