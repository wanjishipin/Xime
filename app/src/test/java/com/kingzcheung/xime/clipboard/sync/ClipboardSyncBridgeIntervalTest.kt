package com.kingzcheung.xime.clipboard.sync

import com.kingzcheung.xime.clipboard.sync.ClipboardSyncBridge.Companion.DEFAULT_PULL_MIN_INTERVAL_MS
import com.kingzcheung.xime.clipboard.sync.ClipboardSyncBridge.Companion.PULL_INTERVAL_SECONDS_MAX
import com.kingzcheung.xime.clipboard.sync.ClipboardSyncBridge.Companion.PULL_INTERVAL_SECONDS_MIN
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 拉取最小间隔的插件配置解析测试（[ClipboardSyncBridge.resolvePullIntervalMs]）。
 *
 * 配置由插件 settings.schema 声明（NUMBER，单位秒），宿主每次 pullOnce 读取：
 * 空/非法 → 默认 30s；越界 → clamp 到 [1, 600]s。
 */
class ClipboardSyncBridgeIntervalTest {

    @Test
    fun `未配置时回退默认30秒`() {
        assertEquals(DEFAULT_PULL_MIN_INTERVAL_MS, ClipboardSyncBridge.resolvePullIntervalMs(null))
        assertEquals(DEFAULT_PULL_MIN_INTERVAL_MS, ClipboardSyncBridge.resolvePullIntervalMs(""))
        assertEquals(DEFAULT_PULL_MIN_INTERVAL_MS, ClipboardSyncBridge.resolvePullIntervalMs("   "))
    }

    @Test
    fun `非法数值回退默认30秒`() {
        assertEquals(DEFAULT_PULL_MIN_INTERVAL_MS, ClipboardSyncBridge.resolvePullIntervalMs("abc"))
        assertEquals(DEFAULT_PULL_MIN_INTERVAL_MS, ClipboardSyncBridge.resolvePullIntervalMs("1.5"))
        assertEquals(DEFAULT_PULL_MIN_INTERVAL_MS, ClipboardSyncBridge.resolvePullIntervalMs("30s"))
    }

    @Test
    fun `合法秒数换算为毫秒`() {
        assertEquals(1_000L, ClipboardSyncBridge.resolvePullIntervalMs("1"))
        assertEquals(30_000L, ClipboardSyncBridge.resolvePullIntervalMs("30"))
        // 允许带首尾空白（设置表单手输常见）
        assertEquals(5_000L, ClipboardSyncBridge.resolvePullIntervalMs(" 5 "))
    }

    @Test
    fun `越界值clamp到允许范围`() {
        assertEquals(PULL_INTERVAL_SECONDS_MIN * 1000L, ClipboardSyncBridge.resolvePullIntervalMs("0"))
        assertEquals(PULL_INTERVAL_SECONDS_MIN * 1000L, ClipboardSyncBridge.resolvePullIntervalMs("-3"))
        assertEquals(
            PULL_INTERVAL_SECONDS_MAX * 1000L,
            ClipboardSyncBridge.resolvePullIntervalMs((PULL_INTERVAL_SECONDS_MAX + 1).toString())
        )
    }
}
