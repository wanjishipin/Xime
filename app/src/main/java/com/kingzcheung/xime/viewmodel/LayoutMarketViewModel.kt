package com.kingzcheung.xime.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.kingzcheung.xime.BuildConfig
import com.kingzcheung.xime.settings.KeysConfigHelper
import com.kingzcheung.xime.settings.MarketUpdateChecker
import com.kingzcheung.xime.settings.MarketLayoutItem
import com.kingzcheung.xime.settings.SchemaManager
import com.kingzcheung.xime.settings.SettingsPreferences
import com.kingzcheung.xime.settings.XimeIndexSource
import com.kingzcheung.xime.ui.theme.KeyboardThemes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class LayoutMarketUiState(
    val layouts: List<MarketLayoutItem> = emptyList(),
    val isLoading: Boolean = true,
    /** 正在应用的布局 id */
    val installingId: String? = null,
    /** 下载进度 0f~1f */
    val installProgress: Float = 0f,
    val errorMessage: String? = null,
    val toastMessage: String? = null,
    /** 本次列表命中的来源端点主机名 */
    val source: String = "",
    /** 用户选择的版本：layoutId → version */
    val selectedVersions: Map<String, String> = emptyMap(),
    /** 选中的分类标签：null 表示全部 */
    val selectedTag: String? = null,
    /** 当前已应用的布局 id / 版本 */
    val appliedLayoutId: String = "",
    val appliedVersion: String = "",
) {
    val availableTags: List<String>
        get() = layouts.flatMap { it.layout.tags }.distinct().sorted()

    val filteredLayouts: List<MarketLayoutItem>
        get() = layouts.filter { item ->
            selectedTag == null ||
                item.layout.tags.isEmpty() ||
                item.layout.tags.any { it == selectedTag }
        }
}

class LayoutMarketViewModel(application: Application) : AndroidViewModel(application) {
    private val context = application.applicationContext

    private val _uiState = MutableStateFlow(LayoutMarketUiState())
    val uiState: StateFlow<LayoutMarketUiState> = _uiState.asStateFlow()

    init {
        _uiState.update {
            it.copy(
                appliedLayoutId = SettingsPreferences.getAppliedLayoutId(context),
                appliedVersion = SettingsPreferences.getAppliedLayoutVersion(context),
            )
        }
        loadLayouts()
    }

    /** 加载/刷新布局列表。 */
    fun loadLayouts(manual: Boolean = false) {
        if (_uiState.value.isLoading && _uiState.value.layouts.isNotEmpty()) return
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            val appliedId = _uiState.value.appliedLayoutId
            val appliedVersions = if (appliedId.isBlank()) emptyMap()
            else mapOf(appliedId to _uiState.value.appliedVersion)
            val installedSchemaIds = withContext(Dispatchers.IO) {
                SchemaManager.discoverSchemas(context).map { it.schemaId }.toSet()
            }
            val result = XimeIndexSource.fetchLayouts(
                context = context,
                appVersion = BuildConfig.VERSION_NAME,
                installedVersions = appliedVersions,
                installedSchemaIds = installedSchemaIds,
            )
            result.onSuccess { fetch ->
                val existingSel = _uiState.value.selectedVersions
                val mergedSel = fetch.layouts.associate { item ->
                    val id = item.layout.id
                    val keep = existingSel[id]
                    if (keep != null && item.layout.versions.any { it.version == keep }) id to keep
                    else id to (item.layout.resolvedVersion()?.version ?: item.layout.currentVersion)
                }
                _uiState.update {
                    it.copy(
                        layouts = fetch.layouts,
                        isLoading = false,
                        source = fetch.source,
                        selectedVersions = mergedSel,
                        toastMessage = if (manual) "已刷新（来源：${fetch.source}）" else it.toastMessage,
                    )
                }
            }.onFailure { e ->
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        errorMessage = if (it.layouts.isEmpty())
                            (e.message ?: "加载失败，请检查网络后重试") else it.errorMessage,
                        toastMessage = if (it.layouts.isNotEmpty()) "刷新失败，已保留当前列表" else it.toastMessage,
                    )
                }
            }
        }
    }

    fun selectVersion(layoutId: String, version: String) {
        _uiState.update {
            it.copy(selectedVersions = it.selectedVersions + (layoutId to version))
        }
    }

    fun selectTag(tag: String?) {
        _uiState.update { it.copy(selectedTag = tag) }
    }

    fun clearToast() = _uiState.update { it.copy(toastMessage = null) }

    /** 应用布局（覆盖当前 xime.custom.yaml，不保留备份）。[version] 为空取 resolvedVersion。 */
    fun applyLayout(item: MarketLayoutItem, version: String? = null) {
        if (_uiState.value.installingId != null) return
        val layout = item.layout
        val targetVersion = version
            ?: _uiState.value.selectedVersions[layout.id]
            ?: layout.resolvedVersion()?.version.orEmpty()
        viewModelScope.launch {
            _uiState.update { it.copy(installingId = layout.id, installProgress = 0f) }
            val result = XimeIndexSource.installLayout(
                context = context,
                layout = layout,
                version = targetVersion.takeIf { it.isNotBlank() },
                onDownloadProgress = { read, total ->
                    if (total > 0) {
                        _uiState.update { it.copy(installProgress = read.toFloat() / total) }
                    }
                },
            )
            if (result.success) {
                SettingsPreferences.setAppliedLayout(context, layout.id, targetVersion, result.files)
                reloadKeyboardConfig()
                MarketUpdateChecker.refreshNow(context)
                _uiState.update { state ->
                    state.copy(
                        installingId = null,
                        installProgress = 0f,
                        appliedLayoutId = layout.id,
                        appliedVersion = targetVersion,
                        layouts = state.layouts.map {
                            if (it.layout.id == layout.id) it.copy(installedVersion = targetVersion) else it
                        },
                        toastMessage = "已应用：${layout.name.ifBlank { layout.id }}",
                    )
                }
            } else {
                _uiState.update {
                    it.copy(
                        installingId = null,
                        installProgress = 0f,
                        toastMessage = result.failureReason ?: "应用失败",
                    )
                }
            }
        }
    }

    /** 恢复默认：删除当前布局写入的文件并重载。 */
    fun resetLayout() {
        if (_uiState.value.appliedLayoutId.isBlank()) return
        if (_uiState.value.installingId != null) return
        viewModelScope.launch {
            val files = SettingsPreferences.getAppliedLayoutFiles(context)
            _uiState.update { it.copy(installingId = "__reset__") }
            withContext(Dispatchers.IO) { XimeIndexSource.resetLayout(context, files) }
            SettingsPreferences.clearAppliedLayout(context)
            reloadKeyboardConfig()
            // 恢复默认后原布局不再计入可更新，立即重算角标
            MarketUpdateChecker.refreshNow(context)
            _uiState.update { state ->
                state.copy(
                    installingId = null,
                    appliedLayoutId = "",
                    appliedVersion = "",
                    layouts = state.layouts.map { it.copy(installedVersion = null) },
                    toastMessage = "已恢复默认",
                )
            }
        }
    }

    /** 重载键盘配置并通知主题刷新（同进程，configVersion 驱动键盘重组）。 */
    private fun reloadKeyboardConfig() {
        KeysConfigHelper.loadConfig(context)
        KeyboardThemes.reload(context)
    }
}