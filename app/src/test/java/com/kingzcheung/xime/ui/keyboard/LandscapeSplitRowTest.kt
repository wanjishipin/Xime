package com.kingzcheung.xime.ui.keyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 横屏分体键盘行拆分测试：
 * 拆分规则（前/后各取 ceil(n/2)，奇数行中间键两侧重复）必须与历史硬编码
 * QWERTY 拆分一致，自定义布局（如 Colemak）按同规则拆分——保证横屏分体
 * 键盘读取 xime.custom.yaml layout.rows 后不回归默认 QWERTY。
 */
class LandscapeSplitRowTest {

    @Test
    fun `内置 QWERTY 拆分与历史硬编码一致`() {
        // 10 键行：5 / 5，无重叠
        splitRowForLandscape(listOf("q", "w", "e", "r", "t", "y", "u", "i", "o", "p")).let { (l, r) ->
            assertEquals(listOf("q", "w", "e", "r", "t"), l)
            assertEquals(listOf("y", "u", "i", "o", "p"), r)
        }
        // 9 键行：5 / 5，中间键 g 两侧重复（历史行为）
        splitRowForLandscape(listOf("a", "s", "d", "f", "g", "h", "j", "k", "l")).let { (l, r) ->
            assertEquals(listOf("a", "s", "d", "f", "g"), l)
            assertEquals(listOf("g", "h", "j", "k", "l"), r)
        }
        // 7 键行：4 / 4，中间键 v 两侧重复（历史行为）
        splitRowForLandscape(listOf("z", "x", "c", "v", "b", "n", "m")).let { (l, r) ->
            assertEquals(listOf("z", "x", "c", "v"), l)
            assertEquals(listOf("v", "b", "n", "m"), r)
        }
    }

    @Test
    fun `自定义 Colemak 布局按同规则拆分`() {
        splitRowForLandscape(listOf("q", "w", "f", "p", "g", "j", "l", "u", "y", ";")).let { (l, r) ->
            assertEquals(listOf("q", "w", "f", "p", "g"), l)
            assertEquals(listOf("j", "l", "u", "y", ";"), r)
        }
        splitRowForLandscape(listOf("a", "r", "s", "t", "d", "h", "n", "e", "i", "o")).let { (l, r) ->
            assertEquals(listOf("a", "r", "s", "t", "d"), l)
            assertEquals(listOf("h", "n", "e", "i", "o"), r)
        }
        splitRowForLandscape(listOf("z", "x", "c", "v", "b", "k", "m")).let { (l, r) ->
            assertEquals(listOf("z", "x", "c", "v"), l)
            assertEquals(listOf("v", "b", "k", "m"), r)
        }
    }

    @Test
    fun `边界：空行与单键`() {
        splitRowForLandscape(emptyList()).let { (l, r) ->
            assertTrue(l.isEmpty() && r.isEmpty())
        }
        splitRowForLandscape(listOf("q")).let { (l, r) ->
            assertEquals(listOf("q"), l)
            assertEquals(listOf("q"), r)
        }
    }
}
