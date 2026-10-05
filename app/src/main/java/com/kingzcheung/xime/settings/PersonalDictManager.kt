package com.kingzcheung.xime.settings

import android.content.Context
import com.kingzcheung.xime.ui.keyboard.isHandwritingSchema
import kotlinx.coroutines.sync.withLock
import java.io.File

object PersonalDictManager {
    private const val CUSTOM_PHRASE_FILE = "custom_phrase.txt"

    fun getCustomPhraseFile(context: Context, schemaId: String? = null): File {
        val rimeDir = SchemaManager.getRimeDir(context)
        val dictName = if (schemaId != null) getCustomPhraseDictName(rimeDir, schemaId)
                       else CUSTOM_PHRASE_FILE.removeSuffix(".txt")
        return File(rimeDir, "$dictName.txt")
    }

    /**
     * 从方案的 .custom.yaml 中读取 `custom_phrase.user_dict` 定义的文件名。
     * 例如 `user_dict: custom_phrase_double` → 对应 `custom_phrase_double.txt`。
     * 若无声明则返回默认的 `custom_phrase`。
     */
    internal fun getCustomPhraseDictName(rimeDir: File, schemaId: String): String {
        val customText = File(rimeDir, "${schemaId}.custom.yaml")
            .takeIf { it.exists() }
            ?.readText(Charsets.UTF_8)
        if (customText != null) {
            val fromCustom = parseCustomPhraseDictName(customText)
            if (fromCustom != null) return fromCustom
        }
        val schemaText = File(rimeDir, "${schemaId}.schema.yaml")
            .takeIf { it.exists() }
            ?.readText(Charsets.UTF_8)
        if (schemaText != null) {
            val fromSchema = parseCustomPhraseDictName(schemaText)
            if (fromSchema != null) return fromSchema
        }
        return CUSTOM_PHRASE_FILE.removeSuffix(".txt")
    }

    /** 从文本中解析 `custom_phrase.user_dict` 声明的文件名，没有则返回 null。 */
    private fun parseCustomPhraseDictName(text: String): String? {        for (cpKey in listOf("\"custom_phrase\"", "'custom_phrase'", "custom_phrase:")) {
            val idx = text.indexOf(cpKey)
            if (idx < 0) continue
            val after = text.substring(idx)
            val udIdx = after.indexOf("user_dict")
            if (udIdx < 0) continue
            val line = after.substring(udIdx).lineSequence().firstOrNull() ?: continue
            val value = line.substringAfter(":").trim().substringBefore(" #").substringBefore("\n")
            if (value.isNotBlank()) return value
        }
        return null
    }

    
    fun ensureCustomPhraseFileExists(context: Context, schemaId: String? = null) {
        val file = getCustomPhraseFile(context, schemaId)
        if (!file.exists()) {
            file.parentFile?.mkdirs()
            file.writeText("""# Rime table
# coding: utf-8
#@/db_name	custom_phrase
#@/db_type	tabledb
#
""", Charsets.UTF_8)
        }
    }

    fun loadCustomPhrases(context: Context, schemaId: String? = null): List<DictEntry> {
        val file = getCustomPhraseFile(context, schemaId)
        if (!file.exists()) return emptyList()
        return try {
            DictionaryHelper.parseCodeTable(file.readText(Charsets.UTF_8))
        } catch (_: Exception) { emptyList() }
    }

    fun saveCustomPhrases(context: Context, schemaId: String? = null, entries: List<DictEntry>) {
        val file = getCustomPhraseFile(context, schemaId)
        file.parentFile?.mkdirs()
        file.writeText(buildStableDbText(STABLEDB_HEADER, entries), Charsets.UTF_8)
    }

    /** 清理 custom_phrase 文件（仅当文件为空时删除，避免丢失用户数据）。 */
    fun removeCustomPhraseFile(context: Context, schemaId: String? = null) {
        val file = getCustomPhraseFile(context, schemaId)
        if (file.exists() && file.readText(Charsets.UTF_8).lineSequence().count { it.isNotBlank() && !it.startsWith('#') } == 0) {
            file.delete()
        }
    }

    // ── 方案配置补丁 ──

    private val schemaPacksMutex = kotlinx.coroutines.sync.Mutex()

    suspend fun ensureSchemaPacks(context: Context) {
        schemaPacksMutex.withLock {
            val rimeDir = SchemaManager.getRimeDir(context)
            val enabledSchemas = SchemaManager.getEnabledSchemas(context)
            for (schemaId in enabledSchemas) {
                ensureSchemaPackInner(rimeDir, context, schemaId)
            }
        }
    }

    suspend fun ensureSchemaPack(context: Context, schemaId: String) {
        schemaPacksMutex.withLock {
            val rimeDir = SchemaManager.getRimeDir(context)
            ensureSchemaPackInner(rimeDir, context, schemaId)
        }
    }

    private suspend fun ensureSchemaPackInner(rimeDir: java.io.File, context: Context, schemaId: String) {
        val schemaFile = java.io.File(rimeDir, "${schemaId}.schema.yaml")
        if (!schemaFile.exists()) return
        // 手写方案（schemas 绑定声明）不经过 librime 词典输入，跳过补丁。
        if (isHandwritingSchema(schemaId)) return
        // 个人词库合并规则已移除：清理旧版本写入的 merged 词典引用，
        // 让 librime 按 schema 原声明编译原始词典名（如 pinyin_simp.table.bin），
        // 恢复 wubi86_pinyin 等方案的 reverse_lookup 拼音反查。
        cleanupStaleMergedPatch(rimeDir, schemaId)
        val dictName = getCustomPhraseDictName(rimeDir, schemaId)
        val phraseFile = File(rimeDir, "$dictName.txt")
        if (!phraseFile.exists()) {
            phraseFile.parentFile?.mkdirs()
            phraseFile.writeText("""# Rime table
# coding: utf-8
#@/db_name	custom_phrase
#@/db_type	tabledb
#
""", Charsets.UTF_8)
        }
        // 有实际条目时才注入翻译器，避免 Rime 因空 .table.bin 报错
        if (phraseFile.readText(Charsets.UTF_8).lineSequence().count { it.isNotBlank() && !it.startsWith('#') } > 0) {
            applyCustomPhraseTranslator(rimeDir, schemaId, dictName)
        }
    }

    /**
     * 清理旧版本个人词库合并规则遗留的产物：
     * 1. 移除 `${schemaId}.custom.yaml` 中的 `translator/dictionary: <schemaId>_merged` 引用，
     *    让 librime 按 schema 原声明编译原始词典名（如 pinyin_simp.table.bin）；
     * 2. 删除 `${schemaId}_merged.dict.yaml` 合并表转发文件。
     */
    internal fun cleanupStaleMergedPatch(rimeDir: java.io.File, schemaId: String) {
        val mergedDict = java.io.File(rimeDir, "${schemaId}_merged.dict.yaml")
        if (mergedDict.exists()) {
            mergedDict.delete()
        }
        val customFile = java.io.File(rimeDir, "${schemaId}.custom.yaml")
        if (!customFile.exists()) return
        val text = customFile.readText(Charsets.UTF_8)
        val mergedRef = "\"translator/dictionary\": ${schemaId}_merged"
        if (!text.contains(mergedRef)) return
        val cleaned = text
            .replace(Regex("""^[ \t]*"?translator/dictionary"?\s*:\s*${schemaId}_merged\s*$""", RegexOption.MULTILINE), "")
            .replace(Regex("\n{3,}"), "\n\n")
            .trimEnd('\n', '\r', ' ') + "\n"
        if (cleaned != text) {
            customFile.writeText(cleaned, Charsets.UTF_8)
        }
    }

    // 为方案添加 custom_phrase 翻译器（独立于主词典音节表）
    internal fun applyCustomPhraseTranslator(rimeDir: java.io.File, schemaId: String, dictName: String) {
        val customFile = java.io.File(rimeDir, "${schemaId}.custom.yaml")
        if (customFile.exists()) {
            val text = customFile.readText(Charsets.UTF_8)
            if (text.contains("table_translator@custom_phrase")) return
        }
        insertUnderPatch(customFile, """  "engine/translators/+":
    - table_translator@custom_phrase
  "custom_phrase":
    dictionary: ""
    user_dict: $dictName
    db_class: stabledb
    enable_completion: false
    enable_sentence: false
    initial_quality: 99
""")
    }

    /** 在 YAML 的 `patch:` 块下增量插入内容，不覆盖已有配置。 */
    private fun insertUnderPatch(file: java.io.File, content: String) {
        if (!file.exists()) {
            file.writeText("patch:\n$content", Charsets.UTF_8)
            return
        }
        val text = file.readText(Charsets.UTF_8)
        val cleaned = text.trimEnd('\n', '\r', ' ').removeSuffix("...").trimEnd()
        val patchLine = Regex("^patch:", RegexOption.MULTILINE).find(cleaned)
        if (patchLine != null) {
            val at = patchLine.range.last + 1
            file.writeText(cleaned.substring(0, at) + "\n$content" + cleaned.substring(at) + "\n", Charsets.UTF_8)
        } else {
            file.writeText("$cleaned\n\npatch:\n$content", Charsets.UTF_8)
        }
    }

    private const val STABLEDB_HEADER = """# Rime table
# coding: utf-8
#@/db_name	custom_phrase
#@/db_type	tabledb
#
"""

    internal fun buildStableDbText(header: String, entries: List<DictEntry>): String {
        val sb = StringBuilder()
        sb.append(header.trimEnd('\n', '\r')).append('\n')
        for (e in entries) {
            sb.append(e.word).append('\t').append(e.code)
            if (e.weight != null) sb.append('\t').append(e.weight)
            sb.append('\n')
        }
        return sb.toString()
    }
}