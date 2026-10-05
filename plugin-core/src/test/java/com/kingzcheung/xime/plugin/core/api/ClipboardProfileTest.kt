package com.kingzcheung.xime.plugin.core.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 剪贴板同步 profile 的值语义与图片构造（Phase 3 / 决策 D12）。
 *
 * 锁定的不变式：
 * - 图片 profile 的 `hash` 与 `dataName` **都由内容决定**（内容寻址 ⇒ 重复推送幂等，
 *   跨设备同一张图 hash 相同，不会来回乒乓）；
 * - `data` 是 `ByteArray`，`equals/hashCode` 必须按内容比较（data class 默认只比较引用，
 *   引用比较会让引擎去重与单测断言都失效）。
 */
class ClipboardProfileTest {

    @Test
    fun `fromText 保持原有语义（hash 与 size 由 utf8 字节算出）`() {
        val p = ClipboardProfile.fromText("中文abc")
        val bytes = "中文abc".toByteArray(Charsets.UTF_8)
        assertEquals("text", p.type)
        assertEquals(ClipboardProfile.sha256Hex(bytes), p.hash)
        assertEquals(bytes.size.toLong(), p.size)
        assertEquals("中文abc", p.text)
        assertFalse(p.hasData)
        assertEquals(null, p.dataName)
        assertEquals(null, p.data)
    }

    @Test
    fun `fromImage 由内容决定 hash 与 dataName`() {
        val bytes = byteArrayOf(1, 2, 3, 4)
        val p = ClipboardProfile.fromImage(bytes, "png")
        assertEquals("image", p.type)
        assertEquals("", p.text)
        assertTrue(p.hasData)
        assertEquals(4L, p.size)
        assertEquals(ClipboardProfile.sha256Hex(bytes), p.hash)
        assertEquals("${p.hash}.png", p.dataName)
        assertTrue("附件字节应是同一份内容", p.data!!.contentEquals(bytes))

        // 同内容 ⇒ 同 profile（幂等推送的前提）
        assertEquals(p, ClipboardProfile.fromImage(byteArrayOf(1, 2, 3, 4), "png"))
        // 不同内容 ⇒ 不同 hash（不会互相覆盖）
        assertNotEquals(p.hash, ClipboardProfile.fromImage(byteArrayOf(1, 2, 3, 5), "png").hash)
        // 扩展名参与 dataName，但不参与 hash
        assertEquals(p.hash, ClipboardProfile.fromImage(bytes, "jpg").hash)
    }

    @Test
    fun `ByteArray 按内容比较而非引用`() {
        val a = ClipboardProfile.fromImage(byteArrayOf(1, 2, 3), "png")
        val b = ClipboardProfile(
            type = "image",
            hash = a.hash,
            text = "",
            hasData = true,
            dataName = a.dataName,
            data = byteArrayOf(1, 2, 3),
            size = 3
        )
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())

        // 内容不同 ⇒ 不等；引用相同 ⇒ 相等
        assertNotEquals(a, b.copy(data = byteArrayOf(9)))
        assertEquals(a, a)
        // 单边为 null ⇒ 不等（不能因为 contentEquals 的空值安全就把"无附件"与"有附件"判等）
        assertNotEquals(a, b.copy(data = null))
        // 非同类对象
        assertFalse(a.equals("not a profile"))
        // toString 不打印字节内容（避免日志里刷出几 MB）
        assertTrue(a.toString().contains("dataBytes=3"))
    }

    @Test
    fun `hash 是小写 hex 的 SHA-256`() {
        val hash = ClipboardProfile.sha256Hex("abc".toByteArray(Charsets.UTF_8))
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", hash)
        assertEquals(64, hash.length)
        assertEquals(hash, hash.lowercase())
    }
}