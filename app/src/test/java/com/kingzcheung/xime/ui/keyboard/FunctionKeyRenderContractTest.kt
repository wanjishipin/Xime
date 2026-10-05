package com.kingzcheung.xime.ui.keyboard

import com.kingzcheung.xime.settings.KeysConfigHelper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 功能键 id 的「配置集合 ↔ 渲染组件」契约。
 *
 * [KeysConfigHelper.FUNCTION_KEY_IDS] 里的每个 id 都必须有渲染组件：只有配置、没有组件的 id
 * 会渲染成「占宽度但看不见、也不响应」的死键（历史上 `symbol` / `emoji` / `voice` 即如此），
 * 用户把 `layout.rows` 里写上它们就等于凭空吃掉一块键盘位置。
 */
class FunctionKeyRenderContractTest {

    @Test
    fun `渲染层实现的功能键与配置集合完全一致`() {
        assertEquals(KeysConfigHelper.FUNCTION_KEY_IDS, RENDERED_FUNCTION_KEY_IDS)
    }

    @Test
    fun `symbol emoji voice 都有组件`() {
        assertTrue(
            "symbol / emoji / voice 缺少组件时会成为死键",
            RENDERED_FUNCTION_KEY_IDS.containsAll(listOf("symbol", "emoji", "voice")),
        )
    }
}