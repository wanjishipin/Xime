package com.kingzcheung.xime.service

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.inputmethod.InputConnection
import android.widget.Toast
import com.kingzcheung.xime.plugin.ExtensionManager
import com.kingzcheung.xime.speech.AsrBackendFactory
import com.kingzcheung.xime.speech.RecognitionState
import com.kingzcheung.xime.speech.SpeechRecognitionManager
import com.kingzcheung.xime.settings.SettingsPreferences
import com.kingzcheung.xime.util.FileLogger

class VoiceRecognitionHandler(
    private val context: Context,
    private val onStateChanged: (InputUIState) -> Unit,
    private val getState: () -> InputUIState,
    private val getInputConnection: () -> InputConnection?,
    private val onVoiceComplete: () -> Unit = {},
    private val onAmplitudeChanged: (Float) -> Unit = {},
    private val onSpectrumChanged: (FloatArray) -> Unit = {},
    /** 语音向输入框写入 composing 文本时回调（标记 composing 区域存在，供 endComposingInputBox 判断）。 */
    private val onComposingWritten: () -> Unit = {},
    /** manager 工厂，供测试注入 mock。 */
    private val managerFactory: (Context) -> SpeechRecognitionManager = { SpeechRecognitionManager(it) },
    /** 超时调度器，供测试注入并手动推进。 */
    private val mainHandler: Handler = Handler(Looper.getMainLooper())
) {
    companion object {
        private const val TAG = "VoiceRecognition"

        /**
         * 松手后等待 ASR 引擎最终结果的超时。在线插件 stop() 只发送结束信号
         * （finish-task/最后一包标记），服务端处理完尾点才经 WebSocket 异步回调
         * 最终结果，通常 1~2s；超时仍未收到则回退提交已收到的部分结果。
         */
        private const val FINISH_TIMEOUT_MS = 3000L
    }

    private lateinit var speechRecognitionManager: SpeechRecognitionManager

    var textBeforeVoiceInput = ""
    var textLengthBeforeVoiceInput = 0

    fun initialize() {
        FileLogger.i(TAG, "Initializing speech recognition system")

        speechRecognitionManager = managerFactory(context)

        speechRecognitionManager.setCallbacks(
            onResult = { text ->
                handleSpeechResult(text)
            },
            onPartialResult = { text ->
                handlePartialResult(text)
            },
            onStateChange = { state ->
                handleSpeechStateChange(state)
            },
            onError = { error, userVisible ->
                handleSpeechError(error, userVisible)
            },
            onAmplitude = { amplitude ->
                handleAmplitudeUpdate(amplitude)
            },
            onSpectrum = { spectrum ->
                handleSpectrumUpdate(spectrum)
            }
        )

        val providerName = resolveProviderName()

        onStateChanged(getState().copy(voicePluginName = providerName))
        FileLogger.i(TAG, "STT provider: $providerName")

        // 若"使用本地模型"开关已开启，启动时即加载模型并常驻，
        // 保证语音时绝不现场加载模型（避免丢开头音频）。
        // 注意：keep-alive 预热也走这里（AsrSupport.warmup 注册常驻后端），
        // 不要再走 manager.preload——那会经 AsrSupport.create 造一个临时后端，
        // 与本 warmup 并发时双重绑定 :asr、双重加载模型（日志曾见两次 initialize）
        if (SettingsPreferences.isSttUseLocal(context) &&
            AsrBackendFactory.getLocalName() != null
        ) {
            Thread {
                AsrBackendFactory.warmup(context)
            }.start()
        }
    }

    private val delayedPreStartRunnable = Runnable {
        if (::speechRecognitionManager.isInitialized) {
            speechRecognitionManager.startPreStart()
        }
    }

    fun startDelayedPreStart(delayMs: Long = 150) {
        mainHandler.removeCallbacks(delayedPreStartRunnable)
        mainHandler.postDelayed(delayedPreStartRunnable, delayMs)
    }

    fun cancelPreStart() {
        mainHandler.removeCallbacks(delayedPreStartRunnable)
        if (::speechRecognitionManager.isInitialized) {
            speechRecognitionManager.cancelPreStart()
        }
    }

    fun startRecognition() {
        if (!::speechRecognitionManager.isInitialized) {
            Log.e(TAG, "speechRecognitionManager not initialized")
            onStateChanged(getState().copy(
                isVoiceMode = false,
                voiceSticky = false,
                voiceRecognitionState = RecognitionState.ERROR
            ))
            return
        }

        // 上一次会话若还在收尾等待中（快速再次开始），直接废弃收尾状态
        finishing = false
        mainHandler.removeCallbacks(finishTimeoutRunnable)
        suppressDuplicateFinal = false
        // 新会话：清掉上一会话与输入框的记账（写入文本/已消费前缀/核对标记）
        resetInputBoxBookkeeping()

        textBeforeVoiceInput = getInputConnection()?.getTextBeforeCursor(1000, 0)?.toString() ?: ""
        textLengthBeforeVoiceInput = textBeforeVoiceInput.length

        val providerName = resolveProviderName()
        onStateChanged(getState().copy(voicePluginName = providerName))

        speechRecognitionManager.startRecognition()
    }

    fun stopRecognition() {
        if (::speechRecognitionManager.isInitialized) {
            speechRecognitionManager.stopRecognition()
        }
        // handleFinalResult is now called from within handleSpeechResult
        // when the final stopRecognition result arrives
    }

    fun release() {
        if (::speechRecognitionManager.isInitialized) {
            speechRecognitionManager.release()
        }
    }

    fun isInitialized(): Boolean = ::speechRecognitionManager.isInitialized

    private fun resolveProviderName(): String {
        // 用户开启"本地识别"且当前构建支持离线语音时，优先显示本地引擎名
        if (SettingsPreferences.isSttUseLocal(context)) {
            val localName = AsrBackendFactory.getLocalName()
            if (localName != null) return localName
        }
        val enabledPlugins = ExtensionManager.getEnabledAsrPlugins(context)
        if (enabledPlugins.isNotEmpty()) {
            val selectedId = SettingsPreferences.getSttOnlinePluginId(context)
            val selected = enabledPlugins.firstOrNull { it.first == selectedId }
                ?: enabledPlugins.firstOrNull()
            if (selected != null) {
                return ExtensionManager.getAllInstalledPlugins()
                    .firstOrNull { it.id == selected.first }?.name ?: selected.first
            }
        }
        return "未配置"
    }

    private var lastPartialText = ""
    // 本会话实际写入输入框的文本（无外部消费时等于 lastPartialText）。最终结果的增量对齐、
    // "删除已上屏部分"的长度都按它算，保证删除量对得上输入框里真实存在的内容。
    private var lastWrittenText = ""
    // 已被外部消费的引擎累计文本前缀（应用在输入法不知情时取走了上屏内容，如微信发送后清空输入框）
    private var consumedEnginePrefix = ""
    // 是否核对成功过"写入的文本确实回显在输入框里"。应用不回传文本时不得误判为"被清空"，否则丢字。
    private var writtenTextVerified = false
    // 本会话是否已做过"回显核对"探测（每次写入都探测会多出同步 IPC，只需首次）
    private var inputBoxEchoProbed = false
    private var lastAmplitudeUpdate = 0L
    private var smoothedAmplitude = 0f
    private var smoothedSpectrum = FloatArray(16)
    // 抬起时已提交当前识别文本后，置真以忽略随后可能迟到的重复最终结果
    private var suppressDuplicateFinal = false
    // 松手收尾中：已停止送音，等待引擎吐出最终结果（超时由 finishTimeoutRunnable 兜底）
    @Volatile
    private var finishing = false
    // 输入法窗口隐藏等场景：丢弃本会话，迟到结果不得写入任何输入框
    private var sessionAbandoned = false
    private var errorToast: Toast? = null

    private val finishTimeoutRunnable = Runnable { onFinishTimeout() }

    /** 输入法隐藏/切换输入框时调用：丢弃当前会话的未识别文本，忽略迟到的最终结果 */
    fun abandonSession() {
        sessionAbandoned = true
        finishing = false
        mainHandler.removeCallbacks(finishTimeoutRunnable)
        lastPartialText = ""
        resetInputBoxBookkeeping()
    }

    /**
     * 发送类动作前的会话封口（语音面板滑到"发送/撤销"抬手、常驻语音下按回车/发送键）。
     *
     * 与"松手收尾"（[finishRecognition] 等引擎最终结果，最长 [FINISH_TIMEOUT_MS]）不同：
     * 用户已经把这批文本发出去了，之后再落进输入框的任何内容都是重复或错位。所以这里：
     * 1. 立即冲刷 composing 中的部分结果——否则收尾等待期间它会被随后的按键或 composing
     *    重写吞掉（"点键盘其他区域清空已上屏内容"）；
     * 2. 置 [suppressDuplicateFinal]，丢弃引擎随后可能迟到的最终结果。
     *
     * 无已识别文本时同样封口：动作之后才吐出的结果一样不该上屏。
     * 注意本方法不结束会话、不停止录音——由调用方继续走 endVoiceSession/onVoiceComplete。
     */
    fun sealPendingForSend() {
        if (sessionAbandoned) return
        // 结束"收尾等待"：不再等引擎最终结果，也不留超时兜底
        finishing = false
        mainHandler.removeCallbacks(finishTimeoutRunnable)
        commitPendingOnRelease()
        suppressDuplicateFinal = true
    }

    // 语音按钮长按抬起时调用：立即提交当前已识别的文本（不依赖可能被断连竞态吞掉的异步最终结果）
    fun commitPendingOnRelease() {
        if (sessionAbandoned) return
        val ic = getInputConnection()
        val partial = lastPartialText
        Log.d(TAG, "commitPendingOnRelease: ic=${ic != null}, partial='$partial', suppress=$suppressDuplicateFinal")
        if (ic == null) return
        if (partial.isEmpty()) return
        // 对账用"上一次写入对应的引擎文本"，必须在清空 lastPartialText 之前做
        reconcileWithInputBox(ic)
        val visibleText = visibleEngineText(partial.replace(" ", ""))
        suppressDuplicateFinal = true
        if (visibleText.isEmpty()) {
            // 本会话内容已被应用取走（如发送后清空输入框），没有可提交的增量
            Log.d(TAG, "commitPendingOnRelease: 内容已被外部消费，跳过提交")
            lastPartialText = ""
            resetInputBoxBookkeeping()
            return
        }
        val punctuatedText = addPunctuation(visibleText)
        commitFinal(ic, punctuatedText, lastWrittenText)
        lastPartialText = ""
        resetInputBoxBookkeeping()
    }

    /**
     * 松手/点按结束语音的收尾入口：停止送音后等待引擎最终结果，而不是立即提交
     * 部分结果——在线 ASR 需 1~2s 处理尾点，立即提交会把未处理完的语音截断。
     * 收到最终结果正常提交；超时（[FINISH_TIMEOUT_MS]）才回退提交部分结果。
     * 完成路径（最终结果/超时/错误）统一经 onVoiceComplete 通知宿主恢复键盘。
     */
    fun finishRecognition() {
        if (!::speechRecognitionManager.isInitialized) return
        if (finishing) return
        if (sessionAbandoned) {
            speechRecognitionManager.stopRecognition()
            return
        }
        if (lastPartialText.isEmpty()) {
            // 无已识别文本（没说话/极短语音）：无内容可等，直接结束；
            // 迟到的最终结果仍走正常提交路径（说了话就该上屏）
            speechRecognitionManager.stopRecognition()
            onVoiceComplete()
            return
        }
        finishing = true
        // 收尾期间保持"正在识别..."显示：引擎 stop 过程中的 IDLE 状态由
        // handleSpeechStateChange 过滤，直到最终结果/超时才结束
        onStateChanged(getState().copy(voiceRecognitionState = RecognitionState.PROCESSING))
        mainHandler.removeCallbacks(finishTimeoutRunnable)
        mainHandler.postDelayed(finishTimeoutRunnable, FINISH_TIMEOUT_MS)
        speechRecognitionManager.stopRecognition()
    }

    private fun onFinishTimeout() {
        if (!finishing) return
        finishing = false
        Log.d(TAG, "finish timeout: committing partial result as fallback")
        // 超时未收到最终结果：提交已收到的部分结果兜底（会话已丢弃时内部直接跳过）
        commitPendingOnRelease()
        onVoiceComplete()
    }

    private fun handleSpeechResult(text: String) {
        Log.d(TAG, "Speech result (final): $text")

        val wasFinishing = finishing
        if (finishing) {
            // 收尾中收到最终结果：取消超时兜底，正常提交完整结果
            finishing = false
            mainHandler.removeCallbacks(finishTimeoutRunnable)
        }

        if (sessionAbandoned) {
            sessionAbandoned = false
            lastPartialText = ""
            onVoiceComplete()
            return
        }

        if (suppressDuplicateFinal) {
            // 抬起时已提交，忽略迟到的重复最终结果
            suppressDuplicateFinal = false
            lastPartialText = ""
            onVoiceComplete()
            return
        }

        val cleanText = text.replace(" ", "")
        val ic = getInputConnection()
        if (ic != null && cleanText.isNotEmpty() && !cleanText.startsWith("错误:")) {
            // 收尾期间应用可能已把我们上屏的内容取走（发送/清空输入框）：先对账，再按可见增量提交
            reconcileWithInputBox(ic)
            val visibleText = visibleEngineText(cleanText)
            if (visibleText.isNotEmpty()) {
                commitFinal(ic, addPunctuation(visibleText), lastWrittenText)
            } else {
                Log.d(TAG, "handleSpeechResult: 内容已被外部消费，跳过提交")
            }
        }
        lastPartialText = ""
        resetInputBoxBookkeeping()

        if (!wasFinishing) {
            // 用户尚未松手就收到 final：流式在线插件按句回调属正常行为，该句已上屏，
            // 会话继续，等松手才结束。绝不能触发 onVoiceComplete——它会把
            // isVoiceMode/voiceRecordingStarted 清零，松手时容器的停止条件
            // （isVoiceMode || isRecording）全部失效，录音线程会一直在后台运行。
            return
        }
        onVoiceComplete()
    }
    
    // 增量语音模式：先结束 composing，再只提交增量，避免重复与整段重写。
    private fun commitFinal(ic: InputConnection, finalText: String, partial: String) {
        ic.finishComposingText()
        if (partial.isNotEmpty() && finalText.startsWith(partial)) {
            val remainder = finalText.substring(partial.length)
            if (remainder.isNotEmpty()) {
                ic.commitText(remainder, 1)
            } else {
                Log.d(TAG, "commitFinal: remainder empty, only finished composing")
            }
        } else {
            // 最终结果与部分结果不一致：删除已上屏的部分，再提交完整结果
            if (partial.isNotEmpty()) {
                ic.deleteSurroundingText(partial.length, 0)
            }
            ic.commitText(finalText, 1)
        }
        Log.d(TAG, "commitFinal: final='$finalText', partial='$partial'")
    }

    /** 会话切换/提交结束时清理与输入框的记账。 */
    private fun resetInputBoxBookkeeping() {
        lastWrittenText = ""
        consumedEnginePrefix = ""
        writtenTextVerified = false
        inputBoxEchoProbed = false
    }

    /**
     * 首次写入后核对一次"应用是否真的回显我们的 composing 文本"，决定本会话是否允许
     * "被外部消费"判定：应用不回传文本（[getTextBeforeCursor] 拿不到我们写的内容）时，
     * 若还按"没读到 = 被清空"处理，会把后续识别内容全部丢弃。
     */
    private fun verifyInputBoxEcho(ic: InputConnection, written: String) {
        if (inputBoxEchoProbed || written.isEmpty()) return
        inputBoxEchoProbed = true
        val onScreen = runCatching {
            ic.getTextBeforeCursor(written.length, 0)?.toString()
        }.getOrNull() ?: return
        if (onScreen == written) {
            writtenTextVerified = true
        } else {
            Log.d(TAG, "输入框未回显语音 composing 文本，本会话不做外部消费判定")
        }
    }

    /**
     * 写入/提交前与输入框对账。
     *
     * 应用可以在输入法完全不知情的情况下取走已经上屏的语音文本（最典型：微信点发送后清空
     * 输入框）。而引擎的部分结果/最终结果是**整段累计**的，此时若继续整段写入，就会把已经
     * 发出去的内容连同新内容一起灌回输入框——真机现象即"点发送后继续说话，已发送文本又回到
     * 输入框"（2026-09-29 日志：单个会话内 partial 从"一个人"变为"一个人，两个人"，
     * 整段 setComposingText 写进已被清空的框）。
     *
     * 判定：光标前的文本比我们上次写入的更短，即"我们写的内容被外部移除"→ 把本会话已识别的
     * 引擎文本整段记为 [consumedEnginePrefix]，后续只写增量。仅在曾经核对成功过
     * （[writtenTextVerified]，证明该应用确实回显我们的文本）时才判定，避免应用不回传文本时误判丢字。
     */
    private fun reconcileWithInputBox(ic: InputConnection) {
        if (lastWrittenText.isEmpty()) return
        val onScreen = runCatching {
            ic.getTextBeforeCursor(lastWrittenText.length, 0)?.toString()
        }.getOrNull() ?: return
        if (onScreen == lastWrittenText) {
            writtenTextVerified = true
            return
        }
        if (!writtenTextVerified) return
        // 只有"输入框内容变短"（被清空/删除）才判定为被外部消费；变长或等长（用户编辑、
        // 应用替换成等长文本）时保守不动，避免误判丢字。
        if (onScreen.length >= lastWrittenText.length) return
        consumedEnginePrefix = lastPartialText
        // "撤销语音输入"按会话开始时的框长度计算删除量，外部清空后基线要跟着落到当前框内容
        textLengthBeforeVoiceInput = onScreen.length
        lastWrittenText = ""
        Log.d(TAG, "输入框已被外部清空/取走内容，本会话已识别文本记为已消费: '$consumedEnginePrefix'")
    }

    /**
     * 引擎结果按"已消费前缀"裁剪出真正该写进输入框的可见文本。
     * 同时去掉开头标点：被消费的那句已经带着标点发出去了，新句子不该以"，"开头。
     */
    private fun visibleEngineText(engineText: String): String {
        if (consumedEnginePrefix.isEmpty()) return engineText
        val visible = if (engineText.startsWith(consumedEnginePrefix)) {
            engineText.substring(consumedEnginePrefix.length)
        } else {
            // 引擎改写了已消费部分，无法可靠切分：整段写入以免丢内容（记日志便于真机排查）
            Log.d(TAG, "引擎结果不再以已消费前缀开头，按整段写入: '$engineText'")
            engineText
        }
        return visible.trimStart('，', '。', '、', ',', '.', '！', '？', '；', '：', ' ', '\n')
    }
    
    private fun addPunctuation(text: String): String {
        val cleanText = text.trim().replace(" ", "")
        if (cleanText.isEmpty()) return text

        // 若文本末尾已带句末标点（如 funasr/volc 等自带标点的后端），不再追加，避免"。。"
        if (cleanText.last() in "。！？；：，、；：,.!?;:，") return cleanText

        return "$cleanText${heuristicPunctuation(cleanText)}"
    }

    private fun heuristicPunctuation(text: String): String {
        return when {
            text.any { it in "吗呢么吧" } || text.contains("什么") || text.contains("怎么") || text.contains("为什么") || text.contains("如何") || text.contains("哪") -> "？"
            text.length < 4 -> "，"
            else -> "。"
        }
    }

    private fun handlePartialResult(text: String) {
        if (sessionAbandoned || suppressDuplicateFinal) return
        if (text == lastPartialText) return

        val ic = getInputConnection()
        // 对账必须在更新 lastPartialText 之前：判定"已消费"要用上一次写入对应的引擎文本
        if (ic != null) reconcileWithInputBox(ic)

        lastPartialText = text
        Log.d(TAG, "Speech result (partial): $text")
        
        // 过滤掉空格，避免显示空白；已被外部消费的前缀（如已发送内容）不再回写
        val cleanText = visibleEngineText(text.replace(" ", ""))
        if (cleanText.isEmpty()) return
        
        if (ic != null) {
            onComposingWritten()
            ic.setComposingText(cleanText, 1)
            lastWrittenText = cleanText
            // 首次写入后回读一次，确认该应用确实回显 composing 文本（之后才允许做"被外部消费"判定）
            verifyInputBoxEcho(ic, cleanText)
        }
        onStateChanged(getState().copy(voiceRecognizedText = cleanText))
    }

    private fun handleSpeechStateChange(state: RecognitionState) {
        Log.d(TAG, "Speech state changed: $state")
        if (state == RecognitionState.LISTENING) {
            lastPartialText = ""
            suppressDuplicateFinal = false
            sessionAbandoned = false
            resetInputBoxBookkeeping()
        }
        // 收尾等待最终结果期间，引擎 stop 产生的 IDLE 不覆盖"正在识别..."显示
        if (finishing && state == RecognitionState.IDLE) return
        onStateChanged(getState().copy(voiceRecognitionState = state))
    }

    private fun handleSpeechError(error: String, userVisible: Boolean) {
        Log.e(TAG, "Speech error: $error")
        FileLogger.e(TAG, "Speech error: $error")
        val wasFinishing = finishing
        finishing = false
        mainHandler.removeCallbacks(finishTimeoutRunnable)
        lastPartialText = ""
        resetInputBoxBookkeeping()
        if (!wasFinishing) {
            // 用户尚未松手时引擎报错：UI 经 onVoiceComplete 恢复后松手停止链即失效，
            // 必须在这里显式停止录音（释放麦克风/引擎连接）；置抑制标志丢弃错误后
            // 可能迟到的部分结果，避免键盘恢复后文字继续往外蹦。
            suppressDuplicateFinal = true
            if (::speechRecognitionManager.isInitialized) {
                speechRecognitionManager.stopRecognition()
            }
        }
        if (userVisible && error.isNotBlank()) {
            errorToast?.cancel()
            errorToast = Toast.makeText(context, error, Toast.LENGTH_LONG)
            errorToast?.show()
        }
        onVoiceComplete()
    }

    private fun handleAmplitudeUpdate(amplitude: Float) {
        val now = System.currentTimeMillis()
        if (now - lastAmplitudeUpdate < 80) return
        lastAmplitudeUpdate = now
        smoothedAmplitude = smoothedAmplitude * 0.45f + amplitude * 0.55f
        onAmplitudeChanged(smoothedAmplitude)
    }

    private fun handleSpectrumUpdate(spectrum: FloatArray) {
        val smoothed = smoothedSpectrum
        for (i in spectrum.indices) {
            smoothed[i] = smoothed[i] * 0.5f + spectrum[i] * 0.5f
        }
        onSpectrumChanged(smoothed.copyOf())
    }
}