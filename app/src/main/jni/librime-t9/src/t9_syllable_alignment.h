#ifndef T9_SYLLABLE_ALIGNMENT_H_
#define T9_SYLLABLE_ALIGNMENT_H_

#include <string>
#include <vector>
#include <optional>

#include "t9_buffer.h"
#include "t9_pinyin_map.h"

namespace rime {

// 音节对齐结果中的匹配类型
//
// 对应 ConsumedResultStatus，但语义更聚焦于"对齐"而非"消费"：
//   kNone    — 未匹配（音节数字码与对应数字段无公共前缀）
//   kFull    — 完整匹配（音节数字码与对应数字段完全一致）
//   kPrefix  — 前缀匹配（声母级别，仅首位数字相同）
enum class MatchType {
    kNone,
    kFull,
    kPrefix,
};

// 音节与 T9Buffer 的对齐结果（dev-t9-v2 §4.2）
//
// 替代散落的字母数差值/贪婪匹配判断，提供语义明确的结构化指标：
//   - selections_consumed: 候选词前缀音节覆盖的 selection 数
//   - digits_consumed:     候选词对齐覆盖的总数字位数（selections 段 + unassigned 段）
//   - unassigned_consumed: 阶段2从 unassigned 消费的数字位数（始终计算，不受
//                          covers_all_selections 影响，S4-统一）
//   - covers_all_selections: 候选词是否覆盖所有 selections
//   - covers_unassigned:     候选词是否覆盖整个 unassigned（仅当 covers_all_selections 时有意义）
//   - last_match_type:       最后一次匹配的类型（Full/Prefix/None）
struct AlignmentResult {
    int selections_consumed = 0;
    int digits_consumed = 0;
    int unassigned_consumed = 0;
    bool covers_all_selections = false;
    bool covers_unassigned = false;
    MatchType last_match_type = MatchType::kNone;
};

// SyllableAlignment — 候选词音节与 T9Buffer 的对齐模型
//
// 设计目标（dev-t9-v2 §4.2）：
//   1. 一次构造，多次使用 — 消除 ParseSyllables 重复调用（S7）
//   2. 预计算音节数字码 — 消除重复 PinyinToDigitCode 调用
//   3. 结构化对齐结果 — 替代散落的字母数差值/贪婪匹配判断
//
// 引入阶段（S1）：作为现有算法的"观察者"，不改旧逻辑；
// S2 起在 ComputeRightCommitConsumption 中作为对照使用；
// S4 起 Strategy 内部基于此重写消费逻辑。
struct SyllableAlignment {
    std::vector<std::string> syllables;       // 候选词音节列表（一次解析）
    std::vector<std::string> syllable_codes;  // 每个音节的数字码（预计算，空串表示无效音节）
    int total_digit_length = 0;               // 所有有效音节数字码总长度

    // 是否为空（无音节）
    bool empty() const { return syllables.empty(); }
    int syllable_count() const { return static_cast<int>(syllables.size()); }

    // 一次性计算候选词音节与 buffer 的对齐结果
    //
    // 算法（两阶段，对应 dev-t9-v2 §4.6）：
    //   阶段1 — 候选词前缀音节与 buf.selections 对齐
    //     逐音节比较 syllable_codes[i] 与 selection[i] 的数字码：
    //       - 完全相同 → 覆盖该 selection，last_match_type=kFull
    //       - 互为前缀（声母/简拼场景）→ 覆盖该 selection，last_match_type=kPrefix
    //       - 否则 → 终止阶段1
    //     digits_consumed 累加 sel.digit_length（selection 已占用的数字位数）
    //
    //   阶段2 — 剩余音节与 buf.unassigned 对齐（S4-统一：始终执行）
    //     从阶段1停止处（selections_consumed）开始：
    //       - 完整匹配（remaining 前缀 == syl_code）→ 消费 syl_code.size() 位
    //       - 否则按"最长公共前缀 + 最后音节退化 + 候选音节超长退化"规则消费
    //       - max_prefix == 0 → 终止阶段2
    //     unassigned_consumed 始终计算（不受 covers_all_selections 影响）
    //     covers_unassigned = (remaining 耗尽 && 所有音节匹配完)，
    //                         且仅当 covers_all_selections 时有意义
    AlignmentResult AlignWithBuffer(const T9Buffer& buf) const;

    // 静态工厂：从候选词拼音注释构建 SyllableAlignment
    // 等价于 ParseSyllables + 逐音节 PinyinToDigitCode 的组合，但只执行一次
    static SyllableAlignment FromCandidatePinyin(
        const std::optional<std::string>& candidate_pinyin);
};

}  // namespace rime

#endif  // T9_SYLLABLE_ALIGNMENT_H_
