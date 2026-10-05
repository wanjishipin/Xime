package com.kingzcheung.xime.settings

import android.content.Context
import android.net.Uri
import android.os.PowerManager
import android.util.Log
import com.kingzcheung.xime.util.FileLogger
import com.charleskorn.kaml.Yaml
import com.charleskorn.kaml.YamlConfiguration
import com.charleskorn.kaml.YamlList
import com.charleskorn.kaml.YamlMap
import com.charleskorn.kaml.YamlNode
import com.charleskorn.kaml.YamlScalar
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import java.io.BufferedReader
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.InputStreamReader
import java.security.DigestInputStream
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream
import java.util.zip.ZipInputStream
import java.util.zip.ZipFile

data class SchemaMeta(
    val schemaId: String,
    val name: String,
    val version: String = "",
    val author: String = "",
    val description: String = ""
)

/**
 * 方案中 `switches` 定义的一个开关项。
 * - [name] 非空时表示布尔开关（如 ascii_mode / full_shape / ascii_punct）。
 * - [options] 非空时表示多选一开关（如简繁切换，同一时刻只有一个 option 为 true）。
 * - [abbrev] 自定义缩写（可能每个状态一个），供菜单栏展示用；为空时取 states 首字符。
 */
data class SchemaSwitch(
    val name: String = "",
    val options: List<String> = emptyList(),
    val states: List<String> = emptyList(),
    val abbrev: List<String> = emptyList(),
)

@Serializable
internal data class SchemaYaml(val schema: SchemaEntry)

@Serializable
internal data class SchemaEntry(
    @SerialName("schema_id") val schemaId: String = "",
    val name: String = "",
    val version: String = "",
    val description: String? = null,
)

object SchemaManager {
    private const val TAG = "SchemaManager"
    private const val CUSTOM_YAML = "default.custom.yaml"
    internal val yaml = Yaml(configuration = YamlConfiguration(strictMode = false, anchorsAndAliases = com.charleskorn.kaml.AnchorsAndAliases.Permitted(maxAliasCount = UInt.MAX_VALUE)))

    private val downloadClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    fun getRimeDir(context: Context): File =
        File(context.filesDir, "rime")

    /** 清空 rime/ 目录除 default.yaml、xime.yaml 及受保护文件之外的所有文件。 */
    fun cleanRimeDir(context: Context) {
        val rimeDir = getRimeDir(context)
        if (!rimeDir.exists()) return
        rimeDir.listFiles()?.forEach { file ->
            val name = file.name
            if (name == "default.yaml" || name == "xime.yaml") return@forEach
            if (isProtectedImportName(name)) return@forEach
            // themes/ 存放用户导入或自定义的背景图片，全量清理时保留
            if (name == "themes") return@forEach
            if (file.isDirectory) {
                file.deleteRecursively()
            } else {
                file.delete()
            }
        }
        Log.i(TAG, "rime/ directory cleaned")
    }

    /** market 根目录（与 rime/ 同级，不属于 Rime 数据）。 */
    fun getMarketDir(context: Context): File =
        File(context.filesDir, "market")

    /** 每个方案在 market 下的独立子目录：market/{schemeId}/ */
    fun getMarketDir(context: Context, schemeId: String): File =
        File(getMarketDir(context), schemeId)

    /** 检查方案的压缩包是否已下载（market/{schemeId}/ 存在且包含文件）。 */
    fun isSchemeDownloaded(context: Context, schemeId: String): Boolean {
        val dir = getMarketDir(context, schemeId)
        return dir.exists() && (dir.listFiles()?.any { it.isFile } == true)
    }

    /** 删除 market 中指定方案的整个子目录（含压缩包），并清理本地版本记录。 */
    fun deleteSchemeArchive(context: Context, schemeId: String): Boolean {
        val dir = getMarketDir(context, schemeId)
        val removed = if (!dir.exists()) false else dir.deleteRecursively()
        if (removed) {
            MarketVersionStore.removeSchemeVersion(context, schemeId)
        }
        return removed
    }

    /**
     * 列出 market/{schemeId}/ 下归档文件解压后将会放置到 rime/ 的所有文件名。
     * 用于安装前的文件冲突提示。
     */
    suspend fun listInstallTargetFiles(context: Context, schemeId: String): List<String> {
        val dir = getMarketDir(context, schemeId)
        if (!dir.exists()) return emptyList()
        val files = dir.listFiles()?.filter { it.isFile } ?: return emptyList()
        return files.flatMap { listArchiveTargetFiles(it) }
    }

    /**
     * 计算市场包中每个目标文件的 sha256（用于冲突检测）。
     * 与 [listInstallTargetFiles] 对应，返回值是相同结构的目标文件名 → sha256。
     */
    suspend fun computeTargetSha256Map(context: Context, schemeId: String): Map<String, String> = withContext(Dispatchers.IO) {
        val dir = getMarketDir(context, schemeId)
        if (!dir.exists()) return@withContext emptyMap()
        val files = dir.listFiles()?.filter { it.isFile } ?: return@withContext emptyMap()
        val result = mutableMapOf<String, String>()

        for (file in files) {
            when {
                file.name.endsWith(".zip", ignoreCase = true) -> {
                    val entryNames = mutableListOf<String>()
                    ZipFile(file).use { zip ->
                        zip.entries().asSequence().forEach { entry ->
                            if (!entry.isDirectory && !isAppleDouble(entry.name)) {
                                entryNames.add(entry.name)
                            }
                        }
                    }
                    val baseDir = findSchemaBaseDir(entryNames)
                    ZipFile(file).use { zip ->
                        zip.entries().asSequence().forEach { entry ->
                            val originalName = entry.name
                            if (entry.isDirectory || isAppleDouble(originalName)) return@forEach
                            val name = originalName.removePrefix(baseDir)
                            if (isProtectedImportName(name)) return@forEach
                            val hash = zip.getInputStream(entry).use { streamSha256(it) }
                            result[name] = hash
                        }
                    }
                }

                file.name.endsWith(".tar.gz", ignoreCase = true) || file.name.endsWith(".tgz", ignoreCase = true) -> {
                    val entryNames = mutableListOf<String>()
                    file.inputStream().buffered().use { input ->
                        TarArchiveInputStream(GzipCompressorInputStream(input)).use { tarIn ->
                            var entry = tarIn.nextEntry
                            while (entry != null) {
                                if (!entry.isDirectory && !isAppleDouble(entry.name)) {
                                    entryNames.add(entry.name)
                                }
                                entry = tarIn.nextEntry
                            }
                        }
                    }
                    val baseDir = findSchemaBaseDir(entryNames)
                    file.inputStream().buffered().use { input ->
                        TarArchiveInputStream(GzipCompressorInputStream(input)).use { tarIn ->
                            var entry = tarIn.nextEntry
                            while (entry != null) {
                                val originalName = entry.name
                                if (entry.isDirectory || isAppleDouble(originalName)) {
                                    entry = tarIn.nextEntry
                                    continue
                                }
                                val name = originalName.removePrefix(baseDir)
                                if (isProtectedImportName(name)) {
                                    entry = tarIn.nextEntry
                                    continue
                                }
                                val hash = streamSha256(tarIn)
                                result[name] = hash
                                entry = tarIn.nextEntry
                            }
                        }
                    }
                }

                else -> {
                    if (!isProtectedImportName(file.name)) {
                        val hash = fileSha256(file)
                        if (hash != null) result[file.name] = hash
                    }
                }
            }
        }
        result
    }

    data class InstallFromDirResult(
        val success: Boolean,
        val conflicts: List<FileConflictInfo> = emptyList(),
        val newSchemaIds: List<String> = emptyList(),
        val unresolvedDeps: List<String> = emptyList(),
        val failureReason: String? = null,
        val parseFailures: List<String> = emptyList(),
    )

    /**
     * 从 market/{packageId}/ 执行统一安装：冲突检测 → 解压 → 清单创建。
     * 所有导入路径（市场/文件/URL）最终都汇聚于此。
     */
    suspend fun installPackageFromMarketDir(
        context: Context,
        packageId: String,
        displayName: String,
        version: String = "",
        fromMarket: Boolean = false,
        dependencies: List<String> = emptyList(),
        resolveDepUrl: (String) -> String? = { null },
        switchEnabled: Boolean = true,
    ): InstallFromDirResult = withContext(Dispatchers.IO) {
        try {
            val dir = getMarketDir(context, packageId)
            if (!dir.exists() || dir.listFiles()?.none { it.isFile } != false) {
                FileLogger.w(TAG, "installPackageFromMarketDir: dir not found or empty: $dir")
                return@withContext InstallFromDirResult(success = false, failureReason = "压缩包不存在")
            }

            val targetFiles = listInstallTargetFiles(context, packageId)
            if (targetFiles.isEmpty()) {
                FileLogger.w(TAG, "installPackageFromMarketDir: no target files in $packageId")
                return@withContext InstallFromDirResult(success = false, failureReason = "归档中没有文件")
            }
            FileLogger.i(TAG, "installPackageFromMarketDir: found ${targetFiles.size} target files for $packageId")

            val sha256Map = computeTargetSha256Map(context, packageId)
            FileLogger.i(TAG, "installPackageFromMarketDir: computed sha256 for ${sha256Map.size} files")

            val conflicts = SchemaManifestManager.detectConflicts(context, packageId, targetFiles, sha256Map)
            if (conflicts.isNotEmpty()) {
                FileLogger.w(TAG, "installPackageFromMarketDir: conflicts detected: ${conflicts.map { "${it.fileName} (${it.claimedBy})" }}")
                return@withContext InstallFromDirResult(success = false, conflicts = conflicts)
            }

            val before = discoverSchemas(context).map { it.schemaId }.toSet()
            FileLogger.i(TAG, "installPackageFromMarketDir: schemas before install: $before")

            val ok = installFromMarketToRime(context, packageId)
            if (!ok) {
                FileLogger.e(TAG, "installPackageFromMarketDir: installFromMarketToRime returned false")
                return@withContext InstallFromDirResult(success = false, failureReason = "安装失败")
            }

            val after = discoverSchemas(context).map { it.schemaId }.toSet()
            val newIds = (after - before).toList()
            FileLogger.i(TAG, "installPackageFromMarketDir: schemas after install: $after, new: $newIds")

            // 检查 target 中的 .schema.yaml 是否都解析成功
            val targetSchemaIds = targetFiles
                .filter { it.endsWith(".schema.yaml") }
                .map { it.removeSuffix(".schema.yaml") }
                .toSet()
            val failedIds = targetSchemaIds - after
            val parseFailures = if (failedIds.isEmpty()) emptyList()
                else failedIds.map { "$it.schema.yaml 解析失败" }
            if (parseFailures.isNotEmpty()) {
                FileLogger.w(TAG, "installPackageFromMarketDir: some schemas failed to parse: $parseFailures")
            }

            var unresolved = emptyList<String>()
            var dependencyIds = emptyList<String>()
            if (dependencies.isNotEmpty()) {
                val completion = RimeDependencyResolver.complete(
                    context = context,
                    schemaId = newIds.firstOrNull() ?: packageId,
                    dependencies = dependencies,
                    resolveUrl = resolveDepUrl,
                )
                unresolved = (completion.unresolved + completion.stillMissingFiles).distinct()
                dependencyIds = completion.downloaded
            }

            SchemaManifestManager.createManifest(
                context = context,
                schemeId = packageId,
                displayName = displayName,
                version = version,
                fromMarket = fromMarket,
                extractedFiles = targetFiles,
                dependencyIds = dependencyIds,
            )

            if (fromMarket) {
                SettingsPreferences.addInstalledMarketId(context, packageId)
            }

            // 至少启用一个新方案，避免 RimeEngine 因 schema_list 为空而挂起。
            // 更新已安装方案时（switchEnabled=false）不强制切换启用列表，保留用户现有方案。
            val firstSchema = newIds.firstOrNull()
                ?: targetFiles.firstOrNull { it.endsWith(".schema.yaml") }
                    ?.removeSuffix(".schema.yaml")
            if (firstSchema != null && switchEnabled) {
                setEnabledSchemas(context, listOf(firstSchema))
            }

            FileLogger.i(TAG, "installPackageFromMarketDir: success for $packageId, firstSchema=$firstSchema")
            InstallFromDirResult(success = true, newSchemaIds = newIds, unresolvedDeps = unresolved, parseFailures = parseFailures)
        } catch (e: Exception) {
            FileLogger.e(TAG, "installPackageFromMarketDir: UNCAUGHT exception for $packageId", e)
            return@withContext InstallFromDirResult(success = false, failureReason = "安装异常: ${e.message}")
        }
    }

    /** 解析单个归档（或普通文件）将被释放到 rime/ 的目标文件名列表。 */
    private fun listArchiveTargetFiles(archiveFile: File): List<String> {
        val entryNames = when {
            archiveFile.name.endsWith(".zip", ignoreCase = true) -> {
                val names = mutableListOf<String>()
                ZipFile(archiveFile).use { zip ->
                    zip.entries().asSequence().forEach { entry ->
                        if (!entry.isDirectory && !isAppleDouble(entry.name)) names.add(entry.name)
                    }
                }
                names
            }
            archiveFile.name.endsWith(".tar.gz", ignoreCase = true) || archiveFile.name.endsWith(".tgz", ignoreCase = true) -> {
                val names = mutableListOf<String>()
                TarArchiveInputStream(GzipCompressorInputStream(archiveFile.inputStream().buffered())).use { tar ->
                    var entry = tar.nextEntry
                    while (entry != null) {
                        if (!entry.isDirectory && !isAppleDouble(entry.name)) names.add(entry.name)
                        entry = tar.nextEntry
                    }
                }
                names
            }
            else -> return listOf(archiveFile.name)
        }
        if (entryNames.isEmpty()) return emptyList()
        val baseDir = findSchemaBaseDir(entryNames)
        return entryNames.map { it.removePrefix(baseDir) }
            .filterNot { isProtectedImportName(it) }
    }

    /** 在 market/{schemeId}/ 中查找已下载的文件。 */
    fun findMarketFile(context: Context, schemeId: String): File? {
        val dir = getMarketDir(context, schemeId)
        if (!dir.exists()) return null
        return dir.listFiles()?.firstOrNull { it.isFile }
    }

    /**
     * 从 market 目录安装方案到 rime 目录：
     * - .zip / .tar.gz / .tgz → 解压
     * - 其他文件 → 直接复制
     */
    /**
     * 从 market/{schemeId}/ 安装所有已下载文件到 rime 目录：
     * - .zip / .tar.gz / .tgz → 解压
     * - 其他文件 → 直接复制
     */
    fun installFromMarketToRime(context: Context, schemeId: String): Boolean {
        val dir = getMarketDir(context, schemeId)
        if (!dir.exists()) return false
        val files = dir.listFiles()?.filter { it.isFile } ?: return false
        if (files.isEmpty()) return false
        val rimeDir = getRimeDir(context)
        if (!rimeDir.exists()) rimeDir.mkdirs()
        var allOk = true
        for (file in files) {
            try {
                val name = file.name
                // 解压前先校验压缩包完整性
                val isArchive = name.endsWith(".zip", ignoreCase = true) ||
                    name.endsWith(".tar.gz", ignoreCase = true) || name.endsWith(".tgz", ignoreCase = true)
                if (isArchive && !validateArchive(file)) {
                    FileLogger.e(TAG, "installFromMarketToRime: ${file.name} is corrupted for $schemeId, deleting")
                    file.delete()
                    allOk = false
                    continue
                }
                val ok = when {
                    name.endsWith(".zip", ignoreCase = true) -> importZipFromFile(file, rimeDir)
                    name.endsWith(".tar.gz", ignoreCase = true) || name.endsWith(".tgz", ignoreCase = true) ->
                        importTarGzFromFile(file, rimeDir)
                    else -> {
                        val target = File(rimeDir, file.name)
                        file.copyTo(target, overwrite = true)
                        FileLogger.i(TAG, "Copied ${file.name} to rime dir")
                        true
                    }
                }
                if (!ok) {
                    FileLogger.e(TAG, "installFromMarketToRime: failed to process ${file.name} for $schemeId, deleting")
                    file.delete()
                    allOk = false
                }
            } catch (e: Exception) {
                FileLogger.e(TAG, "installFromMarketToRime: error processing ${file.name} for $schemeId, deleting", e)
                file.delete()
                allOk = false
            }
        }
        return allOk
    }

    internal fun getBuildDir(context: Context): File =
        File(getRimeDir(context), "build")

    private fun getCustomYamlFile(context: Context): File =
        File(getRimeDir(context), CUSTOM_YAML)

    fun isSchemaCompiled(context: Context, schemaId: String): Boolean {
        val buildDir = getBuildDir(context)
        return File(buildDir, "$schemaId.prism.bin").exists() ||
               File(buildDir, "$schemaId.schema.yaml").exists()
    }

    // F1: 把启用方案直接写进 default.yaml 的 schema_list（纯函数，可单测）
    fun replaceSchemaListBlock(defaultYamlText: String, enabled: List<String>): String {
        if (enabled.isEmpty()) return defaultYamlText
        // 保留原文件换行风格（CRLF/LF），避免把整文件换行符规范化
        val sep = if (defaultYamlText.contains("\r\n")) "\r\n" else "\n"
        val lines = defaultYamlText.lines()
        val headerIdx = lines.indexOfFirst { it.trim() == "schema_list:" }

        if (headerIdx < 0) {
            // 没有 schema_list 块：在文末追加一个
            val sb = StringBuilder(defaultYamlText)
            if (!defaultYamlText.endsWith("\n")) sb.append(sep)
            sb.append(sep).append("schema_list:").append(sep)
            enabled.forEach { sb.append("  - schema: ").append(it).append(sep) }
            return sb.toString()
        }

        // 吃掉紧跟其后的列表项行；保留缩进风格（默认两空格）
        var j = headerIdx + 1
        var indent = "  "
        var first = true
        while (j < lines.size && lines[j].trimStart().startsWith("-")) {
            if (first) {
                indent = lines[j].takeWhile { it == ' ' || it == '\t' }.ifEmpty { "  " }
                first = false
            }
            j++
        }

        val rebuilt = buildList {
            add(lines[headerIdx])                    // 保留原 header 行（含其缩进）
            enabled.forEach { add("$indent- schema: $it") }
        }
        return (lines.subList(0, headerIdx) + rebuilt + lines.subList(j, lines.size))
            .joinToString(sep)
    }

    /**
     * F1: 把启用方案写回 `default.yaml` 的 schema_list。
     * librime 编译以 default.yaml 的真实 schema_list 为准（default.custom.yaml 的
     * patch 在本项目构建里不作用到词典编译阶段），所以必须直接改 default.yaml。
     *
     * @param schemaIds 缺省取当前启用列表；[setEnabledSchemas] 会显式传入避免重复读取。
     */
    fun applyEnabledSchemasToDefaultYaml(
        context: Context,
        schemaIds: List<String> = getEnabledSchemas(context)
    ) {
        if (schemaIds.isEmpty()) return
        val defaultYaml = File(getRimeDir(context), "default.yaml")
        if (!defaultYaml.exists()) return
        try {
            val text = defaultYaml.readText()
            val updated = replaceSchemaListBlock(text, schemaIds)
            if (updated != text) {
                defaultYaml.writeText(updated)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write default.yaml schema_list", e)
        }
    }

    // sha256 校验 + 导入保护（纯函数，可单测）
    fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    fun streamSha256(input: InputStream): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val dis = DigestInputStream(input, digest)
        val buffer = ByteArray(8192)
        while (dis.read(buffer) != -1) { }
        // 不关闭 dis——调用方负责管理流的生命周期
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    fun fileSha256(file: File): String? {
        return try {
            FileInputStream(file).use { streamSha256(it) }
        } catch (e: Exception) {
            Log.w(TAG, "sha256 failed for ${file.name}", e)
            null
        }
    }

    /** 生成基于当前时间的导入 ID，如 import_20260712_015947。 */
    fun generateImportId(): String =
        "import_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())

    /** 导入归档时禁止覆盖系统文件（保护应用核心配置与系统元数据）。 */
    fun isProtectedImportName(name: String): Boolean {
        val base = name.substringAfterLast('/')
        return base == "default.yaml" ||
               base == "xime.yaml" ||
               base == "custom_phrase.txt" ||
               name.startsWith(".registry")
    }

    /**
     * 单文件直接导入是否受清单系统追踪（冲突检测 + 可按清单卸载）。
     * 用户数据（custom_phrase.txt、*.custom.yaml 等）、系统文件（default.yaml、xime.yaml、build/）
     * 与清单元数据本身不追踪，保持直接落盘的历史行为；含 `..` 的路径同样不追踪。
     */
    internal fun shouldTrackImportedFile(name: String): Boolean =
        !isProtectedImportName(name) &&
            !name.startsWith(".manifests") &&
            !SchemaManifestManager.isProtectedSystemFile(name) &&
            !SchemaManifestManager.isUserDataFile(name) &&
            !name.contains("..")

    /** 单文件导入的清单包 id：rime 相对路径展平（manifests 文件名不能含路径分隔符）。 */
    internal fun manifestPackageIdFor(name: String): String = name.replace('/', '_')

    /** macOS Apple Double 资源分支文件（__MACOSX/ 或 ._ 前缀），应当在解压时跳过。 */
    private fun isAppleDouble(name: String): Boolean =
        name.startsWith("__MACOSX/") || name.contains("/._") || name.startsWith("._")

    /** 把归档条目解析到 targetDir 下，越界（zip-slip，如 ../../x）返回 null。 */
    private fun safeChild(targetDir: File, name: String): File? {
        val child = File(targetDir, name)
        return if (child.canonicalPath.startsWith(targetDir.canonicalPath + File.separator)) child else null
    }

    fun getReferencedDictName(context: Context, schemaId: String): String? {
        val schemaFile = File(getRimeDir(context), "$schemaId.schema.yaml")
        if (!schemaFile.exists()) return null
        return try {
            val content = schemaFile.readText()
            // matches "  dictionary: wubi86" or "translator/dictionary: wubi86" or inline {dictionary:wubi86}
            val regex = Regex("""dictionary\s*:\s*['\"]?(\w[\w-]*)['\"]?""")
            regex.find(content)?.groupValues?.getOrNull(1)
        } catch (e: Exception) { null }
    }

    fun hasDictFile(context: Context, schemaId: String): Boolean {
        val dictName = getReferencedDictName(context, schemaId) ?: schemaId
        val f = File(getRimeDir(context), "$dictName.dict.yaml")
        return f.exists()
    }

    fun schemaNeedsDict(context: Context, schemaId: String): Boolean {
        val dictName = getReferencedDictName(context, schemaId) ?: schemaId
        return !File(getRimeDir(context), "$dictName.dict.yaml").exists()
    }

    fun getSchemaIssues(context: Context, schemaId: String): List<String> {
        val issues = mutableListOf<String>()
        val schemaFile = File(getRimeDir(context), "$schemaId.schema.yaml")
        if (!schemaFile.exists()) {
            issues.add("缺少 .schema.yaml 文件")
            return issues
        }
        val dictName = getReferencedDictName(context, schemaId) ?: schemaId
        if (!File(getRimeDir(context), "$dictName.dict.yaml").exists()) {
            issues.add("缺少 $dictName.dict.yaml 词典文件，无法编译")
        }
        return issues
    }

    fun discoverSchemas(context: Context): List<SchemaMeta> {
        val rimeDir = getRimeDir(context)
        if (!rimeDir.exists()) return emptyList()

        val schemas = mutableListOf<SchemaMeta>()
        val schemaFiles = rimeDir.listFiles { f -> f.name.endsWith(".schema.yaml") }
            ?: return emptyList()

        for (file in schemaFiles) {
            val meta = parseSchemaYaml(file)
            if (meta != null) {
                schemas.add(meta)
            }
        }

        schemas.sortBy { it.name }
        return schemas
    }

    internal fun parseSchemaYaml(file: File): SchemaMeta? {
        return try {
            val text = file.readText().trimStart('\uFEFF')
            val schemaBlock = extractSchemaBlock(text) ?: return null
            val entry = yaml.decodeFromString(SchemaYaml.serializer(), schemaBlock).schema
            if (entry.schemaId.isEmpty()) return null

            val author = parseAuthorFromText(text)

            SchemaMeta(
                schemaId = entry.schemaId,
                name = entry.name.ifEmpty { entry.schemaId },
                version = entry.version,
                author = author,
                description = entry.description ?: ""
            )
        } catch (e: Exception) {
            try { Log.w(TAG, "Failed to parse schema file: ${file.name}, error=${e.message}, skip", e) } catch (_: Exception) {}
            null
        }
    }

    /**
     * 从 YAML 文本中提取顶层 `schema:` 块及其子内容，返回一个可独立解析的 YAML 片段。
     * 避免 kaml 因为文件的其他部分（重复 `__include`、锚点等）而报错。
     */
    internal fun extractSchemaBlock(yamlText: String): String? {
        val lines = yamlText.lines()
        val schemaLineIndex = lines.indexOfFirst {
            it.trimStart().let { t -> t.startsWith("schema:") && (t.length == 7 || t[7] == ' ') }
        }
        if (schemaLineIndex < 0) return null

        val schemaIndent = lines[schemaLineIndex].length - lines[schemaLineIndex].trimStart().length
        val result = mutableListOf("schema:")
        for (i in schemaLineIndex + 1 until lines.size) {
            val line = lines[i]
            if (line.isBlank()) continue
            val indent = line.length - line.trimStart().length
            if (indent <= schemaIndent) break
            result.add(line)
        }
        return result.joinToString("\n")
    }

    private fun parseAuthorFromText(yamlText: String): String {
        val lines = yamlText.lines()
        var inAuthor = false
        for (line in lines) {
            val trimmed = line.trimStart()
            if (!inAuthor && trimmed.startsWith("author:")) {
                val rest = trimmed.removePrefix("author:").trim()
                if (rest.isNotEmpty()) return rest.removeSurrounding("\"").removePrefix("- ").trim()
                inAuthor = true
                continue
            }
            if (inAuthor) {
                if (trimmed.startsWith("- ")) {
                    return trimmed.removePrefix("- ").trim().removeSurrounding("\"")
                }
                if (trimmed.isNotEmpty() && !trimmed.startsWith("#")) {
                    inAuthor = false
                }
            }
        }
        return ""
    }

    /** 仅从 .schema.yaml 读取显示名，不依赖编译/启用状态。 */
    fun getSchemaDisplayName(context: Context, schemaId: String): String? {
        val file = File(getRimeDir(context), "$schemaId.schema.yaml")
        if (!file.exists()) return null
        return try {
            val entry = yaml.decodeFromString(SchemaYaml.serializer(), file.readText().trimStart('\uFEFF')).schema
            entry.name.ifEmpty { null }
        } catch (e: Exception) {
            try { Log.e(TAG, "Failed to parse schema name for $schemaId", e) } catch (_: Exception) {}
            null
        }
    }

    /**
     * 读取方案顶层 `switches:` 中可展示的开关项（name 或 options 存在且有 states）。
     * `switches` 位于 schema 块之外，需单独解析。解析失败或不存在时返回空列表。
     */
    fun getSchemaSwitches(context: Context, schemaId: String): List<SchemaSwitch> {
        val file = File(getRimeDir(context), "$schemaId.schema.yaml")
        if (!file.exists()) return emptyList()
        return parseSchemaSwitches(file)
    }

    /** 纯解析：从 .schema.yaml 读取 switches。 */
    internal fun parseSchemaSwitches(file: File): List<SchemaSwitch> {
        return try {
            val text = file.readText().trimStart('\uFEFF')
            val block = extractSwitchesBlock(text) ?: return emptyList()
            val listNode = yaml.parseToYamlNode(block) as? YamlList ?: return emptyList()
            listNode.items.mapNotNull { item ->
                parseSwitchEntry(item as? YamlMap ?: return@mapNotNull null)
            }
        } catch (e: Exception) {
            try { Log.w(TAG, "Failed to parse switches for ${file.name}: ${e.message}", e) } catch (_: Exception) {}
            emptyList()
        }
    }

    private fun parseSwitchEntry(entry: YamlMap): SchemaSwitch? {
        val states = (entry["states"] as? YamlList)
            ?.items?.mapNotNull { (it as? YamlScalar)?.content }
            .orEmpty()
        if (states.isEmpty()) return null
        val name = (entry["name"] as? YamlScalar)?.content.orEmpty()
        val options = (entry["options"] as? YamlList)
            ?.items?.mapNotNull { (it as? YamlScalar)?.content }
            .orEmpty()
        val abbrev = parseAbbrev(entry["abbrev"])
        return when {
            name.isNotBlank() -> SchemaSwitch(name = name, options = emptyList(), states = states, abbrev = abbrev)
            options.isNotEmpty() -> SchemaSwitch(name = "", options = options, states = states, abbrev = abbrev)
            else -> null
        }
    }

    /** abbrev 可以是单个字符串或每个状态一个的字符串列表。 */
    private fun parseAbbrev(node: YamlNode?): List<String> = when (node) {
        is YamlScalar -> listOf(node.content)
        is YamlList -> node.items.mapNotNull { (it as? YamlScalar)?.content }
        else -> emptyList()
    }

    /**
     * 从 YAML 文本中提取顶层 `switches:` 的列表项内容（不含 `switches:` 头行），
     * 返回一个可独立作为列表解析的 YAML 片段；不存在时返回 null。
     */
    internal fun extractSwitchesBlock(yamlText: String): String? {
        val lines = yamlText.lines()
        val idx = lines.indexOfFirst {
            it.trimStart().let { t -> t == "switches:" || (t.startsWith("switches:") && t[9] == ' ') }
        }
        if (idx < 0) return null
        val result = mutableListOf<String>()
        for (i in idx + 1 until lines.size) {
            val line = lines[i]
            if (line.isBlank()) continue
            val indent = line.length - line.trimStart().length
            if (indent == 0) break
            result.add(line)
        }
        if (result.isEmpty()) return null
        return result.joinToString("\n")
    }

    /** 内置方案（保持默认启用顺序）。 */
    internal val BUILTIN_SCHEMAS = listOf("wubi86", "wubi86_pinyin", "pinyin_simp", "t9_pinyin")

    /**
     * 内置方案补齐（纯函数）：用户启用列表尾部按 [BUILTIN_SCHEMAS] 顺序追加缺失项，
     * 用户已有顺序与选择保持不变；无缺失时原样返回。
     *
     * 背景：老版本升级用户的 custom.yaml 早于新内置方案（如 t9_pinyin）创建，
     * 列表里没有它 → 方案永不部署 → 切九键引擎侧静默失败（按键无候选）。
     */
    internal fun mergeBuiltinSchemas(enabled: List<String>): List<String> {
        val missing = BUILTIN_SCHEMAS.filter { it !in enabled }
        return if (missing.isEmpty()) enabled else enabled + missing
    }

    fun getEnabledSchemas(context: Context): List<String> {
        val customFile = getCustomYamlFile(context)
        if (!customFile.exists()) {
            setEnabledSchemas(context, BUILTIN_SCHEMAS)
            SettingsPreferences.setBuiltinSchemasMerged(context, true)
            return BUILTIN_SCHEMAS
        }

        try {
            val content = customFile.readText()
            val schemas = mutableListOf<String>()
            var inSchemaList = false
            for (line in content.lines()) {
                val trimmed = line.trim()
                if (trimmed == "schema_list:") {
                    inSchemaList = true
                    continue
                }
                if (inSchemaList) {
                    if (trimmed.startsWith("- schema:")) {
                        val id = trimmed.removePrefix("- schema:").trim()
                        if (id.isNotEmpty()) schemas.add(id)
                    } else if (!trimmed.startsWith("- ")) {
                        inSchemaList = false
                    }
                }
            }
            if (schemas.isNotEmpty()) {
                // 内置方案补齐只执行一次（新版本首次运行，治老版本升级残留：
                // 列表无 t9_pinyin → 方案永不部署 → 切九键静默失败）。之后用户
                // 在方案管理中移除内置方案是有效选择，不得每次读取强行补回。
                // 补齐写回后 schema_list 计入部署 hash、build 产物按方案校验，
                // 缺失的方案会自动触发重部署。
                val merged = if (SettingsPreferences.isBuiltinSchemasMerged(context)) {
                    schemas
                } else {
                    val m = mergeBuiltinSchemas(schemas)
                    if (m != schemas) {
                        setEnabledSchemas(context, m)
                    }
                    SettingsPreferences.setBuiltinSchemasMerged(context, true)
                    m
                }
                return merged
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read custom.yaml", e)
        }

        return BUILTIN_SCHEMAS
    }

    fun setEnabledSchemas(context: Context, schemaIds: List<String>) {
        val customFile = getCustomYamlFile(context)
        if (!customFile.exists()) {
            // 首次写入以 app 固定模板为基底（含 menu/page_size 等默认 patch），
            // 不再把整文件重写成只剩 schema_list 的空壳——那会丢掉 default.custom.yaml
            // 的全部补丁（menu/page_size 丢失后每页候选数漂移回引擎兜底值 5）
            if (!copyBuiltinDefaultCustom(context, customFile)) {
                // 模板复制失败兜底：保留旧行为，至少保证文件存在（getEnabledSchemas
                // 以文件存在为前提）且 schema_list 有效
                writeSchemaListOnlyCustom(customFile, schemaIds)
                applyEnabledSchemasToDefaultYaml(context, schemaIds)
                return
            }
        }
        // 只替换 schema_list 块，保留 menu/switcher/key_binder 等其余 patch
        try {
            val text = customFile.readText()
            val updated = replaceSchemaListBlock(text, schemaIds)
            if (updated != text) {
                customFile.writeText(updated)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write custom.yaml schema_list", e)
        }
        // F1: 同步写进 default.yaml，确保 librime 真正编译启用的方案
        applyEnabledSchemasToDefaultYaml(context, schemaIds)
    }

    private fun writeSchemaListOnlyCustom(customFile: File, schemaIds: List<String>) {
        val sb = StringBuilder()
        sb.appendLine("patch:")
        sb.appendLine("  schema_list:")
        for (id in schemaIds) {
            sb.appendLine("    - schema: $id")
        }
        customFile.writeText(sb.toString())
    }

    /** 复制 app 固定的 assets/default.custom.yaml 模板（assets 根与 rime 目录同名，与 RimeConfigHelper 同步的是同一份）。 */
    private fun copyBuiltinDefaultCustom(context: Context, target: File): Boolean {
        return try {
            context.assets.open(CUSTOM_YAML).use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to copy builtin $CUSTOM_YAML", e)
            false
        }
    }

    fun toggleSchema(context: Context, schemaId: String) {
        val enabled = getEnabledSchemas(context).toMutableList()
        if (schemaId in enabled) {
            enabled.remove(schemaId)
        } else {
            enabled.add(schemaId)
        }
        setEnabledSchemas(context, enabled)
    }

    fun isSchemaEnabled(context: Context, schemaId: String): Boolean {
        return schemaId in getEnabledSchemas(context)
    }

    suspend fun deleteSchemaFiles(context: Context, schemaId: String): Boolean {
        // 优先使用清单系统精准卸载
        val result = SchemaManifestManager.uninstallWithManifest(context, schemaId)
        if (result.manifestExisted) {
            if (result.success) {
                Log.i(TAG, "Manifest-based uninstall for $schemaId: ${result.message}")
            } else {
                Log.w(TAG, "Manifest-based uninstall for $schemaId failed: ${result.message}")
            }
        } else {
            // 降级到传统逻辑（无清单的旧方案）
            val rimeDir = getRimeDir(context)
            // 先读 dictName 再删 schema.yaml，否则 getReferencedDictName 永远返回 null
            val dictName = getReferencedDictName(context, schemaId) ?: schemaId
            val schemaFile = File(rimeDir, "$schemaId.schema.yaml")
            if (schemaFile.exists()) schemaFile.delete()
            val dictFile = File(rimeDir, "$dictName.dict.yaml")
            if (dictFile.exists()) dictFile.delete()
            Log.i(TAG, "Legacy uninstall for $schemaId (dict=$dictName)")
        }

        // 从已安装市场列表和启用列表中移除
        SettingsPreferences.removeInstalledMarketId(context, schemaId)
        val enabled = getEnabledSchemas(context).toMutableList()
        enabled.remove(schemaId)
        setEnabledSchemas(context, enabled)
        return true
    }

    data class ImportResult(
        val success: Boolean,
        val installedDirect: Boolean = false,
        /** 与已安装方案同名不同内容的文件冲突（清单系统判定）；非空时导入被拒绝。 */
        val conflicts: List<FileConflictInfo> = emptyList(),
    )

    /** 判断文件名是否为压缩包。 */
    fun isArchive(name: String): Boolean =
        name.endsWith(".zip", ignoreCase = true) ||
        name.endsWith(".tar.gz", ignoreCase = true) ||
        name.endsWith(".tgz", ignoreCase = true)

    /** 判断文件名是否为图片（背景图等，导入到 themes/）。 */
    fun isImage(name: String): Boolean =
        name.endsWith(".jpg", ignoreCase = true) ||
        name.endsWith(".jpeg", ignoreCase = true) ||
        name.endsWith(".png", ignoreCase = true)

    /** 判断文件名是否为字体文件（导入到 fonts/）。 */
    fun isFont(name: String): Boolean =
        name.endsWith(".ttf", ignoreCase = true) ||
        name.endsWith(".otf", ignoreCase = true) ||
        name.endsWith(".woff", ignoreCase = true) ||
        name.endsWith(".woff2", ignoreCase = true)

    /** fonts 目录：rime/fonts/，存放用户导入的自定义字体。 */
    fun getFontsDir(context: Context): File =
        File(getRimeDir(context), "fonts")

    /** themes 目录：rime/themes/，存放用户导入或自定义的背景图片。 */
    fun getThemesDir(context: Context): File =
        File(getRimeDir(context), "themes")

    /**
     * 某些 Android 内容提供器会将未知 MIME 类型的文件（如 .yaml）自动追加 .txt 后缀，
     * 导致 xime.custom.yaml 变成 xime.custom.yaml.txt。此处修复已知情况。
     */
    fun sanitizeDisplayName(name: String): String {
        return when {
            name.endsWith(".yaml.txt", ignoreCase = true) ->
                name.removeSuffix(".txt")
            name.endsWith(".dict.yaml.txt", ignoreCase = true) ->
                name.removeSuffix(".txt")
            else -> name
        }
    }

    /**
     * 统一保存导入的文件：
     * - 压缩包（zip/tar.gz/tgz）→ market/{importId}/，等待用户手动安装
     * - 非压缩包（yaml/txt/bin 等）→ 直接放入 rime/
     * 所有导入路径（文件选择器、无线导入、URL 下载等）最终都汇聚于此。
     */
    suspend fun saveImportedFile(
        context: Context,
        displayName: String,
        inputStream: java.io.InputStream,
        autoEnable: Boolean = true,
    ): ImportResult = withContext(Dispatchers.IO) {
        val name = sanitizeDisplayName(displayName)
        if (isFont(name)) {
            // 字体文件：保存到 rime/fonts/，供自定义字体功能使用
            val fontsDir = getFontsDir(context)
            try {
                fontsDir.mkdirs()
                val target = File(fontsDir, name)
                inputStream.use { input ->
                    target.outputStream().use { output -> input.copyTo(output) }
                }
                FileLogger.i(TAG, "Imported $name -> rime/fonts/")
                ImportResult(true)
            } catch (e: Exception) {
                FileLogger.e(TAG, "Failed to import font $name", e)
                ImportResult(false)
            }
        } else if (isImage(name)) {
            // 图片：背景图等，保存到 rime/themes/，供主题使用
            val themesDir = getThemesDir(context)
            try {
                themesDir.mkdirs()
                val target = File(themesDir, name)
                inputStream.use { input ->
                    target.outputStream().use { output -> input.copyTo(output) }
                }
                FileLogger.i(TAG, "Imported $name -> rime/themes/")
                ImportResult(true)
            } catch (e: Exception) {
                FileLogger.e(TAG, "Failed to import image $name", e)
                ImportResult(false)
            }
        } else if (isArchive(name)) {
            // 压缩包：保存到 market/ 等待用户手动安装
            val importId = generateImportId()
            val pkgDir = getMarketDir(context, importId)
            try {
                pkgDir.mkdirs()
                val archiveFile = File(pkgDir, name)
                inputStream.use { input ->
                    archiveFile.outputStream().use { output -> input.copyTo(output) }
                }
                FileLogger.i(TAG, "Imported $name -> $importId (not installed yet)")
                ImportResult(true)
            } catch (e: Exception) {
                FileLogger.e(TAG, "Failed to import archive $name", e)
                try { if (pkgDir.exists()) pkgDir.deleteRecursively() } catch (_: Exception) {}
                ImportResult(false)
            }
        } else {
            // 非压缩包：直接放入 rime/ 目录，不经过 market
            val rimeDir = getRimeDir(context)
            try {
                rimeDir.mkdirs()
                // 受清单追踪的文件先暂存算 sha：与已安装方案同名不同内容时拒绝导入（不落盘，
                // 避免静默覆盖市场方案文件）；同内容视为共享依赖放行；重新导入同一文件视为重装放行。
                val tracked = shouldTrackImportedFile(name)
                var stagedFile: File? = null
                if (tracked) {
                    val staged = File.createTempFile("import_stage_", ".tmp", context.cacheDir)
                    inputStream.use { input ->
                        staged.outputStream().use { output -> input.copyTo(output) }
                    }
                    val sha = fileSha256(staged)
                    if (sha != null) {
                        val conflicts = SchemaManifestManager.detectConflicts(
                            context, manifestPackageIdFor(name), listOf(name), mapOf(name to sha),
                        )
                        if (conflicts.isNotEmpty()) {
                            FileLogger.w(
                                TAG,
                                "Import rejected, $name conflicts with: ${conflicts.flatMap { it.claimedBy }}",
                            )
                            staged.delete()
                            return@withContext ImportResult(false, conflicts = conflicts)
                        }
                    }
                    stagedFile = staged
                }
                try {
                    val target = File(rimeDir, name)
                    val source: java.io.InputStream = stagedFile?.inputStream() ?: inputStream
                    source.use { input ->
                        target.outputStream().use { output -> input.copyTo(output) }
                    }
                    // 如果是 .schema.yaml 文件，自动启用
                    if (autoEnable && name.endsWith(".schema.yaml")) {
                        val schemaId = name.removeSuffix(".schema.yaml")
                        val enabled = getEnabledSchemas(context).toMutableList()
                        if (schemaId !in enabled) {
                            enabled.add(schemaId)
                            setEnabledSchemas(context, enabled)
                        }
                    }
                    if (tracked) {
                        SchemaManifestManager.createManifest(
                            context = context,
                            schemeId = manifestPackageIdFor(name),
                            displayName = name,
                            version = "",
                            fromMarket = false,
                            extractedFiles = listOf(name),
                        )
                    }
                    FileLogger.i(TAG, "Imported $name -> rime/ (direct${if (tracked) ", tracked" else ""})")
                    ImportResult(true, installedDirect = true)
                } finally {
                    stagedFile?.delete()
                }
            } catch (e: Exception) {
                FileLogger.e(TAG, "Failed to import $name directly", e)
                ImportResult(false)
            }
        }
    }

    suspend fun importSchemaFile(context: Context, uri: Uri): ImportResult {
        return withContext(Dispatchers.IO) {
            val displayName = getFileName(context, uri) ?: return@withContext ImportResult(false)

            val inputStream = when (uri.scheme) {
                "file" -> java.io.FileInputStream(uri.path!!)
                else -> context.contentResolver.openInputStream(uri)
            } ?: return@withContext ImportResult(false)

            saveImportedFile(context, displayName, inputStream)
        }
    }

    /**
     * 从 URI 导入 zip，返回解压后的文件列表。
     */
    private fun importZipWithFileList(context: Context, uri: Uri, targetDir: File): List<String> {
        val tempFile = File.createTempFile("import_", ".zip", targetDir)
        try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                tempFile.outputStream().use { output -> input.copyTo(output) }
            } ?: return emptyList()
            val files = listArchiveTargetFiles(tempFile)
            importZipFromFile(tempFile, targetDir)
            return files
        } finally {
            tempFile.delete()
        }
    }

    /**
     * 找到所有 .schema.yaml 文件所在的共同父目录作为基目录。
     * 例如 zip 结构为 rime-ice-main/rime_ice.schema.yaml，
     * 则返回 "rime-ice-main/"，解压时剥离此前缀。
     * 基目录下的子目录（如 cn_dicts/）会原样保留。
     */
    internal fun findSchemaBaseDir(entryNames: List<String>): String {
        val schemaEntries = entryNames.filter { it.endsWith(".schema.yaml") }
        if (schemaEntries.isEmpty()) {
            // 无 .schema.yaml 的包(如 rime-essay 只含 essay.txt、rime-prelude 含 symbols.yaml）：
            // 若所有条目同处唯一壳目录（GitHub 归档形如 <repo>-<branch>/），剥掉它，
            // 否则文件会落进子目录（rime/rime-essay-master/essay.txt）导致 rime 读不到。
            val files = entryNames.filter { it.isNotBlank() }
            if (files.isEmpty() || files.any { !it.contains('/') }) return ""
            val tops = files.map { it.substringBefore('/') }.distinct()
            return if (tops.size == 1) "${tops[0]}/" else ""
        }
        // 获取所有 .schema.yaml 的父目录
        val parentDirs = schemaEntries.map { name ->
            val idx = name.lastIndexOf('/')
            if (idx >= 0) name.substring(0, idx + 1) else ""
        }.distinct()
        // 如果所有 .schema.yaml 在同一个父目录下，返回该目录作为基目录
        if (parentDirs.size == 1) {
            return parentDirs[0]
        }
        // 在不同目录下，找最长公共前缀
        val commonPrefix = parentDirs.reduce { a, b -> a.commonPrefixWith(b) }
        val idx = commonPrefix.lastIndexOf('/')
        return if (idx >= 0) commonPrefix.substring(0, idx + 1) else ""
    }

    private fun importZip(context: Context, uri: Uri, targetDir: File): Boolean {
        try {
            // 第一趟：收集文件名以检测共同根目录
            val entryNames = mutableListOf<String>()
            context.contentResolver.openInputStream(uri)?.use { input ->
                ZipInputStream(input.buffered()).use { zis ->
                    var entry = zis.nextEntry
                    while (entry != null) {
                        if (!entry.isDirectory && !isAppleDouble(entry.name)) entryNames.add(entry.name)
                        zis.closeEntry()
                        entry = zis.nextEntry
                    }
                }
            } ?: return false

            val baseDir = findSchemaBaseDir(entryNames)
            if (baseDir.isNotEmpty()) {
                Log.i(TAG, "Found schema base directory: $baseDir, will strip it on extraction")
            }

            // 第二趟：解压文件，剥离基目录前缀
            val importedSchemas = mutableSetOf<String>()
            var extractedCount = 0
            context.contentResolver.openInputStream(uri)?.use { input ->
                ZipInputStream(input.buffered()).use { zis ->
                    var entry = zis.nextEntry
                    while (entry != null) {
                        val originalName = entry.name
                        val name = originalName.removePrefix(baseDir)
                        if (!entry.isDirectory && !isAppleDouble(originalName) && !isProtectedImportName(name)) {
                            val file = safeChild(targetDir, name)
                            if (file == null) {
                                Log.w(TAG, "Skip unsafe path: $name")
                            } else {
                                file.parentFile?.mkdirs()
                                FileOutputStream(file).use { output ->
                                    zis.copyTo(output)
                                }
                                extractedCount++

                                when {
                                    name.endsWith(".schema.yaml") ->
                                        importedSchemas.add(name.removeSuffix(".schema.yaml").substringAfterLast('/'))
                                    name.endsWith(".dict.yaml") ->
                                        importedSchemas.add(name.removeSuffix(".dict.yaml").substringAfterLast('/'))
                                }

                            }
                        }
                        zis.closeEntry()
                        entry = zis.nextEntry
                    }
                }
            }

            Log.i(TAG, "Imported zip: $extractedCount files extracted, schemas: $importedSchemas")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to import zip", e)
            return false
        }
    }

    /**
     * 从 URL 下载文件到 market/{schemeId}/ 目录（仅下载，不解压）。
     * 支持任意文件类型（zip、tar.gz、yaml 等）。
     */
    /** 下载结果：success + sha256 校验状态（null=未提供, true=通过, false=不通过）。 */
    data class DownloadResult(val success: Boolean, val sha256Verified: Boolean? = null)

    suspend fun downloadToMarket(
        context: Context,
        url: String,
        schemeId: String,
        fileName: String,
        expectedSha256: String? = null,
        onProgress: (Long, Long) -> Unit = { _, _ -> },
    ): DownloadResult = withContext(Dispatchers.IO) {
        val wakeLock = (context.getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Xime:SchemaDownload")
        wakeLock.setReferenceCounted(false)
        wakeLock.acquire(5 * 60 * 1000L)
        try {
            val schemeDir = getMarketDir(context, schemeId)
            if (!schemeDir.exists()) schemeDir.mkdirs()
            val targetFile = File(schemeDir, fileName)

            downloadClient.newCall(Request.Builder().url(url).build()).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.e(TAG, "Download failed: ${response.code} $url")
                    return@withContext DownloadResult(false)
                }
                val body = response.body ?: return@withContext DownloadResult(false)
                val totalBytes = body.contentLength()
                var downloadedBytes = 0L
                val md = if (!expectedSha256.isNullOrBlank()) MessageDigest.getInstance("SHA-256") else null
                body.byteStream().use { input ->
                    FileOutputStream(targetFile).use { output ->
                        val buf = ByteArray(8192)
                        var n = input.read(buf)
                        while (n >= 0) {
                            output.write(buf, 0, n)
                            md?.update(buf, 0, n)
                            downloadedBytes += n
                            if (totalBytes > 0) onProgress(downloadedBytes, totalBytes)
                            n = input.read(buf)
                        }
                    }
                }
                if (md != null && !expectedSha256.isNullOrBlank()) {
                    val actual = md.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
                    if (!actual.equals(expectedSha256.trim(), ignoreCase = true)) {
                        Log.e(TAG, "sha256 mismatch for $url: expected=${expectedSha256.trim()} actual=$actual")
                        targetFile.delete()
                        return@withContext DownloadResult(false, sha256Verified = false)
                    }
                    Log.i(TAG, "Downloaded (sha256 verified): ${targetFile.absolutePath}")
                    DownloadResult(true, sha256Verified = true)
                } else {
                    val valid = validateArchive(targetFile)
                    if (!valid) {
                        Log.e(TAG, "Archive validation failed for $url, file is corrupted")
                        targetFile.delete()
                        return@withContext DownloadResult(false)
                    }
                    Log.i(TAG, "Downloaded (unverified, archive validated): ${targetFile.absolutePath}")
                    DownloadResult(true, sha256Verified = null)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "downloadToMarket failed: $url", e)
            val schemeDir = getMarketDir(context, schemeId)
            val targetFile = File(schemeDir, fileName)
            if (targetFile.exists()) targetFile.delete()
            if (schemeDir.exists() && schemeDir.listFiles().isNullOrEmpty()) {
                schemeDir.delete()
            }
            DownloadResult(false)
        } finally {
            if (wakeLock.isHeld) wakeLock.release()
        }
    }

    /**
     * 验证压缩包完整性：zip 尝试列出条目，tar.gz 尝试读取首条。
     * 非归档文件直接返回 true（无法校验）。
     */
    private fun validateArchive(file: File): Boolean {
        val name = file.name
        return try {
            when {
                name.endsWith(".zip", ignoreCase = true) -> {
                    java.util.zip.ZipFile(file).use { zip -> zip.entries().hasMoreElements() }
                }
                name.endsWith(".tar.gz", ignoreCase = true) || name.endsWith(".tgz", ignoreCase = true) -> {
                    org.apache.commons.compress.archivers.tar.TarArchiveInputStream(
                        java.util.zip.GZIPInputStream(file.inputStream())
                    ).use { tar -> tar.nextTarEntry != null }
                }
                else -> true // 非归档文件无法校验
            }
        } catch (e: Exception) {
            FileLogger.e(TAG, "Archive validation failed for ${file.name}", e)
            false
        }
    }

    /**
     * 从 URL 下载压缩包并解压进 rime 目录。
     * @param expectedSha256 非空时，下载落临时文件并校验 SHA-256；不符则不落盘、返回 false。
     *                       为空/空白时保持原有行为（不校验）。
     */
    /**
     * 从 URL 下载文件（不限格式），通过 [saveImportedFile] 统一存入 rime/ 或 market/。
     * @param archiveName 文件名，为空时从 URL 末段自动推断。
     */
    suspend fun importFromUrl(
        context: Context,
        url: String,
        expectedSha256: String? = null,
        archiveName: String? = null,
        onProgress: (Long, Long) -> Unit = { _, _ -> },
    ): ImportResult = withContext(Dispatchers.IO) {
        try {
            val fileName = archiveName ?: url.substringAfterLast("/").takeIf { it.isNotBlank() }
                ?: "download"
            val tmpFile = File.createTempFile("import_", ".tmp", context.cacheDir)

            try {
                val client = OkHttpClient.Builder()
                    .connectTimeout(30, TimeUnit.SECONDS)
                    .readTimeout(120, TimeUnit.SECONDS)
                    .followRedirects(true)
                    .build()
                val sha256Ok = client.newCall(Request.Builder().url(url).build()).execute().use { response ->
                    if (!response.isSuccessful) {
                        Log.e(TAG, "Download failed: ${response.code} $url")
                        return@withContext ImportResult(false)
                    }
                    val body = response.body ?: return@withContext ImportResult(false)
                    val totalBytes = body.contentLength()
                    var downloadedBytes = 0L
                    val md = MessageDigest.getInstance("SHA-256")
                    body.byteStream().use { input ->
                        FileOutputStream(tmpFile).use { output ->
                            val buf = ByteArray(8192)
                            var n = input.read(buf)
                            while (n >= 0) {
                                output.write(buf, 0, n)
                                md.update(buf, 0, n)
                                downloadedBytes += n
                                if (totalBytes > 0) onProgress(downloadedBytes, totalBytes)
                                n = input.read(buf)
                            }
                        }
                    }
                    if (!expectedSha256.isNullOrBlank()) {
                        val actual = md.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
                        if (!actual.equals(expectedSha256.trim(), ignoreCase = true)) {
                            Log.e(TAG, "sha256 mismatch for $url")
                            return@withContext ImportResult(false)
                        }
                    }
                    true
                }
                if (!sha256Ok) return@withContext ImportResult(false)

                // 通过统一函数保存
                val result = saveImportedFile(context, fileName, tmpFile.inputStream())
                Log.i(TAG, "Imported from url $url -> ${if (result.installedDirect) "rime/" else "market/"} ($fileName)")
                result
            } finally {
                tmpFile.delete()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to import from URL: $url", e)
            ImportResult(false)
        }
    }

    internal fun importZipFromStream(inputStream: InputStream, targetDir: File): Boolean {
        return try {
            // 保存到临时文件，以便两趟处理（检测共同根目录 + 解压）
            val tempFile = File.createTempFile("rime_import_", ".zip", targetDir)
            try {
                tempFile.outputStream().use { output -> inputStream.copyTo(output) }
                importZipFromFile(tempFile, targetDir)
            } finally {
                tempFile.delete()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to extract zip stream", e)
            false
        }
    }

    private fun importZipFromFile(zipFile: File, targetDir: File): Boolean {
        return try {
            // 第一趟：收集文件名以检测共同根目录（排除 Apple Double）
            val entryNames = mutableListOf<String>()
            ZipFile(zipFile).use { zip ->
                zip.entries().asSequence().forEach { entry ->
                    if (!entry.isDirectory && !isAppleDouble(entry.name)) {
                        entryNames.add(entry.name)
                    }
                }
            }

            val baseDir = findSchemaBaseDir(entryNames)
            if (baseDir.isNotEmpty()) {
                Log.i(TAG, "Found schema base directory: $baseDir, will strip it on extraction")
            }

            // 第二趟：解压
            var count = 0
            ZipFile(zipFile).use { zip ->
                zip.entries().asSequence().forEach { entry ->
                    val originalName = entry.name
                    if (entry.isDirectory || isAppleDouble(originalName)) return@forEach
                    val name = originalName.removePrefix(baseDir)
                    val file = if (isProtectedImportName(name)) null else safeChild(targetDir, name)
                    if (file == null) {
                        FileLogger.d(TAG, "Skip protected/unsafe entry: $name")
                    } else {
                        file.parentFile?.mkdirs()
                        zip.getInputStream(entry).use { input ->
                            FileOutputStream(file).use { output -> input.copyTo(output) }
                        }
                        count++
                        FileLogger.d(TAG, "Extracted zip entry: $name")
                    }
                }
            }
            FileLogger.i(TAG, "Extracted $count files from zip stream")
            count > 0
        } catch (e: Exception) {
            FileLogger.e(TAG, "Failed to extract zip file", e)
            false
        }
    }

    internal fun importTarGzFromStream(inputStream: InputStream, targetDir: File): Boolean {
        return try {
            // 落临时文件后走文件版（统一 gunzip + zip-slip/受保护文件 防护）
            val tempFile = File.createTempFile("rime_import_", ".tar.gz", targetDir)
            try {
                tempFile.outputStream().use { output -> inputStream.copyTo(output) }
                importTarGzFromFile(tempFile, targetDir)
            } finally {
                tempFile.delete()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to extract tar.gz stream", e)
            false
        }
    }

    private fun importTarGzFromFile(tarGzFile: File, targetDir: File): Boolean {
        return try {
            // 第一趟：收集文件名以检测共同根目录（排除 Apple Double）
            val entryNames = mutableListOf<String>()
            TarArchiveInputStream(GzipCompressorInputStream(tarGzFile.inputStream().buffered())).use { tarIn ->
                var entry = tarIn.nextEntry
                while (entry != null) {
                    if (!entry.isDirectory && !isAppleDouble(entry.name)) {
                        entryNames.add(entry.name)
                    }
                    entry = tarIn.nextEntry
                }
            }

            val baseDir = findSchemaBaseDir(entryNames)
            if (baseDir.isNotEmpty()) {
                Log.i(TAG, "Found schema base directory in tar.gz: $baseDir, will strip it")
            }

            // 第二趟：解压
            var count = 0
            TarArchiveInputStream(GzipCompressorInputStream(tarGzFile.inputStream().buffered())).use { tarIn ->
                var entry = tarIn.nextEntry
                while (entry != null) {
                    val originalName = entry.name
                    if (entry.isDirectory || isAppleDouble(originalName)) {
                        entry = tarIn.nextEntry
                        continue
                    }
                    val name = originalName.removePrefix(baseDir)
                    val file = if (isProtectedImportName(name)) null else safeChild(targetDir, name)
                    if (file == null) {
                    } else {
                        file.parentFile?.mkdirs()
                        FileOutputStream(file).use { output -> tarIn.copyTo(output) }
                        count++
                    }
                    entry = tarIn.nextEntry
                }
            }
            Log.i(TAG, "Extracted $count files from tar.gz")
            count > 0
        } catch (e: Exception) {
            Log.e(TAG, "Failed to extract tar.gz", e)
            false
        }
    }

    private fun getFileName(context: Context, uri: Uri): String? {
        val cursor = context.contentResolver.query(uri, null, null, null, null)
        cursor?.use {
            if (it.moveToFirst()) {
                val nameIndex = it.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (nameIndex >= 0) {
                    return it.getString(nameIndex)
                }
            }
        }
        return uri.lastPathSegment
    }
}
