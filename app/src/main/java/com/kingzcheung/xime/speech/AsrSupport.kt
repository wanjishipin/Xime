package com.kingzcheung.xime.speech

import android.content.Context
import com.kingzcheung.xime.util.FileLogger

/**
 * 本地 zipformer2 离线识别后端支持。
 *
 * 模型生命周期：
 * - 打开"使用本地模型"开关时 [warmup] 加载模型并常驻 :asr 服务；
 * - 语音时 [create] 复用该常驻后端，绝不现场加载模型；
 * - 关闭开关时 [releaseModel] 卸载并解绑服务。
 */
internal object AsrSupport {

    private const val TAG = "AsrSupport"

    @Volatile
    private var warmBackend: OfflineAsrBackend? = null

    /** 返回本地后端。若已预热则复用常驻实例，否则临时创建（模型未就绪时由调用方处理）。 */
    fun create(context: Context): AsrBackend? {
        val resident = warmBackend
        if (resident != null) {
            return resident
        }
        return OfflineAsrBackend(context)
    }

    fun getLocalName(): String? = "本地 Zipformer"

    /** 加载本地模型并保持 :asr 服务常驻，直到 [releaseModel]。 */
    fun warmup(context: Context) {
        synchronized(this) {
            if (warmBackend != null) return
            val backend = OfflineAsrBackend(context.applicationContext)
            if (!backend.initialize()) {
                FileLogger.e(TAG, "warmup failed")
                return
            }
            warmBackend = backend
            FileLogger.i(TAG, "offline ASR model warmed up and resident")
        }
    }

    /** 卸载常驻模型并解绑 :asr 服务。 */
    fun releaseModel() {
        synchronized(this) {
            val backend = warmBackend
            warmBackend = null
            backend?.releaseModel()
            FileLogger.i(TAG, "offline ASR model released")
        }
    }
}
