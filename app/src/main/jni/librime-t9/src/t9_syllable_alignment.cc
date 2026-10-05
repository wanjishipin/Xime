#include "t9_syllable_alignment.h"

#include <algorithm>

#include "t9_log.h"
#include "t9_right_commit_utils.h"  // ParseSyllables
#include "t9_string_utils.h"        // CountLetters

namespace rime {

// ── 静态工厂 ──

SyllableAlignment SyllableAlignment::FromCandidatePinyin(
    const std::optional<std::string>& candidate_pinyin) {
    SyllableAlignment alignment;
    if (!candidate_pinyin.has_value() || candidate_pinyin->empty()) {
        return alignment;
    }

    alignment.syllables = ParseSyllables(*candidate_pinyin);
    if (alignment.syllables.empty()) {
        return alignment;
    }

    // 预计算每个音节的数字码（无效音节对应空串）
    const auto& pmap = T9PinyinMap::Instance();
    alignment.syllable_codes.reserve(alignment.syllables.size());
    for (const auto& syl : alignment.syllables) {
        auto code_opt = pmap.PinyinToDigitCode(syl);
        if (code_opt.has_value()) {
            alignment.syllable_codes.push_back(std::move(*code_opt));
        } else {
            // 无效音节用空串占位，保持与 syllables 的索引对齐
            alignment.syllable_codes.push_back(std::string{});
        }
    }

    for (const auto& code : alignment.syllable_codes) {
        alignment.total_digit_length += static_cast<int>(code.size());
    }

    return alignment;
}

// ── AlignWithBuffer 实现 ──

AlignmentResult SyllableAlignment::AlignWithBuffer(const T9Buffer& buf) const {
    AlignmentResult result;
    if (syllables.empty()) {
        // 候选词无音节：无法覆盖任何内容，covers 字段均为 false
        // （不采用 vacuous truth，避免调用方误判空候选词"覆盖"了空 buffer）
        return result;
    }

    const auto& pmap = T9PinyinMap::Instance();

    // ── 阶段1：候选词前缀音节与 buf.selections 对齐 ──
    // 通过比较 syllable_codes[i] 与 selection[i] 的数字码判断覆盖关系。
    // 完全匹配或互为前缀（声母/简拼场景）均视为覆盖该 selection。
    int syl_idx = 0;
    for (const auto& sel : buf.selections) {
        if (syl_idx >= syllable_count()) break;
        const std::string& syl_code = syllable_codes[syl_idx];
        if (syl_code.empty()) break;

        auto sel_code_opt = pmap.PinyinToDigitCode(sel.pinyin);
        if (!sel_code_opt.has_value() || sel_code_opt->empty()) break;
        const std::string& sel_code = *sel_code_opt;

        MatchType match = MatchType::kNone;
        if (syl_code == sel_code) {
            match = MatchType::kFull;
        } else if (syl_code.size() < sel_code.size() &&
                   sel_code.compare(0, syl_code.size(), syl_code) == 0) {
            // 候选音节是 selection 的前缀（候选是声母，selection 是全拼）
            match = MatchType::kPrefix;
        } else if (sel_code.size() < syl_code.size() &&
                   syl_code.compare(0, sel_code.size(), sel_code) == 0) {
            // selection 是候选音节的前缀（selection 是声母，候选是全拼）
            match = MatchType::kPrefix;
        }

        if (match == MatchType::kNone) break;

        result.selections_consumed++;
        result.digits_consumed += sel.digit_length;
        result.last_match_type = match;
        ++syl_idx;
    }

    result.covers_all_selections =
        (result.selections_consumed == static_cast<int>(buf.selections.size()));

    // ── 阶段2：剩余音节与 buf.unassigned 对齐 ──
    // S4-统一：始终运行（无论 covers_all_selections），从阶段1停止处的 syl_idx
    // （= selections_consumed，实际匹配的 selection 数）开始匹配 unassigned。
    // 采用"最长公共前缀 + 最后音节退化 + 候选音节超长退化"单一算法
    // （替代 ComputeConsumedDigitsFromPinyin 贪婪 + ComputeConsumedDigitsMultiSyllable 非贪婪双路径）：
    //   1. 完整匹配（remaining.startsWith(syl_code)）→ 消费 syl_code.size()
    //   2. 否则计算最长公共前缀 max_prefix
    //   3. max_prefix == 0 → break
    //   4. 部分匹配（max_prefix < syl_code.size()）时的退化规则：
    //      a. syl_code.size() > remaining.size()（候选音节比剩余长，模糊次优解）→ 退化声母1位
    //         依据：候选音节数字码长度超过剩余数字段，说明候选词无法完整覆盖 unassigned，
    //               属模糊次优解，不应贪婪消费全部 remaining（如 guan="4826" vs remaining="482"）
    //      b. max_prefix == remaining.size()（完全消费 remaining）→ 使用 max_prefix
    //      c. 不是最后音节 → 使用 max_prefix（允许中间音节贪婪，如 zou="968" 消费 "96"）
    //      d. 最后音节 + 未完全消费 → 退化声母1位（防止末音节贪婪跨越音节边界）
    //
    // 统一原则：每个音节要么匹配 selection，要么匹配 unassigned。阶段1停止处
    // 之前的音节已覆盖 selections；之后的音节（含未匹配 selection 的音节）
    // 继续尝试匹配 unassigned，不再采用"跳过前 sel_count 个音节"的结构假设。
    std::string remaining = buf.unassigned();
    int unassigned_len = static_cast<int>(remaining.size());
    bool phase2_ran = false;
    for (; syl_idx < syllable_count(); ++syl_idx) {
        phase2_ran = true;
        if (remaining.empty()) break;
        const std::string& syl_code = syllable_codes[syl_idx];
        if (syl_code.empty()) break;

        // 1. 完整匹配
        if (remaining.size() >= syl_code.size() &&
            remaining.compare(0, syl_code.size(), syl_code) == 0) {
            result.digits_consumed += static_cast<int>(syl_code.size());
            remaining = remaining.substr(syl_code.size());
            result.last_match_type = MatchType::kFull;
            continue;
        }

        // 2. 计算最长公共前缀 max_prefix
        int max_prefix = 0;
        int max_len = std::min(static_cast<int>(syl_code.size()),
                               static_cast<int>(remaining.size()));
        for (int len = 1; len <= max_len; ++len) {
            if (remaining.compare(0, len, syl_code, 0, len) == 0) {
                max_prefix = len;
            }
        }

        // 3. max_prefix == 0 → break
        if (max_prefix == 0) break;

        // 4. 部分匹配时的退化规则
        bool is_last_syllable = (syl_idx == syllable_count() - 1);
        int consume;

        if (syl_code.size() > remaining.size()) {
            // 4a. 候选音节比剩余长（模糊次优解）→ 退化声母1位
            consume = 1;
        } else if (max_prefix == static_cast<int>(remaining.size())) {
            // 4b. 完全消费 remaining → 使用 max_prefix
            consume = max_prefix;
        } else if (!is_last_syllable) {
            // 4c. 不是最后音节 → 使用 max_prefix
            consume = max_prefix;
        } else {
            // 4d. 最后音节 + 未完全消费 → 退化声母1位
            consume = 1;
        }

        result.digits_consumed += consume;
        remaining = remaining.substr(consume);
        result.last_match_type = MatchType::kPrefix;
    }
    result.unassigned_consumed = unassigned_len - static_cast<int>(remaining.size());

    // covers_unassigned：仅在 covers_all_selections 时有意义（与调用方约定一致）
    // - 阶段1未覆盖所有 selections：置 false
    // - 阶段2未执行（候选音节数 == selection 数）：covers_unassigned = unassigned 为空
    // - 阶段2执行过：需 remaining 为空且所有音节匹配完
    if (!result.covers_all_selections) {
        result.covers_unassigned = false;
    } else if (!phase2_ran) {
        result.covers_unassigned = remaining.empty();
    } else {
        result.covers_unassigned =
            remaining.empty() && (syl_idx == syllable_count());
    }

    return result;
}

}  // namespace rime
