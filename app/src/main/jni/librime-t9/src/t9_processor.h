#ifndef T9_PROCESSOR_H_
#define T9_PROCESSOR_H_

#include <rime/processor.h>
#include <rime/component.h>
#include <rime/dict/vocabulary.h>  // DictEntry / Code / SyllableId / Syllabary
#include <optional>
#include <string>
#include <unordered_map>
#include <vector>

#include "t9_buffer.h"
#include "t9_state_machine.h"
#include "t9_undo_model.h"
#include "t9_right_commit_handler.h"
#include "t9_pinyin_map.h"
#include "t9_panel_state.h"  // LeftPanelStateData, T9PanelStateContext, t9_panel_state::*

namespace rime {

// 前向声明（用户词典调频：对齐全键盘 Memorize 机制）
class Dictionary;
class UserDictionary;

// 九键拼音输入处理器（RIME Processor 组件）。
//
// 整合 T1-T6 组件，内聚全部状态与算法（设计稿 §13.1 模块分工）。
// 对应 Kotlin main 分支 T9InputController + T9RimeBridge 的全部逻辑。
//
// 设计决策（规划文档 §3.4 D4）：
//   - 单一 Processor 持有全部状态，避免跨类传递 Context
//   - 通过 RIME engine_->context() 直接读写 RIME 状态
//   - JNI 全局指针 g_active_t9_processor 暴露给 Kotlin 薄包装层
//
// 状态管理：
//   - input_buffer_：结构化输入模型（T9Buffer）
//   - state_machine_：左侧候选区三态状态机
//   - undo_model_：段模型（回退唯一真相源，两阶段状态机；2026-08-06 起命令模式已完全移除）
//   - right_commit_handler_：右侧选词三层消费算法
//   - left_column_locked_ / separator_consumed_digits_ / last_choice_consumed_digits_：
//     分词键与左选交互的临时状态
//   - last_rime_input_：发送去重缓存
class T9Processor : public Processor {
public:
    T9Processor(const Ticket& ticket);
    ~T9Processor() override;

    // ── RIME Processor 接口 ──
    ProcessResult ProcessKeyEvent(const KeyEvent& key_event) override;

    // ── JNI 暴露接口（供 T9InputController 薄包装层调用）──

    // 直接选择拼音（对应 Kotlin handleLeftSelectChoice / handleSelectionReplacement）
    // 内部实现 LeftChoice 双写（设计稿 §5.2）
    void SelectPinyinDirect(const std::string& pinyin, int digit_length);

    // 右侧候选选词，返回 true = 完整消费（full commit）
    // 委托给 T9RightCommitHandler 三层消费算法
    // 注：传入拼音注释（comment）、候选文本与字数而非索引，避免翻页后索引
    //     错位；并按 (comment, text) 双条件定位（同注释歧义防错码，如
    //     几股/击鼓 均 "ji gu"）。
    bool SelectCandidate(const std::string& candidate_pinyin,
                         const std::string& candidate_text,
                         int candidate_text_length);

    // ── 用户词典调频（Kotlin 上屏路径为唯一真相源）──
    // 调频文本与拼音由 Kotlin 在 full commit（含 partial 拼接）时传入，
    // C++ 构造 RIME 原生 DictEntry 写入（key 由 RIME 生成）；撤销段时 ForgetEntry 回滚。
    bool MemorizeEntry(const std::string& text, const std::string& pinyin);
    bool ForgetEntry(const std::string& text, const std::string& pinyin);

    // ── Phrase 码缓存（t9_filter 预存，右选调频捕获兜底）──
    // t9_filter 位于 filters 最前，此时候选尚为带调 Phrase；后续 lua filter 链
    // 可能重建为非 Phrase（As<Phrase> 失败），缓存保证仍能取到精确调频码。
    void CachePhraseCode(const std::string& text, const std::string& comment, const Code& code);
    void ClearPhraseCodeCache();
    // 按 (text, 归一化 comment) 精确匹配返回码；未命中返回空。
    T9SyllableCode FindPhraseCode(const std::string& text,
                                  const std::string& normalized_comment) const;

    // 获取 partial commit 后剩余的数字串
    std::string GetRemainingDigits() const;

    // 获取首音节候选列表（P3 方案 A：替代 Kotlin T9PinyinMap.firstSyllableOptions）
    // 委托给 T9PinyinMap::Instance().FirstSyllableOptions()
    // out 每项格式 "pinyin|digitLength"，供 JNI 序列化传输
    void GetFirstSyllableOptions(const std::string& digits, int max_results,
                                  std::vector<std::string>& out) const;

    // 获取并消费 RightCommit 撤销计数（Kotlin 调用以同步 t9PartialCommitTexts）
    int GetAndConsumeUndoneRightCommitCount();

    // 返回格式：
    // STATE;SELECTED_PINYIN;SELECTED_DIGIT_LENGTH;SELECTION_CANDIDATE_DIGITS;PANEL_DIGITS
    // 当 unassigned 非空时，SELECTED_PINYIN/SELECTED_DIGIT_LENGTH 置空（Kotlin 无需二次判断）
    // PANEL_DIGITS 已内含分词键锁定 + unassigned + selectionCandidateDigits + separatorConsumedDigits 回退逻辑
    //
    // P4/P5（2026-07-19）：保留用于向后兼容/调试日志，
    // 新 JNI 调用应使用 GetLeftPanelState(LeftPanelStateData&) 结构化重载。
    std::string GetLeftPanelState() const;

    // P4/P5：结构化状态查询，消除字符串序列化/解析。
    // 语义与 GetLeftPanelState() 完全一致，仅输出形式不同。
    void GetLeftPanelState(LeftPanelStateData& out) const;

    // ── T8: ReplaceFullPinyin / ClearComposition ──
    // 批量替换 RIME 输入为完整拼音（对应 Kotlin onT9ReplaceFullPinyin）
    void ReplaceFullPinyin(const std::string& pinyin);

    // 两种清理模式：mode=0 (CLEAR_COMPOSITION_ONLY), mode=1 (CLEAR_ALL)
    void ClearComposition(int mode);

    // ── 异步 flush（JNI 调用）──
    // 执行 SendToRime 标记的待发送引擎动作（set_input → compose / clear 等）。
    // 由应用层在 processKey 之后的后台线程调用，避免引擎 compose 阻塞 UI 线程。
    void FlushRimeInput();

private:
    // ── 按键处理子流程 ──
    ProcessResult HandleDigitKey(char ch);
    ProcessResult HandleSeparatorKey();      // 分词键 1
    ProcessResult HandleApostropheKey();     // 分词键 '
    // 回退：段模型两阶段状态机（T9UndoModel::Backspace，2026-08-06 起命令模式已移除）
    ProcessResult HandleBackspace();

    // ── LeftChoice 子流程（设计稿 §5.2）──
    void HandleLeftSelectChoice(const SyllableOption& option);
    void HandleSelectionReplacementChoice(const SyllableOption& option);

    // ── RIME 交互 ──
    // 异步 flush 模型（对标 Kotlin 版异步 sendToRime）：
    //   SendToRime()    只计算"待发送内容"并标记 pending，不直接调用引擎
    //                    （埋点范围不含引擎 compose，对标 Kotlin t9_send_to_rime）
    //   FlushRimeInput() 真正执行引擎调用（set_input → compose），
    //                    由应用层在 processKey 之后的后台线程调用
    //                    （公开声明见上方 JNI 暴露接口区）
    void SendToRime();
    void SyncRimeInput(const std::optional<std::string>& input);
    std::optional<SyllableOption> InferFirstSyllableFromRime(const std::string& digits);

    // ── 状态转换 ──
    void EnterIdle();
    void EnterSelection(const SyllableOption& option,
                        const std::string& candidate_digits,
                        const std::string& confirmed_pinyin = "");
    // 段模型回退后派生 state_machine_（设计文档 §6）：
    //   INPUT：存在 unassigned 段/tail → EnterInput
    //   SELECTION：存在 selected 段 → EnterSelection(最后 selected 段)
    //   IDLE：全 committed 或已删 → EnterIdle
    void DeriveStateMachineFromUndoModel();

    // ── RightCommit 上下文构建/应用 ──
    void BuildHandlerContext(T9RightCommitHandler::Context& out);
    void ApplyHandlerContext(const T9RightCommitHandler::Context& ctx);

    // ── 辅助 ──
    void LogPreeditState();
    // 构造 DictEntry（text + 拼音音节 code）：无声调音节优先带声调变体
    // （toned_syllable_map_，声调保真），其次精确匹配，最后 Prism 兜底；
    // 音节完全无法解析时返回 false（如英文/符号，放弃调频）。
    bool BuildEntryForPinyin(const std::string& text, const std::string& pinyin,
                             DictEntry* entry);
    // 通过方案 Prism（含 speller algebra：xlit 声调消除 / 简拼派生）将拼音音节
    // 解析为词典原生 SyllableId，解决带声调词典（如带声调方案 běn）与无声调调频拼音
    // （ben）的格式差异。返回覆盖整个输入的单音节 id；无法解析返回 nullopt。
    // 例：ben → běn 的 SyllableId；ji → jǐ/轻声 ji 等任一命中变体。
    std::optional<SyllableId> ResolveSyllableViaPrism(const std::string& syllable);
    // 惰性构建 无声调拼写 → 带声调音节 映射（调频声调保真，2026-08-16）：
    // 带声调类词库 syllables 同时含带调音节（jì）与轻声音节（ji，簸箕），
    // 无声调调频拼音直接命中轻声会丢声调 → userdb 候选 comment 无声调。
    // 本映射保证无声调输入优先选带调变体（jī/jí/jǐ/jì 任一）。
    void EnsureTonedSyllableMap();
    // 判断音节是否含声调字符（非 ASCII 字节）。
    static bool HasTone(const std::string& syllable);
    // 调频写入/回滚公共实现（MemorizeEntry/ForgetEntry 共用）：commits=+1 记忆 / -1 回滚。
    bool UpdateDictEntry(const std::string& text, const std::string& pinyin, int commits);
    // 使用已解析的 Code 直接写/回滚用户词典（MemorizeEntry 的右选码捕获路径复用）。
    bool WriteDictEntry(const std::string& text, const Code& code, int commits);

    // 方案 A（消费算法优化）：查询 RIME 候选的实际匹配结束位置，
    // 换算为 T9 应消费的数字位数（RIME input[0:end) 中数字字符数，跳过分隔符）。
    // 候选按 comment 归一化匹配（容忍带调/无调差异，见 NormalizePinyinComment），
    // candidate_text 非空时再按文本双条件定位（同注释歧义防错码）。
    // 返回 -1 表示无法确定（fallback 到现有消费算法）。
    // captured_code / captured_text（可空）：命中候选为 Phrase 时顺带捕获其真实码。
    // out_is_t9_user（可空）：命中候选为 T9 用户词时置 true。
    int QueryRimeConsumedDigits(
        const std::optional<std::string>& candidate_pinyin,
        const std::string& candidate_text,
        Code* captured_code = nullptr,
        std::string* captured_text = nullptr,
        bool* out_is_t9_user = nullptr) const;

    // ════════════════════════════════════════
    // 状态成员（对应 Kotlin T9InputController 的私有字段）
    // ════════════════════════════════════════
    T9Buffer input_buffer_;                              // §2.2 结构化输入模型
    T9StateMachine state_machine_;                       // §3.2 三态状态机
    // 段模型（2026-08-06 起为回退唯一真相源）：输入操作双写
    // （Digit/LeftChoice/Separator/SyncRightCommit），backspace 全部走 T9UndoModel::Backspace。
    T9UndoModel undo_model_;
    T9RightCommitHandler right_commit_handler_;          // §6.2 三层消费算法

    bool left_column_locked_ = false;                    // 分词键锁定标记
    std::optional<std::string> separator_consumed_digits_;   // 分词键确认的数字段
    std::optional<std::string> last_choice_consumed_digits_; // 上次左选消费的数字段
    std::string last_rime_input_;                        // 发送去重缓存
    int undone_right_commit_count_ = 0;                  // RightCommit 撤销计数（供 Kotlin 同步）
    char manual_delimiter_ = '\'';                       // 分隔符字符（从 speller.delimiter 读入）
    std::string original_digit_sequence_;
    std::string last_commit_digit_sequence_;
    // 本次 FullCommit 右选的调频捕获，跨异步上屏链路存活。
    // SelectCandidate 中同步暂存（避免 undo_model 在 EnterIdle 时被清空，
    // 导致 MemorizeEntry 读到时为空、词典词被误判为场景 B/C）。
    std::optional<std::pair<std::string, T9SyllableCode>>
        pending_fullcommit_capture_;
    // 左侧候选区模式（2026-08-07，英文九键适配）：
    // 构造时按 engine/translators 是否含 script_translator 判定（auto），
    // 可被 t9/left_panel_mode: pinyin|none 显式覆盖。
    // kNone（英文/词级预测方案）：左栏返回 IDLE、首音节候选为空、左选 no-op。
    t9_panel_state::LeftPanelMode left_panel_mode_ =
        t9_panel_state::LeftPanelMode::kPinyin;

    // ── 用户词典调频（对齐全键盘 Memorize 机制，2026-08-11）──
    // 通过组件池与主翻译器（script_translator）共享 Table/Prism/db：
    //   dict_      dictionary: rime_frost + prism: t9
    //   user_dict_ user_dict:  rime_frost（enable_user_dict 默认 true）
    // 由 Kotlin 在 full commit 时调用 MemorizeEntry/ForgetEntry 写入（UpdateEntry）。
    the<Dictionary> dict_;
    the<UserDictionary> user_dict_;
    // 音节→SyllableId 映射（惰性构建，与 UserDictionary::Lookup 的 RecruitEntry
    // 构造方式一致：GetSyllabary 返回顺序即 id）。用于把拼音音节转为原生 Code。
    std::unordered_map<std::string, SyllableId> syllabary_map_;
    // 无声调拼写 → 带声调音节 SyllableId（惰性构建，见 EnsureTonedSyllableMap）。
    std::unordered_map<std::string, SyllableId> toned_syllable_map_;
    // Phrase 码缓存条目（t9_filter 预存，每次 flush 重建）。
    struct PhraseCodeEntry {
        std::string text;
        std::string comment;  // 带调原始 comment（t9_filter 阶段，lua 尚未改写）
        T9SyllableCode code;
    };
    std::vector<PhraseCodeEntry> phrase_code_cache_;

    // ── 异步 flush 状态（SendToRime 标记，FlushRimeInput 消费）──
    // pending_action_ / pending_input_ 的读写都发生在 RimeEngine.rimeLock
    // 临界区内（processKey 与 t9FlushRimeInput 均持有该锁），无数据竞争。
    enum class RimePendingAction {
        kNone,             // 无待发送动作
        kSetInput,         // 发送 pending_input_ 到引擎（触发 compose）
        kClear,            // 空 buffer：ctx->Clear()
        kZombieClear,      // 僵尸 RC：清 composition + 置空 input
    };
    RimePendingAction pending_action_ = RimePendingAction::kNone;
    std::string pending_input_;                          // kSetInput 时的参数
};

// 全局活跃 T9Processor 指针（供 JNI 访问）
T9Processor* T9ProcessorRequire();

// P7（2026-07-19）：T9 方案 schema 注入所需的 patch 条目。
//
// C++ 作为组件名的单一真相源：注册名（t9_module.cc）与注入配置名
// 均由此函数返回，Kotlin 端不再硬编码组件名。
// 格式："search_pattern|patch_key|patch_value"
//   - search_pattern: 在 schema YAML 中搜索的文本，判断是否已注入
//   - patch_key: librime patch 语法的 YAML 路径
//   - patch_value: patch 的值
//
// 方案 A: 若 t9 模块未注册（.so 缺失），JNI 层返回空数组，
// Kotlin 端收到空列表后跳过注入，避免写无效配置到 custom.yaml。
std::vector<std::string> GetT9SchemaPatches();

}  // namespace rime

#endif  // T9_PROCESSOR_H_
