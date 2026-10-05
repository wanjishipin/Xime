package com.kingzcheung.xime.viewmodel

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.kingzcheung.xime.settings.DictEntry
import com.kingzcheung.xime.settings.SchemaManager
import com.kingzcheung.xime.settings.UserDictIoManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

data class UserDictUiState(
    /** 本机用户词库（`<词典名>.userdb`），按名称升序。 */
    val dicts: List<UserDictIoManager.UserDict> = emptyList(),
    /** 首次列举词库；已有列表时刷新不再置位，避免页面闪一下加载态。 */
    val isLoading: Boolean = true,
    /** 当前查看的词库名；null 表示本机还没有用户词库。 */
    val selectedDict: String? = null,
    val entries: List<DictEntry> = emptyList(),
    val filteredEntries: List<DictEntry> = emptyList(),
    val searchQuery: String = "",
    /** 正在读取所看词库的词条（native 侧需销毁/重建输入会话，可能可感知地慢）。 */
    val isReading: Boolean = false,
    /** 正在导出 / 导入 / 增删词条（都要销毁重建输入会话，期间不让用户重复触发）。 */
    val isTransferring: Boolean = false,
    /** 一次性结果提示（导出/导入的成败与条数），看完可关掉。 */
    val message: String? = null,
    val messageIsError: Boolean = false
)

/**
 * 「用户词库」页：浏览输入时自动积累的 `<词典名>.userdb`，并导出/导入文本码表。
 *
 * 词库内容由引擎（librime leveldb）维护，读写都要在 native 侧销毁/重建输入会话，
 * 所以每次真正读取都可能需要等待（[UserDictUiState.isReading]）。
 * 页面不再自己维护"列表 / 详情"两级状态：词典由顶部选择卡片切换，返回交给导航路由。
 */
class UserDictViewModel(application: Application) : AndroidViewModel(application) {
    private val context = application.applicationContext

    private val _uiState = MutableStateFlow(UserDictUiState())
    val uiState: StateFlow<UserDictUiState> = _uiState.asStateFlow()

    init {
        refresh()
    }

    /** 重新列出本机词库；首次（或原先选中的词库已不存在）时选中默认词库并读取词条。 */
    fun refresh() {
        viewModelScope.launch {
            if (_uiState.value.dicts.isEmpty()) {
                _uiState.update { it.copy(isLoading = true) }
            }
            val (dicts, defaultDict) = withContext(Dispatchers.IO) {
                val listed = UserDictIoManager.list(context)
                // 默认看当前方案引用的词典（wubi86 方案 → wubi86.userdb），取不到就用列表第一本
                val preferred = SchemaManager.getEnabledSchemas(context).firstOrNull()
                    ?.let { SchemaManager.getReferencedDictName(context, it) }
                listed to UserDictIoManager.pickDefaultDict(listed.map { it.name }, preferred)
            }
            val current = _uiState.value.selectedDict
            val keep = current?.takeIf { name -> dicts.any { it.name == name } }
            val target = keep ?: defaultDict
            _uiState.update { it.copy(dicts = dicts, isLoading = false, selectedDict = target) }
            if (target != current) readEntries(target)
        }
    }

    /** 切换要查看的词库。 */
    fun selectDict(dictName: String) {
        if (dictName == _uiState.value.selectedDict) return
        readEntries(dictName)
    }

    private fun readEntries(dictName: String?) {
        if (dictName == null) {
            _uiState.update {
                it.copy(
                    selectedDict = null,
                    entries = emptyList(),
                    filteredEntries = emptyList(),
                    searchQuery = "",
                    isReading = false
                )
            }
            return
        }
        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    selectedDict = dictName,
                    isReading = true,
                    entries = emptyList(),
                    filteredEntries = emptyList(),
                    searchQuery = "",
                    message = null
                )
            }
            val entries = withContext(Dispatchers.IO) { UserDictIoManager.readEntries(dictName) }
            _uiState.update {
                // 读取期间用户可能已切到别的词库，丢弃过期结果
                if (it.selectedDict != dictName) it
                else it.copy(entries = entries, filteredEntries = filterEntries(entries, ""), isReading = false)
            }
        }
    }

    /**
     * 导出当前查看的词库到 SAF 目标（在文件选择器回调里调用）。
     * 格式为 librime 的文本码表（`词<TAB>码<TAB>频率` + `#@` 元数据头），与 PC 端互通。
     */
    fun exportTo(uri: Uri) {
        val dictName = _uiState.value.selectedDict ?: return
        viewModelScope.launch {
            _uiState.update { it.copy(isTransferring = true, message = null) }
            val result = withContext(Dispatchers.IO) {
                UserDictIoManager.exportTo(context, dictName, uri)
            }
            _uiState.update {
                it.copy(
                    isTransferring = false,
                    message = result.fold(
                        onSuccess = { count -> "已导出 $count 条词条到所选文件" },
                        onFailure = { e -> "导出失败：${e.message}" }
                    ),
                    messageIsError = result.isFailure
                )
            }
        }
    }

    /**
     * 从 SAF 来源导入进当前查看的词库（合并语义，不会清空原有条目）。
     */
    fun importFrom(uri: Uri) {
        val dictName = _uiState.value.selectedDict ?: return
        viewModelScope.launch {
            _uiState.update { it.copy(isTransferring = true, message = null) }
            val result = withContext(Dispatchers.IO) {
                UserDictIoManager.importFrom(context, dictName, uri)
            }
            afterWrite(dictName, result) { r ->
                r.fold(
                    onSuccess = { count -> "已导入 $count 条词条（合并进「$dictName」）" },
                    onFailure = { e -> "导入失败：${e.message}" }
                )
            }
        }
    }

    /**
     * 新增一条词条。走的是与「导入文本码表」同一条通道（写单行码表再 `Import`），
     * 因此键格式与引擎一致；库里已有同词同码时按 librime 的合并语义取较大频率。
     */
    fun addEntry(input: UserDictIoManager.EntryInput) {
        val dictName = _uiState.value.selectedDict ?: return
        viewModelScope.launch {
            _uiState.update { it.copy(isTransferring = true, message = null) }
            val result = withContext(Dispatchers.IO) {
                UserDictIoManager.addEntry(context, dictName, input)
            }
            afterWrite(dictName, result) { r ->
                r.fold(
                    onSuccess = { "已添加「${input.word}」（${input.code}）" },
                    onFailure = { e -> "添加失败：${e.message}" }
                )
            }
        }
    }

    /**
     * 删除一条词条（librime 的 tombstone 语义）。成功后界面立刻不再显示它；
     * 用户之后若再次输入并选中这个词，引擎会把它重新记录（与 PC 端一致）。
     */
    fun deleteEntry(entry: DictEntry) {
        val dictName = _uiState.value.selectedDict ?: return
        viewModelScope.launch {
            _uiState.update { it.copy(isTransferring = true, message = null) }
            val result = withContext(Dispatchers.IO) {
                UserDictIoManager.deleteEntry(context, dictName, entry.word, entry.code)
            }
            afterWrite(dictName, result) { r ->
                r.fold(
                    onSuccess = { "已删除「${entry.word}」（${entry.code}）" },
                    onFailure = { e -> "删除失败：${e.message}" }
                )
            }
        }
    }

    /**
     * 写完用户词库之后的公共收尾（导出不经过这里）：重读词条让界面立刻反映增删、
     * 刷新词库时间戳、给一次性提示。期间用户可能已切到别的词库 ⇒ 丢弃过期结果。
     */
    private suspend fun afterWrite(
        dictName: String,
        result: Result<Int>,
        message: (Result<Int>) -> String,
    ) {
        val entries = if (result.isSuccess) {
            withContext(Dispatchers.IO) { UserDictIoManager.readEntries(dictName) }
        } else {
            null
        }
        _uiState.update { state ->
            if (state.selectedDict != dictName) {
                state
            } else {
                state.copy(
                    isTransferring = false,
                    entries = entries ?: state.entries,
                    filteredEntries = entries?.let { filterEntries(it, state.searchQuery) }
                        ?: state.filteredEntries,
                    message = message(result),
                    messageIsError = result.isFailure
                )
            }
        }
        if (result.isSuccess) {
            // userdb 被改写 ⇒ 列表里的时间戳需要刷新（选择不变，不会重复读取词条）
            refresh()
        }
    }

    /** 关掉导出/导入的结果提示。 */
    fun dismissMessage() {
        _uiState.update { it.copy(message = null) }
    }

    fun setSearchQuery(query: String) {
        _uiState.update { it.copy(searchQuery = query, filteredEntries = filterEntries(it.entries, query)) }
    }

    fun clearSearch() = setSearchQuery("")

    private fun filterEntries(entries: List<DictEntry>, query: String): List<DictEntry> {
        if (query.isEmpty()) return entries
        val lower = query.lowercase(Locale.ROOT)
        return entries.filter {
            it.word.contains(query) || it.code.contains(query, ignoreCase = true) ||
                it.code.lowercase(Locale.ROOT).contains(lower)
        }
    }
}