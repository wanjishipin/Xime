package com.kingzcheung.xime.plugin.core.js

import com.kingzcheung.xime.plugin.core.api.EmojiPlugin
import com.kingzcheung.xime.plugin.core.api.EmojiQuery
import com.kingzcheung.xime.plugin.core.api.PluginResultItem
import com.kingzcheung.xime.plugin.core.js.sdk.JsPluginContract
import com.kingzcheung.xime.plugin.core.model.PluginContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** emoji 类型 JS 插件的宿主侧适配器：实现 EmojiPlugin 接口。 */
class JsEmojiPluginAdapter(
    runtime: JsScriptRuntime,
    pluginContext: PluginContext
) : JsPluginAdapter(runtime, pluginContext), EmojiPlugin {

    override suspend fun getEmojis(query: EmojiQuery): List<PluginResultItem> =
        withContext(Dispatchers.IO) {
            val result = runtime.call(
                JsPluginContract.PATH_EMOJI_QUERY,
                mapOf(
                    "category" to (query.category ?: ""),
                    "keyword" to (query.keyword ?: ""),
                    "topK" to query.topK
                )
            )
            parseResultItems(result, "getEmojis 返回")
        }

    override suspend fun getCategories(): List<String> = withContext(Dispatchers.IO) {
        val result = runtime.call(JsPluginContract.PATH_EMOJI_CATEGORIES)
        val list = JsScriptRuntime.jsToKotlin(result) as? List<*>
        list?.mapNotNull { it?.toString() } ?: emptyList()
    }
}