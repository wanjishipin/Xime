package com.kingzcheung.xime.plugin.core.api

import com.kingzcheung.xime.plugin.core.config.IPluginConfigurable

/**
 * 工具面板结果显示方式：宿主按插件元数据（manifest.capabilities.tool.display）决策
 * 结果交互（直接上屏 or 纯展示面板），插件侧不再返回。
 * manifest 取值小写：`direct` | `passive`（与 inputMode 风格一致）。
 */
enum class ToolResult {
    /** 结果生成结束直接上屏（如 AI 翻译）。 */
    DIRECT,

    /**
     * 纯展示面板（InfoPanel）：无输入框、无生成动作（enter 不触发 generate）、
     * 点击节点不上屏。插件通过 [ToolPanelState.ui] 声明式描述内容
     * （统一 [com.kingzcheung.xime.plugin.core.config.UiNode] 白名单节点）；
     * 多条候选通过 [ToolPanelState.items] 返回，宿主渲染为点击上屏条目
     * （替代原 SELECT 全屏结果页，如 AI 智能回复）。
     */
    PASSIVE,
}

/**
 * 工具面板状态（宿主渲染、插件给数据）。
 */
data class ToolPanelState(
    val inputText: String = "",
    val items: List<PluginResultItem> = emptyList(),
    /** 是否正在生成中（SSE 流式期间为 true，宿主据此展示 loading 并轮询刷新）。 */
    val loading: Boolean = false,
    /**
     * passive 纯展示节点树（声明式 UI，统一模型 [com.kingzcheung.xime.plugin.core.config.UiNode]）。
     * 面板展示白名单：SECTION / TEXT / METRIC / DIVIDER / BUTTON；
     * 未知类型节点由解析层丢弃，渲染层降级为文本兜底。
     */
    val ui: List<com.kingzcheung.xime.plugin.core.config.UiNode>? = null,
)

/**
 * 工具类插件（`type: tool`）契约：宿主渲染通用面板（ToolPanel/InfoPanel），插件提供数据与事件处理。
 *
 * ## 返回协议（宿主强制校验：非法数据将被丢弃并输出协议错误日志）
 *
 * [getPanelState] 必须返回 JS 对象，字段：
 * - `items`（必填，数组）：候选结果，每个元素 `{ id: string, text: string, insertText?: string, imageUrl?: string }`；
 *   `id` 必须非空且全表唯一，`text` 必须非空，否则该元素被宿主丢弃。
 *   多候选（多条回复）时建议 id 用序号；单条时可用固定值（如 `"result"`）。
 *   display=passive 时宿主将 items 渲染为 InfoPanel 内点击上屏条目；
 *   display=direct 时生成结束后 items[0] 直接上屏。
 * - `loading`（布尔）：生成期间为 true，宿主据此轮询刷新直至 false。
 * - `inputText`（字符串，可选）：面板输入框内容回显。缺省（未返回该字段或非字符串）
 *   = 沿用宿主传入的上下文；返回空串 = 明确要求空输入框（插件拒绝预填，
 *   如翻译类插件不希望剪贴板内容被自动填入）。适配器已把"缺省"解析为宿主上下文，
 *   宿主不再做 isNotBlank 兜底。
 * - `ui`（数组，可选）：声明式控件/展示节点树。
 *   节点 = 统一 [com.kingzcheung.xime.plugin.core.config.UiNode] 结构
 *   `{ type: string, key?, label?, value?, options?, ... }`。
 *   display=passive：宿主在 InfoPanel 渲染展示型白名单
 *   `section` / `text` / `metric` / `divider` / `button`，表单型节点降级为只读文本。
 *   display=direct：宿主在输入框下方渲染控件行，v1 白名单：
 *   `text`（带 key=可输入框，value 为初始内容，之后以用户输入为准；
 *   不带 key=只读文本）/ `select`（options 静态来自节点，value 为当前值，
 *   插件是权威——按钮动作重拉后以回传 value 为准）/ `button`（key 即 action id，
 *   点击触发 [onPanelAction]，宿主随后重拉 [getPanelState] 刷新 ui）/
 *   `section`（小标题）/ `divider`（竖分隔）；其余类型 v1 忽略。
 *   字段变更（文本输入、选择）实时经 [onPanelInput](key, value) 通知插件，
 *   插件自行保存状态；主输入框的 key 为空串。
 *   旧字段名（title/content/actionId、action→button）解析层兼容。
 *
 * 传输方式（同步 HTTP / SSE 流式）与结果呈现由插件元数据声明，宿主只消费上述结构化数据。
 *
 * - [getPanelState]：返回面板状态（输入框内容 + 候选列表）
 * - [onPanelInput]：面板字段内容变化通知（key 为空串 = 主输入框；其余为 ui 节点 key）
 * - [onPanelAction]：`generate` 触发生成 / ui 控件行 button 的 key（宿主保留 action id）
 * - [onPanelItemClick]：点候选 → 宿主上屏（host 负责选区替换/追加）
 */
interface ToolPlugin : IPluginEntryClass, IPluginConfigurable {
    fun getPanelState(inputText: String): ToolPanelState

    fun onPanelInput(key: String, value: String) {}

    fun onPanelAction(actionId: String) {}

    fun onPanelItemClick(itemId: String) {}
}