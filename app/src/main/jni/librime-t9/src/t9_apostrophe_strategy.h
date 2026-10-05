#ifndef T9_APOSTROPHE_STRATEGY_H_
#define T9_APOSTROPHE_STRATEGY_H_

#include <string>
#include <vector>
#include <optional>

#include "t9_right_commit_handler.h"
#include "t9_syllable_alignment.h"

namespace rime {

// Apostrophe 策略：selections + unassigned 均非空时的选词处理。
//
// 子路径设计（原 v1.0 HandleApostropheRightCommit 拆分）：
//
//   Handle() 主入口
//     ├── 谓词 IsBranch1Applicable() → HandleBranch1SelectedEndingWithPrevOpt()
//     │     ├── 谓词 CanFullCommit() → ClearAndEnterIdle, return true
//     │     ├── 谓词 IsShengmuOrFullPinyinMatch() → 移除最后选择, return false
//     │     └── 默认 → RestorePrevState, return false
//     ├── 谓词 IsRemainingEmptyNoCover() → RemoveConsumedSelections + RestorePrevState
//     ├── 谓词 IsRemainingEmpty() → HandleRemainingEmpty()
//     ├── 谓词 CanCoverAll() → HandleCanCoverAll()
//     │     ├── 谓词 IsUnassignedCovered() → ClearAndEnterIdle, return true
//     │     └── 默认 → WithRemainingDigits + TransitionToInput
//     └── 默认 → HandlePartialConsume()
//           ├── 谓词 IsSyllableInitialMatch() → 部分消费 + 锁定左侧
//           └── 默认 → WithRemainingDigits + TransitionToInput
//
// S4-3：alignment 由 HandleRightCommit 一次构造，Handle 与 CRCC 共享，
// 消除 ParseSyllables / PinyinToDigitCode 重复调用。
class ApostropheStrategy {
public:
    bool Handle(T9RightCommitHandler& owner,
                T9RightCommitHandler::Context& ctx,
                const std::string& remaining_digits,
                const SyllableAlignment& alignment,
                const std::optional<std::string>& candidate_pinyin,
                const std::optional<SyllableOption>& prev_selected_option,
                const std::optional<std::string>& prev_selection_candidate_digits,
                const std::string& prev_confirmed_pinyin);

private:
    // branch1：confirmedPinyin 以 prevOpt.pinyin 结尾且更长
    // 说明 SELECTION 态的选中项是新候选词的最后一个音节
    bool HandleBranch1SelectedEndingWithPrevOpt(
        T9RightCommitHandler& owner,
        T9RightCommitHandler::Context& ctx,
        const T9Buffer& buf,
        const std::string& remaining_digits,
        const SyllableAlignment& alignment,
        const std::optional<std::string>& candidate_pinyin,
        const std::optional<SyllableOption>& prev_selected_option,
        const std::optional<std::string>& prev_selection_candidate_digits,
        const std::string& prev_confirmed_pinyin);

    // branch1 子路径 1b：候选词最后一个音节与 prevOpt 匹配（声母或全拼）
    // 匹配成功：移除最后一个选择 + TransitionToInput(false)，返回 true
    // 不匹配：返回 false（由调用者走子路径 1c 默认回退）
    // S4-3：用 alignment.syllable_codes 替代手动 PinyinToDigitCode
    bool TryLastSyllableMatch(
        T9RightCommitHandler::Context& ctx,
        const SyllableAlignment& alignment,
        const std::optional<SyllableOption>& prev_selected_option,
        const std::optional<std::string>& prev_selection_candidate_digits,
        const std::string& unconsumed_pinyin);

    // 路径 C：canCoverAll && remaining 非空
    // 验证 unassigned 是否被候选词对应音节覆盖
    // S4-3：用 alignment.syllable_codes 替代手动 PinyinToDigitCode
    bool HandleCanCoverAll(
        T9RightCommitHandler& owner,
        T9RightCommitHandler::Context& ctx,
        const T9Buffer& buf,
        const std::string& remaining_digits,
        const SyllableAlignment& alignment);

    // 路径 D：!canCoverAll && remaining 非空
    // 候选词音节数 < selections+unassigned 总数，可能消费部分 selections
    bool HandlePartialConsume(
        T9RightCommitHandler& owner,
        T9RightCommitHandler::Context& ctx,
        const T9Buffer& buf,
        const std::string& remaining_digits,
        const SyllableAlignment& alignment);

    // 谓词：候选词音节首字母与 selections 拼音首字母逐位匹配
    static bool IsSyllableInitialMatch(
        const std::vector<std::string>& comment_syllables,
        const std::vector<SyllableOption>& selections,
        int consumed_sel_count);

    // 应用部分消费 + 锁定左侧
    static void ApplyPartialConsumeAndLock(
        T9RightCommitHandler& owner,
        T9RightCommitHandler::Context& ctx,
        const T9Buffer& buf,
        const std::vector<SyllableOption>& selections,
        int consumed_sel_count);
};

}  // namespace rime

#endif  // T9_APOSTROPHE_STRATEGY_H_
