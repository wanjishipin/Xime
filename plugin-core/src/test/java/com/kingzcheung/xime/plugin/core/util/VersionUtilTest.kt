package com.kingzcheung.xime.plugin.core.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VersionUtilTest {

    @Test
    fun `compare should order versions correctly`() {
        assertTrue(VersionUtil.compare("2.5.0", "2.6.0") < 0)
        assertTrue(VersionUtil.compare("2.6.0", "2.5.0") > 0)
        assertEquals(0, VersionUtil.compare("2.6.0", "2.6.0"))
    }

    @Test
    fun `compare should ignore prerelease suffix`() {
        assertEquals(0, VersionUtil.compare("2.6.0-beta3", "2.6.0"))
        assertTrue(VersionUtil.compare("2.6.0", "2.7.0-alpha") < 0)
    }

    @Test
    fun `compare should handle fewer segments`() {
        assertTrue(VersionUtil.compare("2", "2.6") < 0)
        assertTrue(VersionUtil.compare("2.6.1", "2.6") > 0)
    }

    @Test
    fun `no range should always be supported`() {
        assertTrue(VersionUtil.isHostSupported("2.6.0", null, null))
        assertTrue(VersionUtil.isHostSupported("2.6.0", "", ""))
    }

    @Test
    fun `min range should reject older host`() {
        assertFalse(VersionUtil.isHostSupported("2.5.0", "2.6.0", null))
        assertTrue(VersionUtil.isHostSupported("2.6.0", "2.6.0", null))
        assertTrue(VersionUtil.isHostSupported("2.7.0", "2.6.0", null))
    }

    @Test
    fun `max range should reject newer host`() {
        assertFalse(VersionUtil.isHostSupported("3.0.0", null, "2.9.0"))
        assertTrue(VersionUtil.isHostSupported("2.9.0", null, "2.9.0"))
        assertTrue(VersionUtil.isHostSupported("2.8.0", null, "2.9.0"))
    }

    @Test
    fun `both bounds should reject outside and accept inside`() {
        assertFalse(VersionUtil.isHostSupported("2.5.0", "2.6.0", "2.9.0"))
        assertTrue(VersionUtil.isHostSupported("2.7.0", "2.6.0", "2.9.0"))
        assertFalse(VersionUtil.isHostSupported("3.0.0", "2.6.0", "2.9.0"))
    }
}
