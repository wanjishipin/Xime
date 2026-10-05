package com.kingzcheung.xime.plugin.core.api

import android.content.Context
import com.kingzcheung.xime.plugin.core.model.PluginContext

data class PluginIcon(
    val text: String? = null,
    val assetName: String? = null
)

/**
 * emoji 表情查询入参（宿主表情页 UI 状态 → 插件）。
 * 位置参数改为单 table 入参，扩展查询维度（排序/过滤）不破坏契约。
 */
data class EmojiQuery(
    val category: String? = null,
    val keyword: String? = null,
    val topK: Int = 100,
)

interface EmojiPlugin : IPluginEntryClass {

    override fun onLoad(context: PluginContext)

    override fun onUnload()

    /**
     * 查询表情候选项。宿主按分类页/搜索框状态组装 [EmojiQuery]。
     * 输出统一为 [PluginResultItem] 列表（`{id, text, insertText?, imageUrl?}`）。
     */
    suspend fun getEmojis(query: EmojiQuery): List<PluginResultItem>

    suspend fun getCategories(): List<String>

    override fun hasSettings(): Boolean = false

    override fun openSettings(context: Context) {}
}