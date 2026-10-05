#ifndef T9_PINYIN_MAP_H_
#define T9_PINYIN_MAP_H_

#include <string>
#include <vector>
#include <optional>
#include <unordered_map>
#include <unordered_set>

namespace rime {

// 音节选项：拼音 + 对应数字位数
// 对应 Kotlin T9PinyinMap.SyllableOption
struct SyllableOption {
    std::string pinyin;
    int digit_length;

    SyllableOption() = default;
    SyllableOption(std::string p, int len) : pinyin(std::move(p)), digit_length(len) {}

    bool operator==(const SyllableOption& other) const {
        return pinyin == other.pinyin && digit_length == other.digit_length;
    }
};

// 九宫格数字→拼音映射
//
// 纯函数式、无状态、无 JNI 调用的轻量模块。
// 替代 T9Decoder 的数字→拼音解码功能。
//
// 设计决策（对应 Kotlin T9PinyinMap）：
// - 不引入模糊音。九键本身已通过「一个数字对应 3-4 个字母」提供容错，
//   叠加模糊音会膨胀左侧候选列并引入无效拼音。
// - 所有有效拼音与字母→数字映射均内聚在本对象中，避免依赖外部数据源。
//
// 使用示例：
//   T9PinyinMap::Instance().FirstSyllableOptions("54")
//     → [SyllableOption("ji", 2), SyllableOption("li", 2), ...]
class T9PinyinMap {
public:
    // 单例访问（对应 Kotlin object）
    static const T9PinyinMap& Instance();

    // 数字→字母映射（UI 候选用）
    const std::vector<char>& LettersForDigit(char digit) const;

    // 获取数字序列的首音节候选选项
    // 从长到短搜索精确匹配的拼音，优先返回完整编码匹配。
    // 同时包含首键字母回退，允许用户选择单个声母。
    // 对应 Kotlin firstSyllableOptions
    std::vector<SyllableOption> FirstSyllableOptions(const std::string& digits,
                                                      int max_results = 12) const;

    // 获取数字序列对应的拼音候选字符串列表
    // FirstSyllableOptions 的便捷封装
    // 对应 Kotlin candidates
    std::vector<std::string> Candidates(const std::string& digits,
                                         int max_results = 12) const;

    // 将拼音字符串转为九宫格数字编码
    // 例如 "ji" → "54"，"gua" → "482"
    // 若包含无法映射的字符则返回 nullopt
    // 对应 Kotlin pinyinToDigitCode（含缓存）
    std::optional<std::string> PinyinToDigitCode(const std::string& pinyin) const;

    // 贪婪分割：将数字序列分割为多个音节
    // 每次从剩余序列中取最长匹配音节，直到无法继续。
    // 例如 "54482" → [SyllableOption("ji", 2), SyllableOption("gua", 3)]
    // 对应 Kotlin greedySplit
    std::vector<SyllableOption> GreedySplit(const std::string& digits) const;

    // 判断两个拼音的 T9 数字编码是否匹配（一个编码是另一个的前缀）
    // 九键中"g"和"ge"同属数字"4"，但"g"只映射"4"。
    // 对应 Kotlin areDigitCodesMatching
    bool AreDigitCodesMatching(const std::string& a, const std::string& b) const;

    // 单字母→数字（性能关键路径，静态内联）
    // 对应 Kotlin LETTER_TO_DIGIT 查表
    static char LetterToDigit(char c);

private:
    T9PinyinMap();

    // 构建 codeToPinyins_ 反向映射
    void BuildCodeToPinyins();

    // 数字→字母映射表
    std::unordered_map<char, std::vector<char>> digit_to_letters_;

    // 字母→数字映射表
    std::unordered_map<char, char> letter_to_digit_;

    // 编码→拼音映射（精确匹配，由 PINYIN_LIST 构建）
    std::unordered_map<std::string, std::vector<std::string>> code_to_pinyins_;

    // pinyinToDigitCode 结果缓存（惰性填充，有效拼音结果才缓存）
    mutable std::unordered_map<std::string, std::string> pinyin_code_cache_;

    // 有效拼音集合
    static const std::vector<std::string>& PinyinList();

    // 最大拼音长度
    static constexpr int kMaxPinyinLen = 6;
};

// ── 声调归一化工具（UTF-8 解码 + 声调字符映射）──
// 统一入口：t9_pinyin_map.cc 实现，t9_filter.cc / t9_processor.cc 共用。

// 将带声调拼音归一化为纯 ASCII 小写（保留空格）。
// 如 "jì huà" → "ji hua"，"JĪ HUÀ" → "ji hua"。
// 用途：PinyinToDigitCode 内部归一化、comment 匹配（容忍带调/无调差异）、
// preedit 转换中的 comment 过滤。
std::string NormalizePinyinComment(const std::string& comment);

}  // namespace rime

#endif  // T9_PINYIN_MAP_H_
