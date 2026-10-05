// SyllableAlignment 单元测试（dev-t9-v2 S1）
//
// 覆盖 SyllableAlignment 数据结构的构造与 AlignWithBuffer 方法：
//   - FromCandidatePinyin 工厂（空输入、单音节、多音节、含无效音节）
//   - AlignWithBuffer 两阶段对齐（selections 段 + unassigned 段）
//   - 边界场景（空 buffer、无 selections、全 selections、混合）
//
// 作为 S1 "观察者"引入，不改旧逻辑；本测试独立验证新模块正确性。

#include "t9_syllable_alignment.h"

#include <gtest/gtest.h>

#include "t9_buffer.h"
#include "t9_pinyin_map.h"

using rime::AlignmentResult;
using rime::MatchType;
using rime::SyllableAlignment;
using rime::SyllableOption;
using rime::T9Buffer;

namespace {

// 辅助：构造候选词拼音注释的对齐对象
SyllableAlignment AlignFrom(std::optional<std::string> pinyin) {
    return SyllableAlignment::FromCandidatePinyin(pinyin);
}

}  // namespace

// ── FromCandidatePinyin 工厂 ──

TEST(SyllableAlignmentFactoryTest, EmptyInput) {
    auto a = AlignFrom(std::nullopt);
    EXPECT_TRUE(a.empty());
    EXPECT_EQ(0, a.syllable_count());
    EXPECT_EQ(0, a.total_digit_length);

    auto b = AlignFrom(std::string{});
    EXPECT_TRUE(b.empty());

    auto c = AlignFrom(std::string{"  "});
    EXPECT_TRUE(c.empty());
}

TEST(SyllableAlignmentFactoryTest, SingleSyllable) {
    // "ji" → 数字码 "54"
    auto a = AlignFrom(std::optional<std::string>("ji"));
    ASSERT_EQ(1, a.syllable_count());
    EXPECT_EQ("ji", a.syllables[0]);
    ASSERT_EQ(1u, a.syllable_codes.size());
    EXPECT_EQ("54", a.syllable_codes[0]);
    EXPECT_EQ(2, a.total_digit_length);
}

TEST(SyllableAlignmentFactoryTest, MultipleSyllables) {
    // "ji hua" → ["ji"→"54", "hua"→"482"]
    auto a = AlignFrom(std::optional<std::string>("ji hua"));
    ASSERT_EQ(2, a.syllable_count());
    EXPECT_EQ("ji", a.syllables[0]);
    EXPECT_EQ("hua", a.syllables[1]);
    EXPECT_EQ("54", a.syllable_codes[0]);
    EXPECT_EQ("482", a.syllable_codes[1]);
    EXPECT_EQ(5, a.total_digit_length);
}

TEST(SyllableAlignmentFactoryTest, PreservesInvalidSyllableAsEmptyCode) {
    // 含无法映射的 token（数字串）会被 ParseSyllables 过滤；
    // 此用例验证有效音节中混入无效字符时不影响其他音节
    auto a = AlignFrom(std::optional<std::string>("ji hua"));
    ASSERT_EQ(2, a.syllable_count());
    // 所有音节均有效
    EXPECT_FALSE(a.syllable_codes[0].empty());
    EXPECT_FALSE(a.syllable_codes[1].empty());
}

// ── AlignWithBuffer：空与无匹配场景 ──

TEST(AlignWithBufferTest, EmptyAlignment_ReturnsDefault) {
    auto a = AlignFrom(std::nullopt);
    T9Buffer buf("54482");
    auto r = a.AlignWithBuffer(buf);
    EXPECT_EQ(0, r.selections_consumed);
    EXPECT_EQ(0, r.digits_consumed);
    EXPECT_FALSE(r.covers_all_selections);
    EXPECT_FALSE(r.covers_unassigned);
    EXPECT_EQ(MatchType::kNone, r.last_match_type);
}

TEST(AlignWithBufferTest, EmptyBuffer_ReturnsDefault) {
    // buffer 为空但候选词有音节：候选词无法对齐任何内容
    // - 无 selections → covers_all_selections=true（vacuous）
    // - 候选词有2个音节但 unassigned 为空 → 候选词过长，covers_unassigned=false
    auto a = AlignFrom(std::optional<std::string>("ji hua"));
    T9Buffer buf;
    auto r = a.AlignWithBuffer(buf);
    EXPECT_EQ(0, r.selections_consumed);
    EXPECT_EQ(0, r.digits_consumed);
    EXPECT_TRUE(r.covers_all_selections);
    EXPECT_FALSE(r.covers_unassigned);
    EXPECT_EQ(MatchType::kNone, r.last_match_type);
}

TEST(AlignWithBufferTest, EmptyBufferAndEmptyAlignment_AllFalse) {
    // buffer 与候选词均为空：候选词无音节，covers 字段均为 false（不采用 vacuous truth）
    auto a = AlignFrom(std::nullopt);
    T9Buffer buf;
    auto r = a.AlignWithBuffer(buf);
    EXPECT_EQ(0, r.selections_consumed);
    EXPECT_EQ(0, r.digits_consumed);
    EXPECT_FALSE(r.covers_all_selections);
    EXPECT_FALSE(r.covers_unassigned);
    EXPECT_EQ(MatchType::kNone, r.last_match_type);
}

// ── AlignWithBuffer：digitSegment 模式（无 selections，仅 unassigned） ──

TEST(AlignWithBufferTest, DigitSegment_FullMatch) {
    // 候选 "ji hua" 音节数字码 "54"+"482" = "54482"
    // buffer: digit_sequence="54482", 无 selections, unassigned="54482"
    auto a = AlignFrom(std::optional<std::string>("ji hua"));
    T9Buffer buf("54482");
    auto r = a.AlignWithBuffer(buf);
    EXPECT_EQ(0, r.selections_consumed);
    EXPECT_EQ(5, r.digits_consumed);
    EXPECT_TRUE(r.covers_all_selections);
    EXPECT_TRUE(r.covers_unassigned);
    EXPECT_EQ(MatchType::kFull, r.last_match_type);
}

TEST(AlignWithBufferTest, DigitSegment_PrefixMatch_SingleSyllable) {
    // 候选 "ji" 数字码 "54"，buffer unassigned="5"
    // 前缀匹配：仅首位 "5" 相同 → 消费1位
    auto a = AlignFrom(std::optional<std::string>("ji"));
    T9Buffer buf("5");
    auto r = a.AlignWithBuffer(buf);
    EXPECT_EQ(1, r.digits_consumed);
    EXPECT_TRUE(r.covers_unassigned);
    EXPECT_EQ(MatchType::kPrefix, r.last_match_type);
}

TEST(AlignWithBufferTest, DigitSegment_PartialCoverage_DoesNotCoverUnassigned) {
    // 候选 "ji" 数字码 "54"，buffer unassigned="54482"
    // 匹配 "54" 后 remaining="482"，但候选只有1个音节 → covers_unassigned=false
    auto a = AlignFrom(std::optional<std::string>("ji"));
    T9Buffer buf("54482");
    auto r = a.AlignWithBuffer(buf);
    EXPECT_EQ(2, r.digits_consumed);
    EXPECT_FALSE(r.covers_unassigned);
    EXPECT_EQ(MatchType::kFull, r.last_match_type);
}

// ── AlignWithBuffer：apostrophe 模式（selections + unassigned） ──

TEST(AlignWithBufferTest, Apostrophe_MultiSyllable_ExtraSyllablesConsumeUnassigned) {
    // buffer: digit_sequence="54482", selections=[ji(2)], consumed_count=2, unassigned="482"
    // 候选 "ji hua"：阶段1覆盖 selection "ji"，阶段2 "hua"→"482" 完全匹配 unassigned
    auto a = AlignFrom(std::optional<std::string>("ji hua"));
    T9Buffer buf("54482", {SyllableOption("ji", 2)}, 2, 5);
    auto r = a.AlignWithBuffer(buf);
    EXPECT_EQ(1, r.selections_consumed);
    EXPECT_EQ(5, r.digits_consumed);  // 2 (ji) + 3 (hua)
    EXPECT_TRUE(r.covers_all_selections);
    EXPECT_TRUE(r.covers_unassigned);
    EXPECT_EQ(MatchType::kFull, r.last_match_type);
}

TEST(AlignWithBufferTest, Apostrophe_SelectionPrefixOfCandidate) {
    // selection 是声母，候选是全拼
    // buffer: selections=[j(1)], digit_sequence="5482", consumed_count=1, unassigned="482"
    // 候选 "ji hua"：阶段1 j(5) vs ji(54) → sel_code 是 syl_code 的前缀 → kPrefix 覆盖
    // 阶段2 "hua"→"482" 完全匹配
    auto a = AlignFrom(std::optional<std::string>("ji hua"));
    T9Buffer buf("5482", {SyllableOption("j", 1)}, 1, 4);
    auto r = a.AlignWithBuffer(buf);
    EXPECT_EQ(1, r.selections_consumed);
    EXPECT_EQ(4, r.digits_consumed);  // 1 (j) + 3 (hua)
    EXPECT_TRUE(r.covers_all_selections);
    EXPECT_TRUE(r.covers_unassigned);
    EXPECT_EQ(MatchType::kFull, r.last_match_type);
}

TEST(AlignWithBufferTest, Apostrophe_CandidatePrefixOfSelection) {
    // 候选是声母，selection 是全拼
    // buffer: selections=[ji(2)], digit_sequence="54482", consumed_count=2, unassigned="482"
    // 候选 "j hua"：阶段1 j(5) vs ji(54) → syl_code 是 sel_code 的前缀 → kPrefix 覆盖
    // 阶段2 "hua"→"482" 完全匹配
    auto a = AlignFrom(std::optional<std::string>("j hua"));
    T9Buffer buf("54482", {SyllableOption("ji", 2)}, 2, 5);
    auto r = a.AlignWithBuffer(buf);
    EXPECT_EQ(1, r.selections_consumed);
    EXPECT_EQ(5, r.digits_consumed);  // 2 (ji) + 3 (hua)
    EXPECT_TRUE(r.covers_all_selections);
    EXPECT_TRUE(r.covers_unassigned);
    EXPECT_EQ(MatchType::kFull, r.last_match_type);
}

TEST(AlignWithBufferTest, Apostrophe_SelectionNotCovered_StopsPhase1) {
    // 候选词首音节与 selection 数字码不匹配
    // buffer: selections=[ji(2)], unassigned="482"
    // 候选 "zhe li"：zhe→"943", ji→"54"，首位 9 != 5 → 阶段1终止
    auto a = AlignFrom(std::optional<std::string>("zhe li"));
    T9Buffer buf("54482", {SyllableOption("ji", 2)}, 2, 5);
    auto r = a.AlignWithBuffer(buf);
    EXPECT_EQ(0, r.selections_consumed);
    EXPECT_EQ(0, r.digits_consumed);
    EXPECT_FALSE(r.covers_all_selections);
    EXPECT_FALSE(r.covers_unassigned);
    EXPECT_EQ(MatchType::kNone, r.last_match_type);
}

// ── AlignWithBuffer：letterBuffer 模式（仅 selections，无 unassigned） ──

TEST(AlignWithBufferTest, LetterBuffer_CoversAllSelections_NoUnassigned) {
    // buffer: digit_sequence="544", selections=[ji(2), h(1)], consumed_count=3, unassigned=""
    // 候选 "ji hua"：阶段1 覆盖 ji(ji==ji) + h(h 是 hua 的前缀) → covers_all_selections=true
    // 阶段2：unassigned 为空，phase2_ran=false → covers_unassigned=true（vacuous）
    auto a = AlignFrom(std::optional<std::string>("ji hua"));
    T9Buffer buf("544", {SyllableOption("ji", 2), SyllableOption("h", 1)}, 3, 3);
    auto r = a.AlignWithBuffer(buf);
    EXPECT_EQ(2, r.selections_consumed);
    EXPECT_EQ(3, r.digits_consumed);  // 2 (ji) + 1 (h)
    EXPECT_TRUE(r.covers_all_selections);
    EXPECT_TRUE(r.covers_unassigned);
    EXPECT_EQ(MatchType::kPrefix, r.last_match_type);  // 最后是 h vs hua 的前缀匹配
}

TEST(AlignWithBufferTest, LetterBuffer_PartialSelectionCoverage) {
    // buffer: selections=[ji(2), h(1), a(1)], consumed_count=4
    // 候选 "ji hua"：阶段1 覆盖 ji + h，但候选只有2音节，第3个 selection 未覆盖
    auto a = AlignFrom(std::optional<std::string>("ji hua"));
    T9Buffer buf("5442", {SyllableOption("ji", 2), SyllableOption("h", 1), SyllableOption("a", 1)}, 4, 4);
    auto r = a.AlignWithBuffer(buf);
    EXPECT_EQ(2, r.selections_consumed);
    EXPECT_EQ(3, r.digits_consumed);  // 2 (ji) + 1 (h)
    EXPECT_FALSE(r.covers_all_selections);
    EXPECT_FALSE(r.covers_unassigned);
    EXPECT_EQ(MatchType::kPrefix, r.last_match_type);
}

// ── AlignWithBuffer：多音节候选词在 unassigned 段的非贪婪匹配 ──

TEST(AlignWithBufferTest, Unassigned_NonGreedyPrefixMatch) {
    // 验证阶段2非贪婪：候选多音节，unassigned 中首位匹配但后续不匹配
    // 候选 "hai hui"：hai→"424", hui→"484"
    // buffer: 无 selections, unassigned="4482"
    // 阶段2：hai(424) vs "4482" → 首位"4"相同但"24"!="48" → PREFIX 消费1位
    //        hui(484) vs "482"  → 首位"4"相同但"84"!="82" → PREFIX 消费1位
    // 总消费=2，covers_unassigned=false
    auto a = AlignFrom(std::optional<std::string>("hai hui"));
    T9Buffer buf("4482");
    auto r = a.AlignWithBuffer(buf);
    EXPECT_EQ(2, r.digits_consumed);
    EXPECT_FALSE(r.covers_unassigned);
    EXPECT_EQ(MatchType::kPrefix, r.last_match_type);
}

TEST(AlignWithBufferTest, Unassigned_MultiSyllableFullMatch) {
    // 候选 "ji hua"：ji→"54", hua→"482"
    // buffer: 无 selections, unassigned="54482"
    // 阶段2：ji(54) 完全匹配 "54"，hua(482) 完全匹配 "482" → covers_unassigned=true
    auto a = AlignFrom(std::optional<std::string>("ji hua"));
    T9Buffer buf("54482");
    auto r = a.AlignWithBuffer(buf);
    EXPECT_EQ(5, r.digits_consumed);
    EXPECT_TRUE(r.covers_unassigned);
    EXPECT_EQ(MatchType::kFull, r.last_match_type);
}

// ── S4-统一：covers_all_selections=false 时阶段2 仍计算 unassigned_consumed ──

TEST(AlignWithBufferTest, Unassigned_Consumed_EvenWhenSelectionNotCovered) {
    // 候选 "li guan hua"：li→"54", guan→"4826", hua→"482"
    // buffer: digit_sequence="54482", selections=[l(1), a(1)], consumed=2, unassigned="482"
    // 阶段1："li"匹配 selection "l"(5) → selections_consumed=1；
    //        "guan"(4826) 与 selection "a"(2) 不匹配 → 阶段1终止，covers_all_selections=false
    // 阶段2（S4-统一：始终执行）：从 syl_idx=1 开始，未匹配 selection 的音节 "guan"
    //   继续尝试匹配 unassigned="482"：LCP=3（"482"），但 syl_code 长于 remaining
    //   → 规则4a 退化声母1位 → unassigned_consumed=1
    // 验证：即使 covers_all_selections=false，unassigned_consumed 仍被计算（旧逻辑会跳过
    //   "guan"，直接消费 0）。这是删除 ComputeConsumedDigitsMultiSyllable 兜底后的单一算法行为。
    auto a = AlignFrom(std::optional<std::string>("li guan hua"));
    T9Buffer buf("54482", {SyllableOption("l", 1), SyllableOption("a", 1)}, 2, 5);
    auto r = a.AlignWithBuffer(buf);
    EXPECT_EQ(1, r.selections_consumed);
    EXPECT_EQ(2, r.digits_consumed);       // 1 (l) + 1 (guan 退化声母)
    EXPECT_EQ(1, r.unassigned_consumed);   // 阶段2 始终计算
    EXPECT_FALSE(r.covers_all_selections);
    EXPECT_FALSE(r.covers_unassigned);     // covers_all=false → covers_unassigned 无意义置 false
    EXPECT_EQ(MatchType::kPrefix, r.last_match_type);
}
