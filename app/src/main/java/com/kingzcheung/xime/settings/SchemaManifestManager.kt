package com.kingzcheung.xime.settings

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.security.DigestInputStream
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class FileConflictInfo(
    val fileName: String,
    val existingSha256: String,
    val newSha256: String,
    val claimedBy: List<String>,
) {
    val isRealConflict: Boolean get() = existingSha256 != newSha256
}

data class UninstallResult(
    val success: Boolean,
    val deletedFiles: Int = 0,
    val manifestExisted: Boolean = true,
    val message: String = "",
)

/**
 * 方案文件清单管理系统：
 * - 全局注册表 (.registry.json)：记录 rime/ 下每个文件被哪些方案声明拥有
 * - 方案清单 (.manifests/{schemeId}.json)：记录单个方案安装的全部文件
 *
 * 用于文件冲突检测、精准卸载、依赖共享追踪。
 */
object SchemaManifestManager {
    private const val TAG = "SchemaManifestManager"
    private const val REGISTRY_FILE = ".registry.json"
    private const val MANIFESTS_DIR = ".manifests"
    private const val REGISTRY_VERSION = 1

    fun getRegistryFile(context: Context): File =
        File(context.filesDir, REGISTRY_FILE)

    fun getManifestsDir(context: Context): File =
        File(context.filesDir, MANIFESTS_DIR)

    fun getManifestFile(context: Context, schemeId: String): File =
        File(getManifestsDir(context), "$schemeId.json")

    // ── Registry ──

    suspend fun loadRegistry(context: Context): JSONObject = withContext(Dispatchers.IO) {
        val file = getRegistryFile(context)
        if (!file.exists()) {
            JSONObject().apply {
                put("version", REGISTRY_VERSION)
                put("files", JSONObject())
            }
        } else {
            try {
                JSONObject(file.readText())
            } catch (e: Exception) {
                Log.w(TAG, "registry corrupted, resetting", e)
                JSONObject().apply {
                    put("version", REGISTRY_VERSION)
                    put("files", JSONObject())
                }
            }
        }
    }

    private suspend fun saveRegistry(context: Context, registry: JSONObject) {
        withContext(Dispatchers.IO) {
            val file = getRegistryFile(context)
            file.parentFile?.mkdirs()
            file.writeText(registry.toString(2))
        }
    }

    // ── Per-scheme Manifest ──

    suspend fun loadManifest(context: Context, schemeId: String): JSONObject? = withContext(Dispatchers.IO) {
        val file = getManifestFile(context, schemeId)
        if (!file.exists()) return@withContext null
        try {
            JSONObject(file.readText())
        } catch (e: Exception) {
            Log.w(TAG, "manifest for $schemeId corrupted", e)
            null
        }
    }

    private suspend fun saveManifest(context: Context, schemeId: String, manifest: JSONObject) {
        withContext(Dispatchers.IO) {
            val file = getManifestFile(context, schemeId)
            file.parentFile?.mkdirs()
            file.writeText(manifest.toString(2))
        }
    }

    suspend fun deleteManifest(context: Context, schemeId: String) {
        withContext(Dispatchers.IO) {
            getManifestFile(context, schemeId).delete()
        }
    }

    // ── SHA256 helper ──

    private fun fileSha256(file: File): String? {
        return try {
            val digest = MessageDigest.getInstance("SHA-256")
            FileInputStream(file).use { input ->
                DigestInputStream(input, digest).use { dis ->
                    val buffer = ByteArray(8192)
                    while (dis.read(buffer) != -1) { }
                }
            }
            digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
        } catch (e: Exception) {
            Log.w(TAG, "sha256 failed for ${file.name}", e)
            null
        }
    }

    // ── Conflict Detection ──

    /**
     * 检测待安装方案与已安装方案的文件冲突。
     * 仅报告真正冲突（同名不同内容）；同内容视为共享依赖自动放行。
     *
     * @param newFileSha256 待安装方案中各目标文件的 sha256 映射。
     *   从市场包的归档内容计算得到，而非从 rime/ 目录下已有文件计算。
     */
    suspend fun detectConflicts(
        context: Context,
        schemeId: String,
        targetFiles: List<String>,
        newFileSha256: Map<String, String> = emptyMap(),
    ): List<FileConflictInfo> = withContext(Dispatchers.IO) {
        val registry = loadRegistry(context)
        val files = registry.optJSONObject("files") ?: return@withContext emptyList()
        val conflicts = mutableListOf<FileConflictInfo>()

        for (fileName in targetFiles) {
            val entry = files.optJSONObject(fileName) ?: continue
            val claimants = entry.optJSONArray("claimedBy") ?: continue
            // 同一方案重新安装/升级：不视为冲突
            if (jsonArrayToList(claimants).any { it == schemeId }) continue

            val existingSha256 = entry.optString("sha256", "")
            val newSha256 = newFileSha256[fileName] ?: continue
            if (existingSha256 != newSha256) {
                conflicts.add(FileConflictInfo(
                    fileName = fileName,
                    existingSha256 = existingSha256,
                    newSha256 = newSha256,
                    claimedBy = jsonArrayToList(claimants),
                ))
            }
        }
        conflicts
    }

    // ── Create Manifest After Installation ──

    /**
     * 安装成功后，为方案创建文件清单并更新全局注册表。
     * @param extractedFiles 安装到 rime/ 的文件相对路径列表
     */
    suspend fun createManifest(
        context: Context,
        schemeId: String,
        displayName: String,
        version: String = "",
        fromMarket: Boolean = true,
        extractedFiles: List<String>,
        dependencyIds: List<String> = emptyList(),
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val rimeDir = SchemaManager.getRimeDir(context)
            val fileEntries = JSONObject()

            for (fileName in extractedFiles) {
                val file = File(rimeDir, fileName)
                if (!file.exists()) continue
                val sha256 = fileSha256(file) ?: continue
                fileEntries.put(fileName, JSONObject().apply {
                    put("sha256", sha256)
                    put("size", file.length())
                })
            }

            val manifest = JSONObject().apply {
                put("schemeId", schemeId)
                put("displayName", displayName)
                put("version", version)
                put("installedAt", SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ", Locale.US).format(Date()))
                put("fromMarket", fromMarket)
                put("legacy", false)
                put("files", fileEntries)
                if (dependencyIds.isNotEmpty()) {
                    put("dependencies", JSONArray(dependencyIds))
                }
            }
            saveManifest(context, schemeId, manifest)

            // 更新全局注册表
            val registry = loadRegistry(context)
            val allFiles = registry.optJSONObject("files") ?: JSONObject()
            val keysIt = fileEntries.keys()
            while (keysIt.hasNext()) {
                val fn = keysIt.next() as String
                val fe = fileEntries.getJSONObject(fn)
                val existing = allFiles.optJSONObject(fn)
                if (existing != null) {
                    val claimants = existing.optJSONArray("claimedBy") ?: JSONArray()
                    if (!jsonArrayToList(claimants).contains(schemeId)) {
                        claimants.put(schemeId)
                    }
                    existing.put("claimedBy", claimants)
                } else {
                    allFiles.put(fn, JSONObject().apply {
                        put("sha256", fe.getString("sha256"))
                        put("size", fe.getLong("size"))
                        put("claimedBy", JSONArray(listOf(schemeId)))
                    })
                }
            }
            registry.put("files", allFiles)
            saveRegistry(context, registry)

            Log.i(TAG, "manifest created for $schemeId: ${fileEntries.length()} files")
            true
        } catch (e: Exception) {
            Log.e(TAG, "failed to create manifest for $schemeId", e)
            false
        }
    }

    /** 在安装依赖后，把依赖包 id 追加到主方案的清单中。 */
    suspend fun appendDependencies(
        context: Context,
        schemeId: String,
        depIds: List<String>,
    ) {
        if (depIds.isEmpty()) return
        withContext(Dispatchers.IO) {
            try {
                val manifest = loadManifest(context, schemeId) ?: return@withContext
                val existing = mutableListOf<String>()
                val depsArray = manifest.optJSONArray("dependencies")
                if (depsArray != null) {
                    for (i in 0 until depsArray.length()) {
                        existing.add(depsArray.getString(i))
                    }
                }
                val merged = (existing + depIds).distinct()
                manifest.put("dependencies", JSONArray(merged))
                saveManifest(context, schemeId, manifest)
            } catch (e: Exception) {
                Log.e(TAG, "appendDependencies failed for $schemeId", e)
            }
        }
    }

    // ── Uninstall Using Manifest ──

    /**
     * 基于清单卸载方案，整体删除方案所属的所有文件（不保留共享文件）。
     */
    suspend fun uninstallWithManifest(
        context: Context,
        schemeId: String,
    ): UninstallResult = withContext(Dispatchers.IO) {
        val manifest = loadManifest(context, schemeId)
        if (manifest == null) {
            return@withContext UninstallResult(
                success = false,
                manifestExisted = false,
                message = "清单不存在，请使用传统方式删除",
            )
        }

        try {
            val files = manifest.optJSONObject("files") ?: JSONObject()
            val rimeDir = SchemaManager.getRimeDir(context)
            val registry = loadRegistry(context)
            val allFiles = registry.optJSONObject("files") ?: JSONObject()

            // 收集清单中所有方案 ID，用于后续清理衍生文件
            val schemaIds = mutableSetOf<String>()
            val keysIt = files.keys()
            while (keysIt.hasNext()) {
                val fn = keysIt.next() as String
                if (fn.endsWith(".schema.yaml")) {
                    schemaIds.add(fn.removeSuffix(".schema.yaml"))
                }
            }

            // 在删除 .custom.yaml 之前，先读取每个方案的 custom_phrase dict 名
            val customPhraseNames = schemaIds.associateWith { sid ->
                PersonalDictManager.getCustomPhraseDictName(rimeDir, sid)
            }

            var deletedCount = 0

            // 删除清单记录的文件（检查 registry claimedBy，多包共享时不删除）
            val keysIt2 = files.keys()
            while (keysIt2.hasNext()) {
                val fn = keysIt2.next() as String
                val claimEntry = allFiles.optJSONObject(fn)
                val claimants = if (claimEntry != null) {
                    val arr = claimEntry.optJSONArray("claimedBy")
                    if (arr != null) jsonArrayToList(arr) else emptyList()
                } else emptyList()

                if (claimants.size <= 1 || (claimants.size == 1 && claimants[0] == schemeId)) {
                    val file = File(rimeDir, fn)
                    if (file.exists()) { file.delete(); deletedCount++ }
                    allFiles.remove(fn)
                } else {
                    // 还有其他包声明该文件：仅移除当前包，保留文件
                    val updated = claimants.filter { it != schemeId }
                    claimEntry!!.put("claimedBy", JSONArray(updated))
                }
            }

            // 清理每个方案由于 ensureSchemaPack 等生成的衍生文件
            val buildDir = SchemaManager.getBuildDir(context)
            for (sid in schemaIds) {
                val customYaml = File(rimeDir, "$sid.custom.yaml")
                if (customYaml.exists()) { customYaml.delete(); deletedCount++ }

                val mergedDict = File(rimeDir, "${sid}_merged.dict.yaml")
                if (mergedDict.exists()) { mergedDict.delete(); deletedCount++ }

                val dictName = customPhraseNames[sid] ?: "custom_phrase"
                val phraseFile = File(rimeDir, "$dictName.txt")
                if (phraseFile.exists()) {
                    // 检查 registry 确认没有其他包声明这个短语文件
                    val pfEntry = allFiles.optJSONObject("$dictName.txt")
                    val pfClaimants = if (pfEntry != null) {
                        val arr = pfEntry.optJSONArray("claimedBy")
                        if (arr != null) jsonArrayToList(arr) else emptyList()
                    } else emptyList()
                    val otherClaimants = pfClaimants.filter { it != schemeId }
                    if (otherClaimants.isEmpty()) {
                        phraseFile.delete(); deletedCount++
                    }
                }

                if (buildDir.exists()) {
                    buildDir.listFiles { f -> f.name.startsWith("$sid.") }
                        ?.forEach { it.delete(); deletedCount++ }
                }
            }

            registry.put("files", allFiles)
            saveRegistry(context, registry)
            getManifestFile(context, schemeId).delete()

            Log.i(TAG, "uninstalled $schemeId: $deletedCount files removed")
            UninstallResult(
                success = true,
                deletedFiles = deletedCount,
                message = "已删除 $deletedCount 个文件",
            )
        } catch (e: Exception) {
            Log.e(TAG, "uninstall failed for $schemeId", e)
            UninstallResult(success = false, message = "卸载失败: ${e.message}")
        }
    }

    /** 检查一个文件是否列入受保护列表（不被方案覆盖和追踪）。 */
    fun isProtectedSystemFile(name: String): Boolean {
        val base = name.substringAfterLast('/')
        return base == "default.yaml" ||
               base == "xime.yaml" ||
               name.startsWith("build/")
    }

    // ── Migration ──

    private const val KEY_MIGRATION_VERSION = "manifest_migration_version"
    private const val MIGRATION_VERSION_CURRENT = 3
    internal const val BUILTIN_PACKAGE_ID = "builtin"

    /**
     * 为旧版已安装方案创建/合并遗留清单。
     * v1 → v2：把旧版单条 per-schema 清单合并成一个 `builtin` 包。
     */
    /**
     * 确保 market/builtin/ 目录存在，若不存在则从已安装的内置方案文件创建。
     * 仅在首次或目录缺失时执行。
     */
    private suspend fun ensureBuiltinBackup(context: Context) {
        withContext(Dispatchers.IO) {
            val builtinDir = SchemaManager.getMarketDir(context, BUILTIN_PACKAGE_ID)
            if (builtinDir.exists() && builtinDir.listFiles()?.isNotEmpty() == true) return@withContext
            try {
                val rimeDir = SchemaManager.getRimeDir(context)
                builtinDir.mkdirs()
                val manifestFile = getManifestFile(context, BUILTIN_PACKAGE_ID)
                if (!manifestFile.exists()) return@withContext
                val manifest = JSONObject(manifestFile.readText())
                val files = manifest.optJSONObject("files") ?: return@withContext
                val keys = files.keys()
                while (keys.hasNext()) {
                    val fn = keys.next() as String
                    val src = File(rimeDir, fn)
                    copyToBuiltinBackup(src, builtinDir, fn)
                }
            } catch (e: Exception) {
                Log.e(TAG, "failed to backup builtin", e)
            }
        }
    }

    /** 迁移 .manifests/ 和 .registry.json 从 rime/ 到 filesDir/。 */
    private suspend fun migrateManifestsLocation(context: Context) = withContext(Dispatchers.IO) {
        val rimeDir = SchemaManager.getRimeDir(context)
        val oldManifests = File(rimeDir, MANIFESTS_DIR)
        val oldRegistry = File(rimeDir, REGISTRY_FILE)
        val newManifests = getManifestsDir(context)
        val newRegistry = getRegistryFile(context)

        try {
            if (oldManifests.exists() && !newManifests.exists()) {
                oldManifests.renameTo(newManifests)
                Log.i(TAG, "Migrated .manifests/ from rime/ to filesDir/")
            } else if (oldManifests.exists()) {
                oldManifests.deleteRecursively()
                Log.i(TAG, "Removed old rime/.manifests/ (already at new location)")
            }

            if (oldRegistry.exists() && !newRegistry.exists()) {
                oldRegistry.renameTo(newRegistry)
                Log.i(TAG, "Migrated .registry.json from rime/ to filesDir/")
            } else if (oldRegistry.exists()) {
                oldRegistry.delete()
                Log.i(TAG, "Removed old rime/.registry.json (already at new location)")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to migrate manifests location", e)
        }
    }

    suspend fun migrateLegacySchemas(context: Context) {
        val prefs = SettingsPreferences.getPrefsPublic(context)
        val prevVersion = prefs.getInt(KEY_MIGRATION_VERSION, 0)

        withContext(Dispatchers.IO) {
            try {
                // v3: 把 .manifests/ 和 .registry.json 从 rime/ 移到 filesDir/
                migrateManifestsLocation(context)

                // 确保 market/builtin/ 存在（迁移前没有此目录的旧版本会在此补齐）
                ensureBuiltinBackup(context)

                // 每次启动将 untracked 文件追加到 builtin 清单（APP 更新后新文件不会被旧清单追踪）
                refreshBuiltinManifest(context)

                if (prevVersion >= MIGRATION_VERSION_CURRENT) return@withContext

                val rimeDir = SchemaManager.getRimeDir(context)
                getManifestsDir(context).mkdirs()
                var changed = false

                // 清理旧的 per-schema 清单（非 market、非 import、非 builtin）
                val manifestDir = getManifestsDir(context)
                if (manifestDir.exists()) {
                    manifestDir.listFiles { f -> f.name.endsWith(".json") }?.forEach { file ->
                        try {
                            val m = JSONObject(file.readText())
                            val id = m.getString("schemeId")
                            if (m.optBoolean("fromMarket", false)) return@forEach
                            if (id == BUILTIN_PACKAGE_ID) return@forEach
                            if (id.startsWith("import_")) return@forEach
                            file.delete()
                            changed = true
                        } catch (_: Exception) { }
                    }
                }

                // 扫描 rime/ 下所有文件，归入 builtin 包（排除系统文件与用户数据）
                val allFilesInRime = mutableSetOf<String>()
                rimeDir.walkTopDown().forEach { f ->
                    if (!f.isFile) return@forEach
                    val relPath = f.toRelativeString(rimeDir).replace('\\', '/')
                    if (isProtectedSystemFile(relPath)) return@forEach
                    if (isUserDataFile(relPath)) return@forEach
                    allFilesInRime.add(relPath)
                }
                if (allFilesInRime.isNotEmpty()) {
                    val fileEntries = JSONObject()

                    val builtinDir = SchemaManager.getMarketDir(context, BUILTIN_PACKAGE_ID)
                    builtinDir.mkdirs()
                    for (fn in allFilesInRime) {
                        val file = File(rimeDir, fn)
                        if (!file.exists()) continue
                        val sha256 = fileSha256(file) ?: continue
                        fileEntries.put(fn, JSONObject().apply {
                            put("sha256", sha256)
                            put("size", file.length())
                        })
                        copyToBuiltinBackup(file, builtinDir, fn)
                    }

                    if (fileEntries.length() == 0) return@withContext

                    val manifest = JSONObject().apply {
                        put("schemeId", BUILTIN_PACKAGE_ID)
                        put("displayName", "系统内置方案")
                        put("version", "")
                        put("installedAt", SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ", Locale.US).format(Date()))
                        put("fromMarket", false)
                        put("legacy", true)
                        put("files", fileEntries)
                    }
                    saveManifest(context, BUILTIN_PACKAGE_ID, manifest)

                    val registry = loadRegistry(context)
                    val allFiles = registry.optJSONObject("files") ?: JSONObject()
                    val keysIt = fileEntries.keys()
                    while (keysIt.hasNext()) {
                        val fn = keysIt.next() as String
                        val fe = fileEntries.getJSONObject(fn)
                        allFiles.put(fn, JSONObject().apply {
                            put("sha256", fe.getString("sha256"))
                            put("size", fe.getLong("size"))
                            put("claimedBy", JSONArray(listOf(BUILTIN_PACKAGE_ID)))
                        })
                    }
                    registry.put("files", allFiles)
                    saveRegistry(context, registry)
                    changed = true
                }

                if (changed) {
                    Log.i(TAG, "migration v2: consolidated into '$BUILTIN_PACKAGE_ID'")
                }

                prefs.edit().putInt(KEY_MIGRATION_VERSION, MIGRATION_VERSION_CURRENT).apply()
            } catch (e: Exception) {
                Log.e(TAG, "legacy migration failed", e)
            }
        }
    }

    // ── Market Package Listing ──

    /** 判断文件是否为用户数据或系统配置（不应被清单追踪，卸载时不应被删除）。 */
    internal fun isUserDataFile(relPath: String): Boolean {
        if (relPath.startsWith("build/")) return true
        if (relPath.contains(".userdb/")) return true
        if (relPath.endsWith(".custom.yaml")) return true
        if (relPath == "installation.yaml") return true
        if (relPath == "custom_phrase.txt") return true
        if (relPath == "xime.custom.yaml") return true
        // themes/ 存放用户导入或自定义的背景图片，不应被清单追踪
        if (relPath.startsWith("themes/")) return true
        return false
    }

    /** 备份文件到 market/builtin/，确保嵌套路径的父目录存在（如 lua/t9_preedit.lua）。 */
    internal fun copyToBuiltinBackup(src: File, builtinDir: File, relPath: String) {
        if (!src.exists()) return
        val dest = File(builtinDir, relPath)
        dest.parentFile?.mkdirs()
        src.copyTo(dest, overwrite = true)
    }

    /**
     * 扫描 rime/ 下未被任何包（registry）追踪的文件，追加到 builtin 清单和注册表中。
     *
     * APP 更新后新版 assets 可能新增了文件，但 builtin 清单仅在首次迁移时创建一次，
     * 新增文件不会被追踪，导致卸载 builtin 包时删不干净。每次安装前调用此函数，
     * 可确保所有内置文件都在清单中。
     *
     * 用户数据文件（.userdb/、*.custom.yaml、installation.yaml、custom_phrase.txt）
     * 不会被追踪，确保卸载时不被误删。
     */
    suspend fun refreshBuiltinManifest(context: Context) {
        withContext(Dispatchers.IO) {
            try {
                refreshBuiltinManifestInternal(context)
            } catch (e: Exception) {
                // 备份/刷新失败不应中断安装流程或导致崩溃，只记录日志
                Log.e(TAG, "refreshBuiltinManifest failed", e)
            }
        }
    }

    private suspend fun refreshBuiltinManifestInternal(context: Context) {
        val rimeDir = SchemaManager.getRimeDir(context)
        if (!rimeDir.exists()) return

        val registry = loadRegistry(context)
        val allFiles = registry.optJSONObject("files") ?: JSONObject()

        // 找出 rime/ 中未被任何包追踪且非用户数据的文件
        val untracked = mutableMapOf<String, File>()
        rimeDir.walkTopDown().forEach { f ->
            if (!f.isFile) return@forEach
            val relPath = f.toRelativeString(rimeDir).replace('\\', '/')
            if (isProtectedSystemFile(relPath)) return@forEach
            if (isUserDataFile(relPath)) return@forEach
            if (allFiles.has(relPath)) return@forEach
            untracked[relPath] = f
        }

        val existingManifest = loadManifest(context, BUILTIN_PACKAGE_ID)
        val fileEntries = existingManifest?.optJSONObject("files") ?: JSONObject()

        // 同步 registry 中 builtin 声明的文件到 manifest（防止 manifest 与 registry 不同步）
        val keysIt = allFiles.keys()
        while (keysIt.hasNext()) {
            val fn = keysIt.next() as String
            if (fileEntries.has(fn)) continue
            val entry = allFiles.optJSONObject(fn) ?: continue
            val claimants = entry.optJSONArray("claimedBy")
            if (claimants != null && jsonArrayToList(claimants).contains(BUILTIN_PACKAGE_ID)) {
                fileEntries.put(fn, JSONObject().apply {
                    put("sha256", entry.optString("sha256", ""))
                    put("size", entry.optLong("size", 0))
                })
            }
        }

        if (untracked.isEmpty() && fileEntries.length() == (existingManifest?.optJSONObject("files")?.length() ?: 0)) return

        for ((relPath, file) in untracked) {
            val sha256 = SchemaManager.fileSha256(file) ?: continue
            fileEntries.put(relPath, JSONObject().apply {
                put("sha256", sha256)
                put("size", file.length())
            })
            allFiles.put(relPath, JSONObject().apply {
                put("sha256", sha256)
                put("size", file.length())
                put("claimedBy", JSONArray(listOf(BUILTIN_PACKAGE_ID)))
            })
        }

        // 更新或创建 builtin 清单
        if (existingManifest != null) {
            existingManifest.put("files", fileEntries)
            saveManifest(context, BUILTIN_PACKAGE_ID, existingManifest)
        } else {
            val manifest = JSONObject().apply {
                put("schemeId", BUILTIN_PACKAGE_ID)
                put("displayName", "系统内置方案")
                put("version", "")
                put("installedAt",
                    SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ", Locale.US).format(Date()))
                put("fromMarket", false)
                put("legacy", true)
                put("files", fileEntries)
            }
            saveManifest(context, BUILTIN_PACKAGE_ID, manifest)
        }

        registry.put("files", allFiles)
        saveRegistry(context, registry)

        // 同步备份到 market/builtin/
        val builtinDir = SchemaManager.getMarketDir(context, BUILTIN_PACKAGE_ID)
        builtinDir.mkdirs()
        for ((relPath, file) in untracked) {
            copyToBuiltinBackup(file, builtinDir, relPath)
        }

        Log.i(TAG, "refreshBuiltinManifest: added ${untracked.size} untracked files")
    }

    /** 从注册表查询指定 schemaId 所属的包 ID。 */
    suspend fun getPackageIdForSchema(context: Context, schemaId: String): String? = withContext(Dispatchers.IO) {
        val registry = loadRegistry(context)
        val files = registry.optJSONObject("files") ?: return@withContext null
        val entry = files.optJSONObject("$schemaId.schema.yaml") ?: return@withContext null
        val claimants = entry.optJSONArray("claimedBy") ?: return@withContext null
        return@withContext if (claimants.length() > 0) claimants.getString(0) else null
    }

    data class MarketPackageInfo(
        val packageId: String,
        val displayName: String,
        val version: String,
        val schemaCount: Int,
        val fileCount: Int,
    )

    /** 获取所有已安装方案的包信息（从清单目录读取）。 */
    suspend fun getInstalledPackages(context: Context): List<MarketPackageInfo> = withContext(Dispatchers.IO) {
        val manifestDir = getManifestsDir(context)
        if (!manifestDir.exists()) return@withContext emptyList()

        manifestDir.listFiles { f -> f.name.endsWith(".json") }
            ?.mapNotNull { file ->
                try {
                    val manifest = JSONObject(file.readText())
                    val files = manifest.optJSONObject("files") ?: JSONObject()
                    val schemaCount = files.keys().asSequence().count { key ->
                        (key as String).endsWith(".schema.yaml")
                    }
                    val id = manifest.getString("schemeId")
                    MarketPackageInfo(
                        packageId = id,
                        displayName = manifest.optString("displayName", id),
                        version = manifest.optString("version", ""),
                        schemaCount = schemaCount,
                        fileCount = files.length(),
                    )
                } catch (_: Exception) { null }
            }?.sortedBy { it.displayName } ?: emptyList()
    }

    // ── Utilities ──

    private fun jsonArrayToList(arr: JSONArray): List<String> {
        val result = mutableListOf<String>()
        for (i in 0 until arr.length()) {
            result.add(arr.getString(i))
        }
        return result
    }
}
