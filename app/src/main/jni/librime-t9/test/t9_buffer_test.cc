// T9Buffer 单元测试
//
// 对应 Kotlin T9BufferManagerTest.kt
// 覆盖 T9Buffer 核心数据模型的基本操作
#include <gtest/gtest.h>

#include "t9_buffer.h"

using rime::SyllableOption;
using rime::T9Buffer;

// ── T9Buffer 基本操作 ──

// 对应 Kotlin: buffer addDigit extends digitSequence
TEST(T9BufferTest, AddDigit_ExtendsDigitSequence) {
    T9Buffer buf = T9Buffer().AddDigit('5').AddDigit('4');
    EXPECT_EQ("54", buf.digit_sequence);
}

// 对应 Kotlin: buffer addSelection updates selections and consumedCount
TEST(T9BufferTest, AddSelection_UpdatesSelectionsAndConsumedCount) {
    T9Buffer buf = T9Buffer("54482").AddSelection("ji", 2);
    EXPECT_EQ(1u, buf.selections.size());
    EXPECT_EQ("ji", buf.selections[0].pinyin);
    EXPECT_EQ(2, buf.consumed_count);
    EXPECT_EQ("482", buf.unassigned());
}

// 对应 Kotlin: buffer toBufferString with selections
TEST(T9BufferTest, ToBufferString_WithSelections) {
    T9Buffer buf("54482", {SyllableOption("ji", 2)}, 2, 5);
    EXPECT_EQ("ji'482", buf.ToBufferString());
}

// 对应 Kotlin: buffer toPreeditString with selections
TEST(T9BufferTest, ToPreeditString_WithSelections) {
    T9Buffer buf("54482", {SyllableOption("ji", 2)}, 2, 5);
    EXPECT_EQ("ji'482", buf.ToPreeditString());
}

// ── 扩展测试：覆盖迁移规划中的关键场景 ──

TEST(T9BufferTest, Empty_IsEmpty_ReturnsTrue) {
    EXPECT_TRUE(T9Buffer().is_empty());
    EXPECT_FALSE(T9Buffer("5").is_empty());
}

TEST(T9BufferTest, AddDigit_TotalDigitsEntered_Increments) {
    T9Buffer buf;
    buf = buf.AddDigit('5');
    EXPECT_EQ(1, buf.total_digits_entered);
    buf = buf.AddDigit('4');
    EXPECT_EQ(2, buf.total_digits_entered);
}

TEST(T9BufferTest, AddSelection_FullyConsumed_UnassignedEmpty) {
    T9Buffer buf = T9Buffer("54").AddSelection("ji", 2);
    EXPECT_TRUE(buf.is_fully_consumed());
    EXPECT_EQ("", buf.unassigned());
}

TEST(T9BufferTest, ToBufferString_PureDigits_ReturnsDigits) {
    T9Buffer buf("54482");
    EXPECT_EQ("54482", buf.ToBufferString());
}

TEST(T9BufferTest, ToBufferString_FullyConsumed_ReturnsSelectedPinyin) {
    T9Buffer buf = T9Buffer("54").AddSelection("ji", 2);
    EXPECT_EQ("ji", buf.ToBufferString());
}

TEST(T9BufferTest, ToBufferString_FullyConsumed_ButShortened_KeepsTrailingApostrophe) {
    // 当 digitSequence 被退格缩短过时，保留尾随 '
    T9Buffer buf("5", {SyllableOption("ji", 2)}, 2, 2);
    // digit_sequence="5" (length 1) < total_digits_entered=2
    EXPECT_EQ("ji'", buf.ToBufferString());
}

TEST(T9BufferTest, ReplaceLastSelection_SwapsPinyin) {
    T9Buffer buf = T9Buffer("54").AddSelection("ji", 2);
    T9Buffer replaced = buf.ReplaceLastSelection("li", 2);
    EXPECT_EQ("li", replaced.selections[0].pinyin);
    EXPECT_EQ(2, replaced.consumed_count);
}

TEST(T9BufferTest, ToPreeditString_Jianpin_Jianpin_InsertsSeparator) {
    // 连续简拼选择间加 ' 分隔
    // 如 j + l → "j'l"
    T9Buffer buf = T9Buffer("55", {SyllableOption("j", 1), SyllableOption("l", 1)}, 2, 2);
    EXPECT_EQ("j'l", buf.ToPreeditString());
}

TEST(T9BufferTest, ToBufferString_Quanpin_Jianpin_NoSeparatorBetweenAbbrevs) {
    // 连续简拼选择间不加 ' （由 ToPreeditString 处理）
    T9Buffer buf = T9Buffer("55", {SyllableOption("j", 1), SyllableOption("l", 1)}, 2, 2);
    // isFullyConsumed && selections_total_len == consumed_count → "jl"
    EXPECT_EQ("jl", buf.ToBufferString());
}

TEST(T9BufferTest, Clear_ReturnsEmptyBuffer) {
    T9Buffer buf = T9Buffer("54482").AddSelection("ji", 2);
    T9Buffer cleared = buf.Clear();
    EXPECT_TRUE(cleared.is_empty());
    EXPECT_EQ(0, cleared.consumed_count);
    EXPECT_TRUE(cleared.selections.empty());
}

// ── 多分隔符模型（2026-08-02）：分词键可多次创建分段 ──

TEST(T9BufferTest, HasSeparator_ReturnsWhetherPositionsExist) {
    T9Buffer no_sep("543");
    EXPECT_FALSE(no_sep.has_separator());
    EXPECT_EQ(-1, no_sep.separator_position());

    T9Buffer sep("543");
    sep.separator_positions = {1};
    EXPECT_TRUE(sep.has_separator());
    EXPECT_EQ(1, sep.separator_position());
}

TEST(T9BufferTest, ToBufferString_MultipleSeparators) {
    // 输入 514316（1 为分词键）→ digitSeq="5436", positions=[1,3]
    // 期望 "5'43'6"
    T9Buffer buf("5436");
    buf.separator_positions = {1, 3};
    EXPECT_EQ("5'43'6", buf.ToBufferString());
}

TEST(T9BufferTest, ToRimeInputString_MultipleSeparators) {
    T9Buffer buf("5436");
    buf.separator_positions = {1, 3};
    EXPECT_EQ("5'43'6", buf.ToRimeInputString());
}

TEST(T9BufferTest, ToPreeditString_MultipleSeparators) {
    T9Buffer buf("5436");
    buf.separator_positions = {1, 3};
    EXPECT_EQ("5'43'6", buf.ToPreeditString());
}

TEST(T9BufferTest, WithRemainingDigits_ClearsSeparators) {
    T9Buffer buf("5436");
    buf.separator_positions = {1, 3};
    T9Buffer rebuilt = buf.WithRemainingDigits("6", buf);
    EXPECT_EQ("6", rebuilt.digit_sequence);
    EXPECT_TRUE(rebuilt.separator_positions.empty());
    EXPECT_FALSE(rebuilt.has_separator());
}

TEST(T9BufferTest, Constructor_HasSepSepPos_BackCompat) {
    // 旧 6 参构造（has_sep, sep_pos）向后兼容 → 单分隔符列表
    T9Buffer buf("543", {}, 0, 3, true, 1);
    EXPECT_TRUE(buf.has_separator());
    EXPECT_EQ(1, buf.separator_position());
    EXPECT_EQ("5'43", buf.ToBufferString());
}

TEST(T9BufferTest, AssertInvariants_StrictlyIncreasingPositions) {
    T9Buffer buf("5436");
    buf.separator_positions = {1, 3};
    buf.AssertInvariants();  // 不崩溃即通过（debug 断言）
}
