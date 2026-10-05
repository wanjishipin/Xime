package com.kingzcheung.xime.settings

import android.content.Context
import android.content.res.AssetManager
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException

/**
 * 回归测试（合并键布局重构引入，见 issue #884 关联的 xime.custom.yaml 不生效）：
 * [KeysConfigHelper.loadConfig] 刷新基线缓存后，必须把标准 26 键的行布局落到
 * `_zhRows`。历史上只调用 [KeysConfigHelper.setActiveKeyboardSchema]，而非合并键
 * 方案的 section 为 null，与重置后的 `_activeMergedSection` 相等会被提前 return，
 * 导致 xime.custom.yaml 的 layout.rows 不生效（重新部署也无效）。
 */
class KeysConfigLayoutReloadTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val defaultXime = """
        keyboard:
          qwerty:
            layout:
              rows:
                - [q, w, e, r, t, y, u, i, o, p]
                - [a, s, d, f, g, h, j, k, l]
                - [z, x, c, v, b, n, m]
    """.trimIndent()

    private val customXime = """
        keyboard:
          qwerty:
            layout:
              rows:
                - [q, w, e, r, t, y, u, i, o, p]
                - [a, s, d, f, g, h, j, k, l, ";"]
                - [z, x, c, v, b, n, m]
    """.trimIndent()

    @Test
    fun `重新部署时 xime custom 行布局生效`() {
        val filesDir = tmp.newFolder("files")
        val rimeDir = File(filesDir, "rime").apply { mkdirs() }
        File(rimeDir, "xime.custom.yaml").writeText(customXime)

        val assets = mock<AssetManager>()
        whenever(assets.open("xime.yaml")).thenAnswer {
            ByteArrayInputStream(defaultXime.toByteArray())
        }
        whenever(assets.open("xime.custom.yaml")).thenThrow(IOException("not in assets"))

        val context = mock<Context>()
        whenever(context.assets).thenReturn(assets)
        whenever(context.filesDir).thenReturn(filesDir)

        KeysConfigHelper.loadConfig(context)

        assertEquals(
            listOf("a", "s", "d", "f", "g", "h", "j", "k", "l", ";"),
            KeysConfigHelper.getKeyRows(false)[1],
        )
    }
}
