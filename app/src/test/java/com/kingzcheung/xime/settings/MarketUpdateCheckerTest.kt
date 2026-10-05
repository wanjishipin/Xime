package com.kingzcheung.xime.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * MarketUpdateChecker 的纯逻辑单测：节流判定 + 三类市场 item 的 hasUpdate 计数口径
 * （与商店列表页完全同口径：已安装且版本 != 索引 currentVersion）。
 */
class MarketUpdateCheckerTest {

    /* ─────────── 节流判定 ─────────── */

    @Test
    fun `shouldCheck - never checked returns true`() {
        assertTrue(MarketUpdateChecker.shouldCheck(nowMs = 1000L, lastCheckedAtMs = 0L, intervalMs = 100L))
    }

    @Test
    fun `shouldCheck - within interval returns false`() {
        assertFalse(MarketUpdateChecker.shouldCheck(nowMs = 10_000L, lastCheckedAtMs = 9_000L, intervalMs = 6_000L))
    }

    @Test
    fun `shouldCheck - past interval returns true`() {
        assertTrue(MarketUpdateChecker.shouldCheck(nowMs = 17_000L, lastCheckedAtMs = 9_000L, intervalMs = 6_000L))
    }

    @Test
    fun `shouldCheck - exactly at interval returns true`() {
        assertTrue(MarketUpdateChecker.shouldCheck(nowMs = 16_000L, lastCheckedAtMs = 10_000L, intervalMs = 6_000L))
    }

    /* ─────────── 方案 hasUpdate ─────────── */

    private fun schemeItem(currentVersion: String, installedVersion: String?) =
        MarketSchemeItem(
            scheme = MarketScheme(id = "s1", currentVersion = currentVersion),
            compatible = true,
            minAppVersion = "",
            installedVersion = installedVersion,
        )

    @Test
    fun `scheme hasUpdate - outdated installed`() {
        assertTrue(schemeItem("1.1", installedVersion = "1.0").hasUpdate)
    }

    @Test
    fun `scheme hasUpdate - same version false`() {
        assertFalse(schemeItem("1.0", installedVersion = "1.0").hasUpdate)
    }

    @Test
    fun `scheme hasUpdate - not installed false`() {
        assertFalse(schemeItem("1.1", installedVersion = null).hasUpdate)
    }

    @Test
    fun `scheme hasUpdate - blank current version false`() {
        assertFalse(schemeItem("", installedVersion = "1.0").hasUpdate)
    }

    /* ─────────── 插件 hasUpdate ─────────── */

    private fun pluginItem(currentVersion: String, installed: Boolean, installedVersion: String?) =
        MarketPluginItem(
            plugin = MarketPlugin(id = "p1", currentVersion = currentVersion),
            compatible = true,
            minAppVersion = "",
            installed = installed,
            installedVersion = installedVersion,
        )

    @Test
    fun `plugin hasUpdate - outdated installed`() {
        assertTrue(pluginItem("2.0", installed = true, installedVersion = "1.0").hasUpdate)
    }

    @Test
    fun `plugin hasUpdate - same version false`() {
        assertFalse(pluginItem("2.0", installed = true, installedVersion = "2.0").hasUpdate)
    }

    @Test
    fun `plugin hasUpdate - not installed false`() {
        assertFalse(pluginItem("2.0", installed = false, installedVersion = null).hasUpdate)
    }

    /* ─────────── 布局 hasUpdate ─────────── */

    private fun layoutItem(currentVersion: String, installedVersion: String?) =
        MarketLayoutItem(
            layout = MarketLayout(id = "l1", currentVersion = currentVersion),
            compatible = true,
            minAppVersion = "",
            installedVersion = installedVersion,
        )

    @Test
    fun `layout hasUpdate - outdated applied`() {
        assertTrue(layoutItem("3.0", installedVersion = "2.0").hasUpdate)
    }

    @Test
    fun `layout hasUpdate - same version false`() {
        assertFalse(layoutItem("3.0", installedVersion = "3.0").hasUpdate)
    }

    @Test
    fun `layout hasUpdate - not applied false`() {
        assertFalse(layoutItem("3.0", installedVersion = null).hasUpdate)
    }

    /* ─────────── 计数口径 ─────────── */

    @Test
    fun `count matches store-style hasUpdate filtering`() {
        val items = listOf(
            schemeItem("1.1", installedVersion = "1.0"),  // 可更新
            schemeItem("1.0", installedVersion = "1.0"),  // 已最新
            schemeItem("2.0", installedVersion = null),   // 未安装
        )
        assertEquals(1, items.count { it.hasUpdate })
    }

    /* ─────────── Summary 合并 ─────────── */

    private val oldSummary = MarketUpdateChecker.Summary(
        schemeUpdates = 1, modelUpdates = 2, pluginUpdates = 3, layoutUpdates = 4,
        checkedAtMs = 1000L,
    )

    @Test
    fun `merge - successful counts replace old values`() {
        val merged = MarketUpdateChecker.merge(
            oldSummary,
            MarketUpdateChecker.MarketCounts(scheme = 5, model = 0, plugin = 1, layout = 0),
            nowMs = 2000L,
        )
        assertEquals(MarketUpdateChecker.Summary(5, 0, 1, 0, checkedAtMs = 2000L), merged)
    }

    @Test
    fun `merge - failed market keeps old count`() {
        val merged = MarketUpdateChecker.merge(
            oldSummary,
            MarketUpdateChecker.MarketCounts(scheme = 5, model = null, plugin = null, layout = 0),
            nowMs = 2000L,
        )
        assertEquals(MarketUpdateChecker.Summary(5, 2, 3, 0, checkedAtMs = 2000L), merged)
    }

    @Test
    fun `merge - all failed keeps old summary untouched`() {
        val merged = MarketUpdateChecker.merge(
            oldSummary,
            MarketUpdateChecker.MarketCounts(scheme = null, model = null, plugin = null, layout = null),
            nowMs = 2000L,
        )
        assertEquals(oldSummary, merged)
    }

    @Test
    fun `totalUpdates - sum of all markets`() {
        assertEquals(10, oldSummary.totalUpdates)
        assertEquals(0, MarketUpdateChecker.Summary().totalUpdates)
    }
}
