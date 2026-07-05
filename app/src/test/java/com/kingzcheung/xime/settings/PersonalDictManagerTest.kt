package com.kingzcheung.xime.settings

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import java.io.File

private fun mockContext(): Context {
    val tmpDir = File.createTempFile("mock_context", "").apply { delete(); mkdirs() }
    return mock {
        on { filesDir } doReturn tmpDir
    }
}

class PersonalDictManagerTest {

    private val defaultHeader = """# Rime dict
---
name: user_simp_pinyin
version: '1.0'
sort: original
use_preset_vocabulary: false
...
"""

    @Test
    fun `default header has correct Rime format`() {
        assertTrue(defaultHeader.startsWith("# Rime dict"))
        assertTrue(defaultHeader.contains("name: user_simp_pinyin"))
        assertTrue(defaultHeader.contains("version: '1.0'"))
        assertTrue(defaultHeader.contains("sort: original"))
        assertTrue(defaultHeader.contains("use_preset_vocabulary: false"))
        assertTrue(defaultHeader.endsWith("...\n"))
    }

    @Test
    fun `extractHeader returns header before marker`() {
        val text = """# Rime dict
---
name: user_simp
version: '1.0'
...
粗鄙之语	cu bi zhi yu
"""
        val file = createTempFile(text)
        val result = PersonalDictManager.extractHeader(file, defaultHeader)
        assertTrue(result.startsWith("# Rime dict"))
        assertTrue(result.endsWith("...\n"))
        assertEquals("""# Rime dict
---
name: user_simp
version: '1.0'
...
""", result)
    }

    @Test
    fun `extractHeader returns default for non-existent file`() {
        val file = File.createTempFile("test_nonexistent", ".tmp")
        file.delete()
        val result = PersonalDictManager.extractHeader(file, defaultHeader)
        assertEquals(defaultHeader, result)
    }

    @Test
    fun `extractHeader returns default for file without marker`() {
        val file = createTempFile("no marker here\njust text\n")
        val result = PersonalDictManager.extractHeader(file, defaultHeader)
        assertEquals(defaultHeader, result)
    }

    @Test
    fun `extractHeaderFromText handles marker in middle`() {
        val text = "prefix\n...\nsuffix"
        val result = PersonalDictManager.extractHeaderFromText(text, defaultHeader)
        assertEquals("prefix\n...\n", result)
    }

    @Test
    fun `extractHeaderFromText handles no preceding newline`() {
        val text = "...\nsuffix"
        val result = PersonalDictManager.extractHeaderFromText(text, defaultHeader)
        assertEquals("...\n", result)
    }

    @Test
    fun `extractHeaderFromText returns default when no marker`() {
        assertEquals(defaultHeader, PersonalDictManager.extractHeaderFromText("no marker", defaultHeader))
    }

    @Test
    fun `buildDictText preserves header and formats entries`() {
        val entries = listOf(
            DictEntry("测试", "ce shi"),
            DictEntry("词条", "ci tiao")
        )
        val result = PersonalDictManager.buildDictText(defaultHeader, entries)
        val expected = """# Rime dict
---
name: user_simp_pinyin
version: '1.0'
sort: original
use_preset_vocabulary: false
...
测试	ce shi
词条	ci tiao
"""
        assertEquals(expected, result)
    }

    @Test
    fun `buildDictText output uses tab delimiter not space`() {
        val result = PersonalDictManager.buildDictText(defaultHeader, listOf(DictEntry("你好", "ni hao")))
        assertTrue(result.contains("你好\tni hao"))
        assertFalse(result.contains("你好 ni hao"))
    }

    @Test
    fun `buildDictText ends each entry with newline`() {
        val result = PersonalDictManager.buildDictText(defaultHeader, listOf(DictEntry("a", "b")))
        assertTrue(result.endsWith("a\tb\n"))
    }

    @Test
    fun `buildDictText handles empty entries`() {
        val result = PersonalDictManager.buildDictText(defaultHeader, emptyList())
        assertEquals(defaultHeader, result)
    }

    @Test
    fun `round trip preserves entries with multi-word codes`() {
        val originalEntries = listOf(
            DictEntry("你好", "ni hao"),
            DictEntry("世界", "shi jie"),
            DictEntry("测试", "ce shi")
        )
        val text = PersonalDictManager.buildDictText(defaultHeader, originalEntries)
        val parsedEntries = PersonalDictManager.parsePersonalDictEntries(text)
        assertEquals(originalEntries.size, parsedEntries.size)
        assertEquals(originalEntries, parsedEntries)
    }

    @Test
    fun `full file round-trip preserves entries`() {
        val file = File.createTempFile("personal_dict_roundtrip", ".tmp")
        file.deleteOnExit()
        val originalEntries = listOf(
            DictEntry("你好", "ni hao"),
            DictEntry("世界", "shi jie")
        )
        PersonalDictManager.buildDictText(defaultHeader, originalEntries).let { file.writeText(it, Charsets.UTF_8) }
        val newHeader = PersonalDictManager.extractHeader(file, defaultHeader)
        assertEquals(defaultHeader, newHeader)
        val parsed = PersonalDictManager.parsePersonalDictEntries(file.readText(Charsets.UTF_8))
        assertEquals(originalEntries, parsed)
    }

    @Test
    fun `file created from scratch has correct format`() {
        val file = File.createTempFile("personal_dict_scratch", ".tmp")
        file.deleteOnExit()
        file.delete()
        val h = PersonalDictManager.extractHeader(file, defaultHeader)
        assertEquals(defaultHeader, h)
        val entries = listOf(DictEntry("a", "b"))
        val text = PersonalDictManager.buildDictText(h, entries)
        file.writeText(text, Charsets.UTF_8)
        val content = file.readText(Charsets.UTF_8)
        assertTrue(content.startsWith("# Rime dict"))
        assertTrue(content.contains("name: user_simp_pinyin"))
        assertTrue(content.contains("...\n"))
        assertTrue(content.contains("a\tb"))
    }

    @Test
    fun `parsePersonalDictEntries reads entries after marker tab-delimited`() {
        val text = "# comment\n---\nname: x\n...\n日\ta\n曰\ta\n郎\tivnl\n"
        assertEquals(
            listOf(DictEntry("日", "a"), DictEntry("曰", "a"), DictEntry("郎", "ivnl")),
            PersonalDictManager.parsePersonalDictEntries(text),
        )
    }

    @Test
    fun `parsePersonalDictEntries preserves spaces in code`() {
        val text = "...\n你好\tni hao\n世界\tshi jie\n"
        assertEquals(
            listOf(DictEntry("你好", "ni hao"), DictEntry("世界", "shi jie")),
            PersonalDictManager.parsePersonalDictEntries(text),
        )
    }

    @Test
    fun `parsePersonalDictEntries falls back to space delimiter if no tab`() {
        val text = "...\n你好 ni hao\n世界 shi jie\n"
        assertEquals(
            listOf(DictEntry("你好", "ni hao"), DictEntry("世界", "shi jie")),
            PersonalDictManager.parsePersonalDictEntries(text),
        )
    }

    @Test
    fun `parsePersonalDictEntries ignores comments and blank lines`() {
        val text = "...\n\n# comment\n测试\tce shi\n"
        assertEquals(
            listOf(DictEntry("测试", "ce shi")),
            PersonalDictManager.parsePersonalDictEntries(text),
        )
    }

    @Test
    fun `parsePersonalDictEntries returns empty for header-only file`() {
        val text = "name: user_simp\nversion: '1.0'\n...\n"
        assertTrue(PersonalDictManager.parsePersonalDictEntries(text).isEmpty())
    }

    @Test
    fun `parsePersonalDictEntries skips lines before marker`() {
        val text = "junk\nbefore\n...\nreal\tentry\n"
        assertEquals(
            listOf(DictEntry("real", "entry")),
            PersonalDictManager.parsePersonalDictEntries(text),
        )
    }

    @Test
    fun `round trip with tab-only codes`() {
        val original = listOf(
            DictEntry("粗鄙之语", "cu bi zhi yu")
        )
        val text = PersonalDictManager.buildDictText(defaultHeader, original)
        val parsed = PersonalDictManager.parsePersonalDictEntries(text)
        assertEquals(original, parsed)
    }

    @Test
    fun `round trip with unicode entries`() {
        val original = listOf(
            DictEntry("日本語", "ni hon go"),
            DictEntry("한국어", "han gu g eo"),
            DictEntry("😊", "biao qing")
        )
        val text = PersonalDictManager.buildDictText(defaultHeader, original)
        val parsed = PersonalDictManager.parsePersonalDictEntries(text)
        assertEquals(original, parsed)
    }

    @Test
    fun `buildDictText generates correct output`() {
        val entries = listOf(DictEntry("你好", "ni hao"))
        val result = PersonalDictManager.buildDictText(defaultHeader, entries)
        assertEquals("""# Rime dict
---
name: user_simp_pinyin
version: '1.0'
sort: original
use_preset_vocabulary: false
...
你好	ni hao
""", result)
    }

    @Test
    fun `buildDictText handles header without data entries`() {
        val result = PersonalDictManager.buildDictText(defaultHeader, listOf(DictEntry("a", "b")))
        assertTrue(result.contains("a\tb\n"))
    }

    @Test
    fun `multiple saves do not corrupt header`() {
        val file = File.createTempFile("personal_dict_multi", ".tmp")
        file.deleteOnExit()
        val entries1 = listOf(DictEntry("a", "b"))
        PersonalDictManager.buildDictText(defaultHeader, entries1).let { file.writeText(it, Charsets.UTF_8) }
        val h1 = PersonalDictManager.extractHeader(file, defaultHeader)
        val entries2 = listOf(DictEntry("a", "b"), DictEntry("c", "d"))
        PersonalDictManager.buildDictText(h1, entries2).let { file.writeText(it, Charsets.UTF_8) }
        val h2 = PersonalDictManager.extractHeader(file, defaultHeader)
        assertEquals(defaultHeader, h2)
        val parsed = PersonalDictManager.parsePersonalDictEntries(file.readText(Charsets.UTF_8))
        assertEquals(entries2, parsed)
    }

    @Test
    fun `output is valid RIME dict format`() {
        val entries = listOf(DictEntry("测试", "ce shi"))
        val result = PersonalDictManager.buildDictText(defaultHeader, entries)
        val lines = result.split('\n')
        assertTrue(lines.any { it.startsWith("name:") })
        val dataStart = lines.indexOfFirst { it == "..." }
        assertTrue(dataStart >= 0)
        val dataLines = lines.drop(dataStart + 1).filter { it.isNotBlank() && !it.startsWith("#") }
        assertTrue(dataLines.any { it.contains('\t') })
    }

    // ── 自定义短语 ──

    private val stubHeader = """# Rime table
# coding: utf-8
#@/db_name	custom_phrase
#@/db_type	tabledb
#
"""

    @Test
    fun `parseStableDbEntries reads entries skipping header`() {
        val text = """# Rime table
# coding: utf-8
#@/db_name	custom_phrase
#@/db_type	tabledb
#
测试	ce shi
词条	ci tiao
"""
        val result = PersonalDictManager.parseStableDbEntries(text)
        assertEquals(
            listOf(DictEntry("测试", "ce shi"), DictEntry("词条", "ci tiao")),
            result
        )
    }

    @Test
    fun `parseStableDbEntries reads weight field`() {
        val text = """#
a	b	99
"""
        val result = PersonalDictManager.parseStableDbEntries(text)
        assertEquals(listOf(DictEntry("a", "b", 99)), result)
    }

    @Test
    fun `parseStableDbEntries handles optional weight`() {
        val text = """#
a	b	99
c	d
"""
        val result = PersonalDictManager.parseStableDbEntries(text)
        assertEquals(listOf(DictEntry("a", "b", 99), DictEntry("c", "d")), result)
    }

    @Test
    fun `buildStableDbText preserves header and appends entries`() {
        val entries = listOf(
            DictEntry("联系一下", "lxyx"),
            DictEntry("等等", "dd")
        )
        val result = PersonalDictManager.buildStableDbText(stubHeader, entries)
        assertTrue(result.startsWith("# Rime table"))
        assertTrue(result.contains("联系一下\tlxyx\n"))
        assertTrue(result.contains("等等\tdd\n"))
    }

    @Test
    fun `buildStableDbText includes weight when present`() {
        val entries = listOf(DictEntry("a", "b", 99))
        val result = PersonalDictManager.buildStableDbText(stubHeader, entries)
        assertTrue(result.contains("a\tb\t99\n"))
    }

    @Test
    fun `buildStableDbText omits weight when null`() {
        val entries = listOf(DictEntry("a", "b"))
        val result = PersonalDictManager.buildStableDbText(stubHeader, entries)
        assertTrue(result.contains("a\tb\n"))
    }

    @Test
    fun `stabledb round trip preserves weight`() {
        val original = listOf(DictEntry("联系一下", "lxyx", 99))
        val text = PersonalDictManager.buildStableDbText(stubHeader, original)
        val parsed = PersonalDictManager.parseStableDbEntries(text)
        assertEquals(original, parsed)
    }

    @Test
    fun `loadCustomPhrases when file missing returns empty`() {
        val context = mockContext()
        assertTrue(PersonalDictManager.loadCustomPhrases(context).isEmpty())
    }

    @Test
    fun `saveCustomPhrases writes to custom_phrase dot txt`() {
        val context = mockContext()
        val entries = listOf(DictEntry("kingzcheung@gmail.com", "yxdz", 99))
        PersonalDictManager.saveCustomPhrases(context, entries)
        val file = PersonalDictManager.getCustomPhraseFile(context)
        assertTrue(file.exists())
        val loaded = PersonalDictManager.parseStableDbEntries(file.readText(Charsets.UTF_8))
        assertTrue(loaded.any { it.word == "kingzcheung@gmail.com" })
    }

    @Test
    fun `applyCustomPhraseTranslator adds translator to custom yaml`() {
        val rimeDir = createTempDir()
        PersonalDictManager.applyCustomPhraseTranslator(rimeDir, "wubi86")
        val customFile = File(rimeDir, "wubi86.custom.yaml")
        assertTrue(customFile.exists())
        val text = customFile.readText(Charsets.UTF_8)
        assertTrue(text.contains("table_translator@custom_phrase"))
        assertTrue(text.contains("db_class: stabledb"))
    }

    @Test
    fun `applyCustomPhraseTranslator is idempotent`() {
        val rimeDir = createTempDir()
        PersonalDictManager.applyCustomPhraseTranslator(rimeDir, "wubi86")
        PersonalDictManager.applyCustomPhraseTranslator(rimeDir, "wubi86")
        val text = File(rimeDir, "wubi86.custom.yaml").readText(Charsets.UTF_8)
        assertEquals(1, text.split("table_translator@custom_phrase").size - 1)
    }

    // ── 方案补丁 ──

    @Test
    fun `hasSpellerAlgebra returns true when algebra present`() {
        val schema = """speller:
  alphabet: abc
  algebra:
    - erase/^xx$/
"""
        val file = createTempFile(schema)
        assertTrue(PersonalDictManager.hasSpellerAlgebra(file))
    }

    @Test
    fun `hasSpellerAlgebra returns false without algebra`() {
        val schema = """speller:
  alphabet: abcdefghijklmnopqrstuvwxyz
  max_code_length: 4
"""
        val file = createTempFile(schema)
        assertFalse(PersonalDictManager.hasSpellerAlgebra(file))
    }

    // ── ensureSchemaPack 按需创建 ──

    @Test
    fun `ensureSchemaPack creates custom yaml for schema with algebra`() {
        val context = mockContext()
        val rimeDir = java.io.File(context.filesDir, "rime")
        rimeDir.mkdirs()
        java.io.File(rimeDir, "pinyin_simp.schema.yaml").writeText("""
speller:
  alphabet: abc
  algebra:
    - erase/^xx$/
""".trimIndent())
        PersonalDictManager.ensureSchemaPack(context, "pinyin_simp")
        val customFile = java.io.File(rimeDir, "pinyin_simp.custom.yaml")
        assertTrue("custom.yaml should be created for schema with algebra", customFile.exists())
        val text = customFile.readText(Charsets.UTF_8)
        assertTrue(text.contains("translator/packs"))
        assertTrue("custom_phrase should also be added", text.contains("table_translator@custom_phrase"))
    }

    @Test
    fun `ensureSchemaPack creates merged dict for schema without algebra`() {
        val context = mockContext()
        val rimeDir = java.io.File(context.filesDir, "rime")
        rimeDir.mkdirs()
        java.io.File(rimeDir, "wubi86.schema.yaml").writeText("""
speller:
  alphabet: abcdefghijklmnopqrstuvwxyz
  max_code_length: 4
""".trimIndent())
        PersonalDictManager.ensureSchemaPack(context, "wubi86")
        assertTrue("merged dict should be created", java.io.File(rimeDir, "wubi86_merged.dict.yaml").exists())
        val customFile = java.io.File(rimeDir, "wubi86.custom.yaml")
        assertTrue("custom.yaml should be created", customFile.exists())
        val text = customFile.readText(Charsets.UTF_8)
        assertTrue(text.contains("translator/dictionary"))
    }

    @Test
    fun `ensureSchemaPack skips reverse_lookup schemas`() {
        val context = mockContext()
        val rimeDir = java.io.File(context.filesDir, "rime")
        rimeDir.mkdirs()
        java.io.File(rimeDir, "wubi86_pinyin.schema.yaml").writeText("""
translator:
  dictionary: wubi86_pinyin
  reverse_lookup_translator: true
""".trimIndent())
        PersonalDictManager.ensureSchemaPack(context, "wubi86_pinyin")
        val customFile = java.io.File(rimeDir, "wubi86_pinyin.custom.yaml")
        assertTrue("custom.yaml should be created (custom_phrase only)", customFile.exists())
        val text = customFile.readText(Charsets.UTF_8)
        assertTrue(text.contains("table_translator@custom_phrase"))
        // reverse_lookup schemas should NOT get packs or merged dict
        assertFalse("should not have packs", text.contains("translator/packs"))
        assertFalse("should not have merged dict", text.contains("translator/dictionary"))
    }

    @Test
    fun `ensureSchemaPack is no-op when schema file missing`() {
        val context = mockContext()
        val rimeDir = java.io.File(context.filesDir, "rime")
        rimeDir.mkdirs()
        // no schema file created
        PersonalDictManager.ensureSchemaPack(context, "nonexistent")
        val customFile = java.io.File(rimeDir, "nonexistent.custom.yaml")
        assertFalse("no custom.yaml should be created for missing schema", customFile.exists())
    }

    @Test
    fun `ensureSchemaPack is idempotent`() {
        val context = mockContext()
        val rimeDir = java.io.File(context.filesDir, "rime")
        rimeDir.mkdirs()
        java.io.File(rimeDir, "pinyin_simp.schema.yaml").writeText("""
speller:
  alphabet: abc
  algebra:
    - erase/^xx$/
""".trimIndent())
        PersonalDictManager.ensureSchemaPack(context, "pinyin_simp")
        PersonalDictManager.ensureSchemaPack(context, "pinyin_simp")
        val text = java.io.File(rimeDir, "pinyin_simp.custom.yaml").readText(Charsets.UTF_8)
        // packs line should appear only once
        assertEquals(1, text.split("user_simp_pinyin").size - 1)
        assertEquals(1, text.split("table_translator@custom_phrase").size - 1)
    }

    @Test
    fun `applyPackConfig creates custom yaml with packs for schema WITH algebra`() {
        val rimeDir = createTempDir()
        PersonalDictManager.applyPackConfig(rimeDir, "pinyin_simp")
        val customFile = File(rimeDir, "pinyin_simp.custom.yaml")
        assertTrue(customFile.exists())
        val text = customFile.readText(Charsets.UTF_8)
        assertTrue(text.contains("translator/packs"))
        assertTrue(text.contains("user_simp_pinyin"))
    }

    @Test
    fun `applyPackConfig is idempotent`() {
        val rimeDir = createTempDir()
        PersonalDictManager.applyPackConfig(rimeDir, "pinyin_simp")
        PersonalDictManager.applyPackConfig(rimeDir, "pinyin_simp")
        val customFile = File(rimeDir, "pinyin_simp.custom.yaml")
        val text = customFile.readText(Charsets.UTF_8)
        assertEquals(1, text.split("user_simp_pinyin").size - 1)
    }

    @Test
    fun `applyMergedDictConfig creates merged dict and custom for schema WITHOUT algebra`() {
        val rimeDir = createTempDir()
        PersonalDictManager.applyMergedDictConfig(rimeDir, "wubi86")
        val dictFile = File(rimeDir, "wubi86_merged.dict.yaml")
        assertTrue(dictFile.exists())
        val dictText = dictFile.readText(Charsets.UTF_8)
        assertTrue(dictText.contains("import_tables:"))
        assertTrue(dictText.contains("- wubi86"))
        assertTrue(dictText.contains("- user_simp_wubi"))
        val customFile = File(rimeDir, "wubi86.custom.yaml")
        assertTrue(customFile.exists())
        val customText = customFile.readText(Charsets.UTF_8)
        assertTrue(customText.contains("translator/dictionary"))
        assertTrue(customText.contains("wubi86_merged"))
    }

    @Test
    fun `applyMergedDictConfig is idempotent`() {
        val rimeDir = createTempDir()
        PersonalDictManager.applyMergedDictConfig(rimeDir, "wubi86")
        PersonalDictManager.applyMergedDictConfig(rimeDir, "wubi86")
        val text = File(rimeDir, "wubi86.custom.yaml").readText(Charsets.UTF_8)
        assertEquals(1, text.split("wubi86_merged").size - 1)
    }

    private fun createTempFile(content: String): File {
        val file = File.createTempFile("personal_dict_test", ".tmp")
        file.writeText(content, Charsets.UTF_8)
        file.deleteOnExit()
        return file
    }

    private fun createTempDir(): File {
        val dir = File.createTempFile("personal_dict_test_dir", "")
        dir.delete()
        dir.mkdirs()
        return dir
    }
}
