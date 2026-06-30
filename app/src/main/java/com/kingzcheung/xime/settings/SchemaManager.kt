package com.kingzcheung.xime.settings

import android.content.Context
import android.net.Uri
import android.os.PowerManager
import android.util.Log
import com.charleskorn.kaml.Yaml
import com.charleskorn.kaml.YamlConfiguration
import com.charleskorn.kaml.YamlList
import com.charleskorn.kaml.YamlMap
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
import java.io.FileOutputStream
import java.io.InputStream
import java.io.InputStreamReader
import java.security.MessageDigest
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
    internal val yaml = Yaml(configuration = YamlConfiguration(strictMode = false))

    private val downloadClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    fun getRimeDir(context: Context): File =
        File(context.filesDir, "rime")

    /** market 根目录。 */
    fun getMarketDir(context: Context): File =
        File(getRimeDir(context), "market")

    /** 每个方案在 market 下的独立子目录：rime/market/{schemeId}/ */
    fun getMarketDir(context: Context, schemeId: String): File =
        File(getMarketDir(context), schemeId)

    /** 检查方案的压缩包是否已下载。 */
    fun isSchemeDownloaded(context: Context, schemeId: String): Boolean {
        val dir = getMarketDir(context, schemeId)
        return dir.exists() && (dir.listFiles()?.any { it.isFile } == true)
    }

    /** 删除 market 中指定方案的整个子目录（含压缩包）。 */
    fun deleteSchemeArchive(context: Context, schemeId: String): Boolean {
        val dir = getMarketDir(context, schemeId)
        if (!dir.exists()) return false
        return dir.deleteRecursively()
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
                    Log.e(TAG, "installFromMarketToRime: ${file.name} is corrupted for $schemeId, deleting")
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
                        Log.i(TAG, "Copied ${file.name} to rime dir")
                        true
                    }
                }
                if (!ok) {
                    Log.e(TAG, "installFromMarketToRime: failed to process ${file.name} for $schemeId, deleting")
                    file.delete()
                    allOk = false
                }
            } catch (e: Exception) {
                Log.e(TAG, "installFromMarketToRime: error processing ${file.name} for $schemeId, deleting", e)
                file.delete()
                allOk = false
            }
        }
        return allOk
    }

    private fun getBuildDir(context: Context): File =
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
                Log.d(TAG, "default.yaml schema_list -> $schemaIds")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write default.yaml schema_list", e)
        }
    }

    // sha256 校验 + 导入保护（纯函数，可单测）
    fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    /** 导入归档时禁止覆盖 default.yaml（保护用户配置）。 */
    fun isProtectedImportName(name: String): Boolean {
        val base = name.substringAfterLast('/')
        return base == "default.yaml"
    }

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
            val entry = yaml.decodeFromString(SchemaYaml.serializer(), text).schema
            if (entry.schemaId.isEmpty()) return null

            // author 可为标量或列表，从原始 YAML 节点手动提取
            val author = parseAuthorFromText(text)

            SchemaMeta(
                schemaId = entry.schemaId,
                name = entry.name.ifEmpty { entry.schemaId },
                version = entry.version,
                author = author,
                description = entry.description ?: ""
            )
        } catch (e: Exception) {
            try { Log.w(TAG, "Failed to parse schema file: ${file.name}, skip") } catch (_: Exception) {}
            null
        }
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

    fun getEnabledSchemas(context: Context): List<String> {
        val customFile = getCustomYamlFile(context)
        if (!customFile.exists()) {
            val defaultBuiltIn = listOf("wubi86", "wubi86_pinyin", "pinyin_simp")
            setEnabledSchemas(context, defaultBuiltIn)
            return defaultBuiltIn
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
            if (schemas.isNotEmpty()) return schemas
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read custom.yaml", e)
        }

        return listOf("wubi86", "wubi86_pinyin", "pinyin_simp")
    }

    fun setEnabledSchemas(context: Context, schemaIds: List<String>) {
        val sb = StringBuilder()
        sb.appendLine("patch:")
        sb.appendLine("  schema_list:")
        for (id in schemaIds) {
            sb.appendLine("    - schema: $id")
        }
        try {
            getCustomYamlFile(context).writeText(sb.toString())
            Log.d(TAG, "Updated custom.yaml with schemas: $schemaIds")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write custom.yaml", e)
        }
        // F1: 同步写进 default.yaml，确保 librime 真正编译启用的方案
        applyEnabledSchemasToDefaultYaml(context, schemaIds)
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

    fun deleteSchemaFiles(context: Context, schemaId: String) {
        val rimeDir = getRimeDir(context)
        val schemaFile = File(rimeDir, "$schemaId.schema.yaml")
        if (schemaFile.exists()) schemaFile.delete()

        val dictName = getReferencedDictName(context, schemaId) ?: schemaId
        val dictFile = File(rimeDir, "$dictName.dict.yaml")
        if (dictFile.exists()) dictFile.delete()

        val enabled = getEnabledSchemas(context).toMutableList()
        enabled.remove(schemaId)
        setEnabledSchemas(context, enabled)
        Log.i(TAG, "Deleted schema files for: $schemaId (dict=$dictName)")
    }

    suspend fun importSchemaFile(context: Context, uri: Uri): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                val rimeDir = getRimeDir(context)
                if (!rimeDir.exists()) rimeDir.mkdirs()

                val displayName = getFileName(context, uri) ?: return@withContext false

                if (displayName.endsWith(".zip", ignoreCase = true)) {
                    return@withContext importZip(context, uri, rimeDir)
                }

                val targetFile = File(rimeDir, displayName)
                context.contentResolver.openInputStream(uri)?.use { input ->
                    FileOutputStream(targetFile).use { output ->
                        input.copyTo(output)
                    }
                } ?: return@withContext false

                Log.i(TAG, "Imported: $displayName")

                true
            } catch (e: Exception) {
                Log.e(TAG, "Failed to import schema file", e)
                false
            }
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

                                Log.d(TAG, "Extracted: $name")
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
            Log.e(TAG, "Archive validation failed for ${file.name}", e)
            false
        }
    }

    /**
     * 从 URL 下载压缩包并解压进 rime 目录。
     * @param expectedSha256 非空时，下载落临时文件并校验 SHA-256；不符则不落盘、返回 false。
     *                       为空/空白时保持原有行为（不校验）。
     */
    /**
     * 从 URL 下载压缩包到 market 目录（保留压缩包），然后解压到 rime 目录。
     * @param archiveName 压缩包在 market 目录中保存的文件名，如 "my_scheme.zip"。
     *                    为空时从 URL 末段自动推断。
     */
    suspend fun importFromUrl(
        context: Context,
        url: String,
        expectedSha256: String? = null,
        archiveName: String? = null,
        onProgress: (Long, Long) -> Unit = { _, _ -> },
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val rimeDir = getRimeDir(context)
            if (!rimeDir.exists()) rimeDir.mkdirs()

            val isZip = url.endsWith(".zip", ignoreCase = true)
            val isTarGz = url.endsWith(".tar.gz", ignoreCase = true) || url.endsWith(".tgz", ignoreCase = true)
            if (!isZip && !isTarGz) {
                Log.e(TAG, "Unsupported format: $url")
                return@withContext false
            }

            // 确定压缩包保存路径
            val ext = when {
                isZip -> ".zip"
                url.endsWith(".tgz", ignoreCase = true) -> ".tgz"
                else -> ".tar.gz"
            }
            val marketDir = getMarketDir(context)
            if (!marketDir.exists()) marketDir.mkdirs()
            val archiveFile = File(marketDir, archiveName ?: (url.substringAfterLast("/").takeIf { it.isNotBlank() } ?: "download$ext"))

            val client = OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(120, TimeUnit.SECONDS)
                .followRedirects(true)
                .build()
            client.newCall(Request.Builder().url(url).build()).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.e(TAG, "Download failed: ${response.code} $url")
                    return@withContext false
                }
                val body = response.body ?: return@withContext false

                // 下载到 market 目录的压缩包文件，边写边算 SHA-256
                val totalBytes = body.contentLength()
                var downloadedBytes = 0L
                val md = MessageDigest.getInstance("SHA-256")
                body.byteStream().use { input ->
                    FileOutputStream(archiveFile).use { output ->
                        val buf = ByteArray(8192)
                        var n = input.read(buf)
                        while (n >= 0) {
                            output.write(buf, 0, n)
                            md.update(buf, 0, n)
                            downloadedBytes += n
                            if (totalBytes > 0) {
                                onProgress(downloadedBytes, totalBytes)
                            }
                            n = input.read(buf)
                        }
                    }
                }
                if (!expectedSha256.isNullOrBlank()) {
                    val actual = md.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
                    if (!actual.equals(expectedSha256.trim(), ignoreCase = true)) {
                        Log.e(TAG, "sha256 mismatch for $url: expected=${expectedSha256.trim()} actual=$actual")
                        archiveFile.delete()
                        return@withContext false
                    }
                }
                // 解压到 rime 目录
                if (isZip) importZipFromFile(archiveFile, rimeDir) else importTarGzFromFile(archiveFile, rimeDir)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to import from URL: $url", e)
            false
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
                        Log.d(TAG, "Skip protected/unsafe entry: $name")
                    } else {
                        file.parentFile?.mkdirs()
                        zip.getInputStream(entry).use { input ->
                            FileOutputStream(file).use { output -> input.copyTo(output) }
                        }
                        count++
                        Log.d(TAG, "Extracted zip entry: $name")
                    }
                }
            }
            Log.i(TAG, "Extracted $count files from zip stream")
            count > 0
        } catch (e: Exception) {
            Log.e(TAG, "Failed to extract zip file", e)
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
                        Log.d(TAG, "Skip protected/unsafe entry: $name")
                    } else {
                        file.parentFile?.mkdirs()
                        FileOutputStream(file).use { output -> tarIn.copyTo(output) }
                        count++
                        Log.d(TAG, "Extracted tar.gz entry: $name")
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
