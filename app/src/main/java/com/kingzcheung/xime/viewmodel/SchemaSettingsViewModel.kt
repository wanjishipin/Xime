package com.kingzcheung.xime.viewmodel

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import android.util.Log
import com.kingzcheung.xime.rime.RimeConfigHelper
import com.kingzcheung.xime.rime.RimeEngine
import com.kingzcheung.xime.settings.KeysConfigHelper
import com.kingzcheung.xime.settings.SchemaManager
import com.kingzcheung.xime.ui.theme.KeyboardThemes
import com.kingzcheung.xime.settings.SchemaMeta
import com.kingzcheung.xime.settings.SettingsPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class SchemaUiState(
    val allSchemas: List<SchemaMeta> = emptyList(),
    val enabledSchemas: List<String> = emptyList(),
    val currentSchema: String = "wubi86",
    val isDeploying: Boolean = false,
    val isDownloading: Boolean = false,
    val toastMessage: String? = null
)

class SchemaSettingsViewModel(application: Application) : AndroidViewModel(application) {
    private val context = application.applicationContext
    private val TAG = "SchemaSettingsViewModel"

    private val _uiState = MutableStateFlow(SchemaUiState())
    val uiState: StateFlow<SchemaUiState> = _uiState.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        // 在 IO 线程读盘（discoverSchemas/getEnabledSchemas 扫描文件），避免 ON_RESUME 在主线程卡顿
        viewModelScope.launch {
            val (allSchemas, enabledSchemas, currentSchema) = withContext(Dispatchers.IO) {
                Triple(
                    SchemaManager.discoverSchemas(context),
                    SchemaManager.getEnabledSchemas(context),
                    SettingsPreferences.getCurrentSchema(context),
                )
            }
            val sorted = allSchemas.sortedByDescending { it.schemaId in enabledSchemas }
            _uiState.update {
                it.copy(
                    allSchemas = sorted,
                    enabledSchemas = enabledSchemas,
                    currentSchema = currentSchema
                )
            }
        }
    }

    fun toggleSchema(schema: SchemaMeta) {
        val enabled = _uiState.value.enabledSchemas.toMutableList()
        if (schema.schemaId in enabled) {
            enabled.remove(schema.schemaId)
        } else {
            enabled.add(schema.schemaId)
        }
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                SchemaManager.setEnabledSchemas(context, enabled)
            }
            _uiState.update { it.copy(enabledSchemas = enabled) }
        }
    }

    fun selectSchema(schema: SchemaMeta) {
        if (_uiState.value.currentSchema == schema.schemaId) return
        SettingsPreferences.setCurrentSchema(context, schema.schemaId)
        _uiState.update { it.copy(currentSchema = schema.schemaId) }
        if (RimeEngine.isInitialized()) {
            val available = RimeEngine.getInstance().getAvailableSchemas()
            if (schema.schemaId in available) {
                RimeEngine.getInstance().switchSchema(schema.schemaId)
                showToast("已切换到${schema.name}")
            } else {
                showToast("请点击「部署」按钮")
            }
        }
    }

    fun importSchemaFile(uri: Uri) {
        viewModelScope.launch {
            val success = SchemaManager.importSchemaFile(context, uri)
            refresh()
            if (success) {
                showToast("导入成功")
            } else {
                showToast("导入失败")
            }
        }
    }

    fun importFromUrl(url: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(isDownloading = true) }
            val success = withContext(Dispatchers.IO) {
                SchemaManager.importFromUrl(getApplication(), url)
            }
            _uiState.update { it.copy(isDownloading = false) }
            refresh()
            showToast(if (success) "导入成功" else "下载或解压失败，请检查链接")
        }
    }

    fun deleteSchema(schema: SchemaMeta) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                SchemaManager.deleteSchemaFiles(context, schema.schemaId)
            }
            if (_uiState.value.currentSchema == schema.schemaId) {
                // 自动切换到另一个可用方案：优先选已启用的，其次选第一个
                val remaining = _uiState.value.allSchemas
                    .filter { it.schemaId != schema.schemaId }
                    .let { list ->
                        list.firstOrNull { it.schemaId in _uiState.value.enabledSchemas }
                            ?: list.firstOrNull()
                    }
                if (remaining != null) {
                    selectSchema(remaining)
                } else {
                    // 没有任何其他方案了，重置当前方案防止残留无效 ID
                    SettingsPreferences.setCurrentSchema(context, "")
                    _uiState.update { it.copy(currentSchema = "") }
                }
            }
            refresh()
            showToast("${schema.name} 已删除")
        }
    }

    fun deploySchema() {
        if (_uiState.value.isDeploying) return
        viewModelScope.launch {
            _uiState.update { it.copy(isDeploying = true) }
            val success = withContext(Dispatchers.IO) {
                KeysConfigHelper.loadConfig(context)
                KeyboardThemes.reload(context)
                val engine = RimeEngine.getInstance()
                val deployed = engine.deploy()
                if (deployed) {
                    RimeConfigHelper.storeDeploymentHash(context)
                }
                deployed
            }
            _uiState.update { it.copy(isDeploying = false) }
            showToast(if (success) "部署完成" else "部署失败")
            refresh()
        }
    }

    fun clearToast() {
        _uiState.update { it.copy(toastMessage = null) }
    }

    private fun showToast(message: String) {
        _uiState.update { it.copy(toastMessage = message) }
    }
}
