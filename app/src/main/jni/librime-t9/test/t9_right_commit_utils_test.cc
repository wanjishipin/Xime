// T9RightCommitUtils 纯函数单元测试
//
// 对应 Kotlin T9RightCommitHandlerTest.kt 中的纯函数测试部分（前 7 个 @Test），
// 并额外覆盖 IsFullCommitByJianpinAlignment / IsAllSelectedConsumed /
// ShouldFullCommitInSelection / IsAllSelectedConsumed / ParseSyllables。
//
// 参考规划文档 §6.3-§6.4 与场景 19 边界条件。

#include "t9_right_commit_utils.h"

#include <gtest/gtest.h>

#include "t9_pinyin_map.h"

namespace rime {
namespace {

// ── ParseSyllables ──

TEST(ParseSyllablesTest, EmptyInput) {
    EXPECT_EQ(ParseSyllables(""), std::vector<std::string>{});
}

TEST(ParseSyllablesTest, SingleSyllable) {
    std::vector<std::string> expected{"ji"};
    EXPECT_EQ(ParseSyllables("ji"), expected);
}

TEST(ParseSyllablesTest, MultipleSyllables) {
    std::vector<std::string> expected{"ji", "hua"};
    EXPECT_EQ(ParseSyllables("ji hua"), expected);
}

TEST(ParseSyllablesTest, HandlesMultipleSpaces) {
    std::vector<std::string> expected{"zhe", "li"};
    EXPECT_EQ(ParseSyllables("  zhe   li  "), expected);
}

TEST(ParseSyllablesTest, HandlesApostropheSeparator) {
    std::vector<std::string> expected{"pi", "h"};
    EXPECT_EQ(ParseSyllables("pi'h"), expected);
}

TEST(ParseSyllablesTest, FiltersNonLetterTokens) {
    // 包含非字母 token 应被过滤
    std::vector<std::string> expected{"ji", "hua"};
    EXPECT_EQ(ParseSyllables("ji 123 hua"), expected);
}

// ── ComputeConsumedDigitsFromPinyin ──
// 对应 Kotlin：
//   computeConsumedDigitsFromPinyin returns letter count when candidatePinyin has letters

TEST(ComputeConsumedDigitsFromPinyinTest, LetterCountWhenCandidateHasLetters) {
    // "ji" 数字码 "54"，segment "54482" 前缀匹配 "54" → 消费 2
    EXPECT_EQ(ComputeConsumedDigitsFromPinyin("54482", std::optional<std::string>("ji")).consumed_digits, 2);
    // "ji hua" 完全匹配 "54482" → 消费 5
    EXPECT_EQ(ComputeConsumedDigitsFromPinyin("54482", std::optional<std::string>("ji hua")).consumed_digits, 5);
    // "j" 不是有效拼音（不在 PINYIN_LIST），PinyinToDigitCode 返回 "5"
    // 但 FirstSyllableOptions 会包含声母 "j" → 消费 1
    // 实际行为：循环时 PinyinToDigitCode("j")="5"，remaining="54482".startsWith("5") → 消费 1
    EXPECT_EQ(ComputeConsumedDigitsFromPinyin("54482", std::optional<std::string>("j")).consumed_digits, 1);
}

// 对应 Kotlin：
//   computeConsumedDigitsFromPinyin falls back to first syllable when candidatePinyin is empty
//   computeConsumedDigitsFromPinyin falls back to first syllable when candidatePinyin is null

TEST(ComputeConsumedDigitsFromPinyinTest, FallsBackToFirstSyllableWhenEmpty) {
    int result = ComputeConsumedDigitsFromPinyin("54482", std::optional<std::string>("")).consumed_digits;
    // FirstSyllableOptions("54482") 应返回 ji(2)/li(2)/... 或 j(1)/k(1)/l(1)
    EXPECT_TRUE(result == 1 || result == 2)
        << "Should return first syllable digitLength, got " << result;
}

TEST(ComputeConsumedDigitsFromPinyinTest, FallsBackToFirstSyllableWhenNull) {
    int result = ComputeConsumedDigitsFromPinyin("54482", std::nullopt).consumed_digits;
    EXPECT_TRUE(result == 1 || result == 2)
        << "Should return first syllable digitLength, got " << result;
}

// 对应 Kotlin：
//   computeConsumedDigitsFromPinyin returns 0 for empty segment

TEST(ComputeConsumedDigitsFromPinyinTest, ReturnsZeroForEmptySegment) {
    EXPECT_EQ(ComputeConsumedDigitsFromPinyin("", std::nullopt).consumed_digits, 0);
    EXPECT_EQ(ComputeConsumedDigitsFromPinyin("", std::optional<std::string>("ji")).consumed_digits, 0);
}

// 对应 Kotlin：
//   computeConsumedDigitsFromPinyin handles partial syllable when letter count exceeds segment length
//   "zhe li" 有 5 字母，segment "9435" 只有 4 位
//   "zhe"(943) 完全匹配 → 消费 3，"li"(54) 前缀匹配 "5" → 消费 1，总 4

TEST(ComputeConsumedDigitsFromPinyinTest, PartialSyllableWhenLetterCountExceedsSegmentLength) {
    EXPECT_EQ(ComputeConsumedDigitsFromPinyin("9435", std::optional<std::string>("zhe li")).consumed_digits, 4);
}

// 对应 Kotlin：
//   computeConsumedDigitsFromPinyin partial syllable with remaining digits after match
//   "zhe li"(94354) 部分匹配 "94356"：zhe=943 完全匹配，li=54 前缀 "5" 匹配 → 消费 4

TEST(ComputeConsumedDigitsFromPinyinTest, PartialSyllableWithRemainingDigitsAfterMatch) {
    EXPECT_EQ(ComputeConsumedDigitsFromPinyin("94356", std::optional<std::string>("zhe li")).consumed_digits, 4);
}

// 对应 Kotlin：
//   computeConsumedDigitsFromPinyin full syllable match still works

TEST(ComputeConsumedDigitsFromPinyinTest, FullSyllableMatchStillWorks) {
    EXPECT_EQ(ComputeConsumedDigitsFromPinyin("54482", std::optional<std::string>("ji hua")).consumed_digits, 5);
}

// 额外覆盖：无候选拼音时回退到贪婪最长匹配

TEST(ComputeConsumedDigitsFromPinyinTest, GreedyFallbackForUnmappedSegment) {
    // "9435" 无候选 → FirstSyllableOptions 应返回 zhe(3)/... 等
    int result = ComputeConsumedDigitsFromPinyin("9435", std::nullopt).consumed_digits;
    EXPECT_GT(result, 0);
}

// ── IsFullCommitByJianpinAlignment ──
// 对应场景19："er 儿"边界条件
//   selectedPinyin="khe" → 数字码 "543"（k=5, h=4, e=3）
//   comment="ka ha er"：ka→k(5)、ha→h(4)、er→e(3)
//   三音节简拼首字母数字码 = "543" == buffer 数字码 → full commit

TEST(IsFullCommitByJianpinAlignmentTest, JianpinAlignmentMatches) {
    std::vector<std::string> comment{"ka", "ha", "er"};
    EXPECT_TRUE(IsFullCommitByJianpinAlignment("khe", comment));
}

TEST(IsFullCommitByJianpinAlignmentTest, SyllableCountMismatch) {
    // 音节数 != 数字码位数
    std::vector<std::string> comment{"ka", "ha"};
    EXPECT_FALSE(IsFullCommitByJianpinAlignment("khe", comment));
}

TEST(IsFullCommitByJianpinAlignmentTest, FirstDigitMismatch) {
    // 首字母数字码不匹配
    std::vector<std::string> comment{"ba", "ha", "er"};  // b=2 != k=5
    EXPECT_FALSE(IsFullCommitByJianpinAlignment("khe", comment));
}

TEST(IsFullCommitByJianpinAlignmentTest, EmptyComment) {
    std::vector<std::string> comment;
    EXPECT_FALSE(IsFullCommitByJianpinAlignment("khe", comment));
}

TEST(IsFullCommitByJianpinAlignmentTest, UnmappableSelectedPinyin) {
    // selectedPinyin 含非字母字符 → PinyinToDigitCode 返回 nullopt
    std::vector<std::string> comment{"ka", "ha", "er"};
    EXPECT_FALSE(IsFullCommitByJianpinAlignment("k1e", comment));
}

// ── IsAllSelectedConsumed ──

TEST(IsAllSelectedConsumedTest, AllSelectedFullyAligned) {
    // inputBuffer="pih"，selectionHistory=[pi(2), h(1)]，comment=["pi","h"]
    // 拼音拼接 "pi"+"h"=="pih"，音节数 2==2，数字码逐位对齐
    std::vector<SyllableOption> history{
        SyllableOption("pi", 2),
        SyllableOption("h", 1),
    };
    std::vector<std::string> comment{"pi", "h"};
    EXPECT_TRUE(IsAllSelectedConsumed("pih", comment, history));
}

TEST(IsAllSelectedConsumedTest, EmptyHistory) {
    std::vector<SyllableOption> history;
    std::vector<std::string> comment{"pi"};
    EXPECT_FALSE(IsAllSelectedConsumed("pi", comment, history));
}

TEST(IsAllSelectedConsumedTest, HistoryNotCoveringBuffer) {
    // 拼接 != inputBuffer
    std::vector<SyllableOption> history{
        SyllableOption("pi", 2),
    };
    std::vector<std::string> comment{"pi"};
    EXPECT_FALSE(IsAllSelectedConsumed("pih", comment, history));
}

TEST(IsAllSelectedConsumedTest, SyllableCountMismatch) {
    std::vector<SyllableOption> history{
        SyllableOption("pi", 2),
        SyllableOption("h", 1),
    };
    std::vector<std::string> comment{"pih"};  // 单音节
    EXPECT_FALSE(IsAllSelectedConsumed("pih", comment, history));
}

TEST(IsAllSelectedConsumedTest, DigitCodeMismatch) {
    // 音节数字码不对齐：comment "ji"=54 != selection "pi"=74
    std::vector<SyllableOption> history{
        SyllableOption("pi", 2),
        SyllableOption("h", 1),
    };
    std::vector<std::string> comment{"ji", "h"};
    EXPECT_FALSE(IsAllSelectedConsumed("pih", comment, history));
}

// ── ShouldFullCommitInSelection ──

TEST(ShouldFullCommitInSelectionTest, FullCommitWhenAllConsumedAndSyllableMatches) {
    // 非选中部分 "li gu" 全部消费，remaining 为空，候选含 "b" 与选中 "b" 匹配
    EXPECT_TRUE(ShouldFullCommitInSelection(
        /*consumed_from_non_selected*/ 4,
        /*non_selected_pinyin_length*/ 4,
        /*remaining_after_commit*/ "",
        /*candidate_pinyin*/ std::optional<std::string>("li gu b"),
        /*selected_pinyin*/ "b"));
}

TEST(ShouldFullCommitInSelectionTest, PartialConsumptionPreventsFullCommit) {
    EXPECT_FALSE(ShouldFullCommitInSelection(
        2, 4, "",
        std::optional<std::string>("li gu b"), "b"));
}

TEST(ShouldFullCommitInSelectionTest, RemainingDigitsPreventsFullCommit) {
    EXPECT_FALSE(ShouldFullCommitInSelection(
        4, 4, "6",
        std::optional<std::string>("li gu b"), "b"));
}

TEST(ShouldFullCommitInSelectionTest, NoMatchingSyllablePreventsFullCommit) {
    // 候选音节的数字码均与 selectedPinyin "b"(=2) 不匹配
    // 注意：a/b/c 同属键 2，故不能用 "a"；选用 "da"(=32) 等不匹配音节
    EXPECT_FALSE(ShouldFullCommitInSelection(
        4, 4, "",
        std::optional<std::string>("li gu da"), "b"));
}

TEST(ShouldFullCommitInSelectionTest, NullCandidatePinyinPreventsFullCommit) {
    EXPECT_FALSE(ShouldFullCommitInSelection(
        4, 4, "",
        std::nullopt, "b"));
}

}  // namespace
}  // namespace rime
