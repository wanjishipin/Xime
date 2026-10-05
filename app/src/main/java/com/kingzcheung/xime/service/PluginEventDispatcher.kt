package com.kingzcheung.xime.service

import android.text.InputType
import android.view.inputmethod.EditorInfo
import com.kingzcheung.xime.plugin.core.js.PluginEvent
import com.kingzcheung.xime.plugin.core.runtime.PluginManager

/**
 * 插件下行事件投递器：把宿主输入态以 conflated 事件推送给声明订阅的插件
 * （manifest capabilities.events 声明才建立通道，未声明插件零开销）。
 *
 * 性能契约：主流程只做内存累计与 trySend（微秒级），无锁无 IO，不阻塞输入。
 * 线程契约：所有调用点均在主线程（onStartInput / commitText / updateUIWithResult /
 * applyComposition），字段无需并发保护。
 * 安全契约：敏感输入框（密码类 / 不学习标记）不产生任何事件，payload 不含密码字符。
 */
internal class PluginEventDispatcher(private val service: XimeInputMethodService) {

    /** 上一次投递的 composing 快照：内容未变化时不重复投递。 */
    private var lastDispatchedInputText: String? = null

    /** 累计快照（仅供同模块测试断言）。 */
    internal val sessionCommittedCharsForTest: Long get() = sessionCommittedChars

    /** 进程生命周期累计上屏字符数（text_committed payload，插件统计用）。 */
    private var sessionCommittedChars: Long = 0L

    /** 进程生命周期累计上屏提交次数。 */
    private var sessionCommits: Long = 0L

    /** 当前输入会话是否敏感输入框（密码类 / 不学习标记）。
     *  主线程写（onStartInput）、key-processing 线程读（候选词变换短路），volatile 保证可见性。 */
    @Volatile
    private var sensitiveInput: Boolean = false

    /** 当前输入会话是否敏感输入框（密码类 / 不学习标记）。
     *  供 hotPath 插件能力（候选词变换）在发起请求前短路。 */
    val isCurrentEditorSensitive: Boolean get() = sensitiveInput

    /** 新输入会话：刷新敏感标记并重置 composing 去重。 */
    fun onStartInput(attribute: EditorInfo?) {
        sensitiveInput = isSensitiveEditor(attribute)
        lastDispatchedInputText = null
    }

    /**
     * composing 编码变化 → input_changed（T9 传与候选栏同源的显示态；
     * 空编码表示本轮输入结束）。
     */
    fun dispatchInputChanged(inputText: String) {
        if (sensitiveInput) return
        if (inputText == lastDispatchedInputText) return
        lastDispatchedInputText = inputText
        PluginManager.dispatchEvent(
            PluginEvent(
                PluginEvent.TYPE_INPUT_CHANGED,
                mapOf(PluginEvent.FIELD_INPUT_TEXT to inputText),
            )
        )
    }

    /**
     * 文本上屏 → text_committed。payload 携带进程生命周期累计值：
     * conflated 丢中间事件不影响统计（插件用前后差值做增量持久化）。
     * [isPaste] 标记粘贴性质上屏（剪贴板点选/编辑面板提交）：事件照发、
     * 计数器照常累计（维持单调差值基准），是否计入打字量由订阅插件按 is_paste 决定。
     */
    fun onTextCommitted(text: String, isPaste: Boolean = false) {
        if (sensitiveInput) return
        // 按 Unicode 代码点计数：emoji/生僻字（增补平面代理对）用户感知为 1 个字，
        // String.length 的 UTF-16 单元会把它们算成 2（"复制12个字统计13"的根源）
        sessionCommittedChars += text.codePointCount(0, text.length)
        sessionCommits++
        PluginManager.dispatchEvent(
            PluginEvent(
                PluginEvent.TYPE_TEXT_COMMITTED,
                mapOf(
                    PluginEvent.FIELD_COMMITTED_TEXT to text,
                    PluginEvent.FIELD_SESSION_TOTAL_CHARS to sessionCommittedChars,
                    PluginEvent.FIELD_SESSION_TOTAL_COMMITS to sessionCommits,
                    PluginEvent.FIELD_IS_PASTE to isPaste,
                )
            )
        )
    }

    /**
     * 编辑器是否敏感：密码类输入框（文本/数字/网页密码）或宿主声明
     * IME_FLAG_NO_PERSONALIZED_LEARNING。
     */
    private fun isSensitiveEditor(attribute: EditorInfo?): Boolean {
        if (attribute == null) return false
        val cls = attribute.inputType and InputType.TYPE_MASK_CLASS
        val variation = attribute.inputType and InputType.TYPE_MASK_VARIATION
        val isPassword = when (cls) {
            InputType.TYPE_CLASS_TEXT -> variation in intArrayOf(
                InputType.TYPE_TEXT_VARIATION_PASSWORD,
                InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
                InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD,
            )
            InputType.TYPE_CLASS_NUMBER ->
                variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD
            else -> false
        }
        val noPersonalized = attribute.imeOptions and EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING != 0
        return isPassword || noPersonalized
    }
}
