package com.kingzcheung.xime.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.kingzcheung.xime.settings.StorageStats
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 存储空间页状态：类目统计（后台扫描）、清理进行中标记与结果消息。
 * 统计结果为 null 表示首次扫描尚未完成（UI 显示加载态）。
 */
class StorageSpaceViewModel(application: Application) : AndroidViewModel(application) {

    private val _categories = MutableStateFlow<List<StorageStats.Category>?>(null)
    val categories: StateFlow<List<StorageStats.Category>?> = _categories.asStateFlow()

    /** 正在清理的类目 id；null 表示空闲 */
    private val _cleaningId = MutableStateFlow<String?>(null)
    val cleaningId: StateFlow<String?> = _cleaningId.asStateFlow()

    /** 清理结果消息（UI 消费后调 [consumeMessage] 置空） */
    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _categories.value = withContext(Dispatchers.IO) {
                StorageStats.collectCategories(getApplication())
            }
        }
    }

    fun clear(categoryId: String, title: String) {
        if (_cleaningId.value != null) return
        viewModelScope.launch {
            _cleaningId.value = categoryId
            val ok = withContext(Dispatchers.IO) {
                try {
                    StorageStats.clearCategory(getApplication(), categoryId)
                } catch (e: Exception) {
                    null
                }
            }
            _cleaningId.value = null
            _message.value = when (ok) {
                true -> "$title 已清理"
                false -> "$title 清理失败，请重试"
                null -> "$title 清理出错"
            }
            refresh()
        }
    }

    fun consumeMessage() {
        _message.value = null
    }
}
