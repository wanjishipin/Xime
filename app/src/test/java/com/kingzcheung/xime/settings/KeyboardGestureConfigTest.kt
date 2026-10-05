package com.kingzcheung.xime.settings

import com.kingzcheung.xime.keyboard.GestureAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class KeyboardGestureConfigTest {

    // ── 槽位默认动作 ──

    @Test
    fun `tap 字符串简写默认为 send_rime`() {
        val kc = parse("q: { tap: \"q\" }")["q"]!!
        assertEquals("q", kc.tap!!.label)
        assertEquals(GestureAction.SEND_RIME, kc.tap!!.action)
        assertEquals("q", kc.tap!!.value)
    }

    @Test
    fun `swipe 字符串简写默认为 commit`() {
        val kc = parse("a: { tap: \"a\", swipe_up: \"!\", swipe_left: \"?\" }")["a"]!!
        assertEquals(GestureAction.COMMIT, kc.swipeUp!!.action)
        assertEquals("!", kc.swipeUp!!.value)
        assertEquals(GestureAction.COMMIT, kc.swipeLeft!!.action)
        assertEquals("?", kc.swipeLeft!!.value)
    }

    @Test
    fun `左右滑对象命令动作解析`() {
        val kc = parse("""
            delete:
              swipe_left: { action: "command", value: "clear_composition" }
              swipe_right: { action: "command", value: "clear_composition" }
        """.trimIndent())["delete"]!!
        assertEquals(GestureAction.COMMAND, kc.swipeLeft!!.action)
        assertEquals("clear_composition", kc.swipeLeft!!.value)
        assertEquals(GestureAction.COMMAND, kc.swipeRight!!.action)
        assertEquals("clear_composition", kc.swipeRight!!.value)
    }

    @Test
    fun `double_tap 字符串简写默认为 commit`() {
        val kc = parse("a: { tap: \"a\", double_tap: \"A\" }")["a"]!!
        assertEquals(GestureAction.COMMIT, kc.doubleTap!!.action)
        assertEquals("A", kc.doubleTap!!.value)
    }

    // ── 对象格式 ──

    @Test
    fun `对象格式指定 action copy`() {
        val su = parse("""
            c:
              swipe_up: { label: "复制", action: "copy" }
        """.trimIndent())["c"]!!.swipeUp!!
        assertEquals("复制", su.label)
        assertEquals(GestureAction.COPY, su.action)
    }

    @Test
    fun `对象格式省略 action 时取槽位默认`() {
        val tap = parse("""
            "comma":
              tap: { label: "，", value: "," }
        """.trimIndent())["comma"]!!.tap!!
        assertEquals(GestureAction.SEND_RIME, tap.action)
        assertEquals(",", tap.value)
    }

    @Test
    fun `action null 表示无动作`() {
        val sd = parse("s: { swipe_down: { label: \"\", action: null } }")["s"]!!.swipeDown!!
        assertNull(sd.action)
    }

    @Test
    fun `shift_swipe_up 字符串简写与对象格式解析`() {
        val keys = parseKeys("""
            q: { tap: "q", swipe_up: "1", shift_swipe_up: "!" }
            a: { tap: "a", shift_swipe_up: { label: "［", value: "[" } }
        """.trimIndent())
        val q = keys["q"]!!
        assertEquals("!", q.shiftSwipeUp!!.label)
        assertEquals("!", q.shiftSwipeUp!!.value)
        assertEquals(GestureAction.COMMIT, q.shiftSwipeUp!!.action)
        val a = keys["a"]!!
        assertEquals("［", a.shiftSwipeUp!!.label)
        assertEquals("[", a.shiftSwipeUp!!.value)
    }

    @Test
    fun `long_press 支持多值数组`() {
        val keys = parseKeys("""
            a:
              long_press:
                values:
                  - { label: "大写", action: "commit", value: "A" }
                  - { label: "Ä",    action: "commit", value: "ä" }
        """.trimIndent())
        val lp = keys["a"]!!.longPress!!
        assertEquals(2, lp.values.size)
        assertEquals("大写", lp.values[0].label)
        assertEquals("A", lp.values[0].value)
        assertEquals("Ä", lp.values[1].label)
    }

    @Test
    fun `未知 action 不生效`() {
        val sd = parse("s: { swipe_up: { label: \"x\", action: \"no_such\" } }")["s"]!!.swipeUp!!
        assertNull(sd.action)
    }

    // ── display ──

    @Test
    fun `字符串简写 display 默认 both 对象默认 key`() {
        val kc = parse("a: { tap: \"a\", swipe_up: \"@\", swipe_down: { label: \"@\", action: \"commit\" } }")["a"]!!
        assertEquals(DisplayMode.BOTH, kc.swipeUp!!.display)
        assertEquals(DisplayMode.KEY, kc.swipeDown!!.display)
    }

    @Test
    fun `display bubble 解析`() {
        val sd = parse("a: { swipe_down: { label: \"@\", action: \"commit\", display: \"bubble\" } }")["a"]!!.swipeDown!!
        assertEquals(DisplayMode.BUBBLE, sd.display)
    }

    @Test
    fun `bubble 独立于 display 解析`() {
        val a = parse("""a: { swipe_up: { value: "1", display: "key", bubble: false } }""")["a"]!!.swipeUp!!
        assertEquals(DisplayMode.KEY, a.display)
        assertFalse(a.bubble)
        val b = parse("""b: { swipe_up: { value: "2", display: "bubble" } }""")["b"]!!.swipeUp!!
        assertEquals(DisplayMode.BUBBLE, b.display)
        assertTrue(b.bubble)
    }

    // ── icon ──

    @Test
    fun `label 以 @ 开头提取 icon 且 label 置空`() {
        val tap = parse("k: { tap: { label: \"@language\", action: \"toggle_ascii\" } }")["k"]!!.tap!!
        assertEquals("", tap.label)
        assertEquals("language", tap.icon)
    }

    @Test
    fun `空的 keys 不报错`() {
        assertEquals(0, parseKeys("{}").size)
    }

    @Test
    fun `部分手势缺失不报错`() {
        val a = parseKeys("""a: { tap: "a" }""".trimIndent())["a"]!!
        assertEquals("a", a.tap!!.label)
        assertNull(a.swipeUp)
        assertNull(a.swipeDown)
        assertNull(a.longPress)
    }

    // ── long_press flow-style 数组 ──

    @Test
    fun `long_press flow-style 字符串数组解析`() {
        val keys = parseKeys("""
            q: { tap: "q", swipe_up: "1", swipe_down: "Q", long_press: { values: ["q", "Q"] } }
        """.trimIndent())
        val lp = keys["q"]!!.longPress!!
        assertEquals(2, lp.values.size)
        assertEquals("q", lp.values[0].label)
        assertEquals(GestureAction.COMMIT, lp.values[0].action)
        assertEquals("q", lp.values[0].value)
        assertEquals("Q", lp.values[1].label)
        assertEquals(GestureAction.COMMIT, lp.values[1].action)
        assertEquals("Q", lp.values[1].value)
        assertEquals(DisplayMode.BUBBLE, lp.display)
    }

    @Test
    fun `long_press flow-style 混合字符串和对象`() {
        val keys = parseKeys("""
            a: { tap: "a", swipe_up: "!", swipe_down: "A", long_press: { values: [{ label: "全选", action: "select_all" }, "a", "A"] } }
        """.trimIndent())
        val lp = keys["a"]!!.longPress!!
        assertEquals(3, lp.values.size)
        // 对象格式
        assertEquals("全选", lp.values[0].label)
        assertEquals(GestureAction.SELECT_ALL, lp.values[0].action)
        assertEquals("", lp.values[0].value)
        // 字符串简写
        assertEquals("a", lp.values[1].label)
        assertEquals(GestureAction.COMMIT, lp.values[1].action)
        assertEquals("a", lp.values[1].value)
        assertEquals("A", lp.values[2].label)
        assertEquals(GestureAction.COMMIT, lp.values[2].action)
        assertEquals("A", lp.values[2].value)
    }

    @Test
    fun `long_press flow-style 带变音符号`() {
        val keys = parseKeys("""
            u: { tap: "u", swipe_up: "7", swipe_down: "U", long_press: { values: ["u", "U", "ù", "ú", "û", "ü"] } }
        """.trimIndent())
        val lp = keys["u"]!!.longPress!!
        assertEquals(6, lp.values.size)
        assertEquals("u", lp.values[0].value)
        assertEquals("U", lp.values[1].value)
        assertEquals("ù", lp.values[2].value)
        assertEquals("ú", lp.values[3].value)
        assertEquals("û", lp.values[4].value)
        assertEquals("ü", lp.values[5].value)
    }

    @Test
    fun `long_press flow-style 单个元素`() {
        val keys = parseKeys("""
            p: { tap: "p", swipe_up: "0", swipe_down: "P", long_press: { values: ["p", "P"] } }
        """.trimIndent())
        val lp = keys["p"]!!.longPress!!
        assertEquals(2, lp.values.size)
    }

    @Test
    fun `long_press flow-style 带有特殊字符的反斜杠`() {
        val keys = parseKeys("""
            c: { tap: "c", swipe_up: "\\", swipe_down: "C", long_press: { values: ["c", "C", "ç"] } }
        """.trimIndent())
        val lp = keys["c"]!!.longPress!!
        assertEquals(3, lp.values.size)
        assertEquals("c", lp.values[0].value)
        assertEquals("C", lp.values[1].value)
        assertEquals("ç", lp.values[2].value)
        assertEquals("\\", keys["c"]!!.swipeUp!!.value)
    }

    // ── action:none ──

    @Test
    fun `swipe_up 对象格式 action none 不产生任何值`() {
        val keys = parseKeys("""
            s: { tap: "s", swipe_up: { action: "none" } }
        """.trimIndent())
        val su = keys["s"]!!.swipeUp!!
        assertEquals("", su.label)
        assertEquals(GestureAction.NONE, su.action)
        assertEquals("", su.value)
    }

    @Test
    fun `swipe_up 对象格式 action none 有 label 仍无值`() {
        val keys = parseKeys("""
            s: { tap: "s", swipe_up: { label: "S", action: "none", display: "bubble" } }
        """.trimIndent())
        val su = keys["s"]!!.swipeUp!!
        assertEquals("S", su.label)
        assertEquals(GestureAction.NONE, su.action)
        assertEquals("", su.value)
    }

    @Test
    fun `swipe_up 对象格式 action commit 无 value 时 label 为回退值`() {
        val keys = parseKeys("""
            s: { tap: "s", swipe_up: { label: "S", action: "commit" } }
        """.trimIndent())
        val su = keys["s"]!!.swipeUp!!
        assertEquals("S", su.label)
        assertEquals(GestureAction.COMMIT, su.action)
        assertEquals("", su.value)
    }

    @Test
    fun `swipe_up 对象格式 action commit 有 value 优先`() {
        val keys = parseKeys("""
            s: { tap: "s", swipe_up: { label: "S", action: "commit", value: "s_swipe" } }
        """.trimIndent())
        val su = keys["s"]!!.swipeUp!!
        assertEquals("S", su.label)
        assertEquals("s_swipe", su.value)
    }

    @Test
    fun `swipe_up 字符串简写解析为 COMMIT`() {
        val keys = parseKeys("""
            a: { tap: "a", swipe_up: "@" }
        """.trimIndent())
        val su = keys["a"]!!.swipeUp!!
        assertEquals("@", su.label)
        assertEquals(GestureAction.COMMIT, su.action)
        assertEquals("@", su.value)
    }

    // ── DisplayMode ──

    @Test
    fun `字符串简写默认 display 为 both`() {
        val keys = parseKeys("""
            a: { tap: "a", swipe_down: "@" }
        """.trimIndent())
        assertEquals(DisplayMode.BOTH, keys["a"]!!.swipeDown!!.display)
    }

    @Test
    fun `对象格式无 display 字段默认为 key`() {
        val keys = parseKeys("""
            a: { tap: "a", swipe_down: { label: "@", action: "commit" } }
        """.trimIndent())
        assertEquals(DisplayMode.KEY, keys["a"]!!.swipeDown!!.display)
    }

    @Test
    fun `对象格式 display_key 解析为 KEY`() {
        val keys = parseKeys("""
            a: { tap: "a", swipe_down: { label: "@", action: "commit", display: "key" } }
        """.trimIndent())
        assertEquals(DisplayMode.KEY, keys["a"]!!.swipeDown!!.display)
    }

    @Test
    fun `对象格式 display_bubble 解析为 BUBBLE`() {
        val keys = parseKeys("""
            a: { tap: "a", swipe_down: { label: "@", action: "commit", display: "bubble" } }
        """.trimIndent())
        assertEquals(DisplayMode.BUBBLE, keys["a"]!!.swipeDown!!.display)
    }

    @Test
    fun `对象格式 display_both 解析为 BOTH`() {
        val keys = parseKeys("""
            a: { tap: "a", swipe_down: { label: "@", action: "commit", display: "both" } }
        """.trimIndent())
        assertEquals(DisplayMode.BOTH, keys["a"]!!.swipeDown!!.display)
    }

    @Test
    fun `对象指定 value 和 label 不同时 value 优先`() {
        val keys = parseKeys("""
            a: { tap: "a", swipe_down: { label: "@", action: "commit", value: "at" } }
        """.trimIndent())
        val sd = keys["a"]!!.swipeDown!!
        assertEquals("@", sd.label)
        assertEquals("at", sd.value)
    }

    // ── 完整 26 键配置 ──

    @Test
    fun `完整 26 键全键盘配置解析`() {
        val yaml = """
            q: { tap: "q", swipe_up: "1", swipe_down: "Q", long_press: { values: [{ label: "q", display: "bubble" }, "Q"] } }
            w: { tap: "w", swipe_up: "2", swipe_down: "W", long_press: { values: [{ label: "w", display: "bubble" }, "W"] } }
            e: { tap: "e", swipe_up: "3", swipe_down: "E", long_press: { values: [{ label: "e", display: "bubble" }, "E", "è", "é", "ê", "ë"] } }
            r: { tap: "r", swipe_up: "4", swipe_down: "R", long_press: { values: [{ label: "r", display: "bubble" }, "R"] } }
            t: { tap: "t", swipe_up: "5", swipe_down: "T", long_press: { values: [{ label: "t", display: "bubble" }, "T"] } }
            y: { tap: "y", swipe_up: "6", swipe_down: "Y", long_press: { values: [{ label: "y", display: "bubble" }, "Y", "ÿ"] } }
            u: { tap: "u", swipe_up: "7", swipe_down: "U", long_press: { values: [{ label: "u", display: "bubble" }, "U", "ù", "ú", "û", "ü"] } }
            i: { tap: "i", swipe_up: "8", swipe_down: "I", long_press: { values: [{ label: "i", display: "bubble" }, "I", "ì", "í", "î", "ï"] } }
            o: { tap: "o", swipe_up: "9", swipe_down: "O", long_press: { values: [{ label: "o", display: "bubble" }, "O", "ò", "ó", "ô", "õ", "ö", "ø"] } }
            p: { tap: "p", swipe_up: "0", swipe_down: "P", long_press: { values: [{ label: "p", display: "bubble" }, "P"] } }
            a: { tap: "a", swipe_up: "!", swipe_down: "A", long_press: { values: [{ label: "a", display: "bubble" }, "A", "à", "á", "â", "ã", "ä", "å", "æ"] } }
            s: { tap: "s", swipe_up: "@", swipe_down: "S", long_press: { values: [{ label: "s", display: "bubble" }, "S", "ß"] } }
            d: { tap: "d", swipe_up: "#", swipe_down: "D", long_press: { values: [{ label: "d", display: "bubble" }, "D"] } }
            f: { tap: "f", swipe_up: "$", swipe_down: "F", long_press: { values: [{ label: "f", display: "bubble" }, "F"] } }
            g: { tap: "g", swipe_up: "%", swipe_down: "G", long_press: { values: [{ label: "g", display: "bubble" }, "G"] } }
            h: { tap: "h", swipe_up: "^", swipe_down: "H", long_press: { values: [{ label: "h", display: "bubble" }, "H"] } }
            j: { tap: "j", swipe_up: "&", swipe_down: "J", long_press: { values: [{ label: "j", display: "bubble" }, "J"] } }
            k: { tap: "k", swipe_up: "(", swipe_down: "K", long_press: { values: [{ label: "k", display: "bubble" }, "K"] } }
            l: { tap: "l", swipe_up: ")", swipe_down: "L", long_press: { values: [{ label: "l", display: "bubble" }, "L"] } }
            z: { tap: "z", swipe_up: "|", swipe_down: "Z", long_press: { values: [{ label: "z", display: "bubble" }, "Z"] } }
            x: { tap: "x", swipe_up: "*", swipe_down: "X", long_press: { values: [{ label: "x", display: "bubble" }, "X"] } }
            c: { tap: "c", swipe_up: "\\", swipe_down: "C", long_press: { values: [{ label: "c", display: "bubble" }, "C", "ç"] } }
            v: { tap: "v", swipe_up: "?", swipe_down: "V", long_press: { values: [{ label: "v", display: "bubble" }, "V"] } }
            b: { tap: "b", swipe_up: "_", swipe_down: "B", long_press: { values: [{ label: "b", display: "bubble" }, "B"] } }
            n: { tap: "n", swipe_up: "-", swipe_down: "N", long_press: { values: [{ label: "n", display: "bubble" }, "N", "ñ"] } }
            m: { tap: "m", swipe_up: "+", swipe_down: "M", long_press: { values: [{ label: "m", display: "bubble" }, "M"] } }
        """.trimIndent()
        val keys = parseKeys(yaml)
        assertEquals("应有 26 个字母键", 26, keys.size)

        // 验证所有字母键都存在
        val allLetters = ('a'..'z').map { it.toString() }
        for (letter in allLetters) {
            assertNotNull("键 $letter 应该存在", keys[letter])
        }

        // 验证每个键的 tap / swipe_up / swipe_down
        for ((key, kc) in keys) {
            assertNotNull("$key.tap 不能为空", kc.tap)
            assertNotNull("$key.swipe_up 不能为空", kc.swipeUp)
            assertNotNull("$key.swipe_down 不能为空", kc.swipeDown)
            assertNotNull("$key.long_press 不能为空", kc.longPress)
        }
    }

    @Test
    fun `完整 26 键 long_press 顺序正确`() {
        val yaml = """
            q: { tap: "q", swipe_up: "1", swipe_down: "Q", long_press: { values: [{ label: "q", display: "bubble" }, "Q"] } }
            w: { tap: "w", swipe_up: "2", swipe_down: "W", long_press: { values: [{ label: "w", display: "bubble" }, "W"] } }
            e: { tap: "e", swipe_up: "3", swipe_down: "E", long_press: { values: [{ label: "e", display: "bubble" }, "E", "è", "é", "ê", "ë"] } }
            r: { tap: "r", swipe_up: "4", swipe_down: "R", long_press: { values: [{ label: "r", display: "bubble" }, "R"] } }
            t: { tap: "t", swipe_up: "5", swipe_down: "T", long_press: { values: [{ label: "t", display: "bubble" }, "T"] } }
            y: { tap: "y", swipe_up: "6", swipe_down: "Y", long_press: { values: [{ label: "y", display: "bubble" }, "Y", "ÿ"] } }
            u: { tap: "u", swipe_up: "7", swipe_down: "U", long_press: { values: [{ label: "u", display: "bubble" }, "U", "ù", "ú", "û", "ü"] } }
            i: { tap: "i", swipe_up: "8", swipe_down: "I", long_press: { values: [{ label: "i", display: "bubble" }, "I", "ì", "í", "î", "ï"] } }
            o: { tap: "o", swipe_up: "9", swipe_down: "O", long_press: { values: [{ label: "o", display: "bubble" }, "O", "ò", "ó", "ô", "õ", "ö", "ø"] } }
            p: { tap: "p", swipe_up: "0", swipe_down: "P", long_press: { values: [{ label: "p", display: "bubble" }, "P"] } }
            a: { tap: "a", swipe_up: "!", swipe_down: "A", long_press: { values: [{ label: "a", display: "bubble" }, "A", "à", "á", "â", "ã", "ä", "å", "æ"] } }
            s: { tap: "s", swipe_up: "@", swipe_down: "S", long_press: { values: [{ label: "s", display: "bubble" }, "S", "ß"] } }
            d: { tap: "d", swipe_up: "#", swipe_down: "D", long_press: { values: [{ label: "d", display: "bubble" }, "D"] } }
            f: { tap: "f", swipe_up: "$", swipe_down: "F", long_press: { values: [{ label: "f", display: "bubble" }, "F"] } }
            g: { tap: "g", swipe_up: "%", swipe_down: "G", long_press: { values: [{ label: "g", display: "bubble" }, "G"] } }
            h: { tap: "h", swipe_up: "^", swipe_down: "H", long_press: { values: [{ label: "h", display: "bubble" }, "H"] } }
            j: { tap: "j", swipe_up: "&", swipe_down: "J", long_press: { values: [{ label: "j", display: "bubble" }, "J"] } }
            k: { tap: "k", swipe_up: "(", swipe_down: "K", long_press: { values: [{ label: "k", display: "bubble" }, "K"] } }
            l: { tap: "l", swipe_up: ")", swipe_down: "L", long_press: { values: [{ label: "l", display: "bubble" }, "L"] } }
            z: { tap: "z", swipe_up: "|", swipe_down: "Z", long_press: { values: [{ label: "z", display: "bubble" }, "Z"] } }
            x: { tap: "x", swipe_up: "*", swipe_down: "X", long_press: { values: [{ label: "x", display: "bubble" }, "X"] } }
            c: { tap: "c", swipe_up: "\\", swipe_down: "C", long_press: { values: [{ label: "c", display: "bubble" }, "C", "ç"] } }
            v: { tap: "v", swipe_up: "?", swipe_down: "V", long_press: { values: [{ label: "v", display: "bubble" }, "V"] } }
            b: { tap: "b", swipe_up: "_", swipe_down: "B", long_press: { values: [{ label: "b", display: "bubble" }, "B"] } }
            n: { tap: "n", swipe_up: "-", swipe_down: "N", long_press: { values: [{ label: "n", display: "bubble" }, "N", "ñ"] } }
            m: { tap: "m", swipe_up: "+", swipe_down: "M", long_press: { values: [{ label: "m", display: "bubble" }, "M"] } }
        """.trimIndent()
        val keys = parseKeys(yaml)

        // 验证带变音符号的键
        assertLongPressValues(keys["e"]!!, listOf("e", "E", "è", "é", "ê", "ë"))
        assertLongPressValues(keys["y"]!!, listOf("y", "Y", "ÿ"))
        assertLongPressValues(keys["u"]!!, listOf("u", "U", "ù", "ú", "û", "ü"))
        assertLongPressValues(keys["i"]!!, listOf("i", "I", "ì", "í", "î", "ï"))
        assertLongPressValues(keys["o"]!!, listOf("o", "O", "ò", "ó", "ô", "õ", "ö", "ø"))
        assertLongPressValues(keys["a"]!!, listOf("a", "A", "à", "á", "â", "ã", "ä", "å", "æ"))
        assertLongPressValues(keys["s"]!!, listOf("s", "S", "ß"))
        assertLongPressValues(keys["c"]!!, listOf("c", "C", "ç"))
        assertLongPressValues(keys["n"]!!, listOf("n", "N", "ñ"))

        // 验证无变音符号的键（仅小写+大写）
        val noAccentKeys = listOf("q", "w", "r", "t", "p", "d", "f", "g", "h", "j", "k", "l", "z", "x", "v", "b", "m")
        for (key in noAccentKeys) {
            val upper = key.uppercase()
            assertLongPressValues(keys[key]!!, listOf(key, upper))
        }
    }

    private fun assertLongPressValues(kc: KeyBinding, expectedLabels: List<String>) {
        val lp = kc.longPress!!
        val actualLabels = lp.values.map { it.label }
        assertEquals("long_press 数量不匹配: 期望 $expectedLabels 实际 $actualLabels",
            expectedLabels.size, lp.values.size)
        for (i in expectedLabels.indices) {
            assertEquals("索引 $i 的 label 不匹配", expectedLabels[i], lp.values[i].label)
            assertEquals("索引 $i 的动作应为 commit", GestureAction.COMMIT, lp.values[i].action)
        }
    }

    // ── 辅助 ──

    private fun parseKeys(yamlFragment: String): Map<String, KeyBinding> = parse(yamlFragment)


    private fun parseSection(yamlText: String, section: String): Map<String, KeyBinding> =
        KeysConfigHelper.parseKeyboardYamlSection(yamlText, section) ?: emptyMap()
    // ── qwerty / qwerty_en 双布局解析 ──

    @Test
    fun `标签以 @ 开头时自动提取 icon label 置空`() {
        val keys = parseKeys("""
            k: { tap: { label: "@language", action: "toggle_ascii" } }
        """.trimIndent())
        val tap = keys["k"]!!.tap!!
        assertEquals("", tap.label)
        assertEquals("language", tap.icon)
    }

    @Test
    fun `字符串简写 @label 也提取 icon`() {
        val tap = parse("k: { tap: \"@language\" }")["k"]!!.tap!!
        assertEquals("", tap.label)
        assertEquals("language", tap.icon)
        assertEquals("@language", tap.value)
    }

    // ── long_press ──

    @Test
    fun `long_press 缺省 display 为 bubble`() {
        val lp = parse("""a: { long_press: { values: ["a", "A", "à"] } }""")["a"]!!.longPress!!
        assertEquals(DisplayMode.BUBBLE, lp.display)
        assertEquals(3, lp.values.size)
        assertEquals("a", lp.values[0].value)
        assertEquals(GestureAction.COMMIT, lp.values[0].action)
        assertEquals("à", lp.values[2].value)
    }

    @Test
    fun `long_press display key 回退为气泡（键面绘制未实现）`() {
        val lp = parse("""q: { long_press: { display: "key", values: ["q", "Q"] } }""")["q"]!!.longPress!!
        assertEquals(DisplayMode.BUBBLE, lp.display)
        assertEquals(2, lp.values.size)
    }

    @Test
    fun `label 写成数组按多行合并`() {
        val kb = parse("""q: { swipe_down: { label: ["q", "Q", "9"], action: "none" } }""")["q"]!!
        assertEquals("q\nQ\n9", kb.swipeDown!!.label)
    }

    @Test
    fun `icon 字段与 label 的 @ 前缀等价`() {
        val byField = parse("""q: { swipe_up: { icon: "mic", action: "voice" } }""")["q"]!!.swipeUp!!
        assertEquals("mic", byField.icon)
        assertEquals("", byField.label)
        val byLabel = parse("""x: { swipe_up: { label: "@mic", action: "voice" } }""")["x"]!!.swipeUp!!
        assertEquals("mic", byLabel.icon)
        assertEquals("", byLabel.label)
        // 显式 icon 优先，普通 label 原样保留
        val both = parse("""y: { swipe_up: { icon: "emoji", label: "表情" } }""")["y"]!!.swipeUp!!
        assertEquals("emoji", both.icon)
        assertEquals("表情", both.label)
    }

    @Test
    fun `未知字段被忽略且不影响其余字段`() {
        val kb = parse("""q: { swipe_up: { swip_up: "1", action: "commit", value: "1" } }""")["q"]!!
        assertEquals(GestureAction.COMMIT, kb.swipeUp!!.action)
        assertEquals("1", kb.swipeUp!!.value)
    }

    @Test
    fun `long_press 单动作写成单元素列表`() {
        val lp = parse("delete: { long_press: { values: [{ action: \"delete\" }] } }")["delete"]!!.longPress!!
        assertEquals(1, lp.values.size)
        assertEquals(GestureAction.DELETE, lp.values[0].action)
    }

    @Test
    fun `已移除的死字段被静默忽略`() {
        // when_composing / sticky / repeat 已从 schema 移除：写入配置不影响其它字段解析
        val kc = parse("""q: { sticky: true, swipe_up: { value: "1", repeat: true }, when_composing: { tap: "a" } }""")["q"]!!
        assertEquals("1", kc.swipeUp!!.value)
    }

    @Test
    fun `long_press 多值冒泡`() {
        val lp = parse("m: { long_press: { values: [{ label: \"number\", action: \"command\", value: \"mode_change_number\" }, { label: \"symbol\", action: \"command\", value: \"mode_change_common_symbol\" }] } }")["m"]!!.longPress!!
        assertEquals(2, lp.values.size)
        assertEquals("number", lp.values[0].label)
        assertEquals("mode_change_number", lp.values[0].value)
    }

    @Test
    fun `long_press 数组简写已不再支持`() {
        val lp = parse("""a: { long_press: ["a", "A"] }""")["a"]!!.longPress
        assertNull(lp)
    }

    @Test
    fun `long_press 显示项无 label 时回退 value`() {
        val lp = parse("""a: { long_press: { values: [{ value: "，" }, { label: "。", value: "。" }] } }""")["a"]!!.longPress!!
        assertEquals(listOf("，", "。"), KeysConfigHelper.longPressDisplayItems(lp))
    }

    @Test
    fun `long_press 显示项与动作映射的键完全一致`() {
        val lp = parse("""a: { long_press: { values: [{ value: "，" }, { label: "复制", action: "copy" }] } }""")["a"]!!.longPress!!
        val map = KeysConfigHelper.longPressActionMap(lp)!!
        assertEquals(setOf("，", "复制"), map.keys)
        assertEquals(GestureAction.COMMIT, map["，"]!!.action)
        assertEquals(GestureAction.COPY, map["复制"]!!.action)
    }

    @Test
    fun `long_press 显示项与查找键都为空时该项被丢弃`() {
        val lp = parse("""a: { long_press: { values: [{ action: "copy" }, { label: "复制", action: "copy" }] } }""")["a"]!!.longPress!!
        assertEquals(listOf("复制"), KeysConfigHelper.longPressDisplayItems(lp))
        assertEquals(setOf("复制"), KeysConfigHelper.longPressActionMap(lp)!!.keys)
    }

    @Test
    fun `long_press 超过 10 项时截断为 10 项`() {
        val items = (1..12).joinToString(", ") { """{ value: "$it" }""" }
        val lp = parse("""a: { long_press: { values: [$items] } }""")["a"]!!.longPress!!
        assertEquals(10, lp.values.size)
        assertEquals(
            listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "10"),
            KeysConfigHelper.longPressDisplayItems(lp),
        )
    }

    @Test
    fun `键级 width 解析`() {
        val withWidth = parse("""enter: { tap: "enter", width: 1.2 }""")["enter"]!!
        assertEquals(1.2f, withWidth.width!!, 0.001f)
        assertNull(parse("""enter: { tap: "enter" }""")["enter"]!!.width)
    }

    // ── actions 预设 ──

    @Test
    fun `use 引用动作预设`() {
        val presets = KeysConfigHelper.parseKeyboardActionsYamlText(
            """
            keyboard:
              actions:
                switch_num: { action: "command", value: "mode_change_number" }
            """.trimIndent()
        )
        assertEquals("mode_change_number", presets["switch_num"]!!.value)
        val kc = parse("m: { tap: { use: \"switch_num\" } }", presets = presets)["m"]!!
        assertEquals(GestureAction.COMMAND, kc.tap!!.action)
        assertEquals("mode_change_number", kc.tap!!.value)
    }

    @Test
    fun `use 引用未知预设不生效`() {
        val kc = parse("m: { tap: { use: \"missing\" } }")["m"]!!
        assertNull(kc.tap!!.action)
    }

    // ── section 独立性 ──

    @Test
    fun `qwerty 与 qwerty_en 独立读取`() {
        val yaml = """
            keyboard:
              qwerty:
                keys:
                  earth: { tap: { label: "英", action: "toggle_ascii" } }
              qwerty_en:
                keys:
                  earth: { tap: { label: "中", action: "toggle_ascii" } }
        """.trimIndent()
        val zh = KeysConfigHelper.parseKeyboardYamlSection(yaml, "qwerty")!!
        val en = KeysConfigHelper.parseKeyboardYamlSection(yaml, "qwerty_en")!!
        assertEquals("英", zh["earth"]!!.tap!!.label)
        assertEquals("中", en["earth"]!!.tap!!.label)
        assertNotEquals(zh["earth"]!!.tap!!.label, en["earth"]!!.tap!!.label)
    }

    @Test
    fun `缺失键不影响其它键`() {
        val yaml = """
            keyboard:
              qwerty:
                keys:
                  q: { tap: "q" }
                  w: { tap: "w" }
        """.trimIndent()
        val zh = KeysConfigHelper.parseKeyboardYamlSection(yaml, "qwerty")!!
        assertEquals(2, zh.size)
        assertNotNull(zh["w"])
        assertNull(zh["z"])
    }

    // ── 上滑默认值回退（旧默认符号表只在未配置 swipe_up 时生效） ──

    @Test
    fun `未配置 swipe_up 的键回退旧默认表`() {
        KeysConfigHelper.setKeyGestureConfigForTest(emptyMap())
        assertEquals("1", KeysConfigHelper.getSwipeUpCommitValue("q"))
        assertEquals("1", KeysConfigHelper.getSwipeUpLabel("q"))
    }

    @Test
    fun `配置了 swipe_up 但无 label 与 value 时不再回退旧默认表`() {
        KeysConfigHelper.setKeyGestureConfigForTest(
            mapOf("q" to KeyBinding(swipeUp = KeyAction(action = GestureAction.COPY)))
        )
        assertNull(KeysConfigHelper.getSwipeUpCommitValue("q"))
        assertNull(KeysConfigHelper.getSwipeUpLabel("q"))
        // 复位，避免影响其它用例
        KeysConfigHelper.setKeyGestureConfigForTest(emptyMap())
    }

    // ── 按压气泡开关（tap.bubble，默认 true） ──

    @Test
    fun `tap 的 bubble 默认开启`() {
        assertTrue(parse("q: { tap: \"q\" }")["q"]!!.tap!!.bubble)
        assertTrue(parse("q: { tap: { value: \"q\", label: \"q\" } }")["q"]!!.tap!!.bubble)
    }

    @Test
    fun `tap 的 bubble 可关闭且不影响其它字段`() {
        val tap = parse("q: { tap: { label: \"q\", value: \"q\", bubble: false } }")["q"]!!.tap!!
        assertFalse(tap.bubble)
        assertEquals("q", tap.label)
        assertEquals("q", tap.value)
        assertEquals(GestureAction.SEND_RIME, tap.action)
    }

    @Test
    fun `tap 与 swipe 的气泡开关互相独立`() {
        val binding = parse("q: { tap: { value: \"q\", bubble: false }, swipe_up: { value: \"1\", bubble: true } }")["q"]!!
        assertFalse(binding.tap!!.bubble)
        assertTrue(binding.swipeUp!!.bubble)
    }

    // ── 辅助 ──

    private fun parse(
        keysFragment: String,
        section: String = "qwerty",
        presets: Map<String, KeyAction> = emptyMap(),
    ): Map<String, KeyBinding> {
        val indented = keysFragment.lines().joinToString("\n") { if (it.isBlank()) it else "      $it" }
        val yaml = "keyboard:\n  $section:\n    keys:\n$indented"
        return KeysConfigHelper.parseKeyboardYamlSection(yaml, section, presets) ?: emptyMap()
    }
}