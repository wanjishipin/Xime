#include "t9_right_commit_handler.h"

#include <algorithm>
#include <cctype>
#include <utility>

#include "t9_apostrophe_strategy.h"
#include "t9_digit_segment_strategy.h"
#include "t9_letter_buffer_strategy.h"
#include "t9_log.h"
#include "t9_right_commit_utils.h"
#include "t9_undo_model.h"
#include "t9_string_utils.h"
#include "t9_syllable_alignment.h"

namespace rime {

// ════════════════════════════════════════════════════════════════
// Context 高层状态转换实现（评估报告 P11）
// ════════════════════════════════════════════════════════════════
//
// v1.0 中以下状态转换组合散落于 15+ 处：
//   sync_state() + update_candidates(true) + set_rime_input(...)
//   state_machine.EnterIdle() / EnterInput() / ClearSelectionHistory() / RestoreFrom(...)
// 各处参数组合略有差异，容易写错且难以维护。
// 这里封装为 3 个语义方法，让 Strategy 代码聚焦于业务分支判断。

void T9RightCommitHandler::Context::TransitionToIdle() {
    input_buffer = T9Buffer::EMPTY;
    left_column_locked = false;
    state_machine.EnterIdle();
    sync_state();
    update_candidates(true);
    set_rime_input(std::nullopt);
}

void T9RightCommitHandler::Context::TransitionToInput(bool keep_history) {
    if (!keep_history) {
        state_machine.ClearSelectionHistory();
    }
    if (input_buffer.is_empty()) {
        state_machine.EnterIdle();
    } else {
        state_machine.EnterInput();
    }
    sync_state();
    update_candidates(true);
    set_rime_input(input_buffer.ToBufferString(manual_delimiter));
}

void T9RightCommitHandler::Context::TransitionToSelection(
    const std::optional<SyllableOption>& option,
    const std::optional<std::string>& digits,
    const std::string& confirmed_pinyin) {
    if (option.has_value()) {
        state_machine.RestoreFrom(
            T9StateMachine::State::kSelection,
            option,
            digits.value_or(""),
            confirmed_pinyin.empty() ? std::string("") : confirmed_pinyin,
            state_machine.selection_history());
    } else {
        state_machine.EnterInput();
    }
    sync_state();
    update_candidates(true);
    set_rime_input(std::nullopt);
}

void T9RightCommitHandler::Context::SyncBufferToRime() {
    sync_state();
    update_candidates(true);
    set_rime_input(input_buffer.ToBufferString(manual_delimiter));
}

// ════════════════════════════════════════════════════════════════
// T9RightCommitHandler 协调器实现
// ════════════════════════════════════════════════════════════════

T9RightCommitHandler::T9RightCommitHandler()
    : apostrophe_strategy_(std::make_unique<ApostropheStrategy>()),
      digit_segment_strategy_(std::make_unique<DigitSegmentStrategy>()),
      letter_buffer_strategy_(std::make_unique<LetterBufferStrategy>()) {}

T9RightCommitHandler::~T9RightCommitHandler() = default;

// ── 协调器辅助方法 ──

void T9RightCommitHandler::ClearAndEnterIdle(Context& ctx) {
    ctx.TransitionToIdle();
}

bool T9RightCommitHandler::RestorePrevState(
    Context& ctx,
    const std::optional<SyllableOption>& prev_selected_option,
    const std::optional<std::string>& prev_selection_candidate_digits,
    const std::string& prev_confirmed_pinyin) {
    ctx.TransitionToSelection(prev_selected_option,
                              prev_selection_candidate_digits,
                              prev_confirmed_pinyin);
    return false;
}

T9Buffer T9RightCommitHandler::RemoveConsumedSelections(
    Context& ctx,
    const T9Buffer& buf,
    const std::string& consumed_pinyin) {

    RCLOG(">> RemoveConsumedSelections: ENTER consumedPinyin='%s', buf.digitSeq='%s', buf.consumedCount=%d, buf.selCount=%zu",
          consumed_pinyin.c_str(), buf.digit_sequence.c_str(), buf.consumed_count,
          buf.selections.size());
    for (size_t i = 0; i < buf.selections.size(); ++i) {
        RCLOG(">>   buf.sel[%zu]: '%s'(%d)", i,
              buf.selections[i].pinyin.c_str(), buf.selections[i].digit_length);
    }

    if (consumed_pinyin.empty()) {
        RCLOG(">> RemoveConsumedSelections: consumedPinyin empty → return buf unchanged");
        return buf;
    }
    std::string remaining = consumed_pinyin;
    int cut_index = 0;
    for (size_t i = 0; i < buf.selections.size(); ++i) {
        if (remaining.empty()) break;
        const auto& sel = buf.selections[i];
        if (static_cast<int>(sel.pinyin.size()) <= static_cast<int>(remaining.size()) &&
            StartsWith(remaining, sel.pinyin)) {
            remaining = Drop(remaining, static_cast<int>(sel.pinyin.size()));
            cut_index = static_cast<int>(i) + 1;
            RCLOG(">>   match sel[%zu] '%s' → remaining='%s', cutIndex=%d",
                  i, sel.pinyin.c_str(), remaining.c_str(), cut_index);
        } else {
            RCLOG(">>   no match sel[%zu] '%s' vs remaining='%s' → break",
                  i, sel.pinyin.c_str(), remaining.c_str());
            break;
        }
    }
    ctx.state_machine.RemoveConsumedHistoryEntries(consumed_pinyin);
    std::vector<SyllableOption> new_selections(
        buf.selections.begin() + cut_index, buf.selections.end());
    int new_selections_total_len = 0;
    for (const auto& s : new_selections) new_selections_total_len += s.digit_length;
    T9Buffer result(buf.digit_sequence, new_selections,
                    buf.consumed_count, buf.total_digits_entered);
    RCLOG(">> RemoveConsumedSelections: EXIT cutIndex=%d, newSelCount=%zu, newSelTotalLen=%d, keptConsumedCount=%d, result.unassigned='%s', result.toBufferString='%s'",
          cut_index, new_selections.size(), new_selections_total_len,
          result.consumed_count, result.unassigned().c_str(),
          result.ToBufferString().c_str());
    return result;
}

// ── 谓词 ──

// 判断当前是否为 letter-buffer 模式下的 SELECTION 态。
// 条件：prev_opt 存在且 selected_pinyin 以 prev_opt.pinyin 结尾且更长。
// 此时候选词应在 selections 段中消费，而非从 unassigned 消费。
bool T9RightCommitHandler::IsLetterBufferSelection(
    const std::string& selected_pinyin,
    const std::optional<SyllableOption>& prev_opt) {
    return prev_opt.has_value() &&
        EndsWith(selected_pinyin, prev_opt->pinyin) &&
        selected_pinyin.size() > prev_opt->pinyin.size();
}

// S3：路由分类谓词。集中 HandleRightCommit 与 CRCC 的路由判定。
//   !has_selections && has_unassigned → kDigitSegment
//    has_selections && has_unassigned → (IsLetterBufferSelection ? kLetterBuffer_Selection : kApostrophe)
//    else（has_selections 仅 / 双空）  → kLetterBuffer_Input
T9RightCommitHandler::RouteType T9RightCommitHandler::ClassifyRoute(
    const T9Buffer& buf,
    const std::optional<SyllableOption>& prev_opt) {
    bool has_selections = !buf.selections.empty();
    bool has_unassigned = !buf.unassigned().empty();
    if (!has_selections && has_unassigned) {
        return RouteType::kDigitSegment;
    }
    if (has_selections && has_unassigned) {
        if (IsLetterBufferSelection(buf.selected_pinyin(), prev_opt)) {
            return RouteType::kLetterBuffer_Selection;
        }
        return RouteType::kApostrophe;
    }
    return RouteType::kLetterBuffer_Input;
}

// ── 消费计算 ──

// 单音节候选词在 multi-selection 上下文中，防止贪婪数字前缀匹配跨越 selection 边界。
// 例如"jin"(546)匹配"54482"时，"i"→4碰巧等于下一 selection "g"→4，
// 贪婪匹配消费2位(j+g)，实际应只消费1位(j)。
// 本质：数字映射是多对一，逐数字贪婪匹配在拼音粒度上不精确。
// S7：接收 syllable_count 参数（由 alignment 提供），消除 ParseSyllables 冗余调用。
static int CapToFirstSelectionDigitLength(int consumed, const T9Buffer& buf,
    int syllable_count) {
    if (!buf.selections.empty() && buf.selections.size() > 1 && syllable_count <= 1) {
        int first_sel_digit_len = buf.selections[0].digit_length;
        if (consumed > first_sel_digit_len) {
            RCLOG(">>   capped consumed=%d → %d (single-syllable, first selection digit_len=%d)",
                  consumed, first_sel_digit_len, first_sel_digit_len);
            return first_sel_digit_len;
        }
    }
    return consumed;
}

std::pair<std::string, std::string>
T9RightCommitHandler::ComputeRightCommitConsumption(
    const T9Buffer& buf,
    const SyllableAlignment& alignment,
    const std::optional<std::string>& candidate_pinyin,
    RouteType route,
    int rime_consumed_digits,
    bool is_t9_user_word) {

    T9_SCOPED_TIMER_TAG("T9RightCommit", "ComputeRightCommitConsumption");
    bool has_selections = !buf.selections.empty();
    const std::string& unassigned = buf.unassigned();

    // S4-3：alignment 由 HandleRightCommit 一次构造并传入，CRCC 与 ApostropheStrategy 共享。
    // alignment.syllables 等价于 ParseSyllables(*candidate_pinyin)，但只解析一次。
    auto align_observation = alignment.AlignWithBuffer(buf);
    RCLOG(">> CRCC alignment: sylCount=%d, totalDigitLen=%d, selConsumed=%d, digitsConsumed=%d, coversAllSel=%d, coversUnassigned=%d, lastMatch=%d",
          alignment.syllable_count(), alignment.total_digit_length,
          align_observation.selections_consumed, align_observation.digits_consumed,
          align_observation.covers_all_selections ? 1 : 0,
          align_observation.covers_unassigned ? 1 : 0,
          static_cast<int>(align_observation.last_match_type));

    // apostrophe 模式：基于候选词音节匹配计算消费
    // 注意：letterBuffer SELECTION 态不应走此路径，因为候选词应在
    // selections 段中消费，而非从 unassigned 中消费。
    //
    // 核心改进：多音节候选词将剩余音节（超出 selections 数的部分）直接匹配到
    // unassigned，而非匹配全部音节到完整 digit_sequence。避免 PREFIX_MATCH
    // 贪婪匹配跨越音节边界（如"jie gou hua"的"hua"=482 被"jie"的 PREFIX_MATCH
    // "54"和"gou"的 PREFIX_MATCH "4"侵蚀，剩余"82"无法匹配"hua"）。
    if (has_selections && !unassigned.empty()) {
        std::string selected_pinyin = buf.selected_pinyin();
        if (route != RouteType::kLetterBuffer_Selection) {
            const auto& syllables = alignment.syllables;
            int sel_count = static_cast<int>(buf.selections.size());

            if (static_cast<int>(syllables.size()) > sel_count) {
                // 多音节候选词：剩余音节匹配到 unassigned。
                // S4-统一：直接使用 alignment 阶段2 的结果（unassigned_consumed 始终计算），
                // 删除 ComputeConsumedDigitsMultiSyllable 兜底（单一算法，行为等价硬约束下
                // 的 4 个对照场景 ZouDe/HaiHui/GuoHui/JieGouGuan 已验证一致）。
                // T9 用户词：input_digits 即组词时的实际输入序列，与当前 unassigned
                // 完全一致，必须全量消费（避免音节对齐不匹配导致 partial commit）。
                int consumed_from_unassigned =
                    is_t9_user_word
                        ? static_cast<int>(unassigned.size())
                        : align_observation.unassigned_consumed;
                if (is_t9_user_word) {
                    RCLOG(">>   apostrophe: t9_user word → full consume unassigned (%d)",
                          consumed_from_unassigned);
                }
                RCLOG(">>   apostrophe: multi-syllable (alignment) sylCount=%zu sels=%d, unassignedConsumed=%d, unassigned='%s'",
                      syllables.size(), sel_count, consumed_from_unassigned, unassigned.c_str());
                if (consumed_from_unassigned > 0) {
                    int consumed_after = consumed_from_unassigned;
                    if (consumed_after > static_cast<int>(unassigned.size()))
                        consumed_after = static_cast<int>(unassigned.size());
                    RCLOG(">>   apostrophe: multi-syllable consumed %d from unassigned, remaining='%s'",
                          consumed_after, Drop(unassigned, consumed_after).c_str());
                    return {Take(unassigned, consumed_after),
                            Drop(unassigned, consumed_after)};
                }
                // 多音节额外音节匹配失败，回退到字母数差值
                int candidate_letter_count = CountLetters(candidate_pinyin);
                int consumed_after = candidate_letter_count -
                                     static_cast<int>(selected_pinyin.size());
                if (consumed_after > 0) {
                    return {Take(unassigned, consumed_after),
                            Drop(unassigned, consumed_after)};
                }
                return {"", unassigned};
            }

            // 单音节候选词：基于完整 digit_sequence 计算消费
            int total_consumed = ComputeConsumedDigitsFromPinyin(
                buf.digit_sequence, candidate_pinyin).consumed_digits;
            RCLOG(">>   apostrophe: single-syllable total_consumed=%d, consumedCount=%d, unassigned='%s'",
                  total_consumed, buf.consumed_count, unassigned.c_str());
            if (total_consumed > 0) {
                int consumed_from_unassigned = total_consumed - buf.consumed_count;
                RCLOG(">>   apostrophe: single-syllable consumed_from_unassigned=%d", consumed_from_unassigned);

                // 单音节候选词：检查是否应从 unassigned 消费
                if (consumed_from_unassigned > 0) {
                    const bool is_single_syllable = syllables.size() == 1;
                    if (is_single_syllable) {
                        auto syl_code_opt = T9PinyinMap::Instance().PinyinToDigitCode(syllables[0]);
                        // S5：命名化 — 贪婪匹配只匹配了音节数字码的前缀（未完整匹配）。
                        // 如"jin"→546 匹配 "54482"→"54"，但第3位"6"不匹配"4"，
                        // 此时不应从 unassigned 消费，避免贪婪前缀匹配跨越 selection 边界。
                        const bool is_partial_syllable_match =
                            syl_code_opt.has_value() &&
                            total_consumed < static_cast<int>(syl_code_opt->size());
                        if (is_partial_syllable_match) {
                            RCLOG(">>   apostrophe: single-syllable partial match (%d < %zu), skip unassigned",
                                  total_consumed, syl_code_opt->size());
                            return {"", unassigned};
                        }
                        // S5：命名化 — 单音节候选词 + 单 selection：selection 已覆盖候选词，
                        // 不从 unassigned 消费。如"及 ji"=54, selection "j"=5，
                        // 消费"5"即覆盖"ji"，剩余"4482"应保持不变。
                        const bool is_single_selection = sel_count == 1;
                        if (is_single_selection) {
                            RCLOG(">>   apostrophe: single-syllable + single-selection (selCount=%d), skip unassigned consumption",
                                  sel_count);
                            return {"", unassigned};
                        }
                    }
                }

                if (consumed_from_unassigned > 0 &&
                    consumed_from_unassigned <= static_cast<int>(unassigned.size())) {
                    return {Take(unassigned, consumed_from_unassigned),
                            Drop(unassigned, consumed_from_unassigned)};
                }
                return {"", unassigned};
            }

            // 回退：字母数差值
            int candidate_letter_count = CountLetters(candidate_pinyin);
            int consumed_after = candidate_letter_count -
                                 static_cast<int>(selected_pinyin.size());
            if (consumed_after > 0) {
                return {Take(unassigned, consumed_after),
                        Drop(unassigned, consumed_after)};
            }
            return {"", unassigned};
        }
        RCLOG(">>   apostrophe: route=kLetterBuffer_Selection → fall through to letterBuffer mode");
    }

    // digitSegment 模式
    if (!has_selections && !unassigned.empty()) {
        // 统一消费计算（DigitSegmentStrategy::ComputeConsumedCount）：
        // 方案 A 的 RIME 候选 end 优先 + AlignWithBuffer fallback + 分隔符分段匹配，
        // 与 DigitSegmentStrategy 共享同一逻辑，消除双算与不一致。
        const std::string& segment = unassigned;
        int consumed_count = DigitSegmentStrategy::ComputeConsumedCount(
            buf, candidate_pinyin, rime_consumed_digits);
        if (consumed_count > static_cast<int>(segment.size()))
            consumed_count = static_cast<int>(segment.size());
        return {Take(segment, consumed_count), Drop(segment, consumed_count)};
    }

    // letterBuffer 模式
    std::string selected_pinyin = buf.selected_pinyin();
    int candidate_letter_count = CountLetters(candidate_pinyin);
    // S5：命名化 — 候选词字母数 < 已选拼音长度 → 候选词部分覆盖 selections
    const bool is_partial_selection_coverage =
        candidate_letter_count > 0 &&
        candidate_letter_count < static_cast<int>(selected_pinyin.size());
    if (is_partial_selection_coverage) {
        // 优先使用 selections 覆盖的数字段来计算消费。
        // 当 consumedCount > selections_total_length 时，说明有右选消费的偏移量，
        // 完整 digit_sequence 的前缀不匹配候选词拼音编码（如 digitSeq="23744",
        // consumedCount=5, selections=[pi(2),h(1)], 候选词"皮pi"的编码"74"在
        // 位置2-3，前缀"23"不匹配"74"），需要从 selections 段开始计算。
        int sel_total_digit_length = buf.selections_digit_length();
        if (sel_total_digit_length > 0 && !buf.selections.empty()) {
            int sel_offset = buf.consumed_count - sel_total_digit_length;
            if (sel_offset >= 0 && sel_offset < static_cast<int>(buf.digit_sequence.length())) {
                std::string sel_digits = buf.digit_sequence.substr(sel_offset, sel_total_digit_length);
                int consumed_from_sel = ComputeConsumedDigitsFromPinyin(sel_digits, candidate_pinyin).consumed_digits;
                consumed_from_sel = CapToFirstSelectionDigitLength(
                    consumed_from_sel, buf, alignment.syllable_count());

                if (consumed_from_sel > 0 &&
                    consumed_from_sel < static_cast<int>(sel_digits.length())) {
                    int total_consumed = sel_offset + consumed_from_sel;
                    return {buf.digit_sequence.substr(0, total_consumed),
                            buf.digit_sequence.substr(total_consumed)};
                }
            }
        }
        // 回退：原逻辑，基于完整 digit_sequence 计算
        int consumed_digit_count = ComputeConsumedDigitsFromPinyin(
            buf.digit_sequence, candidate_pinyin).consumed_digits;
        consumed_digit_count = CapToFirstSelectionDigitLength(
            consumed_digit_count, buf, alignment.syllable_count());

        if (consumed_digit_count > 0 &&
            consumed_digit_count < static_cast<int>(buf.digit_sequence.length())) {
            return {buf.digit_sequence.substr(0, consumed_digit_count),
                    buf.digit_sequence.substr(consumed_digit_count)};
        }
        auto consumed_dig = T9PinyinMap::Instance().PinyinToDigitCode(
            Take(selected_pinyin, candidate_letter_count));
        auto remaining_dig = T9PinyinMap::Instance().PinyinToDigitCode(
            Drop(selected_pinyin, candidate_letter_count));
        return {consumed_dig.value_or(""), remaining_dig.value_or("")};
    }
    if (candidate_letter_count >= static_cast<int>(selected_pinyin.size())) {
        // S5：命名化 — 候选词字母数 ≥ 已选拼音长度 → 候选词完全覆盖 selections，
        // 可能有额外音节从 unassigned 消费。
        if (!unassigned.empty()) {
            const auto& syllables = alignment.syllables;
            int selection_count = static_cast<int>(buf.selections.size());
            if (static_cast<int>(syllables.size()) > selection_count) {
                // S4-统一：直接使用 alignment 阶段2 的结果（unassigned_consumed 始终计算），
                // total_consumed = buf.consumed_count + unassigned_consumed。
                // 删除 ComputeConsumedDigitsMultiSyllable 兜底（单一算法）。
                int consumed_from_unassigned =
                    align_observation.unassigned_consumed;
                RCLOG(">>   CRCC: letterBuffer extraSyllable (alignment) sylCount=%zu sels=%d, unassignedConsumed=%d, unassigned='%s'",
                      syllables.size(), selection_count, consumed_from_unassigned, unassigned.c_str());
                if (consumed_from_unassigned > 0) {
                    int total_consumed = buf.consumed_count + consumed_from_unassigned;
                    RCLOG(">>   CRCC: letterBuffer extraSyllable consumed %d from unassigned, totalConsumed=%d",
                          consumed_from_unassigned, total_consumed);
                    return {buf.digit_sequence.substr(0, total_consumed),
                            buf.digit_sequence.substr(total_consumed)};
                }
            }
        }
        return {"", ""};
    }

    auto digits_opt = T9PinyinMap::Instance().PinyinToDigitCode(selected_pinyin);
    if (digits_opt.has_value()) {
        const std::string& digits = *digits_opt;
        auto options = T9PinyinMap::Instance().FirstSyllableOptions(digits, 1);
        int consumed_digit_count = options.empty() ? 0 : options[0].digit_length;
        if (consumed_digit_count >= 1 &&
            consumed_digit_count < static_cast<int>(digits.size())) {
            return {Take(digits, consumed_digit_count),
                    Drop(digits, consumed_digit_count)};
        }
    }
    return {"", ""};
}

// ════════════════════════════════════════════════════════════════
// T9RightCommitHandler::HandleRightCommit 三层分流入口
// ════════════════════════════════════════════════════════════════
//
// 放在所有 Strategy 类完整定义之后，以满足 unique_ptr<>::operator->
// 对完整类型的要求。

bool T9RightCommitHandler::HandleRightCommit(
    Context& ctx,
    const std::optional<std::string>& candidate_pinyin,
    int candidate_text_length,
    int rime_consumed_digits,
    bool is_t9_user_word) {

    T9_SCOPED_TIMER_TAG("T9RightCommit", "HandleRightCommit");
    if (ctx.input_buffer.is_empty()) return true;

#ifndef NDEBUG
    ctx.input_buffer.AssertInvariants();
#endif

    // 全简拼无候选 → enterLike 提交
    if (!candidate_pinyin.has_value() || candidate_pinyin->empty()) {
        const auto& history = ctx.state_machine.selection_history();
        bool all_abbrev = !history.empty();
        for (const auto& sel : history) {
            if (sel.digit_length != 1) { all_abbrev = false; break; }
        }
        if (all_abbrev) {
            ClearAndEnterIdle(ctx);
            return true;
        }
    }

    const T9Buffer& buf = ctx.input_buffer;
    bool has_selections = !buf.selections.empty();
    bool has_unassigned = !buf.unassigned().empty();

    // S3：路由分类集中到 ClassifyRoute，CRCC 与 Strategy 分发共享同一份语义。
    auto prev_opt = ctx.state_machine.selected_option();
    auto route = ClassifyRoute(buf, prev_opt);

    // S4-3：alignment 一次构造，CRCC 与 ApostropheStrategy 共享，
    // 消除 ParseSyllables / PinyinToDigitCode 重复调用（S7 目标）。
    auto alignment = SyllableAlignment::FromCandidatePinyin(candidate_pinyin);

    auto consumption = ComputeRightCommitConsumption(
        buf, alignment, candidate_pinyin, route, rime_consumed_digits,
        is_t9_user_word);
    const std::string& remaining_digits = consumption.second;

    RCLOG(">> HandleRightCommit: hasSels=%d, hasUnassigned=%d, route=%d, unassigned='%s'",
          has_selections ? 1 : 0, has_unassigned ? 1 : 0,
          static_cast<int>(route), buf.unassigned().c_str());
    RCLOG(">>   consumption: consumed='%s', remaining='%s'",
          consumption.first.c_str(), remaining_digits.c_str());
    RCLOG(">>   selPinyin='%s', candidatePinyin='%s', candidateTextLen=%d",
          buf.selected_pinyin().c_str(),
          candidate_pinyin.has_value() ? candidate_pinyin->c_str() : "(null)",
          candidate_text_length);
    RCLOG(">>   digitSeq='%s', consumedCount=%d",
          buf.digit_sequence.c_str(), buf.consumed_count);

    // 值快照（关键修复 2026-08-05）：prev_buf 必须是右选前的 buffer 拷贝。
    // 若用引用（const T9Buffer&），后续三层策略对 ctx.input_buffer 的赋值
    // （如 letterBuffer 重建 / apostrophe WithRemainingDigits）会修改同一对象，
    // 导致 SyncRightCommit(prev_buf, ctx.input_buffer) 收到两份相同 buffer，
    // 差异推导恒为空（commitIndices=[]）→ 右选不 commit 任何段 → 回退缺少
    // undo RC 步骤（设备实证：右选"九/股"后回退直接删数字，无撤销 commit）。
    const T9Buffer prev_buf = buf;
    auto prev_digits = ctx.state_machine.selection_candidate_digits();
    std::string prev_conf = ctx.state_machine.confirmed_pinyin_before_selection();

    // 清空状态机的临时选择状态
    auto saved_history = ctx.state_machine.selection_history();
    ctx.state_machine.RestoreFrom(T9StateMachine::State::kInput,
                                  std::nullopt, std::nullopt, "", saved_history);
    ctx.separator_consumed_digits = std::nullopt;
    ctx.last_choice_consumed_digits = std::nullopt;

    // 三层分流（策略模式，S3：基于 ClassifyRoute 结果 switch 分发）
    bool is_full_commit;
    switch (route) {
        case RouteType::kLetterBuffer_Selection:
            RCLOG(">> BRANCH: letterBuffer (selection state with unassigned)");
            is_full_commit = letter_buffer_strategy_->Handle(
                *this, ctx, remaining_digits, candidate_pinyin, candidate_text_length,
                prev_opt, prev_digits, prev_conf, prev_buf);
            break;
        case RouteType::kApostrophe:
            RCLOG(">> BRANCH: apostrophe (hasSels && hasUnassigned)");
            is_full_commit = apostrophe_strategy_->Handle(
                *this, ctx, remaining_digits, alignment, candidate_pinyin,
                prev_opt, prev_digits, prev_conf);
            break;
        case RouteType::kDigitSegment:
            RCLOG(">> BRANCH: digitSegment (hasUnassigned only)");
            // remaining_digits 由 CRCC 统一计算，Handle 直接复用（不再重复计算）
            is_full_commit = digit_segment_strategy_->Handle(
                *this, ctx, remaining_digits,
                prev_opt, prev_digits, prev_conf);
            break;
        case RouteType::kLetterBuffer_Input:
            RCLOG(">> BRANCH: letterBuffer (hasSels only)");
            is_full_commit = letter_buffer_strategy_->Handle(
                *this, ctx, remaining_digits, candidate_pinyin, candidate_text_length,
                prev_opt, prev_digits, prev_conf, prev_buf);
            break;
    }

    // 段模型同步：策略执行后，对比 prev_buf 与最终 buffer，
    // 推导本次 commit 涉及的段（RightCommitMulti）+ tail 消费（ConsumeTail）。
    // prev_buf = 策略执行前的 buffer；ctx.input_buffer 已被策略更新。
    if (ctx.undo_model != nullptr) {
        ctx.undo_model->SyncRightCommit(prev_buf, ctx.input_buffer);
    }

#ifndef NDEBUG
    ctx.input_buffer.AssertInvariants();
#endif

    if (is_full_commit) {
        // 段模型同步：full commit 后一切清空（无回退必要）
        if (ctx.undo_model != nullptr) {
            ctx.undo_model->Clear();
        }
    }
    RCLOG(">> HandleRightCommit RESULT: isFullCommit=%d, newBuf.digitSeq='%s', newBuf.unassigned='%s'",
          is_full_commit ? 1 : 0,
          ctx.input_buffer.digit_sequence.c_str(),
          ctx.input_buffer.unassigned().c_str());
    return is_full_commit;
}

}  // namespace rime
