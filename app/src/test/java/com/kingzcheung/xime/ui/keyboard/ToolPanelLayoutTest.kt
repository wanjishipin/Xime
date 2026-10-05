package com.kingzcheung.xime.ui.keyboard

import com.kingzcheung.xime.plugin.core.config.UiNode
import com.kingzcheung.xime.plugin.core.config.UiNodeType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 工具面板布局纯函数：内容自适应高度、控件行白名单、select 选项约定。
 * 面板高度与 service 容器撑高共用 [toolPanelHeightDp]，改常量需同步核对本测试。
 */
class ToolPanelLayoutTest {

    @Test
    fun `面板高度贴合内容`() {
        // 装饰 72 + 输入条 48 = 120（原固定 170，内容贴合后回收空白）
        assertEquals(120, toolPanelHeightDp(hasControls = false))
        // 控件行 +56
        assertEquals(176, toolPanelHeightDp(hasControls = true))
        // loading 指示在标题栏，不影响面板高度
        assertEquals(120, toolPanelHeightDp(hasControls = false))
    }

    @Test
    fun `面板高度有上限`() {
        assertTrue(toolPanelHeightDp(hasControls = true) <= TOOL_PANEL_MAX_HEIGHT_DP)
    }

    @Test
    fun `控件行只渲染白名单节点`() {
        val nodes = listOf(
            UiNode(type = UiNodeType.TEXT, key = "src", label = "输入"),
            UiNode(type = UiNodeType.SELECT, key = "lang", label = "语言", options = listOf("自动检测|auto", "English")),
            UiNode(type = UiNodeType.BUTTON, key = "swap", label = "⇄"),
            UiNode(type = UiNodeType.SECTION, label = "分组"),
            UiNode(type = UiNodeType.DIVIDER),
            // v1 未支持类型应被过滤
            UiNode(type = UiNodeType.SWITCH, key = "sw", label = "开关"),
            UiNode(type = UiNodeType.NUMBER, key = "n", label = "数字"),
            UiNode(type = UiNodeType.METRIC, label = "字数", value = "100"),
        )
        val visible = filterDirectPanelNodes(nodes)
        assertEquals(
            listOf(UiNodeType.TEXT, UiNodeType.SELECT, UiNodeType.BUTTON, UiNodeType.SECTION, UiNodeType.DIVIDER),
            visible.map { it.type },
        )
    }

    @Test
    fun `select 选项 label value 约定`() {
        // "label|value"：显示 label，回传 value
        assertEquals("自动检测" to "auto", parseSelectOption("自动检测|auto"))
        // 无 | 时显示与值相同
        assertEquals("English" to "English", parseSelectOption("English"))
    }
}
