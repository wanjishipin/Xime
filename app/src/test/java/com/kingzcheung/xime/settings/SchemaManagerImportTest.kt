package com.kingzcheung.xime.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure helpers used by the sha256-verified install path. */
class SchemaManagerImportTest {

    @Test
    fun `sha256Hex of abc`() {
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            SchemaManager.sha256Hex("abc".toByteArray()),
        )
    }

    @Test
    fun `sha256Hex of empty`() {
        assertEquals(
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            SchemaManager.sha256Hex(ByteArray(0)),
        )
    }

    @Test
    fun `protects default_yaml from import`() {
        assertTrue(SchemaManager.isProtectedImportName("default.yaml"))
        assertTrue(SchemaManager.isProtectedImportName("sub/dir/default.yaml"))
    }

    @Test
    fun `allows normal schema, dict and custom files`() {
        assertFalse(SchemaManager.isProtectedImportName("cangjie5.schema.yaml"))
        assertFalse(SchemaManager.isProtectedImportName("cangjie5.dict.yaml"))
        assertFalse(SchemaManager.isProtectedImportName("essay.txt"))
        assertFalse(SchemaManager.isProtectedImportName("wubi86.custom.yaml"))
    }

    @Test
    fun `protects xime yaml and metadata from import`() {
        assertTrue(SchemaManager.isProtectedImportName("xime.yaml"))
        assertFalse(SchemaManager.isProtectedImportName("default.custom.yaml"))
        assertFalse(SchemaManager.isProtectedImportName("xime.custom.yaml"))
        assertTrue(SchemaManager.isProtectedImportName(".registry.json"))
        assertFalse(SchemaManager.isProtectedImportName(".manifests/my_scheme.json"))
    }

    @Test
    fun `protects custom_phrase dot txt from import`() {
        assertTrue(SchemaManager.isProtectedImportName("custom_phrase.txt"))
        assertTrue(SchemaManager.isProtectedImportName("sub/dir/custom_phrase.txt"))
    }

    // ── findSchemaBaseDir：剥 GitHub 归档壳目录(修 essay.txt 落进 rime-essay-master/ 子目录) ──

    @Test
    fun `findSchemaBaseDir strips wrapper for package without schema yaml (rime-essay)`() {
        val entries = listOf(
            "rime-essay-master/essay.txt",
            "rime-essay-master/AUTHORS",
            "rime-essay-master/LICENSE",
        )
        assertEquals("rime-essay-master/", SchemaManager.findSchemaBaseDir(entries))
    }

    @Test
    fun `findSchemaBaseDir strips wrapper for rime-prelude (no schema yaml)`() {
        val entries = listOf(
            "rime-prelude-master/symbols.yaml",
            "rime-prelude-master/default.yaml",
            "rime-prelude-master/README.md",
        )
        assertEquals("rime-prelude-master/", SchemaManager.findSchemaBaseDir(entries))
    }

    @Test
    fun `findSchemaBaseDir no strip when no-schema files already at root`() {
        assertEquals("", SchemaManager.findSchemaBaseDir(listOf("essay.txt", "AUTHORS")))
    }

    @Test
    fun `findSchemaBaseDir no strip when no-schema files mixed root and subdir`() {
        assertEquals("", SchemaManager.findSchemaBaseDir(listOf("essay.txt", "sub/x.txt")))
    }

    @Test
    fun `findSchemaBaseDir uses schema parent dir (regression, wrapped scheme)`() {
        val entries = listOf(
            "rime-cangjie-master/cangjie5.schema.yaml",
            "rime-cangjie-master/cangjie5.dict.yaml",
            "rime-cangjie-master/README.md",
        )
        assertEquals("rime-cangjie-master/", SchemaManager.findSchemaBaseDir(entries))
    }

    @Test
    fun `findSchemaBaseDir empty for flat scheme (regression)`() {
        assertEquals(
            "",
            SchemaManager.findSchemaBaseDir(listOf("cangjie5.schema.yaml", "cangjie5.dict.yaml")),
        )
    }

    // ── shouldTrackImportedFile：单文件直接导入是否受清单追踪（冲突检测 + 可卸载） ──

    @Test
    fun `tracks ordinary schema, dict and data files`() {
        assertTrue(SchemaManager.shouldTrackImportedFile("cangjie5.schema.yaml"))
        assertTrue(SchemaManager.shouldTrackImportedFile("cangjie5.dict.yaml"))
        assertTrue(SchemaManager.shouldTrackImportedFile("essay.txt"))
        assertTrue(SchemaManager.shouldTrackImportedFile("lua/t9_preedit.lua"))
    }

    @Test
    fun `does not track user data files`() {
        assertFalse(SchemaManager.shouldTrackImportedFile("custom_phrase.txt"))
        assertFalse(SchemaManager.shouldTrackImportedFile("wubi86.custom.yaml"))
        assertFalse(SchemaManager.shouldTrackImportedFile("xime.custom.yaml"))
        assertFalse(SchemaManager.shouldTrackImportedFile("installation.yaml"))
    }

    @Test
    fun `does not track system files and manifest metadata`() {
        assertFalse(SchemaManager.shouldTrackImportedFile("default.yaml"))
        assertFalse(SchemaManager.shouldTrackImportedFile("xime.yaml"))
        assertFalse(SchemaManager.shouldTrackImportedFile("build/cangjie5.prism.bin"))
        assertFalse(SchemaManager.shouldTrackImportedFile(".registry.json"))
        assertFalse(SchemaManager.shouldTrackImportedFile(".manifests/pkg.json"))
    }

    @Test
    fun `does not track traversal paths`() {
        assertFalse(SchemaManager.shouldTrackImportedFile("../escape.txt"))
        assertFalse(SchemaManager.shouldTrackImportedFile("sub/../../escape.txt"))
    }

    // ── manifestPackageIdFor：manifests 文件名不能含路径分隔符 ──

    @Test
    fun `manifest package id flattens path separators`() {
        assertEquals("lua_t9_preedit.lua", SchemaManager.manifestPackageIdFor("lua/t9_preedit.lua"))
        assertEquals("essay.txt", SchemaManager.manifestPackageIdFor("essay.txt"))
    }
}
