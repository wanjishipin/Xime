package com.kingzcheung.xime.keyboard

import com.kingzcheung.xime.plugin.core.api.PluginIcon

/**
 * 工具栏按钮统一模型：内置按钮与插件声明的按钮合并后的渲染/分发单元。
 *
 * 展示链路：插件声明（manifest.toolbarButtons）→ 插件启用（插件中心）→
 * 用户勾选（toolbar_buttons 偏好 + ToolbarCustomizeView）→ 显示。
 * 插件被禁用/卸载后，偏好中残留的按钮 id 匹配不到任何项 → 自动不显示。
 */
sealed class ToolbarButtonItem {
    abstract val id: String
    abstract val label: String

    data class Builtin(val button: ToolbarButton) : ToolbarButtonItem() {
        override val id: String get() = button.id
        override val label: String get() = button.label
    }

    data class Plugin(
        override val id: String,
        override val label: String,
        val icon: PluginIcon?,
        val pluginId: String,
        val action: String,
    ) : ToolbarButtonItem()
}

/**
 * 按 id 解析工具栏按钮：内置枚举查不到时，从已启用插件的按钮匹配。
 * 两层显示控制：只有启用插件声明的按钮（[pluginItems] 候选池）才能被命中。
 */
fun resolveToolbarButtonItem(
    id: String,
    pluginItems: List<ToolbarButtonItem.Plugin>,
): ToolbarButtonItem? =
    ToolbarButton.fromId(id)?.let { ToolbarButtonItem.Builtin(it) }
        ?: pluginItems.find { it.id == id }