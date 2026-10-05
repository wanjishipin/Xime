#ifndef T9_DIGIT_SEGMENT_STRATEGY_H_
#define T9_DIGIT_SEGMENT_STRATEGY_H_

#include <string>
#include <optional>

#include "t9_right_commit_handler.h"

namespace rime {

// DigitSegment 策略：仅 unassigned 非空（selections 为空）时的选词处理。
class DigitSegmentStrategy {
public:
    // 统一消费计算（CRCC 与 Handle 共享，消除双算与逻辑不一致）：
    //   - 方案 A：rime_consumed_digits > 0 时优先（RIME 候选 end 精确反映派生编码匹配范围）
    //   - fallback：AlignWithBuffer 统一算法（最长公共前缀 + 音节退化）+ 分隔符分段匹配
    // 返回 [0, unassigned.size()] 的消费位数。
    static int ComputeConsumedCount(const T9Buffer& buf,
                                    const std::optional<std::string>& candidate_pinyin,
                                    int rime_consumed_digits);

    // @param remaining_digits CRCC 计算的消费后剩余数字（= consumption.second），
    //                         Handle 不再重复计算，仅做锁定/状态转换。
    bool Handle(T9RightCommitHandler& owner,
                T9RightCommitHandler::Context& ctx,
                const std::string& remaining_digits,
                const std::optional<SyllableOption>& prev_selected_option,
                const std::optional<std::string>& prev_selection_candidate_digits,
                const std::string& prev_confirmed_pinyin);
};

}  // namespace rime

#endif  // T9_DIGIT_SEGMENT_STRATEGY_H_
