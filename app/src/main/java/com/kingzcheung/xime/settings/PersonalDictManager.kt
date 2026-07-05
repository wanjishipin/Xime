package com.kingzcheung.xime.settings

import android.content.Context
import android.util.Log
import java.io.File

object PersonalDictManager {
    private const val TAG = "PersonalDictManager"
    private const val CUSTOM_PHRASE_FILE = "custom_phrase.txt"

    private const val DEFAULT_HEADER = """# Rime dict
---
name: %s
version: '1.0'
sort: original
use_preset_vocabulary: false
...
"""

    private fun packName(schemaId: String): String = when {
        schemaId == "pinyin_simp" || schemaId == "t9_pinyin" -> "user_simp_pinyin"
        schemaId == "wubi86" -> "user_simp_wubi"
        else -> "user_simp_pinyin"
    }

    private fun packFileName(schemaId: String) = "${packName(schemaId)}.dict.yaml"

    private fun packHeader(schemaId: String) = DEFAULT_HEADER.format(packName(schemaId))

    fun getPackFile(context: Context, schemaId: String): File =
        File(SchemaManager.getRimeDir(context), packFileName(schemaId))

    fun getCustomPhraseFile(context: Context): File =
        File(SchemaManager.getRimeDir(context), CUSTOM_PHRASE_FILE)

    fun ensureAllPackFilesExist(context: Context) {
        for (sid in listOf("pinyin_simp", "t9_pinyin", "wubi86")) {
            val file = getPackFile(context, sid)
            if (!file.exists()) {
                file.parentFile?.mkdirs()
                file.writeText(packHeader(sid), Charsets.UTF_8)
            }
        }
    }

    fun ensureCustomPhraseFileExists(context: Context) {
        val file = getCustomPhraseFile(context)
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

    // ── 方案配置补丁 ──
    //
    // 词库扩展包 (packs) 依赖 prism 中存在对应的编码。
    // 有 speller/algebra 的方案（如拼音），algebra 展开后所有有效编码都在 prism 中，
    // packs 可以自由添加新词条。
    // 无 speller/algebra 的方案（如五笔），prism 只包含词典条目中实际出现的编码，
    // 需用 import_tables 合并词典，将个人词库编码一并编入 prism。

    fun ensureSchemaPacks(context: Context) {
        val rimeDir = SchemaManager.getRimeDir(context)
        val enabledSchemas = SchemaManager.getEnabledSchemas(context)
        for (schemaId in enabledSchemas) {
            ensureSchemaPackInner(rimeDir, schemaId)
        }
    }

    fun ensureSchemaPack(context: Context, schemaId: String) {
        val rimeDir = SchemaManager.getRimeDir(context)
        ensureSchemaPackInner(rimeDir, schemaId)
    }

    private fun ensureSchemaPackInner(rimeDir: java.io.File, schemaId: String) {
        val schemaFile = java.io.File(rimeDir, "${schemaId}.schema.yaml")
        if (!schemaFile.exists()) return
        if (hasReverseLookupTranslator(schemaFile)) {
            // reverse_lookup 方案不替换词典，只加 custom_phrase
        } else if (hasSpellerAlgebra(schemaFile)) {
            applyPackConfig(rimeDir, schemaId)
        } else {
            applyMergedDictConfig(rimeDir, schemaId)
        }
        applyCustomPhraseTranslator(rimeDir, schemaId)
    }

    internal fun hasReverseLookupTranslator(schemaFile: java.io.File): Boolean {
        val text = schemaFile.readText(Charsets.UTF_8)
        return text.contains("reverse_lookup_translator")
    }

    // 为方案添加 custom_phrase 翻译器（独立于主词典音节表）
    internal fun applyCustomPhraseTranslator(rimeDir: java.io.File, schemaId: String) {
        val customFile = java.io.File(rimeDir, "${schemaId}.custom.yaml")
        if (customFile.exists()) {
            val text = customFile.readText(Charsets.UTF_8)
            if (text.contains("table_translator@custom_phrase")) return
        }
        val existing = if (customFile.exists()) {
            customFile.readText(Charsets.UTF_8).trimEnd('\n', '\r', ' ')
        } else ""
        // remove trailing "..." if exists from old patch
        val base = existing.removeSuffix("...").trimEnd('\n', '\r', ' ')
        val newContent = """$base
  "engine/translators/+":
    - table_translator@custom_phrase
  "custom_phrase":
    dictionary: ""
    user_dict: custom_phrase
    db_class: stabledb
    enable_completion: false
    enable_sentence: false
    initial_quality: 99
"""
        customFile.writeText(newContent, Charsets.UTF_8)
    }

    internal fun hasSpellerAlgebra(schemaFile: java.io.File): Boolean {
        val text = schemaFile.readText(Charsets.UTF_8).replace("\r\n", "\n")
        val idx = text.indexOf("\n  algebra:\n")
        if (idx < 0) return false
        val section = text.substring(idx, text.indexOf("\n\n", idx).let { if (it < 0) text.length else it })
        return section.contains("- ")
    }

    // 方案有固定音节表：translator/packs via .custom.yaml
    internal fun applyPackConfig(rimeDir: java.io.File, schemaId: String) {
        val pkName = packName(schemaId)
        val customFile = java.io.File(rimeDir, "${schemaId}.custom.yaml")
        if (customFile.exists()) {
            val text = customFile.readText(Charsets.UTF_8)
            if (text.contains(pkName)) return
        }
        customFile.writeText("""# Xime 词库管理补丁 - 自动生成
patch:
  "translator/packs": ["$pkName"]
""", Charsets.UTF_8)
    }

    // 方案无固定音节表：import_tables 合并词典 + translator/dictionary via .custom.yaml
    internal fun applyMergedDictConfig(rimeDir: java.io.File, schemaId: String) {
        val pkName = packName(schemaId)
        val mergedId = "${schemaId}_merged"
        val dictFile = java.io.File(rimeDir, "${mergedId}.dict.yaml")
        if (!dictFile.exists()) {
            dictFile.writeText("""# Rime dict
---
name: $mergedId
version: "1.0"
sort: original
import_tables:
  - $schemaId
  - $pkName
...
""", Charsets.UTF_8)
        }
        val customFile = java.io.File(rimeDir, "${schemaId}.custom.yaml")
        if (customFile.exists()) {
            val text = customFile.readText(Charsets.UTF_8)
            if (text.contains(mergedId)) return
        }
        customFile.writeText("""# Xime 词库管理补丁 - 自动生成
patch:
  "translator/dictionary": $mergedId
""", Charsets.UTF_8)
    }

    // ── 个人词库 ──

    fun loadEntries(context: Context, schemaId: String): List<DictEntry> {
        val file = getPackFile(context, schemaId)
        if (!file.exists()) return emptyList()
        return try {
            parsePersonalDictEntries(file.readText(Charsets.UTF_8))
        } catch (_: Exception) { emptyList() }
    }

    fun saveEntries(context: Context, schemaId: String, entries: List<DictEntry>) {
        val file = getPackFile(context, schemaId)
        file.parentFile?.mkdirs()
        val defaultH = packHeader(schemaId)
        val header = if (file.exists()) extractHeader(file, defaultH) else defaultH
        file.writeText(buildDictText(header, entries), Charsets.UTF_8)
    }

    fun parsePersonalDictEntries(text: String): List<DictEntry> {
        val out = mutableListOf<DictEntry>()
        var inData = false
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            if (!inData) { if (line == "...") inData = true; continue }
            if (line.isEmpty() || line.startsWith("#")) continue
            val parts = line.split('\t')
            if (parts.size >= 2) {
                out.add(DictEntry(parts[0], parts[1], parts.getOrNull(2)?.toIntOrNull()))
            } else {
                val spaceIdx = line.indexOf(' ')
                if (spaceIdx >= 0) {
                    out.add(DictEntry(line.substring(0, spaceIdx), line.substring(spaceIdx + 1).trim()))
                }
            }
        }
        return out
    }

    internal fun buildDictText(header: String, entries: List<DictEntry>): String {
        val sb = StringBuilder()
        sb.append(header.trimEnd('\n', '\r')).append('\n')
        for (e in entries) {
            sb.append(e.word).append('\t').append(e.code)
            if (e.weight != null) sb.append('\t').append(e.weight)
            sb.append('\n')
        }
        return sb.toString()
    }

    internal fun extractHeader(file: File, defaultH: String): String {
        if (!file.exists()) return defaultH
        val text = file.readText(Charsets.UTF_8)
        return extractHeaderFromText(text, defaultH)
    }

    internal fun extractHeaderFromText(text: String, defaultH: String): String {
        val norm = text.replace("\r\n", "\n")
        val idx = norm.indexOf("\n...\n")
        if (idx >= 0) return norm.substring(0, idx + 5)
        val start = norm.indexOf("...\n")
        if (start >= 0) return norm.substring(0, start + 4)
        return defaultH
    }

    // ── 自定义短语 ──
    // custom_phrase.txt 通过独立的 table_translator 加载（db_class: stabledb），
    // 不走主词典音节表，任意编码在所有方案中均可使用。

    fun loadCustomPhrases(context: Context): List<DictEntry> {
        val file = getCustomPhraseFile(context)
        if (!file.exists()) return emptyList()
        return try {
            parseStableDbEntries(file.readText(Charsets.UTF_8))
        } catch (_: Exception) { emptyList() }
    }

    fun saveCustomPhrases(context: Context, entries: List<DictEntry>) {
        val file = getCustomPhraseFile(context)
        file.parentFile?.mkdirs()
        file.writeText(buildStableDbText(STABLEDB_HEADER, entries), Charsets.UTF_8)
    }

    private const val STABLEDB_HEADER = """# Rime table
# coding: utf-8
#@/db_name	custom_phrase
#@/db_type	tabledb
#
"""

    internal fun parseStableDbEntries(text: String): List<DictEntry> {
        val out = mutableListOf<DictEntry>()
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith('#')) continue
            val parts = line.split('\t')
            if (parts.size >= 2) {
                out.add(DictEntry(parts[0], parts[1], parts.getOrNull(2)?.toIntOrNull()))
            }
        }
        return out
    }

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
