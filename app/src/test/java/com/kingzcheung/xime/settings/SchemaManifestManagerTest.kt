package com.kingzcheung.xime.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class SchemaManifestManagerTest {

    @get:Rule
    val tempDir = TemporaryFolder()

    @Test
    fun `copyToBuiltinBackup creates nested parent dirs`() {
        val builtinDir = tempDir.newFolder("builtin")
        val src = tempDir.newFile("t9_preedit.lua")
        src.writeText("local t9 = {}")

        SchemaManifestManager.copyToBuiltinBackup(src, builtinDir, "lua/t9_preedit.lua")

        val dest = File(builtinDir, "lua/t9_preedit.lua")
        assertTrue("nested backup file should exist", dest.exists())
        assertEquals("local t9 = {}", dest.readText())
    }

    @Test
    fun `copyToBuiltinBackup flat path does not require parent creation`() {
        val builtinDir = tempDir.newFolder("builtin")
        val src = tempDir.newFile("default.yaml")
        src.writeText("patch:")

        SchemaManifestManager.copyToBuiltinBackup(src, builtinDir, "default.yaml")

        val dest = File(builtinDir, "default.yaml")
        assertTrue(dest.exists())
        assertEquals("patch:", dest.readText())
    }

    @Test
    fun `copyToBuiltinBackup skips missing source`() {
        val builtinDir = tempDir.newFolder("builtin")
        val missing = File(tempDir.root, "does_not_exist.lua")

        SchemaManifestManager.copyToBuiltinBackup(missing, builtinDir, "lua/missing.lua")

        assertTrue("no nested dir should be created for missing source", !File(builtinDir, "lua").exists())
        assertTrue("no destination file should exist", !File(builtinDir, "lua/missing.lua").exists())
    }

    @Test
    fun `copyToBuiltinBackup overwrites existing nested file`() {
        val builtinDir = tempDir.newFolder("builtin")
        val nested = File(builtinDir, "lua/uuid.lua")
        nested.parentFile.mkdirs()
        nested.writeText("old")
        val src = tempDir.newFile("uuid.lua")
        src.writeText("new content")

        SchemaManifestManager.copyToBuiltinBackup(src, builtinDir, "lua/uuid.lua")

        assertEquals("new content", nested.readText())
    }
}
