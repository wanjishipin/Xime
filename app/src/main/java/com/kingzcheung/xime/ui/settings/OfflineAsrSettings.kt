package com.kingzcheung.xime.ui.settings

import androidx.compose.runtime.Composable

/**
 * 离线语音设置区块。
 * 离线 ASR 已集成进主版本，始终支持。
 */
object OfflineAsrSettings {

    /** 当前构建是否包含离线语音功能 */
    fun isSupported(): Boolean = true

    /** 本地/在线引擎切换开关。 */
    @Composable
    fun EngineSelector(
        useLocal: Boolean,
        onUseLocalChange: (Boolean) -> Unit
    ) {
        OfflineAsrSettingsSupport.EngineSelector(useLocal, onUseLocalChange)
    }

    /** 本地模型下载/管理卡片，仅在开启本地识别时渲染。未安装时点击跳转 [onNavigateToDownload]。 */
    @Composable
    fun ModelSection(onNavigateToDownload: () -> Unit = {}) {
        OfflineAsrSettingsSupport.ModelSection(onNavigateToDownload)
    }
}
