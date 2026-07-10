package com.kingzcheung.xime.clipboard

import android.content.ClipData
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.content.FileProvider
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.io.FileInputStream
import java.util.concurrent.atomic.AtomicLong
import android.content.ClipboardManager as AndroidClipboardManager

data class ClipboardItem(
    val id: Long = ClipboardManager.generateId(),
    val text: String,
    val timestamp: Long = System.currentTimeMillis(),
    val isPinned: Boolean = false,
    val isQuickSend: Boolean = false
)

class ClipboardManager private constructor(private val context: Context) {
    
    companion object {
        private const val TAG = "ClipboardManager"
        private const val MAX_ITEMS = 1000
        const val DEFAULT_MAX_ITEMS = 1000
        private const val MAX_QUICK_SEND_ITEMS = 20
        private const val PREFS_NAME = "clipboard_prefs"
        private const val KEY_CLIPBOARD_ITEMS = "clipboard_items"
        private const val KEY_QUICK_SEND_ITEMS = "quick_send_items"
        private val idCounter = AtomicLong(System.currentTimeMillis())
        
        fun generateId(): Long = idCounter.getAndIncrement()
        
        @Volatile
        private var instance: ClipboardManager? = null
        
        fun getInstance(context: Context): ClipboardManager {
            return instance ?: synchronized(this) {
                instance ?: ClipboardManager(context.applicationContext).also { instance = it }
            }
        }
    }
    
    private val androidClipboardManager = context.getSystemService(Context.CLIPBOARD_SERVICE) as AndroidClipboardManager
    
    private val clipboardListener = AndroidClipboardManager.OnPrimaryClipChangedListener {
        readClipboard()
    }

    private fun readClipboard(retries: Int = 3) {
        try {
            val clipData = androidClipboardManager.primaryClip
            if (clipData != null && clipData.itemCount > 0) {
                val item = clipData.getItemAt(0)
                val text = when {
                    item.text != null -> item.text.toString()
                    item.uri != null -> item.uri.toString()
                    item.intent != null -> item.intent.toUri(0)
                    else -> null
                }
                if (!text.isNullOrEmpty()) {
                    if (retries < 3) Log.d(TAG, "Clipboard read succeeded after retry #${3 - retries}")
                    addItem(text)
                    return
                }
            }
            if (retries > 0) {
                Log.d(TAG, "Clipboard not ready, retrying in 100ms ($retries left)")
                Handler(Looper.getMainLooper()).postDelayed({ readClipboard(retries - 1) }, 100L)
            } else {
                Log.w(TAG, "Failed to read clipboard after all retries")
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "Cannot read clipboard: missing permission", e)
        } catch (e: Exception) {
            Log.e(TAG, "Unexpected error reading clipboard", e)
        }
    }
    
    private val _clipboardItems = MutableStateFlow<List<ClipboardItem>>(emptyList())
    val clipboardItems: StateFlow<List<ClipboardItem>> = _clipboardItems.asStateFlow()

    private val _quickSendItems = MutableStateFlow<List<ClipboardItem>>(emptyList())
    val quickSendItems: StateFlow<List<ClipboardItem>> = _quickSendItems.asStateFlow()

    private val _recentItems = MutableStateFlow<List<ClipboardItem>>(emptyList())
    val recentItems: StateFlow<List<ClipboardItem>> = _recentItems.asStateFlow()
    
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    
    init {
        loadItems()
        loadQuickSendItems()
        updateRecentItems()
        startListening()
    }
    
    private fun loadItems() {
        val itemsJson = prefs.getString(KEY_CLIPBOARD_ITEMS, null)
        if (itemsJson != null) {
            try {
                val items = deserializeItems(itemsJson)
                _clipboardItems.value = items
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load clipboard items", e)
            }
        }
    }
    
    private fun loadQuickSendItems() {
        val itemsJson = prefs.getString(KEY_QUICK_SEND_ITEMS, null)
        if (itemsJson != null) {
            try {
                val items = deserializeItems(itemsJson)
                _quickSendItems.value = items
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load quick send items", e)
            }
        }
    }

    private fun updateRecentItems() {
        val now = System.currentTimeMillis()
        val cutoff = now - 10 * 1000L
        _recentItems.value = _clipboardItems.value.filter { it.timestamp >= cutoff }
    }
    
    private fun saveItems() {
        val itemsJson = serializeItems(_clipboardItems.value)
        prefs.edit().putString(KEY_CLIPBOARD_ITEMS, itemsJson).apply()
    }
    
    private fun saveQuickSendItems() {
        val itemsJson = serializeItems(_quickSendItems.value)
        prefs.edit().putString(KEY_QUICK_SEND_ITEMS, itemsJson).apply()
    }
    
    private fun serializeItems(items: List<ClipboardItem>): String {
        return items.joinToString(separator = "|||") { item ->
            "${item.id}:::${item.text.escape()}:::${item.timestamp}:::${item.isPinned}:::${item.isQuickSend}"
        }
    }
    
    private fun deserializeItems(json: String): List<ClipboardItem> {
        if (json.isEmpty()) return emptyList()
        return json.split("|||").mapNotNull { itemStr ->
            val parts = itemStr.split(":::")
            if (parts.size == 5) {
                try {
                    ClipboardItem(
                        id = parts[0].toLong(),
                        text = parts[1].unescape(),
                        timestamp = parts[2].toLong(),
                        isPinned = parts[3].toBoolean(),
                        isQuickSend = parts[4].toBoolean()
                    )
                } catch (e: Exception) {
                    null
                }
            } else if (parts.size == 4) {
                try {
                    ClipboardItem(
                        id = parts[0].toLong(),
                        text = parts[1].unescape(),
                        timestamp = parts[2].toLong(),
                        isPinned = parts[3].toBoolean(),
                        isQuickSend = false
                    )
                } catch (e: Exception) {
                    null
                }
            } else null
        }
    }
    
    private fun String.escape(): String {
        return this.replace("|||", "〈PIPE〉").replace(":::", "〈COLON〉")
    }
    
    private fun String.unescape(): String {
        return this.replace("〈PIPE〉", "|||").replace("〈COLON〉", ":::")
    }

    fun exportToJson(): String {
        val items = _clipboardItems.value
        val sb = StringBuilder()
        sb.append("[")
        items.forEachIndexed { index, item ->
            if (index > 0) sb.append(",")
            sb.append("{\"id\":${item.id},\"text\":")
            sb.append(escapeJsonString(item.text))
            sb.append(",\"timestamp\":${item.timestamp},\"isPinned\":${item.isPinned},\"isQuickSend\":${item.isQuickSend}}")
        }
        sb.append("]")
        return sb.toString()
    }

    fun importFromJson(json: String, replace: Boolean = false): Int {
        val importedItems = parseJsonItems(json)
        if (importedItems.isEmpty()) return 0

        val currentItems = if (replace) mutableListOf() else _clipboardItems.value.toMutableList()

        for (item in importedItems) {
            val existingIndex = currentItems.indexOfFirst { it.text == item.text }
            if (existingIndex >= 0) {
                currentItems[existingIndex] = item
                currentItems.moveToTop(existingIndex)
            } else {
                currentItems.add(0, item)
            }
        }

        val maxItems = getMaxItems()
        val unpinnedCount = currentItems.count { !it.isPinned }
        if (unpinnedCount > maxItems) {
            val toRemove = currentItems
                .filter { !it.isPinned }
                .sortedBy { it.timestamp }
                .take(unpinnedCount - maxItems)
            currentItems.removeAll(toRemove.toSet())
        }

        _clipboardItems.value = currentItems
        saveItems()
        updateRecentItems()
        return importedItems.size
    }

    private fun parseJsonItems(json: String): List<ClipboardItem> {
        val items = mutableListOf<ClipboardItem>()
        var i = 0
        val len = json.length

        while (i < len) {
            if (json[i] == '{') {
                val end = findMatchingBrace(json, i)
                if (end > i) {
                    val objStr = json.substring(i + 1, end)
                    val item = parseJsonObject(objStr)
                    if (item != null) items.add(item)
                    i = end + 1
                } else {
                    i++
                }
            } else {
                i++
            }
        }
        return items
    }

    private fun findMatchingBrace(s: String, start: Int): Int {
        var depth = 0
        for (i in start until s.length) {
            when (s[i]) {
                '{' -> depth++
                '}' -> { depth--; if (depth == 0) return i }
            }
        }
        return -1
    }

    private fun parseJsonObject(objStr: String): ClipboardItem? {
        try {
            val id = extractJsonLong(objStr, "id") ?: ClipboardManager.generateId()
            val text = extractJsonString(objStr, "text") ?: return null
            val timestamp = extractJsonLong(objStr, "timestamp") ?: System.currentTimeMillis()
            val isPinned = extractJsonBoolean(objStr, "isPinned") ?: false
            val isQuickSend = extractJsonBoolean(objStr, "isQuickSend") ?: false
            return ClipboardItem(id = id, text = text, timestamp = timestamp, isPinned = isPinned, isQuickSend = isQuickSend)
        } catch (e: Exception) {
            return null
        }
    }

    private fun extractJsonString(s: String, key: String): String? {
        val pattern = "\"$key\""
        val idx = s.indexOf(pattern)
        if (idx < 0) return null
        var i = idx + pattern.length
        while (i < s.length && s[i] != ':') i++
        if (i >= s.length) return null
        i++
        while (i < s.length && s[i] == ' ') i++
        if (i >= s.length || s[i] != '"') return null
        val sb = StringBuilder()
        i++
        while (i < s.length && s[i] != '"') {
            if (s[i] == '\\' && i + 1 < s.length) {
                when (s[i + 1]) {
                    '"' -> sb.append('"')
                    '\\' -> sb.append('\\')
                    'n' -> sb.append('\n')
                    't' -> sb.append('\t')
                    'r' -> sb.append('\r')
                    '/' -> sb.append('/')
                    else -> { sb.append(s[i]); sb.append(s[i + 1]) }
                }
                i += 2
            } else {
                sb.append(s[i])
                i++
            }
        }
        return sb.toString()
    }

    private fun extractJsonLong(s: String, key: String): Long? {
        val pattern = "\"$key\""
        val idx = s.indexOf(pattern)
        if (idx < 0) return null
        var i = idx + pattern.length
        while (i < s.length && s[i] != ':') i++
        if (i >= s.length) return null
        i++
        while (i < s.length && s[i] == ' ') i++
        val start = i
        while (i < s.length && (s[i].isDigit() || s[i] == '-')) i++
        if (start == i) return null
        return s.substring(start, i).toLongOrNull()
    }

    private fun extractJsonBoolean(s: String, key: String): Boolean? {
        val pattern = "\"$key\""
        val idx = s.indexOf(pattern)
        if (idx < 0) return null
        var i = idx + pattern.length
        while (i < s.length && s[i] != ':') i++
        if (i >= s.length) return null
        i++
        while (i < s.length && s[i] == ' ') i++
        return when {
            s.startsWith("true", i) -> true
            s.startsWith("false", i) -> false
            else -> null
        }
    }

    private fun escapeJsonString(s: String): String {
        val sb = StringBuilder("\"")
        for (ch in s) {
            when (ch) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\t' -> sb.append("\\t")
                '\r' -> sb.append("\\r")
                else -> sb.append(ch)
            }
        }
        sb.append("\"")
        return sb.toString()
    }
    
    private fun startListening() {
        androidClipboardManager.addPrimaryClipChangedListener(clipboardListener)
    }

    fun release() {
        // Singleton — no cleanup needed.
    }
    
    fun addItem(text: String) {
        if (text.isBlank()) return
        
        val maxItems = getMaxItems()
        
        val currentItems = _clipboardItems.value.toMutableList()
        
        val existingIndex = currentItems.indexOfFirst { it.text == text }
        if (existingIndex >= 0) {
            val existing = currentItems[existingIndex]
            currentItems[existingIndex] = existing.copy(timestamp = System.currentTimeMillis())
            currentItems.moveToTop(existingIndex)
        } else {
            val newItem = ClipboardItem(text = text)
            currentItems.add(0, newItem)
            
            val unpinnedCount = currentItems.count { !it.isPinned }
            if (unpinnedCount > maxItems) {
                val toRemove = currentItems
                    .filter { !it.isPinned }
                    .sortedBy { it.timestamp }
                    .take(unpinnedCount - maxItems)
                currentItems.removeAll(toRemove.toSet())
            }
        }

        _clipboardItems.value = currentItems
        saveItems()
        updateRecentItems()
    }
    
    fun getMaxItems(): Int {
        return try {
            com.kingzcheung.xime.settings.SettingsPreferences.getClipboardMaxItems(context)
        } catch (e: Exception) {
            DEFAULT_MAX_ITEMS
        }
    }
    
    fun applyMaxItems(maxItems: Int) {
        val currentItems = _clipboardItems.value.toMutableList()
        val unpinnedCount = currentItems.count { !it.isPinned }
        if (unpinnedCount > maxItems) {
            val toRemove = currentItems
                .filter { !it.isPinned }
                .sortedBy { it.timestamp }
                .take(unpinnedCount - maxItems)
            currentItems.removeAll(toRemove.toSet())
            _clipboardItems.value = currentItems
            saveItems()
            updateRecentItems()
        }
    }
    
    private fun <T> MutableList<T>.moveToTop(index: Int) {
        if (index > 0) {
            val item = removeAt(index)
            add(0, item)
        }
    }
    
    fun removeItem(id: Long) {
        val currentItems = _clipboardItems.value.toMutableList()
        currentItems.removeAll { it.id == id }
        _clipboardItems.value = currentItems
        saveItems()
    }
    
    fun splitItem(id: Long) {
        val currentItems = _clipboardItems.value.toMutableList()
        val item = currentItems.find { it.id == id } ?: return
        val now = System.currentTimeMillis()
        val newItems = item.text.map { char ->
            ClipboardItem(
                text = char.toString(),
                timestamp = now
            )
        }
        val idx = currentItems.indexOfFirst { it.id == id }
        currentItems.removeAt(idx)
        currentItems.addAll(idx, newItems)
        _clipboardItems.value = currentItems
        saveItems()
    }
    
    fun clearAll() {
        val pinnedItems = _clipboardItems.value.filter { it.isPinned }
        _clipboardItems.value = pinnedItems
        saveItems()
    }
    
    fun addToQuickSend(id: Long) {
        val clipboardItem = _clipboardItems.value.find { it.id == id }
        if (clipboardItem != null) {
            val quickSendItem = clipboardItem.copy(isQuickSend = true, isPinned = true)
            val currentQuickSend = _quickSendItems.value.toMutableList()
            
            val existingIndex = currentQuickSend.indexOfFirst { it.text == quickSendItem.text }
            if (existingIndex >= 0) {
                currentQuickSend[existingIndex] = quickSendItem
            } else {
                currentQuickSend.add(0, quickSendItem)
                
                if (currentQuickSend.size > MAX_QUICK_SEND_ITEMS) {
                    currentQuickSend.removeAt(currentQuickSend.size - 1)
                }
            }
            
            _quickSendItems.value = currentQuickSend
            saveQuickSendItems()
        }
    }
    
    fun removeFromQuickSend(id: Long) {
        val currentQuickSend = _quickSendItems.value.toMutableList()
        currentQuickSend.removeAll { it.id == id }
        _quickSendItems.value = currentQuickSend
        saveQuickSendItems()
    }

    fun togglePinQuickSend(id: Long) {
        val currentItems = _quickSendItems.value.toMutableList()
        val index = currentItems.indexOfFirst { it.id == id }
        if (index > 0) {
            val item = currentItems.removeAt(index)
            currentItems.add(0, item)
            _quickSendItems.value = currentItems
            saveQuickSendItems()
        }
    }

    
    fun updateQuickSendItem(id: Long, newText: String): Boolean {
        if (newText.isBlank()) return false
        val currentItems = _quickSendItems.value.toMutableList()
        val index = currentItems.indexOfFirst { it.id == id }
        if (index < 0) return false
        currentItems[index] = currentItems[index].copy(
            text = newText,
            timestamp = System.currentTimeMillis()
        )
        _quickSendItems.value = currentItems
        saveQuickSendItems()
        return true
    }

    fun addQuickSendItem(text: String) {
        if (text.isBlank()) return
        
        val newItem = ClipboardItem(
            text = text,
            isQuickSend = true,
            isPinned = true
        )
        
        val currentQuickSend = _quickSendItems.value.toMutableList()
        
        val existingIndex = currentQuickSend.indexOfFirst { it.text == text }
        if (existingIndex >= 0) {
            currentQuickSend[existingIndex] = newItem.copy(timestamp = System.currentTimeMillis())
        } else {
            currentQuickSend.add(0, newItem)
            
            if (currentQuickSend.size > MAX_QUICK_SEND_ITEMS) {
                currentQuickSend.removeAt(currentQuickSend.size - 1)
            }
        }
        
        _quickSendItems.value = currentQuickSend
        saveQuickSendItems()
    }
    
    fun copyToSystemClipboard(text: String) {
        val clip = ClipData.newPlainText("kime_clipboard", text)
        androidClipboardManager.setPrimaryClip(clip)
    }
    
    fun getCurrentClipboardText(): String? {
        val clipData = androidClipboardManager.primaryClip
        return if (clipData != null && clipData.itemCount > 0) {
            clipData.getItemAt(0).text?.toString()
        } else null
    }
    
    fun getRecentItems(seconds: Int = 30): List<ClipboardItem> {
        val now = System.currentTimeMillis()
        val cutoff = now - seconds * 1000L
        return _clipboardItems.value.filter { it.timestamp >= cutoff }
    }

    fun copyImageToSystemClipboard(imagePath: String, label: String = "emoji_image"): Boolean {
        return try {
            val imageFile = File(imagePath)
            if (!imageFile.exists()) {
                Log.e(TAG, "Image file not found: $imagePath")
                return false
            }

            val cacheDir = File(context.cacheDir, "emoji_cache")
            if (!cacheDir.exists()) {
                cacheDir.mkdirs()
            }

            val cacheFile = File(cacheDir, imageFile.name)
            FileInputStream(imageFile).use { input ->
                cacheFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }

            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                cacheFile
            )

            val clip = ClipData.newUri(context.contentResolver, label, uri)
            androidClipboardManager.setPrimaryClip(clip)

            Log.d(TAG, "Image copied to clipboard: $uri")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to copy image to clipboard", e)
            false
        }
    }
}