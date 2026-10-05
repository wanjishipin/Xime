package com.kingzcheung.xime.settings

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FileTreeSerializerTest {

    @Test
    fun fileNodeSerializesToFrontendFields() {
        val node = FileNode(
            name = "files",
            path = "/data/data/x/files",
            isDir = true,
            mtime = 1234L,
            children = listOf(
                FileNode(name = "rime", path = "/data/data/x/files/rime", isDir = true, children = emptyList()),
                FileNode(name = "xime.yaml", path = "/data/data/x/files/xime.yaml", isDir = false, size = 42L),
            ),
        )
        val json = Json.encodeToString(FileNode.serializer(), node)
        assertTrue(json.contains("\"name\":\"files\""))
        assertTrue(json.contains("\"isDir\":true"))
        assertTrue(json.contains("\"children\""))
        assertTrue(json.contains("\"size\":42"))
        assertTrue(json.contains("\"mtime\":1234"))
    }

    @Test
    fun leafFileIsDirFalse() {
        val node = FileNode(name = "a.yaml", path = "a.yaml", isDir = false, size = 10)
        val json = Json.encodeToString(FileNode.serializer(), node)
        assertTrue(json.contains("\"isDir\":false"))
        assertFalse(json.contains("\"children\""))
    }

    @Test
    fun defaultFieldsSerializeAndRoundTrip() {
        val node = FileNode(name = "d", path = "d", isDir = true, children = null)
        val back = Json.decodeFromString(FileNode.serializer(), Json.encodeToString(FileNode.serializer(), node))
        assertEquals("d", back.name)
        assertTrue(back.isDir)
        assertEquals(null, back.children)
    }
}
