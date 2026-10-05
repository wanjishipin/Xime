package com.kingzcheung.xime.speech

import android.content.Context

/**
 * ASR 后端工厂。优先使用本地 zipformer2 离线后端（[AsrSupport]），
 * 不可用时由调用方回退到在线插件。
 */
object AsrBackendFactory {
    fun create(context: Context): AsrBackend? = AsrSupport.create(context)

    /** 本地引擎显示名。 */
    fun getLocalName(): String? = AsrSupport.getLocalName()

    /** 预热本地模型并保持常驻（打开"本地识别"开关时调用）。 */
    fun warmup(context: Context) = AsrSupport.warmup(context)

    /** 卸载常驻模型（关闭"本地识别"开关时调用）。 */
    fun releaseModel() = AsrSupport.releaseModel()
}
