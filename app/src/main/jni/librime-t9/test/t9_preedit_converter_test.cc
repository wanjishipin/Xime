// T9ConvertPreedit 单元测试
//
// 对应 Kotlin T9PreeditConverter.kt 的 convertT9PreeditToPinyin()
// 覆盖场景：常规多音节、末尾简拼、单段多音节、中文混合、空 comment
#include <gtest/gtest.h>

#include "t9_filter.h"            // T9ConvertPreedit / T9ConvertCandidatePreedit
#include "t9_pinyin_map.h"        // NormalizePinyinComment

using rime::T9ConvertPreedit;
using rime::T9ConvertCandidatePreedit;

// ── 常规场景 ──

TEST(T9PreeditConverterTest, RegularMultiSyllable) {
    // "54482" + "ji gua" → "jigua"
    EXPECT_EQ(T9ConvertPreedit("54482", "ji gua"), "jigua");
}

TEST(T9PreeditConverterTest, RegularThreeSyllable) {
    // "5482" + "ji hua" → "jihua"
    EXPECT_EQ(T9ConvertPreedit("5482", "ji hua"), "jihua");
}

TEST(T9PreeditConverterTest, SeparatorInPreedit) {
    // "ji'5" + "ji kan" → "ji k"（末尾单数字 5→k）
    EXPECT_EQ(T9ConvertPreedit("ji'5", "ji kan"), "ji k");
}

TEST(T9PreeditConverterTest, MultiDigitAfterSeparator) {
    // "ji'43" + "ji kan" → "ji kan"（末尾多数字→完整拼音）
    EXPECT_EQ(T9ConvertPreedit("ji'43", "ji kan"), "ji kan");
}

TEST(T9PreeditConverterTest, SpaceSeparatorInPreedit) {
    // "ji 5" + "ji kan" → "ji k"
    EXPECT_EQ(T9ConvertPreedit("ji 5", "ji kan"), "ji k");
}

// ── 末尾单数字简拼 ──

TEST(T9PreeditConverterTest, LastSingleDigitJianpin) {
    // "5" + "le" → "l"
    EXPECT_EQ(T9ConvertPreedit("5", "le"), "l");
}

TEST(T9PreeditConverterTest, LastSingleDigitZhChSh) {
    // "9" + "zhong" → "zh"
    EXPECT_EQ(T9ConvertPreedit("9", "zhong"), "zh");
    // "2" + "cheng" → "ch"
    EXPECT_EQ(T9ConvertPreedit("2", "cheng"), "ch");
    // "7" + "shen" → "sh"
    EXPECT_EQ(T9ConvertPreedit("7", "shen"), "sh");
}

TEST(T9PreeditConverterTest, LastSingleDigitAfterSeparator) {
    // "ji'4" + "ji guo" → "ji g"
    EXPECT_EQ(T9ConvertPreedit("ji'4", "ji guo"), "ji g");
}

// ── 单段多音节 ──

TEST(T9PreeditConverterTest, SingleSegmentMultiplePinyins) {
    // "564" + "le ming" → "leming"
    EXPECT_EQ(T9ConvertPreedit("564", "le ming"), "leming");
}

// ── 中文混合 ──

TEST(T9PreeditConverterTest, ChineseMixedPreedit) {
    // "公民7" + "gong min" → "公民g"（末尾单数字 7→g）
    EXPECT_EQ(T9ConvertPreedit("\xe5\x85\xac\xe6\xb0\x91" "7", "gong min"),
              "\xe5\x85\xac\xe6\xb0\x91" "g");
}

TEST(T9PreeditConverterTest, ChineseAtEnd) {
    // "7公民" + "shen" → "shen公民"（7不是末尾段，不触发简拼）
    EXPECT_EQ(T9ConvertPreedit("7\xe5\x85\xac\xe6\xb0\x91", "shen"),
              "shen\xe5\x85\xac\xe6\xb0\x91");
}

// ── 边界情况 ──

TEST(T9PreeditConverterTest, EmptyPreedit) {
    EXPECT_EQ(T9ConvertPreedit("", "ji gua"), "");
}

TEST(T9PreeditConverterTest, EmptyComment) {
    EXPECT_EQ(T9ConvertPreedit("54482", ""), "54482");
}

// ── 英文九键（无拼音注释）──
// 英文 table_translator 方案（如 melt_eng_t9）候选无拼音注释：
// 输入 8378 匹配 "test"，preedit 应显示候选词文本而非数字。

TEST(T9PreeditConverterTest, EnglishNoCommentUsesCandidateText) {
    EXPECT_EQ(T9ConvertCandidatePreedit("8378", "", "test"), "test");
    EXPECT_EQ(T9ConvertCandidatePreedit("8378", "", "vest"), "vest");
}

TEST(T9PreeditConverterTest, EnglishUnitySuffixCommentUsesCandidateText) {
    // '~' 为 librime 统一编码后缀标记（melt_eng '~s'/'~ed'），非拼音 → 显示候选词
    EXPECT_EQ(T9ConvertCandidatePreedit("8378", "~s", "tests"), "tests");
    EXPECT_EQ(T9ConvertCandidatePreedit("8378", "~ed", "tested"), "tested");
}

TEST(T9PreeditConverterTest, ChinesePinyinCommentStillConverts) {
    // 中文九键候选带拼音注释 → 仍走数字→拼音转换，行为不变
    EXPECT_EQ(T9ConvertCandidatePreedit("54482", "ji gua", "计划"), "jigua");
    EXPECT_EQ(T9ConvertCandidatePreedit("5", "le", "了"), "l");
}

TEST(T9PreeditConverterTest, NoDigits) {
    // preedit 无数字 → 原样返回
    EXPECT_EQ(T9ConvertPreedit("jigua", "ji gua"), "jigua");
}

TEST(T9PreeditConverterTest, CommentWhitespaceOnly) {
    EXPECT_EQ(T9ConvertPreedit("54482", "   "), "54482");
}

// ── 大小写处理 ──

TEST(T9PreeditConverterTest, UppercaseComment) {
    // comment 中的拼音大写时，转换结果应小写
    EXPECT_EQ(T9ConvertPreedit("54482", "JI GUA"), "jigua");
}

TEST(T9PreeditConverterTest, MixedCaseComment) {
    EXPECT_EQ(T9ConvertPreedit("54482", "Ji Gua"), "jigua");
}

// ── 带声调 comment（带声调方案场景）──
// 声调元音通过 NormalizePinyinComment 归一化为纯 ASCII 字母，
// 不依赖逐字节 ASCII 过滤（避免 jī→j、huà→hu 的 bug）。

TEST(T9PreeditConverterTest, TonedCommentRegular) {
    // "54482" + "jī huà" → "jihua"（声调字符归一化后与无声调一致）
    EXPECT_EQ(T9ConvertPreedit("54482", "jī huà"), "jihua");
}

TEST(T9PreeditConverterTest, TonedCommentThreeSyllable) {
    // "5482" + "jī huà" → "jihua"
    EXPECT_EQ(T9ConvertPreedit("5482", "jī huà"), "jihua");
}

TEST(T9PreeditConverterTest, TonedCommentAllTones) {
    // 覆盖全部四种声调：āáǎà ēéěè īíǐì ōóǒò ūúǔù ǖǘǚǜ
    EXPECT_EQ(T9ConvertPreedit("54482", "ī í ǐ ì"), "iiii");
    EXPECT_EQ(T9ConvertPreedit("54482", "ā á ǎ à"), "aaaa");
    EXPECT_EQ(T9ConvertPreedit("54482", "ē é ě è"), "eeee");
    EXPECT_EQ(T9ConvertPreedit("54482", "ō ó ǒ ò"), "oooo");
    EXPECT_EQ(T9ConvertPreedit("54482", "ū ú ǔ ù"), "uuuu");
    // ü 映射为 v（与 speller xlit 一致）
    EXPECT_EQ(T9ConvertPreedit("54482", "ǖ ǘ ǚ ǜ"), "vvvv");
    EXPECT_EQ(T9ConvertPreedit("54482", "ü"), "v");
}

TEST(T9PreeditConverterTest, TonedCommentNandM) {
    // ńňǹ→n, ḿ→m
    EXPECT_EQ(T9ConvertPreedit("54482", "ń ň ǹ"), "nnn");
    EXPECT_EQ(T9ConvertPreedit("54482", "ḿ"), "m");
}

TEST(T9PreeditConverterTest, TonedCommentWeirdDelimiter) {
    // 带声调 comment 混合全角括号 → 括号被过滤，声调被归一化
    EXPECT_EQ(T9ConvertPreedit("54482", "〔jī〕〔huà〕"), "jihua");
}

TEST(T9PreeditConverterTest, TonedCommentSingleDigitJianpin) {
    // "5" + "jī" → "j"（单数字段取首字母，不依赖完整拼音）
    EXPECT_EQ(T9ConvertPreedit("5", "jī"), "j");
}

TEST(T9PreeditConverterTest, TonedCommentSingleSegmentMulti) {
    // "54482" + "jī huà" → "jihua"（单段多音节，合并输出）
    // 验证 NormalizePinyinComment 替代逐字节过滤后单段多音节分支仍能正确拼接
    EXPECT_EQ(T9ConvertPreedit("54482", "jī huà"), "jihua");
}

TEST(T9PreeditConverterTest, TonedCommentMixedCase) {
    // 大写声调 comment → 归一化后小写
    EXPECT_EQ(T9ConvertPreedit("54482", "JĪ HUÀ"), "jihua");
}

TEST(T9PreeditConverterTest, TonedCommentPreserveExisting) {
    // 有声调 comment 已有的无声调场景不受影响（回归）
    EXPECT_EQ(T9ConvertPreedit("54482", "ji gua"), "jigua");
    EXPECT_EQ(T9ConvertPreedit("54482", "ji hua"), "jihua");
    EXPECT_EQ(T9ConvertPreedit("5", "le"), "l");
    EXPECT_EQ(T9ConvertPreedit("9", "zhong"), "zh");
}

// ── NormalizePinyinComment（调频声调保真的基础）──
// 归一化用于：comment 匹配（容忍带调/无调差异）与带声调变体选择。
// 核心不变量：带调与无声调归一化结果一致。

TEST(NormalizePinyinCommentTest, TonedAndTonelessEquivalent) {
    // 核心不变量：带声调与无声调的归一化结果一致（调频匹配的前提）
    EXPECT_EQ(rime::NormalizePinyinComment("jì huà"),
              rime::NormalizePinyinComment("ji hua"));
    EXPECT_EQ(rime::NormalizePinyinComment("jī"),
              rime::NormalizePinyinComment("ji"));
    EXPECT_EQ(rime::NormalizePinyinComment("huà"),
              rime::NormalizePinyinComment("hua"));
}

TEST(NormalizePinyinCommentTest, Basic) {
    // 保留空格（音节分隔符），声调归一化为无声调 ASCII
    EXPECT_EQ(rime::NormalizePinyinComment("jì huà"), "ji hua");
    EXPECT_EQ(rime::NormalizePinyinComment("ji hua"), "ji hua");
    EXPECT_EQ(rime::NormalizePinyinComment("jī guā"), "ji gua");
}

TEST(NormalizePinyinCommentTest, NeutralTonePreserved) {
    // 轻声音节（簸箕 ji / 笑话 hua）全 ASCII，归一化原样保留（含空格）
    EXPECT_EQ(rime::NormalizePinyinComment("bò ji"), "bo ji");
    EXPECT_EQ(rime::NormalizePinyinComment("xiào hua"), "xiao hua");
    EXPECT_EQ(rime::NormalizePinyinComment("ji"), "ji");
    EXPECT_EQ(rime::NormalizePinyinComment("hua"), "hua");
}

TEST(NormalizePinyinCommentTest, NonLetterCharsDropped) {
    // 非字母字符（分隔符/括号/数字）被丢弃，与无声调拼音对齐
    EXPECT_EQ(rime::NormalizePinyinComment("〔jì〕〔huà〕"), "jihua");
    EXPECT_EQ(rime::NormalizePinyinComment("nǐ3"), "ni");
}

TEST(NormalizePinyinCommentTest, UppercaseNormalized) {
    EXPECT_EQ(rime::NormalizePinyinComment("JĪ HUÀ"), "ji hua");
    EXPECT_EQ(rime::NormalizePinyinComment("JI HUA"), "ji hua");
}
