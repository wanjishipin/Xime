#ifndef T9_RIGHT_COMMIT_UTILS_H_
#define T9_RIGHT_COMMIT_UTILS_H_

#include <string>
#include <vector>
#include <optional>

#include "t9_pinyin_map.h"

namespace rime {

// 设计文档 6.2 节：三层消费算法 — 纯函数
//
// 对应 Kotlin T9RightCommitUtils.kt

// 消费计算结果的显式状态枚举。
// 替换原 int 返回值的隐式协议（0 = 不匹配 或 多音节首音节不匹配 → 需重试）。
enum class ConsumedResultStatus {
    kFullMatch,       // 完整匹配：已消费数字 > 0，且数字码完全匹配
    kPartialMatch,    // 部分匹配：已消费数字 > 0，但数字码仅前缀匹配（声母场景）
    kNoMatch,         // 无匹配：所有匹配方式均失败
};

// 消费计算结果
struct ConsumedResult {
    int consumed_digits = 0;
    ConsumedResultStatus status = ConsumedResultStatus::kNoMatch;
};

// 根据候选拼音注释计算数字段中被消费的位数。
//
// 逐音节匹配数字码：将 candidatePinyin 按空格拆分为音节，每个音节通过
// T9PinyinMap::PinyinToDigitCode 转为数字码，与剩余数字段前缀匹配。
// - 完全匹配：音节数字码与剩余段前缀完全一致，消费全部数字码位数
// - 前缀匹配（声母匹配）：用户仅输入了声母对应的数字，RIME 翻译器补全了韵母，
//   此时音节数字码的前缀与剩余段匹配，仅消费前缀长度的数字
//   例：用户输入 9435，候选 "这里(zhe li)"，"li" 数字码 "54"，
//   剩余 "5" 是 "54" 的前缀（声母 l 对应 digit 5），消费 1 位
//
// 若音节匹配失败（pinyinToDigitCode 返回 nullopt 或无前缀匹配），
// 回退到字母数（每个字母对应一位数字），最终回退到贪婪最长匹配。
//
// 对应 Kotlin computeConsumedDigitsFromPinyin
ConsumedResult ComputeConsumedDigitsFromPinyin(const std::string& segment,
                                                const std::optional<std::string>& candidate_pinyin);

// 判断候选词各音节"简拼首字母数字码"是否逐位对齐整个 buffer 的数字码。
//
// 用于场景19 等"全拼选中项被候选词多音节简拼消费"的边界条件。
//
// 典型场景（"er 儿"边界条件）：
//   buffer selectedPinyin="khe" → 数字码 "543"（k=5, h=4, e=3）
//   候选"卡哈尔"(comment="ka ha er")：ka→k(5)、ha→h(4)、er→e(3)
//   三音节简拼首字母数字码 = "543" == buffer 数字码 → full commit
//
// 对应 Kotlin isFullCommitByJianpinAlignment
bool IsFullCommitByJianpinAlignment(const std::string& selected_pinyin,
                                     const std::vector<std::string>& comment_syllables);

// 判断 selectionHistory 是否完全覆盖 inputBuffer，且候选词的 comment 音节与
// 选择历史逐位对齐。
//
// 对应 Kotlin isAllSelectedConsumed
bool IsAllSelectedConsumed(const std::string& input_buffer,
                            const std::vector<std::string>& comment_syllables,
                            const std::vector<SyllableOption>& selection_history);

// 判断在 SELECTION 保护路径中是否应触发 full commit。
//
// 对应 Kotlin shouldFullCommitInSelection
bool ShouldFullCommitInSelection(int consumed_from_non_selected,
                                  int non_selected_pinyin_length,
                                  const std::string& remaining_after_commit,
                                  const std::optional<std::string>& candidate_pinyin,
                                  const std::string& selected_pinyin);

// 辅助：将拼音注释按空格拆分为音节列表
// 对应 Kotlin comment.trim().split("\\s+".toRegex()).filter { it.any { c -> c.isLetter() } }
std::vector<std::string> ParseSyllables(const std::string& comment);

}  // namespace rime

#endif  // T9_RIGHT_COMMIT_UTILS_H_
