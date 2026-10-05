package com.kingzcheung.xime.plugin.core.js.ws

/**
 * 插件网络访问策略（纯函数，可单测）。
 *
 * 放行条件（满足其一）：
 * 1. 目标域名 ∈ 宿主可信池（官方域名，静默放行）
 * 2. 目标域名 ∈ 用户已授权的域名集合（authorizedHosts）——用户显式授权（插件中心
 *    手动授权 / 自定义服务器保存配置时自动授权）即用户明确信任，不要求同时声明
 *    （插件可能只声明 allowCustomHosts 而域名来自配置或手动授权，先卡声明会误伤）
 * 3. 目标域名 ∈ 插件 manifest 声明的域名（declaredHosts）
 *    或 ∈ 用户配置的自定义服务器域名（customHosts，allowCustomHosts 插件保存配置时自动授权）
 *    且 ∈ 用户已授权的域名集合（authorizedHosts）
 *
 * 否则拒绝——第三方插件必须声明域名并经用户授权才能联网。
 */
object NetworkPolicy {

    /** 校验 URL 是否允许访问。reason 为拒绝原因（null 表示放行）。 */
    fun check(
        url: String,
        trustedHosts: Set<String>,
        declaredHosts: List<String>,
        authorizedHosts: Set<String>,
        customHosts: Set<String> = emptySet()
    ): String? {
        val host = extractHost(url) ?: return "无法解析 URL: $url"
        if (host in trustedHosts) return null

        // 用户已授权 = 用户明确信任（授权只能经用户显式操作写入：手动授权按钮 /
        // 保存配置时从自定义服务器 URL 提取）。必须先放行再判声明，否则
        // "已授权但未声明"（插件只声明 allowCustomHosts、域名来自配置/手动授权）
        // 会被下面的声明检查误伤——真机实证：授权了 api.openai.com 仍报
        // "插件未声明访问域名"，用户困惑"明明已经授权了"。
        if (host in authorizedHosts) return null

        if (host !in declaredHosts && host !in customHosts) {
            return "插件未声明访问域名 $host，已阻止联网"
        }
        return "访问 $host 未获用户授权，请在插件中心授权"
    }

    /** 从 URL 提取域名（不含端口，纯字符串解析，无 Android 依赖）。 */
    fun extractHost(url: String): String? {
        var rest = url
        val schemeIdx = rest.indexOf("://")
        if (schemeIdx >= 0) rest = rest.substring(schemeIdx + 3)
        val pathIdx = rest.indexOf('/')
        if (pathIdx >= 0) rest = rest.substring(0, pathIdx)
        val queryIdx = rest.indexOf('?')
        if (queryIdx >= 0) rest = rest.substring(0, queryIdx)
        val host = rest.substringBefore(':')
        return host.takeIf { it.isNotBlank() }
    }

    /** 从配置值文本提取 HTTP(S) URL 的域名（非 http/https 值返回 null，供 UI 展示授权候选）。 */
    fun extractHttpHost(value: String): String? {
        val trimmed = value.trim()
        if (!trimmed.startsWith("http://") && !trimmed.startsWith("https://")) return null
        return extractHost(trimmed)
    }
}
