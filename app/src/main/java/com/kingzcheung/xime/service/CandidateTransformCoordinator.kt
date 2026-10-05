package com.kingzcheung.xime.service

import android.util.Log
import com.kingzcheung.xime.plugin.core.js.CandidateTransformCandidate
import com.kingzcheung.xime.plugin.core.js.CandidateTransformItem
import com.kingzcheung.xime.plugin.core.js.CandidateTransformOutcome
import com.kingzcheung.xime.plugin.core.js.CandidateTransformRequest
import com.kingzcheung.xime.plugin.core.js.JsScriptRuntime
import com.kingzcheung.xime.plugin.core.runtime.PluginManager
import com.kingzcheung.xime.rime.RimeCandidate
import com.kingzcheung.xime.rime.RimeProcessResult
import com.kingzcheung.xime.ui.keyboard.isT9Schema
import com.kingzcheung.xime.util.FileLogger

/** 变换结果：显示候选（引擎引用 + 插件候选混合）+ 平行的上屏动作映射。 */
internal data class CandidateTransformResult(
    val candidates: List<RimeCandidate>,
    val actions: List<CandidateAction>,
)

/** T9 场景插件候选快照（text + comment）：引擎候选原样保留，插件候选追加右栏。 */
data class PluginCandidateSnapshot(
    val text: String,
    val comment: String,
)

/**
 * T9 插件候选注入：锚定在某个引擎候选之后。
 * [anchorEngineIndex] = 该条目在插件响应 items 中前一个引擎引用项（engine_index）；
 * null = 无引擎锚点（追加末尾）。两条命中规则在插件 Lua 侧已表达为输出顺序
 * （编码命中紧跟引擎第一候选 → anchor=0 即第二候选位；内容命中紧跟对应候选 → anchor=k），
 * 本模型把该顺序语义无损带过 T9 索引不可信的问题。
 */
data class T9CandidateInjection(
    val anchorEngineIndex: Int?,
    val snapshot: PluginCandidateSnapshot,
)

/**
 * 候选词变换协调器（插件 candidate_transform 能力，首个 hotPath 能力）。
 *
 * 在 rime 返回候选之后、候选栏渲染之前同步调用插件 transformCandidates 修改候选；
 * 插件候选（text）点击后直接上屏插件文本，引擎引用（engine_index）走原引擎选词。
 *
 * 设计契约（docs/plugin-capability-registry.md §4.1/§5）：
 * - 线程：仅在 key-processing 线程调用（阻塞至多 15ms），主线程永不等待插件
 * - 短路：敏感输入框 / T9 / ascii 模式 / 空编码 / 无声明插件 → 不产生请求
 * - 熔断：连续 3 次失败（超时/报错/格式错）本会话禁用，插件重载或进程重启恢复
 * - 降级：任何失败回退原始候选，actions 为空 = 纯引擎语义
 */
internal class CandidateTransformCoordinator(private val service: XimeInputMethodService) {

    companion object {
        private const val MAX_CONSECUTIVE_FAILURES = 3

        /**
         * 插件响应 → 显示候选 + 上屏动作（纯函数，便于单测）。
         * 语义校验：engine_index 越界/重复丢弃；text 空丢弃；总数上限截断。
         * 引用项 comment 覆盖显示注释；无可显示内容返回 null（不干预）。
         */
        internal fun buildDisplay(
            items: List<CandidateTransformItem>,
            engineCandidates: List<RimeCandidate>,
            maxCandidates: Int = JsScriptRuntime.TRANSFORM_MAX_CANDIDATES,
        ): CandidateTransformResult? {
            val display = ArrayList<RimeCandidate>(items.size)
            val actions = ArrayList<CandidateAction>(items.size)
            val seenEngineIdx = HashSet<Int>()
            for (item in items) {
                if (display.size >= maxCandidates) break
                val engineIdx = item.engineIndex
                if (engineIdx != null) {
                    if (engineIdx < 0 || engineIdx >= engineCandidates.size || !seenEngineIdx.add(engineIdx)) continue
                    val engine = engineCandidates[engineIdx]
                    display.add(RimeCandidate(engine.text, item.comment ?: engine.comment))
                    actions.add(CandidateAction.engine(engineIdx))
                } else {
                    val text = item.text?.takeIf { it.isNotEmpty() } ?: continue
                    display.add(RimeCandidate(text, item.comment ?: ""))
                    actions.add(CandidateAction.plugin(text))
                }
            }
            if (display.isEmpty()) return null
            // 纯引擎引用（重排/去注释覆盖）也保留 actions：显示 index 与引擎 index 可能不同
            return CandidateTransformResult(display, actions)
        }
    }

    /** 连续失败熔断标记（本会话禁用；插件重载/进程重启恢复）。 */
    @Volatile
    private var disabledThisSession = false
    private var consecutiveFailures = 0

    /** 对一次引擎按键结果做变换。返回 null = 不干预，调用方原样渲染。 */
    fun transformFor(result: RimeProcessResult): CandidateTransformResult? {
        if (result.isAsciiMode) return null
        if (result.inputText.isEmpty() && result.candidates.isEmpty()) return null
        return transform(
            inputText = result.inputText,
            preedit = result.preeditText,
            engineCandidates = result.candidates.toList(),
            asciiMode = result.isAsciiMode,
        )
    }

    /**
     * 变换入口（key-processing 线程，阻塞至多 15ms）。
     * T9 路径不接入变换（v1 边界），此处防御再判。
     */
    fun transform(
        inputText: String,
        preedit: String,
        engineCandidates: List<RimeCandidate>,
        asciiMode: Boolean,
    ): CandidateTransformResult? {
        if (disabledThisSession) return null
        if (asciiMode) return null
        if (isT9Schema(service.uiState.value.currentSchemaId)) return null
        if (service.pluginEvents.isCurrentEditorSensitive) return null
        val (pluginId, runtime) = findRuntime() ?: return null
        val outcome = runtime.transformCandidates(
            CandidateTransformRequest(
                inputText = inputText,
                preedit = preedit,
                candidates = engineCandidates.map { CandidateTransformCandidate(it.text, it.comment) },
                asciiMode = asciiMode,
            )
        )
        return when (outcome) {
            is CandidateTransformOutcome.NoResponse -> {
                consecutiveFailures = 0
                logTransform("QWERTY", pluginId, inputText, "no-intervention", 0)
                null
            }
            is CandidateTransformOutcome.Failed -> {
                consecutiveFailures++
                logTransform("QWERTY", pluginId, inputText, "failed", 0)
                if (consecutiveFailures >= MAX_CONSECUTIVE_FAILURES) {
                    disabledThisSession = true
                    FileLogger.w(
                        XimeInputMethodService.TAG,
                        "candidate_transform 连续失败 $consecutiveFailures 次，本会话禁用该插件变换"
                    )
                }
                null
            }
            is CandidateTransformOutcome.Success -> {
                consecutiveFailures = 0
                val transformed = buildDisplay(outcome.items, engineCandidates)
                logTransform("QWERTY", pluginId, inputText, "inject", transformed?.candidates?.size ?: 0)
                transformed
            }
        }
    }

    /**
     * T9 场景变换入口（t9Dispatcher/key-processing 线程，阻塞至多 15ms）。
     * 与 [transform] 的区别：T9 候选索引可能被引擎 partial 展示态替换，engine_index
     * 引用不可信 → 响应只取 text 追加项（引擎引用丢弃），但记录每条 text 项的
     * 引擎锚点（其前最近一次引擎引用的 index），供 applyComposition T9 分支按锚点
     * 插入（插件 Lua 的输出顺序已表达"编码命中→第二候选位、内容命中→跟随候选"）。
     * 短路条件同 [transform]；T9 本身不作短路（本方法即 T9 路径）。
     */
    fun transformForT9(result: RimeProcessResult): List<T9CandidateInjection>? {
        if (disabledThisSession) return null
        if (result.isAsciiMode) return null
        if (result.inputText.isEmpty() && result.candidates.isEmpty()) return null
        if (service.pluginEvents.isCurrentEditorSensitive) return null
        val (pluginId, runtime) = findRuntime() ?: return null
        val outcome = runtime.transformCandidates(
            CandidateTransformRequest(
                inputText = result.inputText,
                preedit = result.preeditText,
                candidates = result.candidates.map { CandidateTransformCandidate(it.text, it.comment) },
                asciiMode = result.isAsciiMode,
            )
        )
        return when (outcome) {
            is CandidateTransformOutcome.NoResponse -> {
                consecutiveFailures = 0
                logTransform("T9", pluginId, result.inputText, "no-intervention", 0)
                null
            }
            is CandidateTransformOutcome.Failed -> {
                consecutiveFailures++
                logTransform("T9", pluginId, result.inputText, "failed", 0)
                if (consecutiveFailures >= MAX_CONSECUTIVE_FAILURES) {
                    disabledThisSession = true
                    FileLogger.w(
                        XimeInputMethodService.TAG,
                        "candidate_transform(T9) 连续失败 $consecutiveFailures 次，本会话禁用该插件变换"
                    )
                }
                null
            }
            is CandidateTransformOutcome.Success -> {
                consecutiveFailures = 0
                val injections = mutableListOf<T9CandidateInjection>()
                var lastEngineAnchor: Int? = null
                for (item in outcome.items) {
                    if (item.engineIndex != null) {
                        lastEngineAnchor = item.engineIndex
                        continue
                    }
                    val text = item.text?.takeIf { it.isNotEmpty() } ?: continue
                    injections.add(
                        T9CandidateInjection(lastEngineAnchor, PluginCandidateSnapshot(text, item.comment ?: ""))
                    )
                }
                logTransform("T9", pluginId, result.inputText, "inject", injections.size)
                injections.takeIf { it.isNotEmpty() }
            }
        }
    }

    /** 第一个声明 candidate_transform 且已加载的插件运行时（v1 单插件；链式变换为后续增强）。
     *  返回 (插件 id, 运行时)；无可用插件返回 null。 */
    private fun findRuntime(): Pair<String, JsScriptRuntime>? {
        for ((pluginId, loaded) in PluginManager.loadedPluginsFlow.value) {
            if (loaded.pluginInfo.capabilities?.candidateTransform != true) continue
            val script = loaded.script ?: continue
            return pluginId to script
        }
        return null
    }

    /** 诊断日志：hotPath 每键调用。调用点只传原始值（零字符串操作），
     *  isLoggable 未开启时立即返回（唯一开销是一次 int 读+比较，~1ns）。 */
    private fun logTransform(mode: String, pluginId: String?, input: String, state: String, count: Int) {
        if (!Log.isLoggable(XimeInputMethodService.TAG, Log.DEBUG)) return
        Log.d(XimeInputMethodService.TAG, "candidate_transform[$mode] plugin=$pluginId input='$input' $state=$count")
    }
}
