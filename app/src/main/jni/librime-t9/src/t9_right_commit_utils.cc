#include "t9_right_commit_utils.h"

#include <algorithm>
#include <cctype>

#include "t9_log.h"

namespace rime {

std::vector<std::string> ParseSyllables(const std::string& comment) {
    std::vector<std::string> syllables;
    size_t pos = 0;
    while (pos < comment.size()) {
        // 跳过空白
        while (pos < comment.size() &&
               (comment[pos] == ' ' || comment[pos] == '\t' ||
                comment[pos] == '\'' || comment[pos] == '\n' ||
                comment[pos] == '\r')) {
            ++pos;
        }
        if (pos >= comment.size()) break;

        // 读取一个音节
        size_t start = pos;
        while (pos < comment.size() &&
               comment[pos] != ' ' && comment[pos] != '\t' &&
               comment[pos] != '\'' && comment[pos] != '\n' &&
               comment[pos] != '\r') {
            ++pos;
        }
        std::string syl = comment.substr(start, pos - start);

        // 仅保留包含字母的音节
        bool has_letter = false;
        for (char c : syl) {
            if (std::isalpha(static_cast<unsigned char>(c))) {
                has_letter = true;
                break;
            }
        }
        if (has_letter) {
            syllables.push_back(syl);
        }
    }
    return syllables;
}

ConsumedResult ComputeConsumedDigitsFromPinyin(
    const std::string& segment,
    const std::optional<std::string>& candidate_pinyin) {
    const auto& pmap = T9PinyinMap::Instance();
    ConsumedResult result;

    if (candidate_pinyin.has_value() && !candidate_pinyin->empty()) {
        auto syllables = ParseSyllables(*candidate_pinyin);
        if (!syllables.empty()) {
            int consumed = 0;
            std::string remaining = segment;
            ConsumedResultStatus status = ConsumedResultStatus::kFullMatch;
            for (const auto& syl : syllables) {
                auto syl_code_opt = pmap.PinyinToDigitCode(syl);
                if (!syl_code_opt.has_value()) {
                    RCLOG(">> ComputeConsumed: syl='%s' → nullopt, break", syl.c_str());
                    break;
                }
                const std::string& syl_code = *syl_code_opt;

                if (remaining.size() >= syl_code.size() &&
                    remaining.compare(0, syl_code.size(), syl_code) == 0) {
                    // 完全匹配
                    consumed += static_cast<int>(syl_code.size());
                    remaining = remaining.substr(syl_code.size());
                    RCLOG(">> ComputeConsumed: syl='%s' code='%s' FULL_MATCH consumed=%d remaining='%s'",
                        syl.c_str(), syl_code.c_str(), consumed, remaining.c_str());
                } else {
                    // 前缀匹配：声母场景，用户只输入了音节首字母对应的数字
                    int match_len = 0;
                    int max_len = std::min(static_cast<int>(syl_code.size()),
                                           static_cast<int>(remaining.size()));
                    for (int len = 1; len <= max_len; ++len) {
                        if (remaining.compare(0, len, syl_code, 0, len) == 0) {
                            match_len = len;
                        }
                    }
                    if (match_len > 0) {
                        consumed += match_len;
                        remaining = remaining.substr(match_len);
                        status = ConsumedResultStatus::kPartialMatch;
                        RCLOG(">> ComputeConsumed: syl='%s' code='%s' PREFIX_MATCH matchLen=%d consumed=%d remaining='%s'",
                            syl.c_str(), syl_code.c_str(), match_len, consumed, remaining.c_str());
                    } else {
                        RCLOG(">> ComputeConsumed: syl='%s' code='%s' NO_MATCH remaining='%s', break",
                            syl.c_str(), syl_code.c_str(), remaining.c_str());
                        break;
                    }
                }
            }
            RCLOG(">> ComputeConsumed: total=%d, segment='%s'", consumed, segment.c_str());
            if (consumed > 0) {
                result.consumed_digits = consumed;
                result.status = status;
                return result;
            }
            // 多音节候选词首个音节都不匹配时返回 NoMatch（consumed_digits=0）。
            // 调用方（如 apostrophe fallback）通过 ConsumedResultStatus::kNoMatch
            // 显式判断是否需要提取额外音节重新匹配。
            if (syllables.size() > 1) {
                result.consumed_digits = 0;
                result.status = ConsumedResultStatus::kNoMatch;
                return result;
            }
        }

        // 回退①：字母数代理
        int letter_count = 0;
        for (char c : *candidate_pinyin) {
            if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')) {
                ++letter_count;
            }
        }
        if (letter_count > 0 && letter_count <= static_cast<int>(segment.size())) {
            result.consumed_digits = letter_count;
            result.status = ConsumedResultStatus::kPartialMatch;
            return result;
        }
    }

    // 回退②：贪婪最长匹配
    auto options = pmap.FirstSyllableOptions(segment, 1);
    if (!options.empty()) {
        result.consumed_digits = options[0].digit_length;
        result.status = ConsumedResultStatus::kPartialMatch;
        return result;
    }
    return result;
}

bool IsFullCommitByJianpinAlignment(const std::string& selected_pinyin,
                                     const std::vector<std::string>& comment_syllables) {
    if (comment_syllables.empty()) return false;

    const auto& pmap = T9PinyinMap::Instance();
    auto buffer_digits_opt = pmap.PinyinToDigitCode(selected_pinyin);
    if (!buffer_digits_opt.has_value()) return false;
    const std::string& buffer_digits = *buffer_digits_opt;

    // 条件1：候选音节数 == buffer 数字码位数
    if (static_cast<int>(comment_syllables.size()) !=
        static_cast<int>(buffer_digits.size())) {
        return false;
    }

    // 条件2：每个音节首字母数字码 == buffer 对应位数字
    for (size_t i = 0; i < comment_syllables.size(); ++i) {
        auto syl_code_opt = pmap.PinyinToDigitCode(comment_syllables[i]);
        if (!syl_code_opt.has_value()) return false;
        if (syl_code_opt->empty()) return false;
        if ((*syl_code_opt)[0] != buffer_digits[i]) return false;
    }
    return true;
}

bool IsAllSelectedConsumed(const std::string& input_buffer,
                            const std::vector<std::string>& comment_syllables,
                            const std::vector<SyllableOption>& selection_history) {
    if (selection_history.empty()) return false;

    // 检查 selectionHistory 拼接 == inputBuffer
    std::string joined;
    for (const auto& sel : selection_history) {
        joined += sel.pinyin;
    }
    if (joined != input_buffer) return false;

    // 检查音节数 == selectionHistory 数
    if (comment_syllables.size() != selection_history.size()) return false;

    // 逐音节数字码对齐
    const auto& pmap = T9PinyinMap::Instance();
    for (size_t i = 0; i < comment_syllables.size(); ++i) {
        auto syl_code = pmap.PinyinToDigitCode(comment_syllables[i]);
        auto sel_code = pmap.PinyinToDigitCode(selection_history[i].pinyin);
        if (!syl_code.has_value() || !sel_code.has_value()) return false;
        if (*syl_code != *sel_code) return false;
    }
    return true;
}

bool ShouldFullCommitInSelection(int consumed_from_non_selected,
                                  int non_selected_pinyin_length,
                                  const std::string& remaining_after_commit,
                                  const std::optional<std::string>& candidate_pinyin,
                                  const std::string& selected_pinyin) {
    if (consumed_from_non_selected < non_selected_pinyin_length) return false;
    if (!remaining_after_commit.empty()) return false;

    auto comment_syllables = candidate_pinyin.has_value()
        ? ParseSyllables(*candidate_pinyin)
        : std::vector<std::string>{};

    const auto& pmap = T9PinyinMap::Instance();
    for (const auto& syl : comment_syllables) {
        if (pmap.AreDigitCodesMatching(syl, selected_pinyin)) {
            return true;
        }
    }
    return false;
}

}  // namespace rime
