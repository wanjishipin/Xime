#ifndef T9_BUFFER_H_
#define T9_BUFFER_H_

#include <string>
#include <vector>

#include "t9_pinyin_map.h"

namespace rime {

// 设计文档 §2.2：T9Buffer — 结构化输入模型（替代纯字符串 buffer）
//
// 核心设计：
//   - digit_sequence: 用户输入的全部数字（如 "54482"）
//   - selections:    已确认的拼音选择，按顺序排列
//   - consumed_count: 已被 LeftSelect / RightCommit 消费的数字总数
//
// 字符串 buffer 变为导出属性 ToBufferString()，
// 不再需要 parseBuffer() / digitStreamFrom() 等逆向解析。
//
// 对应 Kotlin T9BufferManager.kt 中的 T9Buffer data class。
//
// 使用示例：
//   T9Buffer("54482").AddSelection("ji", 2)
//     → digit_sequence="54482", selections=[ji(2)], consumed_count=2
//   .ToBufferString() → "ji'482"
struct T9Buffer {
    std::string digit_sequence;        // 用户输入的全部数字序列
    std::vector<SyllableOption> selections;  // 已确认的拼音选择
    int consumed_count = 0;            // 已被消费的数字总数
    int total_digits_entered = 0;      // 累计输入的数字总数（退格不减少）
    // 分隔符位置列表（升序、去重，位置 ∈ [0, digit_sequence.size()]）。
    // 空列表 = 无分隔符（等价旧 has_separator=false）。
    // 多个分词键产生多个分隔符（如 "5'43'6" → digit_sequence="5436", positions=[1,3]）。
    std::vector<int> separator_positions;

    T9Buffer() = default;
    explicit T9Buffer(std::string digits)
        : digit_sequence(std::move(digits)),
          total_digits_entered(static_cast<int>(digit_sequence.length())) {}

    T9Buffer(std::string digits, std::vector<SyllableOption> sels, int consumed,
             int total_entered, bool has_sep = false, int sep_pos = -1)
        : digit_sequence(std::move(digits)),
          selections(std::move(sels)),
          consumed_count(consumed),
          total_digits_entered(total_entered) {
        if (has_sep && sep_pos >= 0) {
            separator_positions.push_back(sep_pos);
        }
    }

    // ── 导出属性 ──

    // 尚未被消费的数字段（对应 Kotlin unassigned）
    std::string unassigned() const {
        return digit_sequence.substr(
            static_cast<size_t>(consumed_count) >= digit_sequence.size()
                ? digit_sequence.size()
                : static_cast<size_t>(consumed_count));
    }

    // 是否有分隔符（等价旧 has_separator 字段的读语义）
    bool has_separator() const { return !separator_positions.empty(); }
    // 第一个分隔符位置（-1 = 无分隔符；等价旧 separator_position 字段的读语义）
    int separator_position() const {
        return separator_positions.empty() ? -1 : separator_positions.front();
    }

    // 所有已选拼音拼接（不含分隔符）（对应 Kotlin selectedPinyin）
    std::string selected_pinyin() const;

    // 是否完全消费（对应 Kotlin isFullyConsumed）
    bool is_fully_consumed() const {
        return consumed_count >= static_cast<int>(digit_sequence.length());
    }

    // 所有已选拼音的 digit_length 之和（对应 Kotlin selectionsDigitLength）
    int selections_digit_length() const {
        int total = 0;
        for (const auto& sel : selections) {
            total += sel.digit_length;
        }
        return total;
    }

    // 是否为空（对应 Kotlin isEmpty）
    bool is_empty() const { return digit_sequence.empty() && selections.empty(); }

    // S6：debug 不变式断言。检查 T9Buffer 字段一致性。
    // 仅在 NDEBUG 未定义时生效（debug 构建）。release 构建为空操作。
    // 不变式：
    //   1. consumed_count ∈ [0, digit_sequence.size()]
    //   2. total_digits_entered >= digit_sequence.size()
    //   3. 所有分隔符位置 ∈ [0, digit_sequence.size()]
    //   4. 分隔符位置严格升序（重复位置由防抖/调用方保证不产生）
    //   5. selections_digit_length() <= digit_sequence.size()
    void AssertInvariants() const;

    // S6：僵尸 RC 状态的 buffer 侧判断。
    // 完整判断还需调用方检查 undo_model_.HasPendingCommit()（段模型侧）。
    // 僵尸 RC：consumed > 0 && unassigned 空 && selections 空
    //   （所有未分配数字已删完，但 consumed 部分仍存在）
    bool IsZombieRCBufferState() const {
        return consumed_count > 0 &&
               unassigned().empty() &&
               selections.empty();
    }

    // ── 不可变突变方法（返回新 T9Buffer） ──

    // 追加一位数字（对应 Kotlin addDigit）
    T9Buffer AddDigit(char d) const;

    // 添加拼音选择并消费对应位数（对应 Kotlin addSelection）
    T9Buffer AddSelection(const std::string& pinyin, int digit_len) const;

    // 替换最后一个选择（同位数替换时合并撤销）（对应 Kotlin replaceLastSelection）
    T9Buffer ReplaceLastSelection(const std::string& pinyin, int digit_len) const;

    // 清空全部状态（对应 Kotlin clear）
    T9Buffer Clear() const { return T9Buffer(); }

    // 用剩余数字重建 buffer（保留 totalDigitsEntered）（对应 Kotlin WithRemainingDigits）
    T9Buffer WithRemainingDigits(const std::string& digits, const T9Buffer& prev) const;

    // ── 导出字符串 ──

    // 重建传统 inputBuffer 字符串（对应 Kotlin toBufferString）
    // @param manual_delimiter 手动分隔符字符（从 speller.delimiter 配置读取，默认 '\''）
    std::string ToBufferString(char manual_delimiter = '\'') const;

    // 计算发给 RIME 引擎的预编辑字符串（对应 Kotlin toPreeditString）
    std::string ToPreeditString(char manual_delimiter = '\'') const;

    // 生成 RIME 引擎输入字符串
    std::string ToRimeInputString(char manual_delimiter = '\'') const;

    // ── 静态常量 ──

    static const T9Buffer EMPTY;

private:
    // ToRimeInputString 辅助方法（分支提取，提升可读性和可测试性）

    // 无 selections 时的 RIME 输入（含 separator / consumed_count 处理）
    std::string BuildRimeInputForEmptySelections(char manual_delimiter) const;

    // 有 selections 且 unassigned 非空时的输入
    std::string BuildRimeInputWithUnassigned() const;

    // 有 selections 且完全消费时的输入（全简拼/混合/全全拼）
    std::string BuildRimeInputFullyConsumed() const;
};

// 辅助：判断选择是否为简拼（单字母）
inline bool IsAbbreviation(const SyllableOption& sel) {
    return sel.pinyin.length() == 1;
}

}  // namespace rime

#endif  // T9_BUFFER_H_
