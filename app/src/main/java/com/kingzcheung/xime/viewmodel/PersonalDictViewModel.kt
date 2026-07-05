package com.kingzcheung.xime.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.kingzcheung.xime.settings.DictEntry
import com.kingzcheung.xime.settings.PersonalDictManager
import com.kingzcheung.xime.settings.SchemaManager
import com.kingzcheung.xime.settings.SchemaMeta
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

data class PersonalDictUiState(
    val selectedSchema: String = "pinyin_simp",
    val availableSchemas: List<SchemaMeta> = emptyList(),
    val entries: List<DictEntry> = emptyList(),
    val filteredEntries: List<DictEntry> = emptyList(),
    val searchQuery: String = "",
    val isLoading: Boolean = true,
    val showAddDialog: Boolean = false,
    val showEditDialog: Boolean = false,
    val editIndex: Int = -1,
    val editWord: String = "",
    val editCode: String = ""
)

class PersonalDictViewModel(application: Application) : AndroidViewModel(application) {
    private val context = application.applicationContext

    private val _uiState = MutableStateFlow(PersonalDictUiState())
    val uiState: StateFlow<PersonalDictUiState> = _uiState.asStateFlow()

    init {
        loadSchemas()
    }

    private fun loadSchemas() {
        viewModelScope.launch {
            val schemas = withContext(Dispatchers.IO) {
                SchemaManager.discoverSchemas(context)
            }
            _uiState.update { it.copy(availableSchemas = schemas) }
            loadEntries()
        }
    }

    fun selectSchema(schemaId: String) {
        _uiState.update { it.copy(selectedSchema = schemaId, searchQuery = "") }
        loadEntries()
    }

    private fun loadEntries() {
        val schemaId = _uiState.value.selectedSchema
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            val entries = withContext(Dispatchers.IO) {
                PersonalDictManager.ensureSchemaPack(context, schemaId)
                PersonalDictManager.loadEntries(context, schemaId)
            }
            _uiState.update {
                it.copy(
                    entries = entries,
                    filteredEntries = filterEntries(entries, ""),
                    searchQuery = "",
                    isLoading = false
                )
            }
        }
    }

    fun addEntry(word: String, code: String) {
        val trimmedWord = word.trim()
        val trimmedCode = code.trim()
        if (trimmedWord.isEmpty() || trimmedCode.isEmpty()) return
        val schemaId = _uiState.value.selectedSchema

        viewModelScope.launch {
            val current = _uiState.value.entries
            val updated = current + DictEntry(trimmedWord, trimmedCode)
            withContext(Dispatchers.IO) {
                PersonalDictManager.saveEntries(context, schemaId, updated)
            }
            _uiState.update {
                val query = it.searchQuery
                it.copy(entries = updated, filteredEntries = filterEntries(updated, query))
            }
        }
    }

    fun updateEntry(index: Int, word: String, code: String) {
        val trimmedWord = word.trim()
        val trimmedCode = code.trim()
        if (trimmedWord.isEmpty() || trimmedCode.isEmpty()) return
        val schemaId = _uiState.value.selectedSchema

        viewModelScope.launch {
            val current = _uiState.value.entries.toMutableList()
            if (index < 0 || index >= current.size) return@launch
            current[index] = DictEntry(trimmedWord, trimmedCode)
            withContext(Dispatchers.IO) {
                PersonalDictManager.saveEntries(context, schemaId, current)
            }
            _uiState.update {
                val query = it.searchQuery
                it.copy(entries = current, filteredEntries = filterEntries(current, query))
            }
        }
    }

    fun deleteEntry(index: Int) {
        val schemaId = _uiState.value.selectedSchema
        viewModelScope.launch {
            val current = _uiState.value.entries.toMutableList()
            if (index < 0 || index >= current.size) return@launch
            current.removeAt(index)
            withContext(Dispatchers.IO) {
                PersonalDictManager.saveEntries(context, schemaId, current)
            }
            _uiState.update {
                val query = it.searchQuery
                it.copy(entries = current, filteredEntries = filterEntries(current, query))
            }
        }
    }

    fun setSearchQuery(query: String) {
        _uiState.update {
            it.copy(
                searchQuery = query,
                filteredEntries = filterEntries(it.entries, query)
            )
        }
    }

    fun clearSearch() {
        setSearchQuery("")
    }

    fun showAddDialog() {
        _uiState.update { it.copy(showAddDialog = true, editWord = "", editCode = "", editIndex = -1) }
    }

    fun hideAddDialog() {
        _uiState.update { it.copy(showAddDialog = false) }
    }

    fun showEditDialog() {
        _uiState.update { it.copy(showEditDialog = true) }
    }

    fun hideEditDialog() {
        _uiState.update { it.copy(showEditDialog = false) }
    }

    fun setEditing(index: Int, word: String, code: String) {
        _uiState.update { it.copy(editIndex = index, editWord = word, editCode = code) }
    }

    fun setEditWord(word: String) {
        _uiState.update { it.copy(editWord = word) }
    }

    fun setEditCode(code: String) {
        _uiState.update { it.copy(editCode = code) }
    }

    private fun filterEntries(entries: List<DictEntry>, query: String): List<DictEntry> {
        if (query.isEmpty()) return entries
        val lowerQuery = query.lowercase(Locale.ROOT)
        return entries.filter {
            it.word.contains(query) ||
                it.code.contains(query, ignoreCase = true) ||
                it.code.lowercase(Locale.ROOT).contains(lowerQuery)
        }
    }
}
