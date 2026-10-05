#ifndef T9_STATE_MACHINE_H_
#define T9_STATE_MACHINE_H_

#include <string>
#include <vector>
#include <optional>

#include "t9_pinyin_map.h"

namespace rime {

// 左侧候选区三态状态机
//
// 封装 T9InputController 中左侧候选区的状态管理逻辑。
// 严格遵循设计文档第 2.3 节"左侧选择模型"和第 3.2 节"交互状态机"。
//
// 对应 Kotlin T9StateMachine.kt
//
// 设计决策：
// - 本类为纯逻辑层，不持有 Compose 状态。
// - selection_history 记录当前会话中每次左选确认，用于候选词过滤。
// - LeftSelection 封装选中态的完整上下文（文档 2.3 节）。
class T9StateMachine {
public:
    // 左侧候选区状态
    enum class State { kIdle, kInput, kSelection };

    // 左侧选择模型（设计文档 2.3 节）。
    // 封装 SELECTION 态下当前选中项的完整上下文。
    struct LeftSelection {
        SyllableOption selected_option;       // 当前选中的拼音/字母
        std::string selection_digits;          // 选中项对应的数字子串（来自 unassigned）
        std::string pre_selected_pinyin;       // 选中项之前、已由左侧选择产生的拼音序列

        int digit_length() const { return selected_option.digit_length; }
        const std::string& pinyin() const { return selected_option.pinyin; }
    };

    // 状态快照，用于撤销恢复
    struct StateSnapshot {
        State state = State::kIdle;
        std::optional<SyllableOption> selected_option;
        std::optional<std::string> selection_candidate_digits;
        std::string confirmed_pinyin_before_selection;
        std::vector<SyllableOption> selection_history;
    };

    T9StateMachine() = default;

    // ── 状态查询 ──

    State state() const { return state_; }

    // 选择态下当前选中的拼音/字母选项
    const std::optional<SyllableOption>& selected_option() const { return selected_option_; }

    // 选择态下产生当前候选列表的数字段
    const std::optional<std::string>& selection_candidate_digits() const {
        return selection_candidate_digits_;
    }

    // 选择态下选中项之前的已确认拼音前缀
    const std::string& confirmed_pinyin_before_selection() const {
        return confirmed_pinyin_before_selection_;
    }

    // 当前输入会话中所有已选择的拼音/字母历史（按选择顺序）
    const std::vector<SyllableOption>& selection_history() const {
        return selection_history_;
    }

    // 当前选择上下文；非 SELECTION 态时为 nullopt
    std::optional<LeftSelection> left_selection() const {
        if (is_selection() && selected_option_.has_value()) {
            return LeftSelection{
                *selected_option_,
                selection_candidate_digits_.value_or(""),
                confirmed_pinyin_before_selection_
            };
        }
        return std::nullopt;
    }

    // ── 状态转换 ──

    // 进入选择态，记录本次选择到历史
    void EnterSelection(const SyllableOption& option,
                        const std::string& candidate_digits,
                        const std::string& confirmed_pinyin = "");

    // 进入空闲态，清空全部状态含选择历史
    void EnterIdle();

    // 进入输入态（保留选择历史）
    void EnterInput();

    // ── 选择历史维护 ──

    // 清空选择历史
    void ClearSelectionHistory() { selection_history_.clear(); }

    // 移除最后一个选择历史条目
    void RemoveLastSelectionHistoryEntry();

    // partial commit 后从 selectionHistory 头部移除已被消费的条目
    // 对应 Kotlin removeConsumedHistoryEntries
    void RemoveConsumedHistoryEntries(const std::string& consumed_pinyin);

    // ── 快照 / 恢复 ──

    // 获取当前状态快照
    StateSnapshot Snapshot() const;

    // 从原始值恢复状态（用于快照撤销场景）
    void RestoreFrom(State state,
                     const std::optional<SyllableOption>& option,
                     const std::optional<std::string>& digits,
                     const std::string& confirmed_pinyin = "",
                     const std::vector<SyllableOption>& history = {});

    // ── 便捷查询 ──

    bool is_idle() const { return state_ == State::kIdle; }
    bool is_input() const { return state_ == State::kInput; }
    bool is_selection() const { return state_ == State::kSelection; }
    bool has_selection() const { return is_selection() && selected_option_.has_value(); }

private:
    State state_ = State::kIdle;
    std::optional<SyllableOption> selected_option_;
    std::optional<std::string> selection_candidate_digits_;
    std::string confirmed_pinyin_before_selection_;
    std::vector<SyllableOption> selection_history_;
};

}  // namespace rime

#endif  // T9_STATE_MACHINE_H_
