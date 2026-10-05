package com.kingzcheung.xime.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class SyncManagerTest {

    @get:Rule
    val tempDir = TemporaryFolder()

    private fun zipOf(entries: Map<String, String>): ByteArray {
        val bos = ByteArrayOutputStream()
        ZipOutputStream(bos).use { zos ->
            entries.forEach { (name, content) ->
                zos.putNextEntry(ZipEntry(name))
                zos.write(content.toByteArray())
                zos.closeEntry()
            }
        }
        return bos.toByteArray()
    }

    private val snapshotContent = "Rime user dictionary export\naaa\t1\t2024-01-01"

    // ---- ensureInstallationFile：三态 ----

    @Test
    fun `ensure creates yaml when missing`() {
        val yaml = File(tempDir.root, "rime/installation.yaml")
        val id = SyncManager.ensureInstallationFile(yaml, "id-1")
        assertEquals("id-1", id)
        assertTrue(yaml.exists())
        assertTrue(yaml.readText().contains("installation_id: \"id-1\""))
    }

    @Test
    fun `ensure keeps existing file when id matches`() {
        val yaml = File(tempDir.root, "rime/installation.yaml")
        yaml.parentFile.mkdirs()
        yaml.writeText("installation_id: \"id-1\"\nrime_version: 1.17.0\n")
        SyncManager.ensureInstallationFile(yaml, "id-1")
        assertTrue(yaml.readText().contains("rime_version: 1.17.0"))
    }

    @Test
    fun `ensure rewrites when id differs`() {
        val yaml = File(tempDir.root, "rime/installation.yaml")
        yaml.parentFile.mkdirs()
        yaml.writeText("installation_id: \"old-id\"\nrime_version: 1.17.0\n")
        SyncManager.ensureInstallationFile(yaml, "new-id")
        val text = yaml.readText()
        assertTrue(text.contains("installation_id: \"new-id\""))
        assertFalse(text.contains("old-id"))
    }

    // ---- packSyncDir / unpackArchive 往返 ----

    @Test
    fun `pack returns null when sync dir missing or empty`() {
        val rimeDir = tempDir.newFolder("rime")
        assertNull(SyncManager.packSyncDir(rimeDir))
        File(rimeDir, "sync").mkdirs()
        assertNull(SyncManager.packSyncDir(rimeDir))
    }

    @Test
    fun `pack and unpack round trip preserves snapshot structure`() {
        val rimeDir = tempDir.newFolder("rime")
        val snapshot = File(rimeDir, "sync/id-A/luna_pinyin.userdb.txt")
        snapshot.parentFile.mkdirs()
        snapshot.writeText(snapshotContent)

        val bytes = SyncManager.packSyncDir(rimeDir)!!
        assertTrue(SyncManager.unpackArchive(File(tempDir.root, "out"), bytes) == 1)
        val restored = File(tempDir.root, "out/id-A/luna_pinyin.userdb.txt")
        assertEquals(snapshotContent, restored.readText())
    }

    @Test
    fun `unpack strips leading sync prefix`() {
        val base = tempDir.newFolder("base")
        val bytes = zipOf(mapOf("sync/id-A/luna_pinyin.userdb.txt" to snapshotContent))
        assertEquals(1, SyncManager.unpackArchive(base, bytes))
        assertTrue(File(base, "id-A/luna_pinyin.userdb.txt").exists())
    }

    // ---- 解包安全 ----

    @Test
    fun `unpack rejects path traversal entries`() {
        val base = tempDir.newFolder("base")
        val bytes = zipOf(mapOf("sync/../../evil.txt" to "boom"))
        assertThrows(SecurityException::class.java) { SyncManager.unpackArchive(base, bytes) }
        assertFalse(File(tempDir.root, "evil.txt").exists())
        assertFalse(File(tempDir.root, "base/evil.txt").exists())
    }

    @Test
    fun `unpack skips empty and directory entries`() {
        val base = tempDir.newFolder("base")
        val bos = ByteArrayOutputStream()
        ZipOutputStream(bos).use { zos ->
            zos.putNextEntry(ZipEntry("sync/"))
            zos.closeEntry()
            zos.putNextEntry(ZipEntry("sync/id-A/"))
            zos.closeEntry()
        }
        assertEquals(0, SyncManager.unpackArchive(base, bos.toByteArray()))
    }

    // ---- 远端条目前缀过滤 ----

    @Test
    fun `remote sync prefix filter distinguishes snapshots from config backups`() {
        assertTrue(SyncManager.isRemoteSyncEntry("rime-sync-abc-123.zip"))
        assertFalse(SyncManager.isRemoteSyncEntry("rime-snapshot.zip"))
        assertFalse(SyncManager.isRemoteSyncEntry("Xime配置-2026-09-25.zip"))
    }
}
