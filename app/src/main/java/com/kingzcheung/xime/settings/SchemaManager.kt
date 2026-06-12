package com.kingzcheung.xime.settings

import android.content.Context
import android.net.Uri
import android.util.Log
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
import java.util.zip.ZipInputStream
import java.util.zip.ZipFile

data class SchemaMeta(
    val schemaId: String,
    val name: String,
    val version: String = "",
    val author: String = "",
    val description: String = ""
)

object SchemaManager {
    private const val TAG = "SchemaManager"
    private const val CUSTOM_YAML = "default.custom.yaml"

    fun getRimeDir(context: Context): File =
        File(context.filesDir, "rime")

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

    private fun parseSchemaYaml(file: File): SchemaMeta? {
        try {
            val lines = file.readLines()
            var schemaId = ""
            var name = ""
            var version = ""
            var author = ""
            var description = ""
            var inSchemaBlock = false
            var inAuthorBlock = false
            var inDescription = false
            val descriptionLines = mutableListOf<String>()

            for (line in lines) {
                val trimmed = line.trimStart()

                if (trimmed == "schema:") {
                    inSchemaBlock = true
                    inAuthorBlock = false
                    inDescription = false
                    continue
                }

                if (!inSchemaBlock && !trimmed.startsWith("schema:")) continue

                if (trimmed.startsWith("schema_id:")) {
                    schemaId = trimmed.removePrefix("schema_id:").trim().removeSurrounding("\"")
                } else if (trimmed.startsWith("name:")) {
                    name = trimmed.removePrefix("name:").trim().removeSurrounding("\"")
                } else if (trimmed.startsWith("version:")) {
                    version = trimmed.removePrefix("version:").trim().removeSurrounding("\"")
                } else if (trimmed.startsWith("author:")) {
                    inAuthorBlock = true
                    inDescription = false
                    val rest = trimmed.removePrefix("author:").trim()
                    if (rest.isNotEmpty()) {
                        author = rest.removeSurrounding("\"").removePrefix("- ").trim()
                    }
                } else if (trimmed.startsWith("description:")) {
                    inAuthorBlock = false
                    inDescription = true
                    val rest = trimmed.removePrefix("description:").trim()
                    if (rest.isNotEmpty() && rest != "|") {
                        description = rest.removeSurrounding("\"")
                    }
                } else if (inAuthorBlock && trimmed.startsWith("- ")) {
                    if (author.isEmpty()) {
                        author = trimmed.removePrefix("- ").trim().removeSurrounding("\"")
                    }
                } else if (inDescription && trimmed.isNotEmpty()) {
                    descriptionLines.add(trimmed)
                } else {
                    if (trimmed.isNotEmpty() && !trimmed.startsWith("#")) {
                        inAuthorBlock = false
                        inDescription = false
                    }
                }
            }

            if (schemaId.isNotEmpty()) {
                if (descriptionLines.isNotEmpty()) {
                    description = descriptionLines.joinToString(" ").trim()
                }
                return SchemaMeta(
                    schemaId = schemaId,
                    name = name.ifEmpty { schemaId },
                    version = version,
                    author = author,
                    description = description
                )
            }
            return null
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse schema file: ${file.name}", e)
            return null
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
                        if (!entry.isDirectory) entryNames.add(entry.name)
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
                        if (!entry.isDirectory && !isProtectedImportName(name)) {
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
     * 从 URL 下载压缩包并解压进 rime 目录。
     * @param expectedSha256 非空时，下载落临时文件并校验 SHA-256；不符则不落盘、返回 false。
     *                       为空/空白时保持原有行为（不校验）。
     */
    suspend fun importFromUrl(
        context: Context,
        url: String,
        expectedSha256: String? = null,
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

                // 下载到临时文件并边写边算 SHA-256（先校验，校验通过才解压落盘）
                val tmp = File.createTempFile("rime_dl_", if (isZip) ".zip" else ".tar.gz", context.cacheDir)
                try {
                    val md = MessageDigest.getInstance("SHA-256")
                    body.byteStream().use { input ->
                        FileOutputStream(tmp).use { output ->
                            val buf = ByteArray(8192)
                            var n = input.read(buf)
                            while (n >= 0) {
                                output.write(buf, 0, n)
                                md.update(buf, 0, n)
                                n = input.read(buf)
                            }
                        }
                    }
                    if (!expectedSha256.isNullOrBlank()) {
                        val actual = md.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
                        if (!actual.equals(expectedSha256.trim(), ignoreCase = true)) {
                            Log.e(TAG, "sha256 mismatch for $url: expected=${expectedSha256.trim()} actual=$actual")
                            return@withContext false
                        }
                    }
                    if (isZip) importZipFromFile(tmp, rimeDir) else importTarGzFromFile(tmp, rimeDir)
                } finally {
                    tmp.delete()
                }
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
            // 第一趟：收集文件名以检测共同根目录
            val entryNames = mutableListOf<String>()
            ZipFile(zipFile).use { zip ->
                zip.entries().asSequence().forEach { entry ->
                    if (!entry.isDirectory) entryNames.add(entry.name)
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
                    val name = originalName.removePrefix(baseDir)
                    if (!entry.isDirectory) {
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
            // 第一趟：收集文件名以检测共同根目录（注意 .tar.gz 需先 gunzip 再解 tar）
            val entryNames = mutableListOf<String>()
            TarArchiveInputStream(GzipCompressorInputStream(tarGzFile.inputStream().buffered())).use { tarIn ->
                var entry = tarIn.nextEntry
                while (entry != null) {
                    if (!entry.isDirectory) entryNames.add(entry.name)
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
                    val name = entry.name.removePrefix(baseDir)
                    if (!entry.isDirectory) {
                        val file = if (isProtectedImportName(name)) null else safeChild(targetDir, name)
                        if (file == null) {
                            Log.d(TAG, "Skip protected/unsafe entry: $name")
                        } else {
                            file.parentFile?.mkdirs()
                            FileOutputStream(file).use { output -> tarIn.copyTo(output) }
                            count++
                            Log.d(TAG, "Extracted tar.gz entry: $name")
                        }
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
