#include "t9_digit_segment_strategy.h"

#include "t9_log.h"
#include "t9_right_commit_handler.h"
#include "t9_right_commit_utils.h"
#include "t9_string_utils.h"
#include "t9_syllable_alignment.h"

namespace rime {

int DigitSegmentStrategy::ComputeConsumedCount(
    const T9Buffer& buf,
    const std::optional<std::string>& candidate_pinyin,
    int rime_consumed_digits) {

    const std::string& segment = buf.unassigned();

    // 方案 A：RIME 候选 end 优先（精确反映 schema 派生编码的匹配范围）。
    // 如"涌动 yong dong"派生编码 966+36 匹配整个 96636，RIME end 换算=5，
    // 而 AlignWithBuffer 用完整拼音编码（yong=9664, dong=3664）只算出 4。
    if (rime_consumed_digits > 0) {
        int consumed = std::min(rime_consumed_digits,
                                static_cast<int>(segment.size()));
        RCLOG(">> DigitSegment: rimeConsumed=%d (segment='%s')", consumed, segment.c_str());
        return consumed;
    }

    // S4：基于 SyllableAlignment::AlignWithBuffer 单一算法计算消费位数
    // 替代双路径（ComputeConsumedDigitsMultiSyllable 非贪婪 +
    // ComputeConsumedDigitsFromPinyin 贪婪 fallback）。
    // DigitSegment 场景下 buf.selections 为空，阶段1 vacuous true（covers_all_selections=true），
    // 阶段2 直接对每个音节匹配 unassigned（最长公共前缀 + 最后音节退化 +
    // 候选音节超长退化），与旧双路径在以下对照场景行为等价：
    //   - ZouDe(4位)、HaiHui(2位)、GuoHui(1位)、JieGouGuan(1位)
    auto alignment = SyllableAlignment::FromCandidatePinyin(candidate_pinyin);
    auto align_result = alignment.AlignWithBuffer(buf);
    int consumed_count = align_result.digits_consumed;
    RCLOG(">> DigitSegment: alignment sylCount=%d, digitsConsumed=%d, coversUnassigned=%d, segment='%s'",
          alignment.syllable_count(), consumed_count,
          align_result.covers_unassigned ? 1 : 0, segment.c_str());

    // 分隔符模式：当候选词音节数与段数匹配时，验证分段匹配结果
    // alignment 阶段2 不感知分隔符位置，需保留此特殊路径：
    // 例如 "543" (separator_positions=[1]) → "jia ge" (2 syllables)
    //   标准匹配 "jia"→"542" 从 "543" 消耗 "54"(2位)，剩余 "3" 不匹配 "ge"→"43"
    //   分段匹配 "jia"→"5"(1位) + "ge"→"43"(2位) = 3位 = full commit
    // 多分隔符推广：如 "5436" (positions=[1,3]) → "jian ge mian" (3 syllables)
    //   逐段匹配 [5]→"jian"、[43]→"ge"、[6]→"mian"，全部匹配且总消费 == 段长 → full commit
    if (consumed_count > 0 &&
        consumed_count < static_cast<int>(segment.size()) &&
        buf.has_separator() &&
        alignment.syllable_count() ==
            static_cast<int>(buf.separator_positions.size()) + 1) {
        // 按分隔符位置切段（consumed_count==0 时位置直接适用）
        std::vector<std::string> segs;
        int prev = 0;
        for (int pos : buf.separator_positions) {
            int p = std::min(pos, static_cast<int>(segment.size()));
            if (p >= prev) {
                segs.push_back(segment.substr(prev, p - prev));
                prev = p;
            }
        }
        segs.push_back(segment.substr(prev));
        // 逐段匹配音节，全部匹配且总消费 == 段长 → full commit
        int total_consumed = 0;
        bool all_segments_match = true;
        for (size_t i = 0; i < segs.size() && i < alignment.syllables.size(); ++i) {
            int c = ComputeConsumedDigitsFromPinyin(
                segs[i], std::optional<std::string>(alignment.syllables[i])).consumed_digits;
            if (c <= 0) {
                all_segments_match = false;
                break;
            }
            total_consumed += c;
        }
        if (all_segments_match &&
            total_consumed == static_cast<int>(segment.size())) {
            consumed_count = static_cast<int>(segment.size());
        }
    }
    return consumed_count;
}

bool DigitSegmentStrategy::Handle(
    T9RightCommitHandler& owner,
    T9RightCommitHandler::Context& ctx,
    const std::string& remaining_digits,
    const std::optional<SyllableOption>& prev_selected_option,
    const std::optional<std::string>& prev_selection_candidate_digits,
    const std::string& prev_confirmed_pinyin) {

    T9_SCOPED_TIMER_TAG("T9RightCommit", "DigitSegmentStrategy.Handle");
    const T9Buffer& buf = ctx.input_buffer;
    std::string segment = buf.unassigned();

    // 消费位数由 CRCC 统一计算（ComputeConsumedCount），Handle 从剩余数字反推，
    // 不再重复调用 AlignWithBuffer（消除双算与逻辑不一致）。
    int consumed_count = static_cast<int>(segment.size()) -
        static_cast<int>(remaining_digits.size());
    if (consumed_count < 0) consumed_count = 0;

    // 仅当仍有活跃 selections 时才锁定数字
    int locked_digits = 0;
    if (prev_selected_option.has_value() && !buf.selections.empty()) {
        locked_digits = prev_selection_candidate_digits.has_value()
            ? static_cast<int>(prev_selection_candidate_digits->size())
            : static_cast<int>(segment.size());
    }
    int max_consumable = static_cast<int>(segment.size()) - locked_digits;

    // 子路径 A：可消费数为 0
    if (max_consumable <= 0) {
        if (consumed_count > 0) {
            owner.ClearAndEnterIdle(ctx);
            return true;
        }
        ctx.left_column_locked = false;
        // undo fallback 特殊场景：RestoreFrom 后仅 sync_state + Pop undo，
        // 不调用 update_candidates / set_rime_input（保留原 RIME 候选与 input 状态）
        // 因此不复用 Context::TransitionTo* 系列
        ctx.state_machine.RestoreFrom(
            prev_selected_option.has_value()
                ? T9StateMachine::State::kSelection
                : T9StateMachine::State::kInput,
            prev_selected_option,
            prev_selection_candidate_digits,
            "",
            ctx.state_machine.selection_history());
        ctx.sync_state();
        return false;
    }

    // 子路径 B：正常消费
    if (consumed_count > max_consumable) consumed_count = max_consumable;
    std::string remaining = Drop(segment, consumed_count);

    if (remaining.empty()) {
        ctx.input_buffer = T9Buffer::EMPTY;
    } else {
        ctx.input_buffer = T9Buffer(
            buf.digit_sequence, {}, buf.consumed_count + consumed_count,
            buf.total_digits_entered);
    }
    ctx.left_column_locked = false;

    if (ctx.input_buffer.is_empty()) {
        ctx.TransitionToIdle();
        return true;
    }
    return owner.RestorePrevState(ctx, prev_selected_option,
                                   prev_selection_candidate_digits, prev_confirmed_pinyin);
}

}  // namespace rime
