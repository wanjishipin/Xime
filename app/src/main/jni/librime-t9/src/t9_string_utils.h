// T9 字符串工具函数集
//
// 设计目标：
//   1. 消除 t9_right_commit_handler.cc 中 8 个 static 辅助函数的单文件作用域局限
//   2. 提供跨模块复用的字符串操作（CountLetters / StartsWith / EndsWith / Drop / Take / DropLast / FilterLetters / JoinPinyins）
//   3. 部分函数在 C++20 后可由 STL 替代（starts_with / ends_with），
//      但为兼容低版本 NDK 暂保留实现
//
// 对应评估报告 P13：抽取 string utils

#ifndef T9_STRING_UTILS_H_
#define T9_STRING_UTILS_H_

#include <optional>
#include <string>
#include <vector>

#include "t9_pinyin_map.h"  // SyllableOption

namespace rime {

// 统计字符串中的字母数量
// 重载：支持 std::optional<std::string>，nullopt 视为 0
int CountLetters(const std::string& s);
int CountLetters(const std::optional<std::string>& s);

// 判断 s 是否以 prefix 开头 / 以 suffix 结尾
// 注：C++20 的 std::string::starts_with / ends_with 可替代，
//     保留以兼容 NDK 较低版本
bool StartsWith(const std::string& s, const std::string& prefix);
bool EndsWith(const std::string& s, const std::string& suffix);

// 字符串切片操作（函数式风格，对应 Kotlin drop/take/dropLast）
//   Drop(s, n)     → 去除前 n 个字符
//   Take(s, n)     → 取前 n 个字符
//   DropLast(s, n) → 去除后 n 个字符
// 边界处理：n 越界时返回空串或完整串，保证无 UB
std::string Drop(const std::string& s, int n);
std::string Take(const std::string& s, int n);
std::string DropLast(const std::string& s, int n);

// 仅保留字符串中的字母字符（过滤数字、标点、空白等）
std::string FilterLetters(const std::string& s);

// 拼接选择历史的拼音（不含分隔符）
// 与 T9Buffer::selected_pinyin() 等价，但接受独立 vector 参数，
// 便于在没有 T9Buffer 实例时使用（如对历史快照操作）
std::string JoinPinyins(const std::vector<SyllableOption>& sels);

}  // namespace rime

#endif  // T9_STRING_UTILS_H_
