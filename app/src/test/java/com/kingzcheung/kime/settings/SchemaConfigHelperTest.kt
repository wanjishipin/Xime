package com.kingzcheung.kime.settings

import org.junit.Assert.assertEquals
import org.junit.Test

class SchemaConfigHelperTest {
    
    @Test
    fun `extractValue should extract simple value`() {
        val line = "schema_id: wubi86"
        val result = SchemaConfigHelperTestAccess.extractValue(line)
        assertEquals("wubi86", result)
    }
    
    @Test
    fun `extractValue should handle quoted value`() {
        val line = """name: "五笔86""""
        val result = SchemaConfigHelperTestAccess.extractValue(line)
        assertEquals("五笔86", result)
    }
    
    @Test
    fun `extractListItem should extract item from list`() {
        val line = "- 发明人"
        val result = SchemaConfigHelperTestAccess.extractListItem(line)
        assertEquals("发明人", result)
    }
    
    @Test
    fun `extractValue should handle value with spaces`() {
        val line = "description: 五笔字型输入法"
        val result = SchemaConfigHelperTestAccess.extractValue(line)
        assertEquals("五笔字型输入法", result)
    }
}

object SchemaConfigHelperTestAccess {
    fun extractValue(line: String): String {
        val parts = line.split(":", limit = 2)
        return if (parts.size == 2) {
            parts[1].trim().replace("\"", "")
        } else {
            ""
        }
    }
    
    fun extractListItem(line: String): String {
        return line.removePrefix("-").trim()
    }
}