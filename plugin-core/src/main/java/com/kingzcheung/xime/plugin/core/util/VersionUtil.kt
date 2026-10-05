package com.kingzcheung.xime.plugin.core.util

import android.content.Context

/**
 * 简单的版本号比较与插件兼容性判定。
 *
 * 版本号形如 "2.6.0-beta3"，比较时忽略预发布/构建后缀，仅取数字段（最多 4 段）逐位比较。
 */
object VersionUtil {

    /** 读取宿主 app 的 versionName。 */
    fun getHostVersionName(context: Context): String? {
        return try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        } catch (e: Exception) {
            null
        }
    }

    /** 判断宿主版本 [hostVersion] 是否落在插件声明的 [min]/[max] 范围内。min/max 为 null 表示不限。 */
    fun isHostSupported(hostVersion: String, min: String?, max: String?): Boolean {
        if (min.isNullOrBlank() && max.isNullOrBlank()) return true
        val host = parse(hostVersion) ?: return true
        if (!min.isNullOrBlank()) {
            val m = parse(min) ?: return true
            if (compare(host, m) < 0) return false
        }
        if (!max.isNullOrBlank()) {
            val m = parse(max) ?: return true
            if (compare(host, m) > 0) return false
        }
        return true
    }

    /** 比较两个版本号：a < b 返回负数，a > b 返回正数，相等返回 0。 */
    fun compare(a: String, b: String): Int {
        val pa = parse(a) ?: return 0
        val pb = parse(b) ?: return 0
        return compare(pa, pb)
    }

    private fun parse(v: String): IntArray? {
        val digits = v.split(Regex("[.\\-+]"))
            .filter { it.isNotEmpty() && it.all(Char::isDigit) }
        if (digits.isEmpty()) return null
        return IntArray(4) { i -> digits.getOrNull(i)?.toIntOrNull() ?: 0 }
    }

    private fun compare(a: IntArray, b: IntArray): Int {
        for (i in 0 until 4) {
            if (a[i] != b[i]) return if (a[i] > b[i]) 1 else -1
        }
        return 0
    }
}
