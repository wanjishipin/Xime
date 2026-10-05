package com.kingzcheung.xime.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class BackupManagerTest {

    @get:Rule
    val tempDir = TemporaryFolder()

    /** 恢复期间收到的 prefs 恢复调用（prefsName -> json 文本）。 */
    private val restoredPrefs = mutableMapOf<String, String>()

    private fun zipOf(entries: Map<String, ByteArray>): ByteArray {
        val bos = java.io.ByteArrayOutputStream()
        ZipOutputStream(bos).use { zos ->
            entries.forEach { (name, content) ->
                zos.putNextEntry(ZipEntry(name))
                zos.write(content)
                zos.closeEntry()
            }
        }
        return bos.toByteArray()
    }

    private fun zipOfText(entries: Map<String, String>): ByteArray =
        zipOf(entries.mapValues { it.value.toByteArray() })

    private fun restore(filesDir: File, bytes: ByteArray) =
        BackupManager.restoreArchive(filesDir, { prefsName, json ->
            restoredPrefs[prefsName] = json.toString(Charsets.UTF_8)
        }, bytes)

    // ---- rime 条目（包根，兼容旧格式） ----

    @Test
    fun `restore unpacks rime entries into rime dir`() {
        val filesDir = tempDir.newFolder("files")
        val bytes = zipOfText(
            mapOf(
                "default.yaml" to "config:",
                "opencc/ts.txt" to "opencc data"
            )
        )

        val result = restore(filesDir, bytes)

        assertTrue(result.isSuccess)
        assertEquals("config:", File(filesDir, "rime/default.yaml").readText())
        assertEquals("opencc data", File(filesDir, "rime/opencc/ts.txt").readText())
    }

    @Test
    fun `restore overwrites existing rime files`() {
        val filesDir = tempDir.newFolder("files")
        File(filesDir, "rime").mkdirs()
        File(filesDir, "rime/default.yaml").writeText("old:")
        val bytes = zipOfText(mapOf("default.yaml" to "new:"))

        val result = restore(filesDir, bytes)

        assertTrue(result.isSuccess)
        assertEquals("new:", File(filesDir, "rime/default.yaml").readText())
    }

    @Test
    fun `restore rejects path traversal entries`() {
        val filesDir = tempDir.newFolder("files")
        val bytes = zipOfText(mapOf("../evil.txt" to "boom"))

        val result = restore(filesDir, bytes)

        assertTrue(result.isFailure)
        assertTrue(!File(filesDir.parentFile, "evil.txt").exists())
    }

    @Test
    fun `restore rejects absolute path entries`() {
        val filesDir = tempDir.newFolder("files")
        val bytes = zipOfText(mapOf("/etc/evil.txt" to "boom"))

        val result = restore(filesDir, bytes)

        assertTrue(result.isFailure)
    }

    // ---- META 元数据条目 ----

    @Test
    fun `restore routes settings json to prefs restorer`() {
        val filesDir = tempDir.newFolder("files")
        val bytes = zipOfText(
            mapOf("${BackupManager.META_PREFIX}settings.json" to """{"ascii_mode":{"t":"b","v":true}}""")
        )

        val result = restore(filesDir, bytes)

        assertTrue(result.isSuccess)
        assertEquals("kime_settings", restoredPrefs.keys.single())
        assertTrue(restoredPrefs["kime_settings"]!!.contains("ascii_mode"))
    }

    @Test
    fun `restore routes plugin config json to prefs restorer`() {
        val filesDir = tempDir.newFolder("files")
        val bytes = zipOfText(
            mapOf(
                "${BackupManager.META_PREFIX}plugin_configs/com.example.plugin.json" to """{"url":{"t":"s","v":"secret"}}"""
            )
        )

        val result = restore(filesDir, bytes)

        assertTrue(result.isSuccess)
        assertEquals("plugin_cfg_com.example.plugin", restoredPrefs.keys.single())
    }

    @Test
    fun `restore rejects plugin config id with path separators`() {
        val filesDir = tempDir.newFolder("files")
        val bytes = zipOfText(
            mapOf("${BackupManager.META_PREFIX}plugin_configs/../evil.json" to "{}")
        )

        val result = restore(filesDir, bytes)

        // id 含路径分隔符：不写 prefs、不落盘（条目被忽略），恢复本身不失败
        assertTrue(result.isSuccess)
        assertTrue(restoredPrefs.isEmpty())
        assertTrue(!File(filesDir, "evil.json").exists())
    }

    @Test
    fun `restore writes plugins xml and plugin packages`() {
        val filesDir = tempDir.newFolder("files")
        val bytes = zipOfText(
            mapOf(
                "${BackupManager.META_PREFIX}plugins.xml" to "<plugins/>",
                "${BackupManager.META_PREFIX}plugins/com.example.plugin/main.lua" to "return {}",
                "${BackupManager.META_PREFIX}unknown.json" to "{}"
            )
        )

        val result = restore(filesDir, bytes)

        assertTrue(result.isSuccess)
        assertEquals("<plugins/>", File(filesDir, "plugins.xml").readText())
        assertEquals("return {}", File(filesDir, "plugins/com.example.plugin/main.lua").readText())
        // 未知元数据条目被跳过（向前兼容），且不落入 rime 目录
        assertTrue(!File(filesDir, "rime/unknown.json").exists())
    }

    @Test
    fun `restore rejects plugin package traversal`() {
        val filesDir = tempDir.newFolder("files")
        val bytes = zipOfText(
            mapOf("${BackupManager.META_PREFIX}plugins/../evil.lua" to "boom")
        )

        val result = restore(filesDir, bytes)

        assertTrue(result.isFailure)
        assertTrue(!File(filesDir, "evil.lua").exists())
    }

    @Test
    fun `restore fails on empty archive`() {
        val filesDir = tempDir.newFolder("files")
        val bytes = zipOfText(emptyMap())

        val result = restore(filesDir, bytes)

        assertTrue(result.isFailure)
    }
}
