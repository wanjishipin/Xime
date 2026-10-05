#include "t9_buffer.h"

#include <algorithm>
#include <cassert>

#include "t9_log.h"

namespace rime {

// 在字符串的多个指定位置插入分隔符字符
// 位置列表升序，基于原始 input 的索引；从后往前插入避免位置偏移。
// 用于 separator_positions 在 digit_sequence 中的定位
static std::string InsertDelimiters(const std::string& input,
                                    const std::vector<int>& positions,
                                    char delimiter) {
    std::string result(input);
    for (auto it = positions.rbegin(); it != positions.rend(); ++it) {
        if (*it < 0 || *it > static_cast<int>(result.length())) {
            continue;
        }
        result.insert(result.begin() + *it, delimiter);
    }
    return result;
}

const T9Buffer T9Buffer::EMPTY;

// S6：debug 不变式断言。release 构建下 assert 宏为空操作。
void T9Buffer::AssertInvariants() const {
    const int ds_size = static_cast<int>(digit_sequence.size());
    assert(consumed_count >= 0 && consumed_count <= ds_size);
    assert(total_digits_entered >= ds_size);
    for (size_t i = 0; i < separator_positions.size(); ++i) {
        assert(separator_positions[i] >= 0 &&
               separator_positions[i] <= ds_size);
        if (i > 0) {
            assert(separator_positions[i] > separator_positions[i - 1]);
        }
    }
    assert(selections_digit_length() <= ds_size);
}

std::string T9Buffer::selected_pinyin() const {
    std::string result;
    for (const auto& sel : selections) {
        result += sel.pinyin;
    }
    return result;
}

T9Buffer T9Buffer::AddDigit(char d) const {
    T9Buffer result = *this;
    result.digit_sequence.push_back(d);
    result.total_digits_entered = std::max(
        total_digits_entered,
        static_cast<int>(digit_sequence.length()) + 1);
    return result;
}

T9Buffer T9Buffer::AddSelection(const std::string& pinyin, int digit_len) const {
    T9Buffer result = *this;
    result.selections.push_back(SyllableOption(pinyin, digit_len));
    result.consumed_count = consumed_count + digit_len;
    return result;
}

T9Buffer T9Buffer::ReplaceLastSelection(const std::string& pinyin,
                                         int digit_len) const {
    if (selections.empty()) return *this;
    T9Buffer result = *this;
    int prev_len = result.selections.back().digit_length;
    result.selections.back() = SyllableOption(pinyin, digit_len);
    result.consumed_count = consumed_count - prev_len + digit_len;
    return result;
}

T9Buffer T9Buffer::WithRemainingDigits(const std::string& digits,
                                        const T9Buffer& prev) const {
    // 用剩余数字构建新 buffer：保留 prev 的历史总位数，
    // 显式重置所有消费相关状态（selections / consumed_count / separator）。
    T9Buffer result;
    result.digit_sequence = digits;
    result.total_digits_entered = std::max(
        static_cast<int>(digits.length()),
        prev.total_digits_entered);
    result.consumed_count = 0;          // 显式清零：纯数字 buffer 无已消费前缀
    result.selections.clear();          // 显式清空：不保留任何已确认选择
    result.separator_positions.clear(); // 显式重置：新 buffer 不带分词标记
    return result;
}

std::string T9Buffer::ToBufferString(char manual_delimiter) const {
    T9_SCOPED_TIMER_TAG("T9Buffer", "ToBufferString");
    if (selections.empty() && consumed_count == 0) {
        if (!separator_positions.empty()) {
            std::string result = InsertDelimiters(digit_sequence, separator_positions, manual_delimiter);
            BUFLOG(">> ToBufferString: with separator → '%s'", result.c_str());
            return result;
        }
        return digit_sequence;
    }
    // 无选择时只展示剩余未分配数字（right-commit 已消费的数字不显示）
    if (selections.empty()) {
        return unassigned();
    }

    // 完全消费且选择总位数等于消费数
    int selections_total_len = 0;
    for (const auto& sel : selections) {
        selections_total_len += sel.digit_length;
    }

    if (is_fully_consumed() && selections_total_len == consumed_count) {
        // 仅当 digitSequence 被退格缩短过（有原始数字被删除）时才保留尾随 '
        if (static_cast<int>(digit_sequence.length()) < total_digits_entered) {
            return selected_pinyin() + "'";
        }
        return selected_pinyin();
    }

    // offset: 被非 selection 方式（right-commit / 分词键）消费的前导数字数
    // selections 在 digitSequence 中的实际起始位置 = offset
    int offset = std::max(consumed_count - selections_total_len, 0);
    std::string sb;
    int pos = offset;
    for (size_t i = 0; i < selections.size(); ++i) {
        const auto& sel = selections[i];
        sb += sel.pinyin;
        pos += sel.digit_length;
        // 仅当存在未分配数字（pos 已进入 unconsumed 区）时才添加分隔符
        if (pos >= consumed_count && pos < static_cast<int>(digit_sequence.length())) {
            bool next_is_abbrev = (i + 1 < selections.size()) &&
                                  IsAbbreviation(selections[i + 1]);
            if (!IsAbbreviation(sel) || !next_is_abbrev) {
                sb += "'";
            }
        }
    }
    // 未消费数字
    sb += unassigned();
    return sb;
}

std::string T9Buffer::ToPreeditString(char manual_delimiter) const {
    T9_SCOPED_TIMER_TAG("T9Buffer", "ToPreeditString");
    if (selections.empty()) {
        std::string result = unassigned();
        // consumed_count == 0 时应用分隔符位置
        if (!separator_positions.empty() && consumed_count == 0) {
            result = InsertDelimiters(result, separator_positions, manual_delimiter);
        }
        BUFLOG(">> ToPreeditString: empty sels → '%s'", result.c_str());
        return result;
    }

    std::string sb;
    for (size_t i = 0; i < selections.size(); ++i) {
        if (i > 0) sb += "'";
        sb += selections[i].pinyin;
    }
    if (!is_fully_consumed()) {
        if (!selections.empty()) sb += "'";
        sb += unassigned();
    }
    BUFLOG(">> ToPreeditString: digitSeq='%s', consumedCount=%d, selCount=%zu, selPinyin='%s', fullyConsumed=%d → '%s'",
          digit_sequence.c_str(), consumed_count, selections.size(),
          selected_pinyin().c_str(), is_fully_consumed() ? 1 : 0, sb.c_str());
    return sb;
}

std::string T9Buffer::ToRimeInputString(char manual_delimiter) const {
    T9_SCOPED_TIMER_TAG("T9Buffer", "ToRimeInputString");
    if (selections.empty()) {
        return BuildRimeInputForEmptySelections(manual_delimiter);
    }
    if (!unassigned().empty()) {
        return BuildRimeInputWithUnassigned();
    }
    return BuildRimeInputFullyConsumed();
}

std::string T9Buffer::BuildRimeInputForEmptySelections(char manual_delimiter) const {
    // 无选择时：
    // - consumed_count == 0：发完整 digit_sequence（正常输入态）
    //   separator_position 有效时，在对应位置插入分隔符
    // - consumed_count > 0 且 unassigned 非空：只发 unassigned 部分
    //   （已被 RightCommit 消费的前导数字不应再发给 RIME）
    // - consumed_count > 0 且 unassigned 为空：发 digit_sequence
    //   （僵尸 RC 状态：所有未分配数字已删完，需让 RIME 为已消费数字生成候选）
    if (consumed_count > 0) {
        std::string una = unassigned();
        if (!una.empty()) {
            BUFLOG(">> ToRimeInputString: empty-sels consumed>0 unassigned!='' → '%s'", una.c_str());
            return una;
        }
    }
    // consumed_count == 0 时应用分隔符位置
    if (!separator_positions.empty() && consumed_count == 0) {
        std::string result = InsertDelimiters(digit_sequence, separator_positions, manual_delimiter);
        BUFLOG(">> ToRimeInputString: empty-sels with separator → '%s'", result.c_str());
        return result;
    }
    BUFLOG(">> ToRimeInputString: empty-sels → digit_sequence='%s'", digit_sequence.c_str());
    return digit_sequence;
}

std::string T9Buffer::BuildRimeInputWithUnassigned() const {
    // RIME comment 生成规则（script_translator.cc:549）：
    //   always_show_comments: true 时，spelling==preedit 仍生成 comment
    //   因此所有分支均可发拼音格式，无需发数字码确保 comment 生成。
    //   发拼音格式还能限定候选词范围（如 "an" 只返回 an 相关候选，而非 "26" 全部）。
    std::string una = unassigned();
    std::string sb;
    for (size_t i = 0; i < selections.size(); ++i) {
        if (i > 0) sb += "'";
        sb += selections[i].pinyin;
    }
    if (!selections.empty()) sb += "'";
    sb += una;
    BUFLOG(">> ToRimeInputString: has-unassigned → '%s'", sb.c_str());
    return sb;
}

std::string T9Buffer::BuildRimeInputFullyConsumed() const {
    // 无未分配数字（全部被 selections 消费）
    // 判断所有 selections 是否都是简拼（digit_length == 1）
    bool all_abbrev = std::all_of(selections.begin(), selections.end(),
        [](const SyllableOption& s) { return s.digit_length == 1; });
    if (all_abbrev) {
        // 全简拼：发拼音格式（如 "b"），comment 仍生成（spelling!=preedit）
        std::string sb;
        for (size_t i = 0; i < selections.size(); ++i) {
            if (i > 0) sb += "'";
            sb += selections[i].pinyin;
        }
        BUFLOG(">> ToRimeInputString: all-abbrev → '%s'", sb.c_str());
        return sb;
    }

    // 有非简拼 selection：需要区分是否包含简拼
    bool has_abbrev = std::any_of(selections.begin(), selections.end(),
        [](const SyllableOption& s) { return s.digit_length == 1; });
    if (has_abbrev) {
        // 包含简拼（如 [gu(2), b(1)]）：发拼音 "gu'b"
        // 简拼 b 的 spelling("ba")!=preedit("b") → comment 生成
        std::string sb;
        for (size_t i = 0; i < selections.size(); ++i) {
            if (i > 0) sb += "'";
            sb += selections[i].pinyin;
        }
        BUFLOG(">> ToRimeInputString: has-abbrev-mix → '%s'", sb.c_str());
        return sb;
    }

    // 全部全拼（如 [an(2)] 或 [gua(3)]）：
    // always_show_comments: true 时，spelling==preedit 仍生成 comment，
    // 可安全发拼音格式，限定 RIME 候选词范围。
    // （旧逻辑发数字码确保 comment 生成，但导致不同拼音选项产生相同数字码，
    //   RIME 候选词不更新，如 an→"26" 和 ao→"26"）
    {
        std::string sb;
        for (size_t i = 0; i < selections.size(); ++i) {
            if (i > 0) sb += "'";
            sb += selections[i].pinyin;
        }
        BUFLOG(">> ToRimeInputString: all-full-pinyin → '%s'", sb.c_str());
        return sb;
    }
}

}  // namespace rime
