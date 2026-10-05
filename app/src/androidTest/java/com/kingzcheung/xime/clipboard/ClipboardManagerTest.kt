package com.kingzcheung.xime.clipboard

import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import com.kingzcheung.xime.clipboard.db.ClipboardDatabase
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class ClipboardManagerTest {

    private lateinit var context: Context
    private lateinit var clipboardManager: ClipboardManager

    @Before
    fun setup() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        clearClipboardPrefs()
        runBlocking {
            ClipboardDatabase.getInstance(context).clipboardDao().deleteAll()
        }
        clipboardManager = ClipboardManager.getInstance(context)
        awaitCondition({ clipboardManager.clipboardItems.value.isEmpty() })
    }

    @After
    fun tearDown() {
        clearClipboardPrefs()
        runBlocking {
            ClipboardDatabase.getInstance(context).clipboardDao().deleteAll()
        }
    }

    private fun clearClipboardPrefs() {
        context.getSharedPreferences("clipboard_prefs", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
    }

    private fun awaitCondition(condition: () -> Boolean, timeoutMs: Long = 5000) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition()) {
            if (System.currentTimeMillis() > deadline) {
                fail("Timed out waiting for condition")
            }
            Thread.sleep(20)
        }
    }

    @Test
    fun getInstanceReturnsSingleton() {
        val instance1 = ClipboardManager.getInstance(context)
        val instance2 = ClipboardManager.getInstance(context)

        assertEquals(instance1, instance2)
    }

    @Test
    fun clipboardItemsEmptyInitially() {
        assertTrue("Initial clipboard should be empty", clipboardManager.clipboardItems.value.isEmpty())
    }

    @Test
    fun quickSendItemsEmptyInitially() {
        assertTrue("Initial quick send should be empty", clipboardManager.quickSendItems.value.isEmpty())
    }

    @Test
    fun addItemAddsItemToList() {
        clipboardManager.addItem("Test text")

        awaitCondition({ clipboardManager.clipboardItems.value.isNotEmpty() })
        val items = clipboardManager.clipboardItems.value
        assertEquals(1, items.size)
        assertEquals("Test text", items[0].text)
        assertFalse("New item should not be pinned", items[0].isPinned)
    }

    @Test
    fun addItemNotAddBlankText() {
        clipboardManager.addItem("")
        clipboardManager.addItem("   ")

        Thread.sleep(200)
        assertTrue("Blank text should not be added", clipboardManager.clipboardItems.value.isEmpty())
    }

    @Test
    fun addItemUpdatesTimestampForExistingText() {
        clipboardManager.addItem("Test text")
        awaitCondition({ clipboardManager.clipboardItems.value.size == 1 })
        val firstTimestamp = clipboardManager.clipboardItems.value[0].timestamp

        Thread.sleep(10)
        clipboardManager.addItem("Test text")

        awaitCondition({ clipboardManager.clipboardItems.value.size == 1 })
        val items = clipboardManager.clipboardItems.value
        assertEquals("Should still have one item", 1, items.size)
        assertTrue("Timestamp should be updated", items[0].timestamp >= firstTimestamp)
        assertEquals("Item should be at top", 0, items.indexOfFirst { it.text == "Test text" })
    }

    @Test
    fun addItemMovesExistingItemToTop() {
        clipboardManager.addItem("First")
        awaitCondition({ clipboardManager.clipboardItems.value.size == 1 })
        clipboardManager.addItem("Second")
        awaitCondition({ clipboardManager.clipboardItems.value.size == 2 })

        assertEquals(listOf("Second", "First"), clipboardManager.clipboardItems.value.map { it.text })

        clipboardManager.addItem("First")

        awaitCondition({ clipboardManager.clipboardItems.value.first().text == "First" })
        assertEquals("First should now be at top", listOf("First", "Second"), clipboardManager.clipboardItems.value.map { it.text })
    }

    @Test
    fun removeItemRemovesItemById() {
        clipboardManager.addItem("Test text")
        awaitCondition({ clipboardManager.clipboardItems.value.isNotEmpty() })
        val itemId = clipboardManager.clipboardItems.value[0].id

        clipboardManager.removeItem(itemId)

        awaitCondition({ clipboardManager.clipboardItems.value.isEmpty() })
    }

    @Test
    fun removeItemNotAffectOtherItems() {
        clipboardManager.addItem("First")
        clipboardManager.addItem("Second")
        awaitCondition({ clipboardManager.clipboardItems.value.size == 2 })
        val firstId = clipboardManager.clipboardItems.value.find { it.text == "First" }!!.id

        clipboardManager.removeItem(firstId)

        awaitCondition({ clipboardManager.clipboardItems.value.size == 1 })
        assertEquals("Second", clipboardManager.clipboardItems.value[0].text)
    }

    @Test
    fun splitItemSplitsTextIntoCharacters() {
        clipboardManager.addItem("你好")
        awaitCondition({ clipboardManager.clipboardItems.value.isNotEmpty() })
        val itemId = clipboardManager.clipboardItems.value[0].id

        clipboardManager.splitItem(itemId)

        awaitCondition({ clipboardManager.clipboardItems.value.size == 2 })
        val items = clipboardManager.clipboardItems.value
        assertEquals("你", items[0].text)
        assertEquals("好", items[1].text)
    }

    @Test
    fun splitItemHandlesSingleCharacter() {
        clipboardManager.addItem("A")
        awaitCondition({ clipboardManager.clipboardItems.value.isNotEmpty() })
        val itemId = clipboardManager.clipboardItems.value[0].id

        clipboardManager.splitItem(itemId)

        awaitCondition({ clipboardManager.clipboardItems.value.size == 1 })
        assertEquals("A", clipboardManager.clipboardItems.value[0].text)
    }

    @Test
    fun clearAllClearsAllItems() {
        clipboardManager.addItem("Item1")
        awaitCondition({ clipboardManager.clipboardItems.value.size == 1 })
        clipboardManager.addItem("Item2")
        awaitCondition({ clipboardManager.clipboardItems.value.size == 2 })

        clipboardManager.clearAll()

        awaitCondition({ clipboardManager.clipboardItems.value.isEmpty() })
    }

    @Test
    fun clearAllRemovesUnpinnedItems() {
        clipboardManager.addItem("First")
        awaitCondition({ clipboardManager.clipboardItems.value.size == 1 })
        clipboardManager.addItem("Second")
        awaitCondition({ clipboardManager.clipboardItems.value.size == 2 })
        clipboardManager.addItem("Third")
        awaitCondition({ clipboardManager.clipboardItems.value.size == 3 })

        clipboardManager.clearAll()

        awaitCondition({ clipboardManager.clipboardItems.value.isEmpty() })
    }

    @Test
    fun addToQuickSendAddsItemToQuickSendList() {
        clipboardManager.addItem("Quick text")
        awaitCondition({ clipboardManager.clipboardItems.value.isNotEmpty() })
        val itemId = clipboardManager.clipboardItems.value[0].id

        clipboardManager.addToQuickSend(itemId)

        awaitCondition({ clipboardManager.quickSendItems.value.isNotEmpty() })
        val quickSendItems = clipboardManager.quickSendItems.value
        assertEquals(1, quickSendItems.size)
        assertEquals("Quick text", quickSendItems[0].text)
        assertTrue("Quick send item should be marked", quickSendItems[0].isQuickSend)
        assertTrue("Quick send item should be pinned", quickSendItems[0].isPinned)
    }

    @Test
    fun removeFromQuickSendRemovesItem() {
        clipboardManager.addItem("Quick text")
        awaitCondition({ clipboardManager.clipboardItems.value.isNotEmpty() })
        val itemId = clipboardManager.clipboardItems.value[0].id
        clipboardManager.addToQuickSend(itemId)
        awaitCondition({ clipboardManager.quickSendItems.value.isNotEmpty() })

        clipboardManager.removeFromQuickSend(itemId)

        awaitCondition({ clipboardManager.quickSendItems.value.isEmpty() })
    }

    @Test
    fun removeItemsRemovesMultipleClipboardItems() {
        clipboardManager.addItem("First")
        awaitCondition({ clipboardManager.clipboardItems.value.size == 1 })
        clipboardManager.addItem("Second")
        awaitCondition({ clipboardManager.clipboardItems.value.size == 2 })
        clipboardManager.addItem("Third")
        awaitCondition({ clipboardManager.clipboardItems.value.size == 3 })
        val ids = clipboardManager.clipboardItems.value.map { it.id }

        clipboardManager.removeItems(ids)

        awaitCondition({ clipboardManager.clipboardItems.value.isEmpty() })
    }

    @Test
    fun removeItemsKeepsQuickSendIntact() {
        clipboardManager.addItem("Shared text")
        awaitCondition({ clipboardManager.clipboardItems.value.isNotEmpty() })
        val itemId = clipboardManager.clipboardItems.value[0].id
        clipboardManager.addToQuickSend(itemId)
        awaitCondition({ clipboardManager.quickSendItems.value.isNotEmpty() })
        clipboardManager.addItem("Clipboard only")
        awaitCondition({ clipboardManager.clipboardItems.value.size == 2 })
        val clipboardIds = clipboardManager.clipboardItems.value.map { it.id }

        clipboardManager.removeItems(clipboardIds)

        awaitCondition({ clipboardManager.clipboardItems.value.isEmpty() })
        assertEquals(
            "Quick send items must survive clipboard batch delete",
            1,
            clipboardManager.quickSendItems.value.size
        )
        assertEquals("Shared text", clipboardManager.quickSendItems.value[0].text)
    }

    @Test
    fun clearClipboardClearsOnlyClipboardItems() {
        clipboardManager.addItem("Clipboard A")
        awaitCondition({ clipboardManager.clipboardItems.value.isNotEmpty() })
        clipboardManager.addItem("Clipboard B")
        awaitCondition({ clipboardManager.clipboardItems.value.size == 2 })
        clipboardManager.addQuickSendItem("Quick A")
        awaitCondition({ clipboardManager.quickSendItems.value.isNotEmpty() })

        clipboardManager.clearClipboard()

        awaitCondition({ clipboardManager.clipboardItems.value.isEmpty() })
        assertEquals(
            "Quick send items must survive clipboard clear",
            1,
            clipboardManager.quickSendItems.value.size
        )
        assertEquals("Quick A", clipboardManager.quickSendItems.value[0].text)
    }

    @Test
    fun addQuickSendItemAddsTextDirectly() {
        clipboardManager.addQuickSendItem("Direct quick")

        awaitCondition({ clipboardManager.quickSendItems.value.isNotEmpty() })
        val quickSendItems = clipboardManager.quickSendItems.value
        assertEquals(1, quickSendItems.size)
        assertEquals("Direct quick", quickSendItems[0].text)
        assertTrue("Item should be marked as quick send", quickSendItems[0].isQuickSend)
    }

    @Test
    fun addQuickSendItemNotAddBlankText() {
        clipboardManager.addQuickSendItem("")
        clipboardManager.addQuickSendItem("   ")

        Thread.sleep(200)
        assertTrue("Blank text should not be added to quick send", clipboardManager.quickSendItems.value.isEmpty())
    }

    @Test
    fun getCurrentClipboardTextReturnsNullWhenEmpty() {
        val text = clipboardManager.getCurrentClipboardText()
        assertNull("Should return null when clipboard is empty", text)
    }

    @Test
    fun copyToSystemClipboardSetsClipboardText() {
        clipboardManager.copyToSystemClipboard("Copied text")

        val text = clipboardManager.getCurrentClipboardText()
        assertEquals("Copied text", text)
    }

    @Test
    fun clipboardItemHasCorrectDefaultValues() {
        val item = ClipboardItem(text = "Test")

        assertNotNull(item.id)
        assertEquals("Test", item.text)
        assertFalse(item.isPinned)
        assertFalse(item.isQuickSend)
        assertTrue(item.timestamp > 0)
    }

    @Test
    fun clipboardItemCopyPreservesValues() {
        val original = ClipboardItem(
            id = 123L,
            text = "Original",
            timestamp = 1000L,
            isPinned = true,
            isQuickSend = true
        )

        val copied = original.copy(isPinned = false)

        assertEquals(123L, copied.id)
        assertEquals("Original", copied.text)
        assertEquals(1000L, copied.timestamp)
        assertFalse(copied.isPinned)
        assertTrue(copied.isQuickSend)
    }

    @Test
    fun migrateLegacyPrefsPreservesData() {
        val items = listOf(
            ClipboardItem(id = 1L, text = "Test:::with|||special", timestamp = 1000L, isPinned = true, isQuickSend = false),
            ClipboardItem(id = 2L, text = "Normal text", timestamp = 2000L, isPinned = false, isQuickSend = true)
        )

        val prefs = context.getSharedPreferences("clipboard_prefs", Context.MODE_PRIVATE)
        prefs.edit().putString("clipboard_items",
            items.joinToString("|||") { item ->
                "${item.id}:::${item.text.replace("|||", "〈PIPE〉").replace(":::", "〈COLON〉")}:::${item.timestamp}:::${item.isPinned}:::${item.isQuickSend}"
            }
        ).commit()

        clipboardManager.migrateLegacyData()

        awaitCondition({ clipboardManager.clipboardItems.value.size == 2 })
        val loaded = clipboardManager.clipboardItems.value
        assertEquals("Test:::with|||special", loaded[0].text)
        assertEquals("Normal text", loaded[1].text)
    }

    @Test
    fun maxItemsLimitEnforced() {
        for (i in 1..1010) {
            clipboardManager.addItem("Item $i")
        }

        awaitCondition({ clipboardManager.clipboardItems.value.size in 990..1000 })
        assertTrue("Should cap at 1000 items", clipboardManager.clipboardItems.value.size in 990..1000)
    }

    // ---- 同步导入（Phase 3 / D12）：远端图片落盘 + 入库 ----

    /** 真实 PNG/JPEG 字节（Bitmap 编码生成，保证魔数与尺寸都可解）。 */
    private fun encodedImage(format: android.graphics.Bitmap.CompressFormat, width: Int = 4, height: Int = 3): ByteArray {
        val bitmap = android.graphics.Bitmap.createBitmap(width, height, android.graphics.Bitmap.Config.ARGB_8888)
        val out = java.io.ByteArrayOutputStream()
        assertTrue("图片编码应成功", bitmap.compress(format, 100, out))
        bitmap.recycle()
        return out.toByteArray()
    }

    @Test
    fun importImageFromSyncSavesContentAddressedFile() {
        val bytes = encodedImage(android.graphics.Bitmap.CompressFormat.PNG)
        val hash = com.kingzcheung.xime.plugin.core.api.ClipboardProfile.sha256Hex(bytes)

        val path = runBlocking { clipboardManager.importImageFromSync(bytes, "$hash.png") }

        assertNotNull("导入应返回绝对路径", path)
        val file = java.io.File(path!!)
        assertTrue("图片文件应落盘", file.exists())
        assertEquals("文件名应为内容寻址", "$hash.png", file.name)

        awaitCondition({ clipboardManager.clipboardItems.value.any { it.isImage } })
        val item = clipboardManager.clipboardItems.value.first { it.isImage }
        assertEquals("image/png", item.mimeType)
        assertEquals(bytes.size.toLong(), item.sizeBytes)
        assertEquals(4, item.width)
        assertEquals(3, item.height)
        assertEquals(path, clipboardManager.imageFileOf(item)?.absolutePath)
    }

    @Test
    fun importImageFromSyncDedupesSameContent() {
        val bytes = encodedImage(android.graphics.Bitmap.CompressFormat.PNG)

        val first = runBlocking { clipboardManager.importImageFromSync(bytes, null) }
        awaitCondition({ clipboardManager.clipboardItems.value.any { it.isImage } })
        val second = runBlocking { clipboardManager.importImageFromSync(bytes, null) }

        assertEquals("同内容应复用同一文件", first, second)
        awaitCondition({ clipboardManager.clipboardItems.value.count { it.isImage } == 1 })
        assertEquals(1, clipboardManager.clipboardItems.value.count { it.isImage })
    }

    @Test
    fun importImageFromSyncTrustsMagicBytesOverDataName() {
        // dataName 谎报 png，字节其实是 JPEG：类型判定必须以字节为准
        val bytes = encodedImage(android.graphics.Bitmap.CompressFormat.JPEG)

        val path = runBlocking { clipboardManager.importImageFromSync(bytes, "evil.png") }

        assertNotNull(path)
        assertTrue("应按魔数落成 .jpg", path!!.endsWith(".jpg"))
        awaitCondition({ clipboardManager.clipboardItems.value.any { it.isImage } })
        assertEquals("image/jpeg", clipboardManager.clipboardItems.value.first { it.isImage }.mimeType)
    }

    @Test
    fun importImageFromSyncRejectsUnsupportedBytes() {
        val path = runBlocking {
            clipboardManager.importImageFromSync("not an image at all".toByteArray(), "fake.png")
        }

        assertNull("非图片字节不应入库", path)
        assertTrue(clipboardManager.clipboardItems.value.none { it.isImage })
    }
}
