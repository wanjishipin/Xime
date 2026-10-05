// T9PinyinMap 单元测试
//
// 对应 Kotlin T9PinyinMapTest.kt
// 覆盖核心场景：单音节匹配、贪婪分割、拼音→数字编码、数字编码匹配
#include <gtest/gtest.h>

#include "t9_pinyin_map.h"

#include <algorithm>

using rime::SyllableOption;
using rime::T9PinyinMap;

// 辅助：检查 SyllableOption 列表是否包含指定拼音
static bool ContainsPinyin(const std::vector<SyllableOption>& options,
                           const std::string& pinyin) {
    return std::any_of(options.begin(), options.end(),
                       [&](const SyllableOption& o) { return o.pinyin == pinyin; });
}

// 辅助：提取拼音列表
static std::vector<std::string> PinyinsOf(const std::vector<SyllableOption>& options) {
    std::vector<std::string> result;
    result.reserve(options.size());
    for (const auto& o : options) result.push_back(o.pinyin);
    return result;
}

// 辅助：提取数字位数列表
static std::vector<int> DigitLengthsOf(const std::vector<SyllableOption>& options) {
    std::vector<int> result;
    result.reserve(options.size());
    for (const auto& o : options) result.push_back(o.digit_length);
    return result;
}

// ── 单音节匹配（左侧列展示） ──

// 对应 Kotlin: firstSyllableOptions 54 returns ji and li
TEST(T9PinyinMapTest, FirstSyllableOptions_54_Returns_Ji_And_Li) {
    auto options = T9PinyinMap::Instance().FirstSyllableOptions("54");
    EXPECT_TRUE(ContainsPinyin(options, "ji"));
    EXPECT_TRUE(ContainsPinyin(options, "li"));
}

// 对应 Kotlin: firstSyllableOptions 5 returns first key letters j k l
TEST(T9PinyinMapTest, FirstSyllableOptions_5_Returns_J_K_L) {
    auto options = T9PinyinMap::Instance().FirstSyllableOptions("5");
    EXPECT_TRUE(ContainsPinyin(options, "j"));
    EXPECT_TRUE(ContainsPinyin(options, "k"));
    EXPECT_TRUE(ContainsPinyin(options, "l"));
}

// 对应 Kotlin: firstSyllableOptions 482 returns hua and gua
TEST(T9PinyinMapTest, FirstSyllableOptions_482_Returns_Hua_And_Gua) {
    auto options = T9PinyinMap::Instance().FirstSyllableOptions("482");
    EXPECT_TRUE(ContainsPinyin(options, "hua"));
    EXPECT_TRUE(ContainsPinyin(options, "gua"));
}

// 对应 Kotlin: firstSyllableOptions empty returns empty
TEST(T9PinyinMapTest, FirstSyllableOptions_Empty_Returns_Empty) {
    auto options = T9PinyinMap::Instance().FirstSyllableOptions("");
    EXPECT_TRUE(options.empty());
}

// ── 贪婪分割（仅用于分词键"1"场景） ──

// 对应 Kotlin: greedySplit 54482 returns ji and gua
TEST(T9PinyinMapTest, GreedySplit_54482_Returns_Ji_And_Gua) {
    auto split = T9PinyinMap::Instance().GreedySplit("54482");
    EXPECT_EQ(std::vector<std::string>({"ji", "gua"}), PinyinsOf(split));
    EXPECT_EQ(std::vector<int>({2, 3}), DigitLengthsOf(split));
}

// 对应 Kotlin: greedySplit 54 returns ji
TEST(T9PinyinMapTest, GreedySplit_54_Returns_Ji) {
    auto split = T9PinyinMap::Instance().GreedySplit("54");
    EXPECT_EQ(std::vector<std::string>({"ji"}), PinyinsOf(split));
    EXPECT_EQ(std::vector<int>({2}), DigitLengthsOf(split));
}

// 对应 Kotlin: greedySplit empty returns empty
TEST(T9PinyinMapTest, GreedySplit_Empty_Returns_Empty) {
    auto split = T9PinyinMap::Instance().GreedySplit("");
    EXPECT_TRUE(split.empty());
}

// 对应 Kotlin: greedySplit 54143 returns ji only
TEST(T9PinyinMapTest, GreedySplit_54143_Returns_Ji_Only) {
    auto split = T9PinyinMap::Instance().GreedySplit("54143");
    EXPECT_EQ(std::vector<std::string>({"ji"}), PinyinsOf(split));
}

// 对应 Kotlin: greedySplit 24264 returns at least one syllable
TEST(T9PinyinMapTest, GreedySplit_24264_Returns_At_Least_One_Syllable) {
    auto split = T9PinyinMap::Instance().GreedySplit("24264");
    EXPECT_GE(split.size(), 1u);
}

// ── 拼音→数字编码 ──

TEST(T9PinyinMapTest, PinyinToDigitCode_Ji_Returns_54) {
    auto code = T9PinyinMap::Instance().PinyinToDigitCode("ji");
    ASSERT_TRUE(code.has_value());
    EXPECT_EQ("54", *code);
}

TEST(T9PinyinMapTest, PinyinToDigitCode_Gua_Returns_482) {
    auto code = T9PinyinMap::Instance().PinyinToDigitCode("gua");
    ASSERT_TRUE(code.has_value());
    EXPECT_EQ("482", *code);
}

TEST(T9PinyinMapTest, PinyinToDigitCode_Invalid_Returns_Nullopt) {
    auto code = T9PinyinMap::Instance().PinyinToDigitCode("@#$");
    EXPECT_FALSE(code.has_value());
}

TEST(T9PinyinMapTest, PinyinToDigitCode_CacheHit) {
    const auto& map = T9PinyinMap::Instance();
    auto code1 = map.PinyinToDigitCode("zhang");
    auto code2 = map.PinyinToDigitCode("zhang");
    ASSERT_TRUE(code1.has_value());
    ASSERT_TRUE(code2.has_value());
    EXPECT_EQ(*code1, *code2);
    EXPECT_EQ("94264", *code1);
}

TEST(T9PinyinMapTest, PinyinToDigitCode_UpperCase_Normalized) {
    auto code = T9PinyinMap::Instance().PinyinToDigitCode("JI");
    ASSERT_TRUE(code.has_value());
    EXPECT_EQ("54", *code);
}

// ── 数字编码匹配 ──

// 对应 Kotlin: areDigitCodesMatching — "he" 与 "ge" 在九键中均映射到 "43"
// 注：he → 43, ge → 43，两者编码相同
TEST(T9PinyinMapTest, AreDigitCodesMatching_He_Ge_Returns_True) {
    EXPECT_TRUE(T9PinyinMap::Instance().AreDigitCodesMatching("he", "ge"));
}

// he → 43, hu → 48，编码不同
TEST(T9PinyinMapTest, AreDigitCodesMatching_He_Hu_Returns_False) {
    EXPECT_FALSE(T9PinyinMap::Instance().AreDigitCodesMatching("he", "hu"));
}

// 前缀匹配：g → 4, ge → 43，4 是 43 的前缀
TEST(T9PinyinMapTest, AreDigitCodesMatching_G_Ge_Returns_True_Prefix) {
    EXPECT_TRUE(T9PinyinMap::Instance().AreDigitCodesMatching("g", "ge"));
}

// 无效拼音返回 false
TEST(T9PinyinMapTest, AreDigitCodesMatching_Invalid_Returns_False) {
    EXPECT_FALSE(T9PinyinMap::Instance().AreDigitCodesMatching("@", "a"));
}

// ── LetterToDigit 静态方法 ──

TEST(T9PinyinMapTest, LetterToDigit_J_Returns_5) {
    EXPECT_EQ('5', T9PinyinMap::LetterToDigit('j'));
}

TEST(T9PinyinMapTest, LetterToDigit_A_Returns_2) {
    EXPECT_EQ('2', T9PinyinMap::LetterToDigit('a'));
}

TEST(T9PinyinMapTest, LetterToDigit_Z_Returns_9) {
    EXPECT_EQ('9', T9PinyinMap::LetterToDigit('z'));
}

TEST(T9PinyinMapTest, LetterToDigit_UpperCase_Normalized) {
    EXPECT_EQ('5', T9PinyinMap::LetterToDigit('J'));
}

TEST(T9PinyinMapTest, LetterToDigit_Invalid_Returns_0) {
    EXPECT_EQ(0, T9PinyinMap::LetterToDigit('@'));
}

// ── Candidates 便捷封装 ──

TEST(T9PinyinMapTest, Candidates_54_Returns_Pinyin_Strings) {
    auto candidates = T9PinyinMap::Instance().Candidates("54");
    EXPECT_FALSE(candidates.empty());
    EXPECT_NE(candidates.end(), std::find(candidates.begin(), candidates.end(), "ji"));
    EXPECT_NE(candidates.end(), std::find(candidates.begin(), candidates.end(), "li"));
}
