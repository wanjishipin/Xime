package com.kingzcheung.xime.plugin.core.js

import com.kingzcheung.xime.plugin.core.api.IPluginEntryClass
import com.kingzcheung.xime.plugin.core.api.PluginIcon
import com.kingzcheung.xime.plugin.core.config.IPluginConfigurable
import com.kingzcheung.xime.plugin.core.config.UiNode
import com.kingzcheung.xime.plugin.core.config.UiNodeType
import com.kingzcheung.xime.plugin.core.js.sdk.JsPluginContract
import com.kingzcheung.xime.plugin.core.model.PluginContext

/**
 * JS 脚本插件的宿主侧适配器基类：实现通用接口（入口生命周期 + 设置项 + 图标），
 * 具体能力接口（EmojiPlugin / AsrPlugin）由按插件类别派生的子类实现，
 * 保证 `instance is AsrPlugin` 只对 speech 类型插件成立。
 *
 * 值模型为纯 Kotlin 结构（Map/List/基础类型），由 [JsScriptRuntime] 负责
 * JS ↔ Kotlin 转换；本类只做协议校验与 UiNode 解析。
 */
open class JsPluginAdapter(
    protected val runtime: JsScriptRuntime,
    protected val pluginContext: PluginContext
) : IPluginEntryClass, IPluginConfigurable {

    override fun getSettingsSchema(): List<UiNode> {
        val result = runtime.call(JsPluginContract.PATH_SETTINGS_SCHEMA)
        val list = (JsScriptRuntime.jsToKotlin(result) as? List<*>)
            ?: return emptyList()
        // 设置字段必须带 key（configStore 绑定）；无 key 的展示节点由渲染层容忍
        return parseUiNodes(list).filter { !it.key.isNullOrBlank() }
    }

    override suspend fun onAction(action: String): String? {
        if (action.isBlank()) return "未知操作"
        val result = runtime.callAsync(action)
        val v = JsScriptRuntime.jsToKotlin(result)
        val msg = when (v) {
            null -> null
            is Map<*, *> -> v["message"]?.toString()
            else -> v.toString()
        }
        return msg?.takeIf { it.isNotBlank() }
    }

    override fun getOptions(key: String): List<String>? {
        val result = runtime.call(JsPluginContract.PATH_SETTINGS_OPTIONS, key)
        val list = JsScriptRuntime.jsToKotlin(result) as? List<*>
            ?: return null
        return list.mapNotNull { it?.toString() }
    }

    /**
     * 统一声明式 UI 节点解析（设置表单 getSettingsSchema 与面板 getPanelState.ui 共用契约）。
     * 节点 = { type, key?, label?, value?, defaultValue?, options?, placeholder?, helpText?,
     *   unit?, style?, section?, required? }。
     */
    protected fun parseUiNodes(value: List<*>): List<UiNode> {
        val nodes = ArrayList<UiNode>()
        for (node in value) {
            val m = JsScriptRuntime.jsToKotlin(node) as? Map<*, *> ?: continue
            nodes += UiNode(
                type = parseUiNodeType(m["type"]?.toString()),
                key = (m["key"] ?: m["actionId"] ?: m["action"])?.toString()?.takeIf { it.isNotBlank() },
                label = (m["label"] ?: m["title"])?.toString(),
                value = (m["value"] ?: m["content"])?.toString(),
                defaultValue = m["defaultValue"]?.toString(),
                options = stringList(m["options"]),
                placeholder = m["placeholder"]?.toString(),
                helpText = m["helpText"]?.toString(),
                unit = m["unit"]?.toString(),
                style = m["style"]?.toString(),
                section = m["section"]?.toString(),
                required = (m["required"] as? Boolean) ?: false,
            )
            if (nodes.size >= MAX_UI_NODES) break
        }
        return nodes
    }

    private fun parseUiNodeType(type: String?): UiNodeType = when (type?.lowercase()) {
        "textarea" -> UiNodeType.TEXTAREA
        "secret" -> UiNodeType.SECRET
        "select" -> UiNodeType.SELECT
        "multi_select" -> UiNodeType.MULTI_SELECT
        "switch" -> UiNodeType.SWITCH
        "number" -> UiNodeType.NUMBER
        "action", "button" -> UiNodeType.BUTTON // 旧契约 "action" 兼容
        "section" -> UiNodeType.SECTION
        "metric" -> UiNodeType.METRIC
        "divider" -> UiNodeType.DIVIDER
        else -> UiNodeType.TEXT // 未知 type 降级为普通文本
    }

    protected fun stringList(value: Any?): List<String> {
        val list = JsScriptRuntime.jsToKotlin(value) as? List<*> ?: return emptyList()
        return list.mapNotNull { it?.toString() }
    }

    override fun getIcon(): PluginIcon? {
        val result = runtime.call(JsPluginContract.PATH_EMOJI_ICON)
        val map = JsScriptRuntime.jsToKotlin(result) as? Map<*, *> ?: return null
        val text = map["text"]?.toString()?.takeIf { it.isNotBlank() }
        if (text != null) return PluginIcon(text = text)
        val assetName = map["assetName"]?.toString()
            ?.takeIf {
                it.isNotBlank() && com.kingzcheung.xime.plugin.core.runtime.installer.InstallerManager.isValidResourcePath(it)
            }
        if (assetName != null) return PluginIcon(assetName = assetName)
        return null
    }

    override fun onLoad(context: PluginContext) {
        if (runtime.load()) {
            runtime.callOnLoad()
        }
    }

    override fun onUnload() {
        runtime.close()
    }

    /**
     * 通用配置就绪判定：所有 required 配置字段均已有值。
     */
    open fun isConfigured(): Boolean {
        val schema = getSettingsSchema()
        if (schema.isEmpty()) return true
        return schema.none { it.required && it.key != null && pluginContext.configStore.get(it.key).isNullOrBlank() }
    }

    /**
     * 统一的候选项解析（emoji 与 tool 的 items 共用协议 `{id, text, insertText?, imageUrl?}`）。
     */
    protected fun parseResultItems(value: Any?, what: String): List<com.kingzcheung.xime.plugin.core.api.PluginResultItem> {
        val raw = JsScriptRuntime.jsToKotlin(value) as? List<*> ?: return emptyList()
        val items = ArrayList<com.kingzcheung.xime.plugin.core.api.PluginResultItem>()
        val seenIds = HashSet<String>()
        for (entry in raw) {
            val m = JsScriptRuntime.jsToKotlin(entry) as? Map<*, *>
            if (m == null) {
                protocolWarn("$what 元素必须是对象，已丢弃")
                continue
            }
            val id = m[JsPluginContract.FIELD_ID]?.toString()?.takeIf { it.isNotBlank() }
            val text = m[JsPluginContract.FIELD_TEXT]?.toString()?.takeIf { it.isNotBlank() }
            if (id == null || text == null) {
                protocolWarn("$what 元素缺少非空 id/text（协议要求 { id, text }），已丢弃")
                continue
            }
            if (!seenIds.add(id)) {
                protocolWarn("$what 元素 id 重复（'$id'），已丢弃重复项")
                continue
            }
            items += com.kingzcheung.xime.plugin.core.api.PluginResultItem(
                id = id,
                text = text,
                insertText = m[JsPluginContract.FIELD_INSERT_TEXT]?.toString()?.takeIf { it.isNotBlank() },
                imageUrl = m[JsPluginContract.FIELD_IMAGE_URL]?.toString()?.takeIf { it.isNotBlank() },
            )
        }
        return items
    }

    /** 协议违规统一告警：Log.w 级别（不随调用链抛出，避免拖垮宿主轮询），tag 含插件 id。 */
    protected fun protocolWarn(message: String) {
        android.util.Log.w("PluginProtocol", "[${pluginContext.pluginId}] $message")
    }

    companion object {
        private const val MAX_UI_NODES = 64
    }
}