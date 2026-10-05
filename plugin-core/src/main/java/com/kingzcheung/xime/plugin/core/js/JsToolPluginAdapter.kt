package com.kingzcheung.xime.plugin.core.js

import com.kingzcheung.xime.plugin.core.api.PluginResultItem
import com.kingzcheung.xime.plugin.core.api.ToolPanelState
import com.kingzcheung.xime.plugin.core.api.ToolPlugin
import com.kingzcheung.xime.plugin.core.js.sdk.JsPluginContract
import com.kingzcheung.xime.plugin.core.model.PluginContext

/**
 * tool 类型 JS 插件的宿主侧适配器：实现 [ToolPlugin] 接口。
 *
 * 宿主强制协议校验：插件返回的 items 不符合协议时非法数据被丢弃并输出协议错误日志；
 * 宿主 UI 消费的永远是协议合规数据。
 */
class JsToolPluginAdapter(
    runtime: JsScriptRuntime,
    pluginContext: PluginContext
) : JsPluginAdapter(runtime, pluginContext), ToolPlugin {

    override fun getPanelState(inputText: String): ToolPanelState {
        val result = runtime.callAsync(JsPluginContract.PATH_PANEL_STATE, mapOf("inputText" to inputText))
        val map = JsScriptRuntime.jsToKotlin(result) as? Map<*, *>
        if (map == null) {
            protocolWarn("getPanelState 必须返回对象（当前为 ${result?.javaClass?.simpleName ?: "null"}），已按空状态处理")
            return ToolPanelState(inputText = inputText)
        }
        // inputText 契约见 inputTextFromState
        return ToolPanelState(
            inputText = inputTextFromState(map["inputText"], inputText),
            items = parseResultItems(map["items"], "getPanelState.items"),
            loading = (map["loading"] as? Boolean) ?: false,
            ui = parseUiNodes(stringListForUi(map["ui"])),
        )
    }

    private fun stringListForUi(value: Any?): List<*> {
        return JsScriptRuntime.jsToKotlin(value) as? List<*> ?: emptyList<Any>()
    }

    override fun onPanelInput(key: String, value: String) {
        runtime.call(JsPluginContract.PATH_PANEL_ON_INPUT, mapOf("key" to key, "value" to value))
    }

    override fun onPanelAction(actionId: String) {
        runtime.callAsync(JsPluginContract.PATH_PANEL_ON_ACTION, mapOf("actionId" to actionId))
    }

    override fun onPanelItemClick(itemId: String) {
        runtime.call(JsPluginContract.PATH_PANEL_ON_ITEM_CLICK, mapOf("itemId" to itemId))
    }

    companion object {
        /**
         * inputText 契约解析：插件未返回该字段或返回非字符串 → 沿用宿主上下文（hostInput）；
         * 返回空串 → 明确要求空输入框（如翻译插件拒绝剪贴板预填），宿主不得再用上下文兜底。
         * 纯函数抽出以便引擎无关的单测。
         */
        @JvmStatic
        fun inputTextFromState(raw: Any?, hostInput: String): String =
            (raw as? String) ?: hostInput
    }
}