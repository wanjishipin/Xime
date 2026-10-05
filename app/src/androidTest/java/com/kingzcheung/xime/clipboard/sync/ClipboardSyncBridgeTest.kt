package com.kingzcheung.xime.clipboard.sync

import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import com.kingzcheung.xime.clipboard.ClipboardManager
import com.kingzcheung.xime.clipboard.db.ClipboardDatabase
import com.kingzcheung.xime.plugin.core.api.ClipboardProfile
import com.kingzcheung.xime.plugin.core.api.ClipboardSyncPlugin
import com.kingzcheung.xime.plugin.core.model.PluginContext
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * 剪贴板同步引擎的图片路径（Phase 3 / 决策 D12）。
 *
 * 用假插件驱动引擎，覆盖三件用纯 JVM 测不到的事：
 * 1. 远端图片 profile → 落盘 + 入库（出现在剪贴板面板里，与本地采集同一条路径）；
 * 2. 字节 hash 与声明不符的远端附件**必须丢弃**（远端内容不可信）；
 * 3. 内容 hash 去重：刚拉下来的图片不会再被推回去（否则两台设备会来回乒乓）；
 *    以及未声明 `attachments` 的插件自动降级为仅文本同步。
 */
class ClipboardSyncBridgeTest {

    private lateinit var context: Context
    private lateinit var clipboardManager: ClipboardManager

    @Before
    fun setup() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        context.getSharedPreferences("clipboard_prefs", Context.MODE_PRIVATE).edit().clear().commit()
        runBlocking { ClipboardDatabase.getInstance(context).clipboardDao().deleteAll() }
        clipboardManager = ClipboardManager.getInstance(context)
        awaitCondition { clipboardManager.clipboardItems.value.isEmpty() }
    }

    @After
    fun tearDown() {
        context.getSharedPreferences("clipboard_prefs", Context.MODE_PRIVATE).edit().clear().commit()
        runBlocking { ClipboardDatabase.getInstance(context).clipboardDao().deleteAll() }
    }

    private fun awaitCondition(timeoutMs: Long = 5000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition()) {
            if (System.currentTimeMillis() > deadline) fail("Timed out waiting for condition")
            Thread.sleep(20)
        }
    }

    /** 假同步插件：记录推送，按队列吐出拉取结果。 */
    private class FakeSyncPlugin : ClipboardSyncPlugin {
        val pushed = ConcurrentLinkedQueue<ClipboardProfile>()
        val pulls = ConcurrentLinkedQueue<ClipboardProfile>()

        override fun onLoad(context: PluginContext) {}
        override fun onUnload() {}

        override suspend fun push(profile: ClipboardProfile): Boolean {
            pushed.add(profile)
            return true
        }

        override suspend fun pull(): ClipboardProfile? = pulls.poll()

        override suspend fun testConnection(): String? = null
    }

    private fun pngBytes(width: Int = 4, height: Int = 3): ByteArray {
        val bitmap = android.graphics.Bitmap.createBitmap(width, height, android.graphics.Bitmap.Config.ARGB_8888)
        val out = java.io.ByteArrayOutputStream()
        assertTrue(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out))
        bitmap.recycle()
        return out.toByteArray()
    }

    @Test
    fun remoteImageIsImportedAndNotPushedBack() {
        val plugin = FakeSyncPlugin()
        val bytes = pngBytes()
        plugin.pulls.add(ClipboardProfile.fromImage(bytes, "png", source = "device-a"))
        val bridge = ClipboardSyncBridge(
            clipboardManager, plugin, pluginId = "fake", supportsAttachments = true
        )
        try {
            bridge.start() // 启动即拉取一次（不受节流限制）

            awaitCondition { clipboardManager.clipboardItems.value.any { it.isImage } }
            val item = clipboardManager.clipboardItems.value.first { it.isImage }
            assertEquals("image/png", item.mimeType)
            assertEquals(bytes.size.toLong(), item.sizeBytes)
            assertEquals(ClipboardProfile.sha256Hex(bytes), item.imageHash)
            val file = clipboardManager.imageFileOf(item)
            assertNotNull("图片文件应已落盘", file)
            assertTrue(file!!.exists())
            assertEquals("${ClipboardProfile.sha256Hex(bytes)}.png", file.name)

            // 内容 hash 去重：刚拉下来的内容不能再推回去（避免双设备乒乓）
            Thread.sleep(300)
            assertTrue(
                "远端拉到的图片不应被回推",
                plugin.pushed.none { it.hasData || it.type == "image" }
            )
        } finally {
            bridge.release()
        }
    }

    @Test
    fun remoteAttachmentWithMismatchedHashIsDiscarded() {
        val plugin = FakeSyncPlugin()
        val bytes = pngBytes()
        plugin.pulls.add(ClipboardProfile.fromImage(bytes, "png").copy(hash = "ab".repeat(32)))
        val bridge = ClipboardSyncBridge(
            clipboardManager, plugin, pluginId = "fake", supportsAttachments = true
        )
        try {
            bridge.start()
            Thread.sleep(500)
            assertTrue(
                "hash 与字节不符的远端附件必须丢弃",
                clipboardManager.clipboardItems.value.none { it.isImage }
            )
        } finally {
            bridge.release()
        }
    }

    @Test
    fun pluginWithoutAttachmentsSkipsRemoteImageAndStillSyncsText() {
        val plugin = FakeSyncPlugin()
        plugin.pulls.add(ClipboardProfile.fromImage(pngBytes(), "png"))
        val bridge = ClipboardSyncBridge(
            clipboardManager, plugin, pluginId = "legacy", supportsAttachments = false
        )
        try {
            bridge.start()
            Thread.sleep(500)
            assertTrue(
                "未声明 attachments 的插件不应导入远端图片",
                clipboardManager.clipboardItems.value.none { it.isImage }
            )

            // 文本路径不受影响（回归保护）
            clipboardManager.addItem("legacy text sync")
            awaitCondition { plugin.pushed.any { it.text == "legacy text sync" } }
        } finally {
            bridge.release()
        }
    }

    @Test
    fun localImageIsPushedWhenPluginSupportsAttachments() {
        val plugin = FakeSyncPlugin()
        val bridge = ClipboardSyncBridge(
            clipboardManager, plugin, pluginId = "fake", supportsAttachments = true
        )
        try {
            bridge.start()
            // 走"远端导入"造出一张本地图片（等价于本地采集入库 + clipboardChanged 事件）
            val bytes = pngBytes()
            runBlocking { clipboardManager.importImageFromSync(bytes, null) }
            // 该事件的 hash 尚未被引擎登记（不是引擎自己写回的内容）→ 应推送
            awaitCondition { plugin.pushed.any { it.hasData && it.type == "image" } }
            val pushed = plugin.pushed.first { it.type == "image" }
            assertEquals(ClipboardProfile.sha256Hex(bytes), pushed.hash)
            assertEquals("${ClipboardProfile.sha256Hex(bytes)}.png", pushed.dataName)
            assertTrue("载荷应是原图字节", pushed.data!!.contentEquals(bytes))
        } finally {
            bridge.release()
        }
    }
}