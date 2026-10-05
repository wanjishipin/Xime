package com.kingzcheung.xime.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.kingzcheung.xime.settings.MarketUpdateChecker
import com.kingzcheung.xime.settings.FileConflictInfo
import com.kingzcheung.xime.settings.SchemaManifestManager
import com.kingzcheung.xime.settings.SchemaManager
import com.kingzcheung.xime.settings.SchemaManager.InstallFromDirResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class LocalPackageItem(
    val packageId: String,
    val displayName: String,
    val version: String,
    val downloaded: Boolean,
    val installed: Boolean,
    val schemaCount: Int = 0,
    val fileCount: Int = 0,
) {
    val isImport: Boolean get() = packageId.startsWith("import_")
    val statusLabel: String
        get() = when {
            installed && downloaded -> "已安装"
            installed -> "已安装"
            downloaded -> "已下载"
            else -> ""
        }
}

data class SchemaLocalUiState(
    val packages: List<LocalPackageItem> = emptyList(),
    val isLoading: Boolean = false,
    val installingId: String? = null,
    val errorMessage: String? = null,
    val toastMessage: String? = null,
    val conflictPackageId: String? = null,
    val conflictingSchemeIds: List<String> = emptyList(),
)

class SchemaLocalViewModel(application: Application) : AndroidViewModel(application) {
    private val context = application.applicationContext

    private val _uiState = MutableStateFlow(SchemaLocalUiState())
    val uiState: StateFlow<SchemaLocalUiState> = _uiState.asStateFlow()

    init {
        loadLocalPackages()
    }

    fun loadLocalPackages() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            val installedPkgs = withContext(Dispatchers.IO) {
                SchemaManifestManager.getInstalledPackages(context)
            }
            val downloadedIds = withContext(Dispatchers.IO) {
                val marketDir = SchemaManager.getMarketDir(context)
                if (!marketDir.exists()) emptySet()
                else marketDir.listFiles()?.mapNotNull { sub ->
                    if (sub.isDirectory && sub.listFiles()?.any { it.isFile } == true) sub.name
                    else null
                }?.toSet() ?: emptySet()
            }
            val installedMap = installedPkgs.associateBy { it.packageId }
            val allIds = (downloadedIds + installedMap.keys).sorted()
            val packages = allIds.map { id ->
                val info = installedMap[id]
                LocalPackageItem(
                    packageId = id,
                    displayName = info?.displayName ?: id,
                    version = info?.version ?: "",
                    downloaded = id in downloadedIds,
                    installed = info != null,
                    schemaCount = info?.schemaCount ?: 0,
                    fileCount = info?.fileCount ?: 0,
                )
            }
            _uiState.update { it.copy(packages = packages, isLoading = false) }
        }
    }

    fun installPackage(item: LocalPackageItem) {
        if (_uiState.value.installingId != null) return
        viewModelScope.launch {
            _uiState.update { it.copy(installingId = item.packageId) }

            // 检查 rime/ 下是否已有其他方案（无论是否被清单追踪）
            val hasSchemas = withContext(Dispatchers.IO) {
                SchemaManager.discoverSchemas(context).isNotEmpty()
            }
            if (hasSchemas) {
                _uiState.update { it.copy(installingId = null) }
                val otherInstalled = withContext(Dispatchers.IO) {
                    SchemaManifestManager.getInstalledPackages(context)
                        .map { it.packageId }
                        .filter { it != item.packageId }
                }
                val targetIds = if (otherInstalled.isNotEmpty()) otherInstalled else listOf("builtin")
                _uiState.update {
                    it.copy(
                        conflictPackageId = item.packageId,
                        conflictingSchemeIds = targetIds,
                    )
                }
                return@launch
            }

            // 直接安装（后续由 UI 提示用户部署）
            val result = try {
                withContext(Dispatchers.IO) {
                    SchemaManager.installPackageFromMarketDir(
                        context = context,
                        packageId = item.packageId,
                        displayName = item.displayName,
                        version = item.version,
                        fromMarket = !item.isImport,
                    )
                }
            } catch (e: Exception) {
                android.util.Log.e("SchemaLocalViewModel", "installPackage exception", e)
                InstallFromDirResult(success = false, failureReason = "安装异常: ${e.message}")
            }
            _uiState.update { st -> st.copy(installingId = null) }
            if (result.success) {
                val msg = buildString {
                    append("已安装「${item.displayName}」，点「部署」生效")
                    if (result.parseFailures.isNotEmpty()) {
                        append("\n以下方案解析失败：")
                        result.parseFailures.forEach { append("\n  · $it") }
                    }
                }
                showToast(msg)
            } else if (result.conflicts.isNotEmpty()) {
                // 注册表冲突 → 进入冲突解决流程
                val otherInstalled = withContext(Dispatchers.IO) {
                    SchemaManifestManager.getInstalledPackages(context)
                        .map { it.packageId }
                        .filter { it != item.packageId }
                }
                val targetIds = result.conflicts
                    .flatMap { it.claimedBy }
                    .distinct()
                    .filter { it != item.packageId }
                    .ifEmpty { otherInstalled }
                    .ifEmpty { listOf("builtin") }
                _uiState.update {
                    it.copy(
                        conflictPackageId = item.packageId,
                        conflictingSchemeIds = targetIds,
                    )
                }
            } else {
                showToast(result.failureReason ?: "安装失败")
            }
            loadLocalPackages()
        }
    }

    fun confirmInstallWithUninstall() {
        if (_uiState.value.installingId != null) return
        val pkgId = _uiState.value.conflictPackageId ?: return
        val item = _uiState.value.packages.firstOrNull { it.packageId == pkgId } ?: return
        viewModelScope.launch {
            _uiState.update { it.copy(conflictPackageId = null, installingId = pkgId) }
            for (sid in _uiState.value.conflictingSchemeIds) {
                val uninstallOk = withContext(Dispatchers.IO) {
                    try {
                        // 先刷新 builtin 清单：APP 更新后可能新增了未被清单追踪的文件
                        SchemaManifestManager.refreshBuiltinManifest(context)
                        SchemaManifestManager.uninstallWithManifest(context, sid).success
                    } catch (e: Exception) {
                        android.util.Log.e("SchemaLocalViewModel", "uninstall $sid failed", e)
                        false
                    }
                }
                if (!uninstallOk) {
                    _uiState.update {
                        it.copy(installingId = null, conflictPackageId = null, conflictingSchemeIds = emptyList())
                    }
                    showToast("卸载冲突方案「$sid」失败，安装已取消")
                    loadLocalPackages()
                    return@launch
                }
            }
            val result = try {
                withContext(Dispatchers.IO) {
                    SchemaManager.installPackageFromMarketDir(
                        context = context,
                        packageId = pkgId,
                        displayName = item.displayName,
                        version = item.version,
                        fromMarket = !item.isImport,
                    )
                }
            } catch (e: Exception) {
                android.util.Log.e("SchemaLocalViewModel", "confirmInstallWithUninstall exception", e)
                InstallFromDirResult(success = false, failureReason = "安装异常: ${e.message}")
            }
            _uiState.update { st -> st.copy(installingId = null, conflictingSchemeIds = emptyList()) }
            if (result.success) {
                val msg = buildString {
                    append("已安装「${item.displayName}」，点「部署」生效")
                    if (result.parseFailures.isNotEmpty()) {
                        append("\n以下方案解析失败：")
                        result.parseFailures.forEach { append("\n  · $it") }
                    }
                }
                showToast(msg)
            } else {
                val reason = if (result.failureReason != null) {
                    result.failureReason
                } else if (result.conflicts.isNotEmpty()) {
                    result.conflicts.joinToString("、") { "${it.fileName}（已被 ${it.claimedBy.joinToString("、")} 使用）" }
                } else {
                    "安装失败"
                }
                showToast(reason)
            }
            loadLocalPackages()
        }
    }

    fun cancelConflictInstall() {
        _uiState.update { it.copy(conflictPackageId = null, conflictingSchemeIds = emptyList()) }
    }

    fun deleteDownloaded(item: LocalPackageItem) {
        if (item.packageId == "builtin") { showToast("内置方案不可删除"); return }
        viewModelScope.launch {
            val ok = withContext(Dispatchers.IO) {
                val dir = SchemaManager.getMarketDir(context, item.packageId)
                if (dir.exists()) { dir.deleteRecursively(); true } else false
            }
            showToast(if (ok) "已删除" else "删除失败")
            if (ok) MarketUpdateChecker.refreshNow(context)
            loadLocalPackages()
        }
    }

    fun uninstall(item: LocalPackageItem) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                SchemaManager.deleteSchemaFiles(context, item.packageId)
                SchemaManager.deleteSchemeArchive(context, item.packageId)
            }
            // 若卸载后没有已安装的方案了，全量清理 rime/ 残留
            val remaining = withContext(Dispatchers.IO) {
                SchemaManifestManager.getInstalledPackages(context)
            }
            if (remaining.isEmpty()) {
                withContext(Dispatchers.IO) {
                    SchemaManager.cleanRimeDir(context)
                }
            }
            showToast("已卸载")
            MarketUpdateChecker.refreshNow(context)
            loadLocalPackages()
        }
    }

    fun clearToast() = _uiState.update { it.copy(toastMessage = null) }

    private fun showToast(message: String) = _uiState.update { it.copy(toastMessage = message) }
}
