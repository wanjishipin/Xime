package com.kingzcheung.xime.settings

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * xime-index（ximeiorg/xime-index）市场索引的数据模型。
 * 所有字段给默认值；解析时 strictMode=false 忽略未知键，对索引演进有韧性。
 */

@Serializable
data class MarketIndex(
    @SerialName("index_version") val indexVersion: Int = 1,
    @SerialName("updated_at") val updatedAt: String = "",
    val schemas: IndexRef? = null,
    val plugins: IndexRef? = null,
    val sources: List<IndexSource> = emptyList(),
)

@Serializable
data class IndexRef(val from: String = "")

@Serializable
data class IndexSource(
    val id: String = "",
    val name: String = "",
    val url: String = "",
    val description: String = "",
)

/**
 * 扁平索引格式：schemas 直接内联 MarketScheme 对象列表。
 * 对应 rimes/index.yaml（由 scripts/generate_index.py 生成）。
 */
@Serializable
data class SchemasDirectIndex(
    @SerialName("index_version") val indexVersion: Int = 1,
    @SerialName("updated_at") val updatedAt: String = "",
    val schemas: List<MarketScheme> = emptyList(),
)

@Serializable
data class SchemesSubIndex(
    @SerialName("index_version") val indexVersion: Int = 1,
    @SerialName("updated_at") val updatedAt: String = "",
    val schemas: List<SubIndexEntry> = emptyList(),
)

@Serializable
data class SubIndexEntry(val file: String = "", val version: String = "")

@Serializable
data class MarketScheme(
    val id: String = "",
    val name: String = "",
    val author: String = "",
    val description: String = "",
    val type: String = "remote",
    val tags: List<String> = emptyList(),
    val dependencies: List<String> = emptyList(),
    @SerialName("appVersion") val appVersion: String = "",
    @SerialName("currentVersion") val currentVersion: String = "",
    val versions: List<SchemeVersion> = emptyList(),
    val homepage: String = "",
    val license: String = "",
    val warning: String = "",
) {
    /** 当前应安装的版本：优先匹配 currentVersion，否则取第一条，都没有则 null。 */
    fun resolvedVersion(): SchemeVersion? =
        versions.firstOrNull { it.version == currentVersion } ?: versions.firstOrNull()
}

/** 下载条目（多文件时 downloadUrl 数组中的单条）。 */
@Serializable
data class DownloadItem(
    val url: String = "",
    val sha256: String? = null,
    val size: String? = null,
)

@Serializable
data class SchemeVersion(
    val version: String = "",
    val date: String = "",
    val changelog: String = "",
    @SerialName("downloadUrl")
    val downloadUrls: List<DownloadItem> = emptyList(),
    val size: String = "",
    val sha256: String = "",
)

/** 列表项 = 方案 + 运行期派生状态（不污染可序列化模型）。 */
data class MarketSchemeItem(
    val scheme: MarketScheme,
    val compatible: Boolean,
    val minAppVersion: String,
)
