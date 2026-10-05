package com.kingzcheung.xime.rime

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * [RimeConfigHelper.isBrokenBuildArtifact] 单测：部署产物半成品检测。
 *
 * librime 的 prism/table 产物文件头（偏移 0）是 Metadata.format 字符串：
 * "Rime::Prism/4.0" / "Rime::Table/4.0"。部署进行中进程被杀会留下空文件或
 * 截断文件——这类产物 mtime 比源新，librime 增量维护会跳过重编，导致该方案
 * 查询永远零命中。
 */
class BuildArtifactIntegrityTest {

    private fun newDir(): File =
        File(System.getProperty("java.io.tmpdir"), "build-artifact-test-${System.nanoTime()}")
            .apply { mkdirs() }

    private fun write(dir: File, name: String, content: ByteArray): File =
        File(dir, name).apply { writeBytes(content) }

    private val prismMagic = "Rime::Prism/".toByteArray(Charsets.US_ASCII)
    private val tableMagic = "Rime::Table/".toByteArray(Charsets.US_ASCII)

    @Test
    fun `合法 prism 头不是损坏`() {
        val dir = newDir()
        val file = write(dir, "t9_pinyin.prism.bin", prismMagic + "4.0".toByteArray() + ByteArray(100))
        assertFalse(RimeConfigHelper.isBrokenBuildArtifact(file))
        dir.deleteRecursively()
    }

    @Test
    fun `合法 table 头不是损坏`() {
        val dir = newDir()
        val file = write(dir, "pinyin_simp.table.bin", tableMagic + "4.0".toByteArray() + ByteArray(100))
        assertFalse(RimeConfigHelper.isBrokenBuildArtifact(file))
        dir.deleteRecursively()
    }

    @Test
    fun `magic 错乱判损坏`() {
        val dir = newDir()
        val file = write(dir, "t9_pinyin.prism.bin", "GARBAGE-GARBAGE".toByteArray() + ByteArray(50))
        assertTrue(RimeConfigHelper.isBrokenBuildArtifact(file))
        dir.deleteRecursively()
    }

    @Test
    fun `空文件判损坏`() {
        val dir = newDir()
        val file = write(dir, "pinyin_simp.table.bin", ByteArray(0))
        assertTrue(RimeConfigHelper.isBrokenBuildArtifact(file))
        dir.deleteRecursively()
    }

    @Test
    fun `截断文件短于magic长度判损坏`() {
        val dir = newDir()
        val file = write(dir, "t9_pinyin.prism.bin", prismMagic.copyOf(5))
        assertTrue(RimeConfigHelper.isBrokenBuildArtifact(file))
        dir.deleteRecursively()
    }

    @Test
    fun `未知产物类型不做magic判定`() {
        val dir = newDir()
        val file = write(dir, "wubi86.reverse.bin", "whatever".toByteArray())
        assertFalse(RimeConfigHelper.isBrokenBuildArtifact(file))
        dir.deleteRecursively()
    }

    @Test
    fun `文件不存在判损坏`() {
        val dir = newDir()
        assertTrue(RimeConfigHelper.isBrokenBuildArtifact(File(dir, "missing.prism.bin")))
        dir.deleteRecursively()
    }
}
