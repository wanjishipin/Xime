#ifndef T9_LETTER_BUFFER_STRATEGY_H_
#define T9_LETTER_BUFFER_STRATEGY_H_

#include <string>
#include <vector>
#include <optional>

#include "t9_right_commit_handler.h"

namespace rime {

// 前轮选择上下文：封装 SELECTION 态下频繁传递的三个参数。
// 用于减少 HandleSelectionPrefixConsumed / HandleSelectionLetterBufferCommit 等方法的参数数量。
struct SelectionContext {
    const std::optional<SyllableOption>& prev_selected_option;
    const std::optional<std::string>& prev_selection_candidate_digits;
    const std::string& prev_confirmed_pinyin;
};

// 额外音节 full commit 判定结果（CheckExtraSyllableCommit 的返回值）
struct ExtraSyllableCommitCheck {
    bool last_syl_covers_prev_opt = false;  // 覆盖末选择的音节是否匹配末选择
    bool is_full_commit = false;            // 是否构成 full commit
    int covered_prefix_len = 0;             // 覆盖末选择的音节与末选择的公共前缀长度
};

// LetterBuffer 策略：仅 selections 非空（unassigned 为空）时的选词处理。
//
// 含 SELECTION 子模式（4 个子方法）：
//   - HandleSelectionPrefixConsumed
//   - HandleSelectionLetterBufferCommit
//   - HandleConsumedAllNonSelected
//   - HandlePartialConsumedNonSelected
class LetterBufferStrategy {
public:
    bool Handle(T9RightCommitHandler& owner,
                T9RightCommitHandler::Context& ctx,
                const std::string& remaining_digits,
                const std::optional<std::string>& candidate_pinyin,
                int candidate_text_length,
                const std::optional<SyllableOption>& prev_selected_option,
                const std::optional<std::string>& prev_selection_candidate_digits,
                const std::string& prev_confirmed_pinyin,
                const T9Buffer& prev_buf);

    // 纯函数：候选词音节数 > 选择数（completion 额外音节）时的 full commit 判定。
    // 覆盖末选择的音节是候选词第 selection_count 个音节（非最后一个——额外音节
    // 如"kai de qi wan xiao"的"xiao"不以末选择"w"开头，而第 4 个音节"wan"匹配）。
    // 无副作用，可独立单测。
    static ExtraSyllableCommitCheck CheckExtraSyllableCommit(
        const std::vector<std::string>& comment_syllables,
        int selection_count,
        const SyllableOption& prev_selected_option,
        bool has_unassigned,
        int candidate_text_length);

private:
    // SELECTION 态：消费前缀拼音（基于 consumed_pinyin_len 从 selected_pinyin 中 Take）
    bool HandleSelectionPrefixConsumed(
        T9RightCommitHandler& owner,
        T9RightCommitHandler::Context& ctx,
        const T9Buffer& buf,
        const std::string& selected_pinyin,
        const std::string& remaining_digits,
        int consumed_pinyin_len,
        const SelectionContext& sel_ctx);

    // SELECTION 态字母 buffer 处理（设计稿 §6.6）
    bool HandleSelectionLetterBufferCommit(
        T9RightCommitHandler& owner,
        T9RightCommitHandler::Context& ctx,
        const std::optional<std::string>& candidate_pinyin,
        int candidate_text_length,
        int letter_count,
        const SelectionContext& sel_ctx,
        const T9Buffer& prev_buf);

    int ComputeSelectionConsumedCount(
        bool has_syllable_boundaries,
        int candidate_text_length,
        const std::vector<std::string>& comment_syllables,
        int letter_count,
        const SyllableOption& prev_selected_option,
        const std::string& non_selected_digits,
        const std::optional<std::string>& candidate_pinyin);

    bool HandleConsumedAllNonSelected(
        T9RightCommitHandler& owner,
        T9RightCommitHandler::Context& ctx,
        bool has_syllable_boundaries,
        int candidate_text_length,
        const std::vector<std::string>& comment_syllables,
        const std::optional<std::string>& candidate_pinyin,
        const SyllableOption& prev_selected_option,
        const std::optional<std::string>& prev_selection_candidate_digits,
        const std::string& non_selected_part,
        const std::string& non_selected_digits,
        int consumed_count,
        const T9Buffer& prev_buf);

    bool TryShengmuFallback(
        T9RightCommitHandler& owner,
        T9RightCommitHandler::Context& ctx,
        bool has_syllable_boundaries,
        const std::vector<std::string>& comment_syllables,
        const SyllableOption& prev_selected_option,
        const std::optional<std::string>& prev_selection_candidate_digits,
        const std::string& non_selected_part,
        const T9Buffer& prev_buf);

    bool HandlePartialConsumedNonSelected(
        T9RightCommitHandler& owner,
        T9RightCommitHandler::Context& ctx,
        int consumed_count,
        const std::string& non_selected_part,
        const std::string& non_selected_digits,
        const SyllableOption& prev_selected_option,
        const std::optional<std::string>& prev_selection_candidate_digits,
        const std::string& prev_confirmed_pinyin);

    // 防御性无注释提交
    bool HandleDefensiveNoCommentCommit(
        T9RightCommitHandler& owner,
        T9RightCommitHandler::Context& ctx,
        int candidate_text_length,
        const SelectionContext& sel_ctx,
        const T9Buffer& prev_buf);

    // ── 子路径 C 条件谓词 ──

    // 候选词音节数等于 selections 数，remaining 空但有 unassigned
    // → 候选词仅覆盖 selections，不覆盖 unassigned
    static bool IsFullSelectionOnly(const std::string& remaining_digits,
        const T9Buffer& buf, int syllable_count, int selection_count);

    // 候选词音节数大于 selections 数，且有 unassigned 和 remaining
    // → 候选词有额外音节部分消费了 unassigned，但未全部消费完
    static bool IsExtraSyllablePartial(const std::string& remaining_digits,
        const T9Buffer& buf, int syllable_count, int selection_count);
};

}  // namespace rime

#endif  // T9_LETTER_BUFFER_STRATEGY_H_
