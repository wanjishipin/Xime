package com.kingzcheung.xime.plugin.core.config

/**
 * 声明式 UI 节点类型：设置表单字段（TEXT..BUTTON）与面板展示节点（SECTION/METRIC/DIVIDER）
 * 统一为一套，宿主按场景分派渲染：
 * - 设置页：表单型（输入/开关/选择/按钮，值读写 configStore）
 * - InfoPanel：展示型（SECTION 标题 / TEXT 文本 / METRIC 指标 / DIVIDER 分隔 / BUTTON 动作）；
 *   表单型节点在面板出现时降级为只读文本
 */
enum class UiNodeType {
    // ---- 表单字段（设置页） ----
    TEXT,
    TEXTAREA,
    SECRET,
    SELECT,
    MULTI_SELECT,
    SWITCH,
    NUMBER,
    /** 按钮：设置页触发 [IPluginConfigurable.onAction]，面板触发 onPanelAction（key 即 action id）。 */
    BUTTON,

    // ---- 面板展示（InfoPanel，dual 语义：value 由插件每次提供） ----
    /** 分组标题。 */
    SECTION,
    /** 指标行（label + value + unit?）。 */
    METRIC,
    /** 分隔线。 */
    DIVIDER,
}

/**
 * 统一声明式 UI 节点（令牌化，宿主渲染，插件给数据）。
 *
 * JS 契约字段与 Kotlin 一致；旧字段名（title/content/actionId/action）由解析层兼容
 * （仅新字段写入文档）。
 * - [key]：表单字段的 configStore key，或 BUTTON 的 action id
 * - [value]：当前值（面板由插件动态提供；设置页初始读 configStore[key]）
 * - [options]：SELECT / MULTI_SELECT 静态选项（空则宿主经 [IPluginConfigurable.getOptions] 动态拉取）
 * - [section]：设置页分组名
 * - [required]：设置页必填校验（SECRET 必填缺失时禁止保存）
 */
data class UiNode(
    val type: UiNodeType,
    val key: String? = null,
    val label: String? = null,
    val value: String? = null,
    val defaultValue: String? = null,
    val options: List<String> = emptyList(),
    val placeholder: String? = null,
    val helpText: String? = null,
    /** METRIC 单位。 */
    val unit: String? = null,
    /** 文本样式（如 "caption"）。 */
    val style: String? = null,
    val section: String? = null,
    val required: Boolean = false,
)

interface IPluginConfigurable {
    fun getSettingsSchema(): List<UiNode> = emptyList()

    /**
     * 动态选项：表单渲染 SELECT / MULTI_SELECT 时，若 [UiNode.options]
     * 为空则调用本方法异步拉取（插件自行实现，如模型列表等运行时接口数据）。
     * 返回 null 表示无动态选项。
     */
    fun getOptions(key: String): List<String>? = null

    /**
     * 处理表单 BUTTON 节点点击（key 见 [UiNode.key]）。
     *
     * 默认实现：把 [action] 当作插件 JS 导出的方法名调用，返回其返回值
     * （nil/空 = 成功，否则为错误消息）。
     *
     * @return null 表示成功；非 null 为错误消息（表单层提示用户）
     */
    suspend fun onAction(action: String): String? = null
}
