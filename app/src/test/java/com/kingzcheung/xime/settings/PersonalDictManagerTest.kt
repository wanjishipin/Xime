package com.kingzcheung.xime.settings

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import java.io.File

private fun createCustomPhraseFile(rimeDir: File, schemaId: String) {
    val dictName = PersonalDictManager.getCustomPhraseDictName(rimeDir, schemaId)
    File(rimeDir, "$dictName.txt").writeText("""# Rime table
# coding: utf-8
#@/db_name	custom_phrase
#@/db_type	tabledb
#
hello	hw
123	abc
""")
}

private fun mockContext(): Context {
    val tmpDir = File.createTempFile("mock_context", "").apply { delete(); mkdirs() }
    return mock {
        on { filesDir } doReturn tmpDir
    }
}

class PersonalDictManagerTest {

    // ── ensureSchemaPack：仅自定义短语 + 清理旧 merged patch ──

    @Test
    fun `ensureSchemaPack creates custom phrase translator when schema has entries`() {
        val context = mockContext()
        val rimeDir = java.io.File(context.filesDir, "rime")
        rimeDir.mkdirs()
        java.io.File(rimeDir, "pinyin_simp.schema.yaml").writeText("""
speller:
  alphabet: abc
  algebra:
    - erase/^xx$/
""".trimIndent())
        createCustomPhraseFile(rimeDir, "pinyin_simp")
        runBlocking { PersonalDictManager.ensureSchemaPack(context, "pinyin_simp") }
        val customFile = java.io.File(rimeDir, "pinyin_simp.custom.yaml")
        assertTrue("custom.yaml should be created", customFile.exists())
        val text = customFile.readText(Charsets.UTF_8)
        assertTrue(text.contains("table_translator@custom_phrase"))
        assertFalse("no merged dictionary patch", text.contains("translator/dictionary"))
    }

    @Test
    fun `ensureSchemaPack does not inject translator when no custom phrase entries`() {
        val context = mockContext()
        val rimeDir = java.io.File(context.filesDir, "rime")
        rimeDir.mkdirs()
        java.io.File(rimeDir, "wubi86.schema.yaml").writeText("""
speller:
  alphabet: abcdefghijklmnopqrstuvwxyz
  max_code_length: 4
""".trimIndent())
        runBlocking { PersonalDictManager.ensureSchemaPack(context, "wubi86") }
        val customFile = java.io.File(rimeDir, "wubi86.custom.yaml")
        if (customFile.exists()) {
            assertFalse("no translator when empty phrase file", customFile.readText(Charsets.UTF_8).contains("table_translator@custom_phrase"))
        }
        assertFalse("no merged dict created", java.io.File(rimeDir, "wubi86_merged.dict.yaml").exists())
    }

    @Test
    fun `ensureSchemaPack is no-op when schema file missing`() {
        val context = mockContext()
        val rimeDir = java.io.File(context.filesDir, "rime")
        rimeDir.mkdirs()
        // no schema file created
        runBlocking { PersonalDictManager.ensureSchemaPack(context, "nonexistent") }
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
        createCustomPhraseFile(rimeDir, "pinyin_simp")
        runBlocking { PersonalDictManager.ensureSchemaPack(context, "pinyin_simp") }
        runBlocking { PersonalDictManager.ensureSchemaPack(context, "pinyin_simp") }
        val text = java.io.File(rimeDir, "pinyin_simp.custom.yaml").readText(Charsets.UTF_8)
        assertEquals(1, text.split("table_translator@custom_phrase").size - 1)
    }

    @Test
    fun `ensureSchemaPack cleans stale merged patch and merged dict`() {
        val context = mockContext()
        val rimeDir = java.io.File(context.filesDir, "rime")
        rimeDir.mkdirs()
        java.io.File(rimeDir, "pinyin_simp.schema.yaml").writeText("""
speller:
  alphabet: abc
  algebra:
    - erase/^xx$/
""".trimIndent())
        // 模拟旧版本遗留：merged dict 转发文件 + custom.yaml 引用 merged 词典
        java.io.File(rimeDir, "pinyin_simp_merged.dict.yaml").writeText(
            "# Rime dict\n---\nname: pinyin_simp_merged\nversion: \"1.0\"\nsort: original\nimport_tables:\n  - pinyin_simp\n...\n",
            Charsets.UTF_8
        )
        java.io.File(rimeDir, "pinyin_simp.custom.yaml").writeText("""patch:
  "translator/dictionary": pinyin_simp_merged
""", Charsets.UTF_8)
        runBlocking { PersonalDictManager.ensureSchemaPack(context, "pinyin_simp") }
        assertFalse("stale merged dict should be removed", java.io.File(rimeDir, "pinyin_simp_merged.dict.yaml").exists())
        val customText = java.io.File(rimeDir, "pinyin_simp.custom.yaml").readText(Charsets.UTF_8)
        assertFalse("merged reference should be removed", customText.contains("pinyin_simp_merged"))
    }

    @Test
    fun `cleanupStaleMergedPatch removes merged dict and reference`() {
        val rimeDir = createTempDir()
        java.io.File(rimeDir, "wubi86_merged.dict.yaml").writeText(
            "# Rime dict\n---\nname: wubi86_merged\nversion: \"1.0\"\nsort: original\nimport_tables:\n  - wubi86\n...\n",
            Charsets.UTF_8
        )
        java.io.File(rimeDir, "wubi86.custom.yaml").writeText("""patch:
  "translator/dictionary": wubi86_merged
  "engine/filters/@before 0": custom_filter
""", Charsets.UTF_8)
        PersonalDictManager.cleanupStaleMergedPatch(rimeDir, "wubi86")
        assertFalse("merged dict should be deleted", java.io.File(rimeDir, "wubi86_merged.dict.yaml").exists())
        val text = java.io.File(rimeDir, "wubi86.custom.yaml").readText(Charsets.UTF_8)
        assertFalse("merged reference should be removed", text.contains("wubi86_merged"))
        assertTrue("other patches preserved", text.contains("custom_filter"))
    }

    @Test
    fun `cleanupStaleMergedPatch no-op when no stale patch`() {
        val rimeDir = createTempDir()
        java.io.File(rimeDir, "wubi86.custom.yaml").writeText("""patch:
  "engine/translators/+":
    - lua_translator@my_script
""", Charsets.UTF_8)
        PersonalDictManager.cleanupStaleMergedPatch(rimeDir, "wubi86")
        val text = java.io.File(rimeDir, "wubi86.custom.yaml").readText(Charsets.UTF_8)
        assertTrue("non-merged patches untouched", text.contains("lua_translator@my_script"))
    }

    // ── 自定义短语 ──

    private val stubHeader = """# Rime table
# coding: utf-8
#@/db_name	custom_phrase
#@/db_type	tabledb
#
"""

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
        val parsed = DictionaryHelper.parseCodeTable(text)
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
        PersonalDictManager.saveCustomPhrases(context, null, entries)
        val file = PersonalDictManager.getCustomPhraseFile(context)
        assertTrue(file.exists())
        val loaded = DictionaryHelper.parseCodeTable(file.readText(Charsets.UTF_8))
        assertTrue(loaded.any { it.word == "kingzcheung@gmail.com" })
    }

    @Test
    fun `applyCustomPhraseTranslator adds translator to custom yaml`() {
        val rimeDir = createTempDir()
        PersonalDictManager.applyCustomPhraseTranslator(rimeDir, "wubi86", "custom_phrase")
        val customFile = File(rimeDir, "wubi86.custom.yaml")
        assertTrue(customFile.exists())
        val text = customFile.readText(Charsets.UTF_8)
        assertTrue(text.contains("table_translator@custom_phrase"))
        assertTrue(text.contains("db_class: stabledb"))
    }

    @Test
    fun `applyCustomPhraseTranslator is idempotent`() {
        val rimeDir = createTempDir()
        PersonalDictManager.applyCustomPhraseTranslator(rimeDir, "wubi86", "custom_phrase")
        PersonalDictManager.applyCustomPhraseTranslator(rimeDir, "wubi86", "custom_phrase")
        val text = File(rimeDir, "wubi86.custom.yaml").readText(Charsets.UTF_8)
        assertEquals(1, text.split("table_translator@custom_phrase").size - 1)
    }

    @Test
    fun `applyCustomPhraseTranslator with custom dictName uses that dictName in config`() {
        val rimeDir = createTempDir()
        PersonalDictManager.applyCustomPhraseTranslator(rimeDir, "wubi86", "custom_phrase_double")
        val text = File(rimeDir, "wubi86.custom.yaml").readText(Charsets.UTF_8)
        assertTrue(text.contains("user_dict: custom_phrase_double"))
    }

    @Test
    fun `applyCustomPhraseTranslator with custom dictName is idempotent`() {
        val rimeDir = createTempDir()
        PersonalDictManager.applyCustomPhraseTranslator(rimeDir, "wubi86", "custom_phrase_double")
        PersonalDictManager.applyCustomPhraseTranslator(rimeDir, "wubi86", "custom_phrase_double")
        val text = File(rimeDir, "wubi86.custom.yaml").readText(Charsets.UTF_8)
        assertEquals(1, text.split("table_translator@custom_phrase").size - 1)
    }

    @Test
    fun `applyCustomPhraseTranslator preserves existing patches`() {
        val dir = createTempDir()
        val file = File(dir, "pinyin_simp.custom.yaml")
        file.writeText("""patch:
  engine/filters/@before/0:
    - lua_filter@custom_filter
  menu/page_size: 8
""", Charsets.UTF_8)
        PersonalDictManager.applyCustomPhraseTranslator(dir, "pinyin_simp", "custom_phrase")
        val text = file.readText(Charsets.UTF_8)
        assertTrue("Lua filter preserved", text.contains("lua_filter@custom_filter"))
        assertTrue("page_size preserved", text.contains("menu/page_size: 8"))
        assertTrue("custom_phrase added", text.contains("table_translator@custom_phrase"))
        assertTrue("custom_phrase config complete", text.contains("db_class: stabledb"))
    }

    @Test
    fun `getCustomPhraseDictName reads user_dict from custom yaml`() {
        val rimeDir = createTempDir()
        File(rimeDir, "wubi86.custom.yaml").writeText("""patch:
  "custom_phrase":
    user_dict: custom_phrase_double
""")
        val result = PersonalDictManager.run { getCustomPhraseDictName(rimeDir, "wubi86") }
        assertEquals("custom_phrase_double", result)
    }

    @Test
    fun `getCustomPhraseDictName returns default when no custom yaml`() {
        val rimeDir = createTempDir()
        val result = PersonalDictManager.run { getCustomPhraseDictName(rimeDir, "wubi86") }
        assertEquals("custom_phrase", result)
    }

    @Test
    fun `getCustomPhraseDictName returns default when no custom_phrase section`() {
        val rimeDir = createTempDir()
        File(rimeDir, "wubi86.custom.yaml").writeText("""patch:
  "translator/packs": ["user_simp_pinyin"]
""")
        val result = PersonalDictManager.run { getCustomPhraseDictName(rimeDir, "wubi86") }
        assertEquals("custom_phrase", result)
    }

    @Test
    fun `saveCustomPhrases with schemaId writes to the correct custom dict file`() {
        val context = mockContext()
        val rimeDir = File(context.filesDir, "rime")
        rimeDir.mkdirs()
        File(rimeDir, "wubi86.custom.yaml").writeText("""patch:
  "custom_phrase":
    user_dict: custom_phrase_double
""")
        val entries = listOf(DictEntry("test", "ce", 1))
        PersonalDictManager.saveCustomPhrases(context, "wubi86", entries)
        val file = File(rimeDir, "custom_phrase_double.txt")
        assertTrue(file.exists())
        val loaded = DictionaryHelper.parseCodeTable(file.readText(Charsets.UTF_8))
        assertTrue(loaded.any { it.word == "test" })
    }

    @Test
    fun `saveCustomPhrases without schemaId writes to default custom_phrase dot txt`() {
        val context = mockContext()
        val entries = listOf(DictEntry("hello", "hw"))
        PersonalDictManager.saveCustomPhrases(context, null, entries)
        val file = PersonalDictManager.getCustomPhraseFile(context)
        assertTrue(file.exists())
        val loaded = DictionaryHelper.parseCodeTable(file.readText(Charsets.UTF_8))
        assertTrue(loaded.any { it.word == "hello" })
    }

    private fun createTempDir(): File {
        val dir = File.createTempFile("personal_dict_test_dir", "")
        dir.delete()
        dir.mkdirs()
        return dir
    }
}
