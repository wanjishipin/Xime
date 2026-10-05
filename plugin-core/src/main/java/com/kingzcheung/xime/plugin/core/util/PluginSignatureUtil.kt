package com.kingzcheung.xime.plugin.core.util

import com.kingzcheung.xime.plugin.core.model.PluginSource
import com.kingzcheung.xime.plugin.core.model.TrustLevel

/**
 * 插件信任等级判定。
 *
 * JS 脚本插件无 APK 签名，信任由来源判定：
 * 内置（ASSET / SYSTEM）视为官方（TRUSTED），用户导入（FILE）视为第三方（THIRD_PARTY）。
 * 后续可扩展为"脚本哈希白名单"判定官方插件。
 */
object PluginSignatureUtil {

    /**
     * JS 脚本插件信任判定：按插件来源分类，
     * 内置插件（随宿主分发）标记为官方，用户导入的插件标记为第三方。
     */
    fun classifyScriptPlugin(source: PluginSource): TrustLevel {
        return when (source) {
            PluginSource.SYSTEM, PluginSource.ASSET,
            PluginSource.REMOTE -> TrustLevel.TRUSTED
            PluginSource.FILE -> TrustLevel.THIRD_PARTY
        }
    }
}
