package com.kingzcheung.xime.plugin.core.model

/** 插件信任等级：Lua 插件无签名，由插件中心按来源与用户授权展示信任标记。 */
enum class TrustLevel {
    /** 官方：与宿主来源一致 */
    TRUSTED,

    /** 第三方：非官方来源，需用户确认 */
    THIRD_PARTY,

    /** 未知：无法判定 */
    UNKNOWN
}

data class PluginInfo(
    val id: String,
    val name: String,
    val iconResId: Int,
    val versionCode: Long,
    val versionName: String,
    val path: String,
    val description: String,
    val type: String = "unknown",
    val enabled: Boolean = true,
    val installTime: Long = System.currentTimeMillis(),
    val source: PluginSource = PluginSource.SYSTEM,
    val minHostVersion: String? = null,
    val maxHostVersion: String? = null,
    val trustLevel: TrustLevel = TrustLevel.UNKNOWN,
    /** JS 入口脚本路径（相对插件包目录）。插件逻辑全部由该脚本导出。 */
    val entryScript: String? = null,
    /** 插件声明需要访问的域名（manifest.network.hosts）。联网时需命中可信池或获用户授权。 */
    val declaredHosts: List<String> = emptyList(),
    /** 是否接受用户自定义服务器地址（manifest.network.allowCustomHosts）。仅作能力声明：
     *  用户在此类插件配置中填写的服务器域名会在保存配置时自动获得联网授权，
     *  也可在插件中心「网络访问」区块手动授权/撤销。 */
    val allowCustomHosts: Boolean = false,
    /** 插件声明的工具栏按钮（manifest.toolbarButtons）。宿主据此在工具栏渲染插件入口。 */
    val toolbarButtons: List<PluginToolbarButton> = emptyList(),
    /** manifest 顶层 icon 原文：文字（如 "译"）或 resources/ 下图片文件名。工具栏按钮无专属图标时兜底。 */
    val manifestIcon: String? = null,
    /** manifest.capabilities 能力声明（emoji/speech/tool/clipboard_sync 各类型）。宿主消费能力的唯一来源。 */
    val capabilities: PluginCapabilities? = null,
    /** 目标平台声明（manifest.platforms；缺省视为 android，与存量插件行为一致）。宿主按平台门禁加载。 */
    val platforms: List<String> = listOf(PLATFORM_ANDROID),
) {
    val version: String get() = versionName
    val category: PluginCategory get() = PluginCategory.fromId(type)

    /** 是否面向指定平台（platforms 缺省视为仅 android）。 */
    fun supportsPlatform(platform: String): Boolean = platform in platforms

    companion object {
        /** 宿主平台标识（platforms 缺省值；加载门禁以此判定）。 */
        const val PLATFORM_ANDROID = "android"
    }
}

/**
 * 插件声明的工具栏按钮。
 *
 * @param id     全局限定 id（建议 `pluginId:action` 形式，如 `com.kingzcheung.xime.plugin.ai_reply`），
 *               贯穿偏好存储/匹配/分发全链路；不允许包含逗号（偏好存储按逗号分隔）
 * @param label  显示文字标签（图标缺失或加载失败时的兜底文本），默认取 id
 * @param icon   图标资源文件名（插件包 resources/ 下相对路径），可为空
 * @param action 点击行为标识，宿主按需分发（当前仅 `open_panel`：打开该插件的通用面板）
 */
data class PluginToolbarButton(
    val id: String,
    val label: String = "",
    val icon: String? = null,
    val action: String = "open_panel"
)
