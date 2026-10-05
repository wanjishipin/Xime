package com.kingzcheung.xime.settings

import android.content.Context
import android.content.SharedPreferences

/**
 * 扩展商店的本地版本号记录：方案/模型 下载时写入实际版本，删除时清除。
 * 插件不走这里：已安装版本直接来自 PluginManager 注册表（PluginInfo.versionName）。
 */
object MarketVersionStore {
    private const val PREFS_NAME = "market_installed_versions"
    private const val KEY_PREFIX_SCHEME = "scheme:"
    private const val KEY_PREFIX_MODEL = "model:"

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /* ─────────── 方案 ─────────── */

    fun getSchemeVersion(context: Context, schemeId: String): String? =
        prefs(context).getString(KEY_PREFIX_SCHEME + schemeId, null)

    /** 全部已下载方案：schemeId → version。 */
    fun getAllSchemeVersions(context: Context): Map<String, String> =
        prefs(context).all.entries
            .filter { it.key.startsWith(KEY_PREFIX_SCHEME) }
            .associate { it.key.removePrefix(KEY_PREFIX_SCHEME) to (it.value as? String).orEmpty() }
            .filterValues { it.isNotBlank() }

    fun setSchemeVersion(context: Context, schemeId: String, version: String) {
        prefs(context).edit().putString(KEY_PREFIX_SCHEME + schemeId, version).apply()
    }

    fun removeSchemeVersion(context: Context, schemeId: String) {
        prefs(context).edit().remove(KEY_PREFIX_SCHEME + schemeId).apply()
    }

    /* ─────────── 模型 ─────────── */

    fun getModelVersion(context: Context, modelId: String): String? =
        prefs(context).getString(KEY_PREFIX_MODEL + modelId, null)

    /** 全部已下载模型：modelId → version。 */
    fun getAllModelVersions(context: Context): Map<String, String> =
        prefs(context).all.entries
            .filter { it.key.startsWith(KEY_PREFIX_MODEL) }
            .associate { it.key.removePrefix(KEY_PREFIX_MODEL) to (it.value as? String).orEmpty() }
            .filterValues { it.isNotBlank() }

    fun setModelVersion(context: Context, modelId: String, version: String) {
        prefs(context).edit().putString(KEY_PREFIX_MODEL + modelId, version).apply()
    }

    fun removeModelVersion(context: Context, modelId: String) {
        prefs(context).edit().remove(KEY_PREFIX_MODEL + modelId).apply()
    }
}
