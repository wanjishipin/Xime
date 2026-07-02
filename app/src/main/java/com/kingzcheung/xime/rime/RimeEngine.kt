package com.kingzcheung.xime.rime

import android.util.Log

data class RimeCandidate(
    val text: String,
    val comment: String
)

/**
 * 批量查询当前 composition 状态。
 *
 * 通过 JNI 一次性返回 input/preedit/commit/candidates/paging/ascii_mode，
 * 避免 updateUI 中多次独立 JNI 调用带来的固定开销。
 */
data class RimeComposition(
    val input: String,
    val preedit: String,
    val committedText: String,
    val candidates: Array<RimeCandidate>,
    val hasNextPage: Boolean,
    val hasPrevPage: Boolean,
    val isAsciiMode: Boolean
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is RimeComposition) return false
        return input == other.input &&
                preedit == other.preedit &&
                committedText == other.committedText &&
                candidates.contentEquals(other.candidates) &&
                hasNextPage == other.hasNextPage &&
                hasPrevPage == other.hasPrevPage &&
                isAsciiMode == other.isAsciiMode
    }

    override fun hashCode(): Int {
        var result = input.hashCode()
        result = 31 * result + preedit.hashCode()
        result = 31 * result + committedText.hashCode()
        result = 31 * result + candidates.contentHashCode()
        result = 31 * result + hasNextPage.hashCode()
        result = 31 * result + hasPrevPage.hashCode()
        result = 31 * result + isAsciiMode.hashCode()
        return result
    }
}

data class RimeProcessResult(
    val processed: Boolean,
    val committedText: String,
    val inputText: String,
    val preeditText: String,
    val candidates: Array<RimeCandidate>,
    val isAsciiMode: Boolean,
    val hasNextPage: Boolean,
    val hasPrevPage: Boolean
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is RimeProcessResult) return false
        return processed == other.processed &&
                committedText == other.committedText &&
                inputText == other.inputText &&
                preeditText == other.preeditText &&
                candidates.contentEquals(other.candidates) &&
                isAsciiMode == other.isAsciiMode &&
                hasNextPage == other.hasNextPage &&
                hasPrevPage == other.hasPrevPage
    }

    override fun hashCode(): Int {
        var result = processed.hashCode()
        result = 31 * result + committedText.hashCode()
        result = 31 * result + inputText.hashCode()
        result = 31 * result + preeditText.hashCode()
        result = 31 * result + candidates.contentHashCode()
        result = 31 * result + isAsciiMode.hashCode()
        result = 31 * result + hasNextPage.hashCode()
        result = 31 * result + hasPrevPage.hashCode()
        return result
    }
}

class RimeEngine {

    companion object {
        private const val TAG = "RimeEngine"
        private var instance: RimeEngine? = null
        private var deploymentCallback: ((Boolean, String) -> Unit)? = null

        /** 全局 Rime 引擎锁 — 所有 native 调用必须通过此锁同步 */
        val rimeLock = Any()

        init {
            System.loadLibrary("rime_jni")
            Log.d(TAG, "Native library loaded")
        }

        fun getInstance(): RimeEngine {
            return instance ?: synchronized(this) {
                instance ?: RimeEngine().also { instance = it }
            }
        }

        fun isInitialized(): Boolean = instance?.isInitialized ?: false

        /**
         * 检查指定的 Rime 模块是否已注册（用于验证插件集成）
         */
        fun isModuleRegistered(moduleName: String): Boolean {
            val engine = instance ?: return false
            if (!engine.isInitialized) return false
            return engine.nativeIsModuleRegistered(moduleName)
        }

        fun setDeploymentCallback(callback: (isDeploying: Boolean, message: String) -> Unit) {
            deploymentCallback = callback
        }
    }

    private var isInitialized = false
    private val initLock = Any()

    private fun notifyDeploymentStatus(isDeploying: Boolean, message: String) {
        deploymentCallback?.invoke(isDeploying, message)
    }

    fun initialize(userDataDir: String, sharedDataDir: String) {
        if (!isInitialized) {
            synchronized(initLock) {
                if (!isInitialized) {
                    try {
                        Log.d(TAG, "Initializing Rime: userDataDir=$userDataDir, sharedDataDir=$sharedDataDir")

                        notifyDeploymentStatus(true, "正在加载输入法引擎...")
                        nativeInitialize(userDataDir, sharedDataDir)
                        isInitialized = true

                        // 参考 trime: startup 只初始化引擎，不创建 session
                        // session 在第一次使用时延迟创建（ensureSession）
                        // 部署在后台异步运行，不阻塞

                        notifyDeploymentStatus(false, "")
                        Log.d(TAG, "Rime engine initialized (session deferred)")
                    } catch (e: Exception) {
                        Log.e(TAG, "Error during Rime initialization", e)
                        notifyDeploymentStatus(false, "初始化失败")
                    }
                }
            }
        }
    }

    fun ensureSession(timeoutMs: Long = 60000L): Boolean {
        if (!isInitialized) return false

        // Quick check without lock — native calls are thread-safe reads
        if (nativeHasSession() && getAvailableSchemas().isNotEmpty()) return true

        // 等待编译完成（不持有 rimeLock，避免阻塞主线程 UI 操作）
        var waited = 0L
        while (nativeIsMaintaining() && waited < timeoutMs) {
            try {
                Thread.sleep(1000)
            } catch (_: InterruptedException) {
                return false
            }
            waited += 1000
            Log.d(TAG, "ensureSession: waiting for maintenance... (${waited / 1000}s)")
        }

        synchronized(rimeLock) {
            waited = 0L
            while (waited < timeoutMs) {
                if (!nativeHasSession()) {
                    nativeCreateSession()
                }
                if (getAvailableSchemas().isNotEmpty()) {
                    Log.d(TAG, "ensureSession: schemas ready after ${waited}ms")
                    return true
                }
                try {
                    Thread.sleep(1000)
                } catch (_: InterruptedException) {
                    return false
                }
                waited += 1000
                Log.d(TAG, "ensureSession: waiting for schemas... (${waited / 1000}s)")
            }
            Log.w(TAG, "ensureSession: schemas not available after ${timeoutMs}ms, deployment may still be running")
            return false
        }
    }

    fun isMaintaining(): Boolean {
        return nativeIsMaintaining()
    }

    fun getCurrentSchema(): String {
        if (!nativeHasSession()) return ""
        synchronized(rimeLock) {
            return nativeGetCurrentSchema() ?: ""
        }
    }

    fun processKey(keycode: Int, mask: Int): Boolean {
        if (!isInitialized) return false
        synchronized(rimeLock) {
            if (!nativeHasSession() && !nativeCreateSession()) return false
            return nativeProcessKey(keycode, mask)
        }
    }

    fun processKeyAndGetResult(keycode: Int, mask: Int): RimeProcessResult {
        if (!isInitialized) return RimeProcessResult(false, "", "", "", emptyArray(), false, false, false)
        if (!nativeHasSession() && !nativeCreateSession())
            return RimeProcessResult(false, "", "", "", emptyArray(), false, false, false)
        return nativeProcessKeyAndGetResult(keycode, mask)
    }

    fun getProcessResult(processed: Boolean): RimeProcessResult {
        if (!isInitialized) return RimeProcessResult(false, "", "", "", emptyArray(), false, false, false)
        return nativeGetProcessResult(processed)
    }

    fun getCandidates(): Array<String> {
        synchronized(rimeLock) {
            if (!nativeHasSession()) return emptyArray()
            return nativeGetCandidates() ?: emptyArray()
        }
    }

    fun getCandidatesWithComments(): Array<RimeCandidate> {
        synchronized(rimeLock) {
            if (!nativeHasSession()) return emptyArray()
            val rawCandidates = nativeGetCandidatesWithComments() ?: emptyArray()
            return rawCandidates.map { pair ->
                RimeCandidate(
                    text = pair.getOrElse(0) { "" },
                    comment = pair.getOrElse(1) { "" }
                )
            }.toTypedArray()
        }
    }

    fun getInput(): String {
        synchronized(rimeLock) {
            return nativeGetInput() ?: ""
        }
    }

    /**
     * 批量查询当前 composition 全部信息。
     *
     * 一次 JNI 调用返回 input、preedit、committedText、candidates、分页和 ascii_mode，
     * 是 T9 路径 updateUI 的首选查询接口，可替代多次独立 JNI 调用。
     */
    fun getComposition(): RimeComposition {
        if (!isInitialized) return RimeComposition("", "", "", emptyArray(), false, false, false)
        synchronized(rimeLock) {
            if (!nativeHasSession() && !nativeCreateSession())
                return RimeComposition("", "", "", emptyArray(), false, false, false)
            return nativeGetComposition()
        }
    }

    fun selectCandidate(index: Int): Boolean {
        synchronized(rimeLock) {
            if (!nativeHasSession()) return false
            return nativeSelectCandidate(index)
        }
    }

    fun pageDown(): Boolean {
        synchronized(rimeLock) {
            if (!nativeHasSession()) return false
            return nativePageDown()
        }
    }

    fun pageUp(): Boolean {
        synchronized(rimeLock) {
            if (!nativeHasSession()) return false
            return nativePageUp()
        }
    }

    fun hasNextPage(): Boolean {
        synchronized(rimeLock) {
            if (!nativeHasSession()) return false
            return nativeHasNextPage()
        }
    }

    fun hasPrevPage(): Boolean {
        synchronized(rimeLock) {
            if (!nativeHasSession()) return false
            return nativeHasPrevPage()
        }
    }

    fun commit(): String {
        synchronized(rimeLock) {
            return nativeCommit() ?: ""
        }
    }

    fun clearComposition() {
        if (!nativeHasSession()) return
        synchronized(rimeLock) {
            nativeClearComposition()
        }
    }

    /**
     * 设置 RIME 引擎的输入字符串。
     *
     * 一次 JNI 调用完成整个输入设置，替代逐字符 processKey。
     * 调用后引擎会重新执行完整的处理管线（Speller → Segmentor → Translator）。
     * 支持分隔符 '，如 setInput("ji'he") 会告知 RIME 音节边界。
     *
     * @param input 拼音或数字字符串，如 "zhongguo" 或 "54482"
     * @return 是否设置成功
     */
    fun setInput(input: String): Boolean {
        if (!isInitialized) return false
        synchronized(rimeLock) {
            if (!nativeHasSession() && !nativeCreateSession()) return false
            return nativeSetInput(input)
        }
    }

    fun toggleAsciiMode(): Boolean {
        synchronized(rimeLock) {
            if (!nativeHasSession()) return false
            return nativeToggleAsciiMode()
        }
    }

    fun isAsciiMode(): Boolean {
        synchronized(rimeLock) {
            if (!nativeHasSession()) return false
            return nativeIsAsciiMode()
        }
    }

    fun setOption(option: String, value: Boolean) {
        if (!nativeHasSession()) return
        nativeSetOption(option, value)
    }

    fun getOption(option: String): Boolean {
        if (!nativeHasSession()) return false
        return nativeGetOption(option)
    }

    fun setPageSize(schemaId: String, pageSize: Int) {
        if (!isInitialized) return
        nativeSetPageSize(schemaId, pageSize)
    }

    fun switchSchema(schemaId: String): Boolean {
        synchronized(rimeLock) {
            if (!nativeHasSession()) return false
            return nativeSwitchSchema(schemaId)
        }
    }

    fun startMaintenance(full: Boolean): Boolean {
        if (!isInitialized) return false
        synchronized(rimeLock) {
            return nativeStartMaintenance(full)
        }
    }

    fun deploy(): Boolean {
        if (!isInitialized) return false
        synchronized(rimeLock) {
            return nativeDeploy()
        }
    }

    fun lookupText(text: String): String {
        if (!isInitialized || text.isEmpty()) return ""
        synchronized(rimeLock) {
            if (!nativeHasSession()) return ""
            return nativeLookupText(text) ?: ""
        }
    }

    fun getAvailableSchemas(): Array<String> {
        return nativeGetAvailableSchemas() ?: emptyArray()
    }

    fun destroy() {
        if (isInitialized) {
            synchronized(rimeLock) {
                Log.d(TAG, "Destroying Rime engine")
                nativeDestroy()
                isInitialized = false
            }
        }
    }

    // Native 方法声明
    private external fun nativeInitialize(userDataDir: String, sharedDataDir: String)
    private external fun nativeCreateSession(): Boolean
    private external fun nativeHasSession(): Boolean
    private external fun nativeIsMaintaining(): Boolean
    private external fun nativeGetCurrentSchema(): String?
    private external fun nativeProcessKey(keycode: Int, mask: Int): Boolean
    private external fun nativeProcessKeyAndGetResult(keycode: Int, mask: Int): RimeProcessResult
    private external fun nativeGetProcessResult(processed: Boolean): RimeProcessResult
    private external fun nativeGetCandidates(): Array<String>?
    private external fun nativeGetCandidatesWithComments(): Array<Array<String>>?
    private external fun nativeGetInput(): String?
    private external fun nativeGetComposition(): RimeComposition
    private external fun nativeSelectCandidate(index: Int): Boolean
    private external fun nativePageDown(): Boolean
    private external fun nativePageUp(): Boolean
    private external fun nativeHasNextPage(): Boolean
    private external fun nativeHasPrevPage(): Boolean
    private external fun nativeCommit(): String?
    private external fun nativeClearComposition()
    private external fun nativeSetInput(input: String): Boolean
    private external fun nativeToggleAsciiMode(): Boolean
    private external fun nativeIsAsciiMode(): Boolean
    private external fun nativeSetOption(option: String, value: Boolean)
    private external fun nativeGetOption(option: String): Boolean
    private external fun nativeSwitchSchema(schemaId: String): Boolean
    private external fun nativeStartMaintenance(full: Boolean): Boolean
    private external fun nativeDeploy(): Boolean
    private external fun nativeDeploySchema(schemaId: String): Boolean
    private external fun nativeLookupText(text: String): String
    private external fun nativeGetAvailableSchemas(): Array<String>?
    private external fun nativeIsModuleRegistered(moduleName: String): Boolean
    private external fun nativeUpdateLastBuildTime()
    private external fun nativeSetPageSize(schemaId: String, pageSize: Int)
    private external fun nativeDestroy()

    fun deploySchema(schemaId: String): Boolean {
        if (!isInitialized) return false
        Log.d(TAG, "Deploying single schema: $schemaId")
        return nativeDeploySchema(schemaId)
    }

    fun updateLastBuildTime() {
        if (!isInitialized) return
        nativeUpdateLastBuildTime()
    }
}
