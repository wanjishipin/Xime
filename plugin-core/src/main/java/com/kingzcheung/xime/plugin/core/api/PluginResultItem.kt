package com.kingzcheung.xime.plugin.core.api

/**
 * 插件候选结果实体——所有"候选项列表"的统一协议（emoji 表情、tool 工具面板通用）。
 *
 * JS 侧协议字段（adapter 解析）：
 * - `id`      必填，非空字符串；emoji 与 tool 均要求全表唯一（宿主 LazyColumn key）
 * - `text`    必填，非空字符串；显示文本与默认上屏文本
 * - `insertText` 可选，上屏文本覆盖（缺省 = text，如 meme-bunny 显示名称但插入 "[表情名]"）
 * - `imageUrl` 可选，图文项图片路径（emoji 通过 host.resource.path 得到）
 *
 * emoji 显示用 [text]，点击上屏 [insertText] ?: [text]；
 * tool 面板（ToolPanel/InfoPanel）显示与上屏均用 [text]。
 */
data class PluginResultItem(
    val id: String,
    val text: String,
    val insertText: String? = null,
    val imageUrl: String? = null,
)