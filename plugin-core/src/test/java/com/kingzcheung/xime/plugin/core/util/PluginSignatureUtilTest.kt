package com.kingzcheung.xime.plugin.core.util

import com.kingzcheung.xime.plugin.core.model.PluginSource
import com.kingzcheung.xime.plugin.core.model.TrustLevel
import org.junit.Assert.assertEquals
import org.junit.Test

class PluginSignatureUtilTest {

    @Test
    fun `内置插件标记为官方`() {
        assertEquals(TrustLevel.TRUSTED, PluginSignatureUtil.classifyScriptPlugin(PluginSource.ASSET))
        assertEquals(TrustLevel.TRUSTED, PluginSignatureUtil.classifyScriptPlugin(PluginSource.SYSTEM))
    }

    @Test
    fun `用户导入的插件标记为第三方`() {
        assertEquals(TrustLevel.THIRD_PARTY, PluginSignatureUtil.classifyScriptPlugin(PluginSource.FILE))
    }
}
