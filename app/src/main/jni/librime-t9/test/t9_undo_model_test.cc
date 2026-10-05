// T9UndoModel 段模型单元测试
//
// 验证设计文档 §3-§6 的核心：
// 1. 段创建/消费（DigitPressed/LeftChoice/RightCommit）
// 2. 回退算法（P1 栈顶 RC / P2 删数字 / P34 undo + partial RC 延后）
// 3. 场景13 完整 9 次回退序列（文档【故,b,2,gu,8,4,里,4,5】）
// 4. 段 digits 独立管理（删除保留）
// 5. 空闲态查询（committed 段不参与候选）
#include <gtest/gtest.h>

#include "t9_undo_model.h"

using rime::T9Segment;
using rime::T9UndoModel;
using rime::T9Buffer;
using rime::SyllableOption;

namespace {

// 场景13 段构建：54482 → 左选 li(54) → 左选 gu(48) → 左选 b(2) → 右选"里"(li) → 右选"故"(gu)
T9UndoModel MakeScenario13() {
    T9UndoModel m;
    for (char d : std::string("54482")) m.DigitPressed(d);
    m.LeftChoice(SyllableOption("li", 2));
    m.LeftChoice(SyllableOption("gu", 2));
    m.LeftChoice(SyllableOption("b", 1));
    m.RightCommit(0);  // li → "里"
    m.RightCommit(1);  // gu → "故"
    return m;
}

// 左选场景构建（设计文档 §5.4）：数字序列 + 左选序列（拼音, 数字长度）
T9UndoModel MakeLeftChoiceModel(
    const std::string& digits,
    const std::vector<std::pair<std::string, int>>& choices) {
    T9UndoModel m;
    for (char d : digits) m.DigitPressed(d);
    for (const auto& c : choices) m.LeftChoice(SyllableOption(c.first, c.second));
    return m;
}

}  // namespace

// ── 段创建与消费 ──

TEST(T9UndoModelTest, DigitPressed_AppendsToTail) {
    T9UndoModel m;
    m.DigitPressed('5');
    m.DigitPressed('4');
    EXPECT_EQ("54", m.tail_digits());
    EXPECT_EQ(2, m.total_digits_entered());
    EXPECT_TRUE(m.HasSelectableDigits());
    EXPECT_FALSE(m.HasSelectedSegment());
}

TEST(T9UndoModelTest, LeftChoice_ConsumesTailAsSegmentDigits) {
    T9UndoModel m;
    for (char d : std::string("54482")) m.DigitPressed(d);
    m.LeftChoice(SyllableOption("li", 2));
    ASSERT_EQ(1u, m.segments().size());
    EXPECT_EQ("li", m.segments()[0].option.pinyin);
    EXPECT_EQ("54", m.segments()[0].digits);
    EXPECT_EQ(T9Segment::kSelected, m.segments()[0].phase);
    EXPECT_EQ("482", m.tail_digits());
}

TEST(T9UndoModelTest, RightCommit_SetsCommitted) {
    auto m = MakeScenario13();
    ASSERT_EQ(3u, m.segments().size());
    EXPECT_EQ(T9Segment::kCommitted, m.segments()[0].phase);  // li → "里"
    EXPECT_EQ(T9Segment::kCommitted, m.segments()[1].phase);  // gu → "故"
    EXPECT_EQ(T9Segment::kSelected, m.segments()[2].phase);   // b 仍 selected
    EXPECT_FALSE(m.HasSelectableDigits());                    // 全部消费
    EXPECT_TRUE(m.HasSelectedSegment());                      // b 段 selected
}

// ── 回退：场景13 完整 9 次（文档【故,b,2,gu,8,4,里,4,5】）──

TEST(T9UndoModelTest, Scenario13_FullUndoSequence) {
    auto m = MakeScenario13();
    // bs1: undo 故 → gu 回 selected（gu 段有 LC，非被替换段，不合并）
    EXPECT_TRUE(m.Backspace());
    EXPECT_EQ(T9Segment::kSelected, m.segments()[1].phase);
    EXPECT_EQ(T9Segment::kCommitted, m.segments()[0].phase);  // "里" 字仍在
    EXPECT_EQ(T9Segment::kSelected, m.segments()[2].phase);   // b 仍在

    // bs2: undo b（RC(里) 延后，撤销下层 LC(b)）
    EXPECT_TRUE(m.Backspace());
    EXPECT_EQ(T9Segment::kUnassigned, m.segments()[2].phase);
    EXPECT_EQ("2", m.segments()[2].digits);

    // bs3: 删 '2'（P2）
    EXPECT_TRUE(m.Backspace());
    EXPECT_EQ("", m.segments()[2].digits);

    // bs4: undo gu（RC(里) 仍延后，撤销下层 LC(gu)）
    EXPECT_TRUE(m.Backspace());
    EXPECT_EQ(T9Segment::kUnassigned, m.segments()[1].phase);
    EXPECT_EQ("48", m.segments()[1].digits);

    // bs5: 删 '8'
    EXPECT_TRUE(m.Backspace());
    EXPECT_EQ("4", m.segments()[1].digits);

    // bs6: 删 '4'
    EXPECT_TRUE(m.Backspace());
    EXPECT_EQ("", m.segments()[1].digits);

    // bs7: undo 里 → li 是被替换段（idx0），合并撤销 LC(li)，段回 unassigned
    EXPECT_TRUE(m.Backspace());
    EXPECT_EQ(T9Segment::kUnassigned, m.segments()[0].phase);
    EXPECT_FALSE(m.segments()[0].has_lc);
    EXPECT_FALSE(m.segments()[0].has_rc);

    // bs8: 删 '4'
    EXPECT_TRUE(m.Backspace());
    EXPECT_EQ("5", m.segments()[0].digits);

    // bs9: 删 '5'
    EXPECT_TRUE(m.Backspace());
    EXPECT_EQ("", m.segments()[0].digits);

    // bs10: 无可撤销
    EXPECT_FALSE(m.Backspace());
    EXPECT_TRUE(m.IsEmpty());
    // undo 故 + undo 里 各撤销 1 个 commit → 计数 2（供 Kotlin 同步）
    EXPECT_EQ(2, m.ConsumeUndoneCommitCount());
}

// ── 回退：数字删除（P2）与 undo LC/RC 的优先级 ──

TEST(T9UndoModelTest, Backspace_DeletesTailDigit_WhenNoSegments) {
    T9UndoModel m;
    for (char d : std::string("54482")) m.DigitPressed(d);
    EXPECT_TRUE(m.Backspace());  // P2：删 tail '2'
    EXPECT_EQ("5448", m.tail_digits());
    EXPECT_TRUE(m.Backspace());
    EXPECT_EQ("544", m.tail_digits());
}

TEST(T9UndoModelTest, Backspace_UndoSelectedSegment_AfterTailDeleted) {
    // 两阶段状态机（段撤销优先）：先 undo LC(gu)（gu 回 unassigned '48'），
    // 再按位置从后往前删 tail '2' → gu '8' → gu '4'。
    T9UndoModel m;
    for (char d : std::string("54482")) m.DigitPressed(d);
    m.LeftChoice(SyllableOption("li", 2));
    m.LeftChoice(SyllableOption("gu", 2));  // tail='2'
    // 阶段 A：undo 最后左选段 gu（段撤销优先于删 tail）
    EXPECT_TRUE(m.Backspace());
    EXPECT_EQ(T9Segment::kUnassigned, m.segments()[1].phase);
    EXPECT_EQ("48", m.segments()[1].digits);
    EXPECT_EQ("2", m.tail_digits());
    // 阶段 B：位置删除（tail '2' 在 gu 段 '48' 之后）
    EXPECT_TRUE(m.Backspace());
    EXPECT_EQ("", m.tail_digits());
    EXPECT_TRUE(m.Backspace());
    EXPECT_EQ("4", m.segments()[1].digits);
    EXPECT_TRUE(m.Backspace());
    EXPECT_EQ("", m.segments()[1].digits);
}

// ── 删除保留（段 digits 独立）──

TEST(T9UndoModelTest, DeletedSegmentDigits_NotRestored) {
    // 完整场景13：54482 → 左选 li/gu/b → 右选"里"(li) → 右选"故"(gu)。
    // 按文档回退顺序回退，验证 undo RC(里) 不恢复已删除的 gu 段数字。
    T9UndoModel m;
    for (char d : std::string("54482")) m.DigitPressed(d);
    m.LeftChoice(SyllableOption("li", 2));
    m.LeftChoice(SyllableOption("gu", 2));
    m.LeftChoice(SyllableOption("b", 1));
    m.RightCommit(0);  // li → "里"
    m.RightCommit(1);  // gu → "故"
    // bs1: undo 故（P1）→ gu 回 selected
    EXPECT_TRUE(m.Backspace());
    EXPECT_EQ(T9Segment::kSelected, m.segments()[1].phase);
    // bs2: undo b（RC(里) 延后，跳过撤销 LC(b)）
    EXPECT_TRUE(m.Backspace());
    EXPECT_EQ(T9Segment::kUnassigned, m.segments()[2].phase);
    // bs3: 删 b '2'
    EXPECT_TRUE(m.Backspace());
    EXPECT_EQ("", m.segments()[2].digits);
    // bs4: undo gu
    EXPECT_TRUE(m.Backspace());
    EXPECT_EQ(T9Segment::kUnassigned, m.segments()[1].phase);
    // bs5: 删 gu '8'
    EXPECT_TRUE(m.Backspace());
    EXPECT_EQ("4", m.segments()[1].digits);
    // bs6: 删 gu '4'
    EXPECT_TRUE(m.Backspace());
    EXPECT_EQ("", m.segments()[1].digits);
    // bs7: undo 里（合并 li）——gu 已删数字不得恢复
    EXPECT_TRUE(m.Backspace());
    EXPECT_EQ(T9Segment::kUnassigned, m.segments()[0].phase);
    EXPECT_EQ("", m.segments()[1].digits);  // gu 保持已删
}

// ── 空闲态查询（产品决策：committed 段不参与候选）──

TEST(T9UndoModelTest, CommittedSegments_YieldIdleWhenAllConsumed) {
    auto m = MakeScenario13();
    // b 段 selected → 有选中
    EXPECT_TRUE(m.HasSelectedSegment());
    // 全部 committed 后：无候选、无选中 → 空闲态
    m.RightCommit(2);  // b → committed
    EXPECT_FALSE(m.HasSelectableDigits());
    EXPECT_FALSE(m.HasSelectedSegment());
}

// ── T9Buffer 派生（设计文档 §8）──

TEST(T9UndoModelTest, ToBuffer_DerivesScenario13States) {
    auto m = MakeScenario13();
    // 初始：全消费，selections=[b]（li/gu committed）
    auto b0 = m.ToBuffer();
    EXPECT_EQ("54482", b0.digit_sequence);
    EXPECT_EQ(5, b0.consumed_count);
    ASSERT_EQ(1u, b0.selections.size());
    EXPECT_EQ("b", b0.selections[0].pinyin);
    EXPECT_TRUE(b0.unassigned().empty());

    // bs1 undo 故：gu 回 selected，selections=[gu, b]
    m.Backspace();
    auto b1 = m.ToBuffer();
    EXPECT_EQ(5, b1.consumed_count);
    ASSERT_EQ(2u, b1.selections.size());
    EXPECT_EQ("gu", b1.selections[0].pinyin);
    EXPECT_EQ("b", b1.selections[1].pinyin);
    EXPECT_TRUE(b1.unassigned().empty());

    // bs2 undo b + bs3 删 '2'：gu selected，li committed
    m.Backspace();  // undo b
    m.Backspace();  // 删 '2'
    auto b3 = m.ToBuffer();
    EXPECT_EQ("5448", b3.digit_sequence);
    EXPECT_EQ(4, b3.consumed_count);
    ASSERT_EQ(1u, b3.selections.size());
    EXPECT_EQ("gu", b3.selections[0].pinyin);

    // bs4 undo gu：无 selections，gu 段 '48' 回 unassigned
    m.Backspace();
    auto b4 = m.ToBuffer();
    EXPECT_EQ("5448", b4.digit_sequence);
    EXPECT_EQ(2, b4.consumed_count);
    EXPECT_TRUE(b4.selections.empty());
    EXPECT_EQ("48", b4.unassigned());

    // bs5-6 删 '8','4' → bs7 undo 里（合并 li）：全部回退
    m.Backspace();
    m.Backspace();
    m.Backspace();  // undo 里
    auto b7 = m.ToBuffer();
    EXPECT_EQ("54", b7.digit_sequence);
    EXPECT_EQ(0, b7.consumed_count);
    EXPECT_TRUE(b7.selections.empty());
    EXPECT_EQ("54", b7.unassigned());
}

// ── 变体2（简拼 j/g → 右选"九"、"股"）完整回退 ──
//
// 场景：54482 → 左选 j(5) → 左选 g(4) → 右选"九"(j commit) → 右选"股"(g commit)。
// tail '482' 未被消费。回退顺序 = 操作栈 LIFO（设计文档 §5）：
//   undo 股(g) → 删 '2' → 删 '8' → 删 '4' → undo LC(g)（RC(九) 延后）
//   → 删 '4'(g) → undo 九(合并 LC(j)) → 删 '5' → 清空。
// 关键验证：① bs1 直接 undo 股（P1 稳定，无需 letterBuffer 重建中间态）；
// ② bs2 防连击（不连续 P1 撤销"九"）；③ undo 九 不恢复已删 tail 数字。
TEST(T9UndoModelTest, Variant2_JiuGu_FullUndoSequence) {
    T9UndoModel m;
    for (char d : std::string("54482")) m.DigitPressed(d);
    m.LeftChoice(SyllableOption("j", 1));
    m.LeftChoice(SyllableOption("g", 1));
    m.RightCommit(0);  // j → "九"
    m.RightCommit(1);  // g → "股"

    // bs1: undo 股（P1）→ g 回 selected，tail '482' 保留
    EXPECT_TRUE(m.Backspace());
    EXPECT_EQ(T9Segment::kCommitted, m.segments()[0].phase);
    EXPECT_EQ(T9Segment::kSelected, m.segments()[1].phase);
    EXPECT_EQ("482", m.tail_digits());

    // bs2: 防连击 → 不连续 P1 撤销"九"，P2 删 tail '2'
    EXPECT_TRUE(m.Backspace());
    EXPECT_EQ("48", m.tail_digits());
    EXPECT_EQ(T9Segment::kCommitted, m.segments()[0].phase);

    // bs3-4: 删 '8'、'4'
    EXPECT_TRUE(m.Backspace());
    EXPECT_EQ("4", m.tail_digits());
    EXPECT_TRUE(m.Backspace());
    EXPECT_EQ("", m.tail_digits());

    // bs5: 无数字可删，RC(九) 延后（下层 LC(g) 未撤销）→ undo LC(g)
    EXPECT_TRUE(m.Backspace());
    EXPECT_EQ(T9Segment::kUnassigned, m.segments()[1].phase);
    EXPECT_EQ("4", m.segments()[1].digits);              // 段 digits 保留
    EXPECT_EQ(T9Segment::kCommitted, m.segments()[0].phase);  // "九" 字仍在

    // bs6: 删 g 段 '4'
    EXPECT_TRUE(m.Backspace());
    EXPECT_EQ("", m.segments()[1].digits);

    // bs7: undo 九（字母段 j 非 merge_first，对标主流输入法 2026-08-06）→ j 回 selected，digits 保留
    EXPECT_TRUE(m.Backspace());
    EXPECT_EQ(T9Segment::kSelected, m.segments()[0].phase);
    EXPECT_EQ("5", m.segments()[0].digits);

    // bs8: undo j（LC0）→ j 回 unassigned（用户有单独撤销左选的机会）
    EXPECT_TRUE(m.Backspace());
    EXPECT_EQ(T9Segment::kUnassigned, m.segments()[0].phase);
    EXPECT_EQ("5", m.segments()[0].digits);

    // bs9: 删 '5'
    EXPECT_TRUE(m.Backspace());
    EXPECT_EQ("", m.segments()[0].digits);

    // bs10: 无可撤销
    EXPECT_FALSE(m.Backspace());
    EXPECT_TRUE(m.IsEmpty());
    // undo 股 + undo 九 各撤销 1 个 commit
    EXPECT_EQ(2, m.ConsumeUndoneCommitCount());
}

// ── 多词 commit（"价格"）+ tail 消费（"咕"）完整回退 ──
//
// 场景：54482 → 左选 j(5) → 左选 g(4) → 右选"价格"(RightCommitMulti j、g)
//   → 右选"咕"(ConsumeTail '48'，tail '482' → '2')。
// 回退顺序：
//   撤咕(恢复 tail '482') → 删 '2'(防连击，不连续 P1) → 删 '8' → 删 '4'
//   → undo LC(g)（RC(价格) 延后）→ 删 '4'(g) → undo 价格(合并 LC(j)) → 删 '5' → 清空。
// 关键验证：① ConsumeTail undo 恢复 prev_tail；② bs2 防连击不撤销"价格"；
// ③ RightCommitMulti undo 后多段回退（j 合并、g 先经 LC 撤销）。
TEST(T9UndoModelTest, Variant2_JiaGeGu_FullUndoSequence) {
    T9UndoModel m;
    for (char d : std::string("54482")) m.DigitPressed(d);
    m.LeftChoice(SyllableOption("j", 1));
    m.LeftChoice(SyllableOption("g", 1));
    m.RightCommitMulti({0, 1});  // j、g → "价格"
    m.ConsumeTail(2);            // 从 tail '482' 消费 '48'（"咕"）

    // bs1: undo 咕（P1）→ tail 恢复 '482'
    EXPECT_TRUE(m.Backspace());
    EXPECT_EQ("482", m.tail_digits());

    // bs2: 防连击 → 不连续 P1 撤销"价格"，P2 删 tail '2'
    EXPECT_TRUE(m.Backspace());
    EXPECT_EQ("48", m.tail_digits());
    EXPECT_EQ(T9Segment::kCommitted, m.segments()[0].phase);
    EXPECT_EQ(T9Segment::kCommitted, m.segments()[1].phase);

    // bs3-4: 删 '8'、'4'
    EXPECT_TRUE(m.Backspace());
    EXPECT_EQ("4", m.tail_digits());
    EXPECT_TRUE(m.Backspace());
    EXPECT_EQ("", m.tail_digits());

    // bs5: undo 价格（整体撤销，j、g 都回 selected）。
    //   NeedsDefer 修复：g 段已被 RC({0,1}) commit（∈ commit_indices），
    //   是"被 commit 段"而非"其他段"，RC 不延后、整体撤销。
    EXPECT_TRUE(m.Backspace());
    EXPECT_EQ(T9Segment::kSelected, m.segments()[0].phase);
    EXPECT_EQ(T9Segment::kSelected, m.segments()[1].phase);

    // bs6: undo LC(g) → g 回 unassigned（digits 保留）
    EXPECT_TRUE(m.Backspace());
    EXPECT_EQ(T9Segment::kUnassigned, m.segments()[1].phase);
    EXPECT_EQ("4", m.segments()[1].digits);
    EXPECT_EQ(T9Segment::kSelected, m.segments()[0].phase);

    // bs7: P2 优先 → 删 g 段 '4'（unassigned 数字，先于 undo LC(j)）
    EXPECT_TRUE(m.Backspace());
    EXPECT_EQ("", m.segments()[1].digits);
    EXPECT_EQ(T9Segment::kSelected, m.segments()[0].phase);

    // bs8: undo LC(j) → j 回 unassigned
    EXPECT_TRUE(m.Backspace());
    EXPECT_EQ(T9Segment::kUnassigned, m.segments()[0].phase);
    EXPECT_EQ("5", m.segments()[0].digits);

    // bs9: 删 '5'
    EXPECT_TRUE(m.Backspace());
    EXPECT_EQ("", m.segments()[0].digits);

    // bs10: 无可撤销
    EXPECT_FALSE(m.Backspace());
    EXPECT_TRUE(m.IsEmpty());
    // undo 咕(kTailConsume) + undo 价格(kRC) 各撤销 1 个 commit
    EXPECT_EQ(2, m.ConsumeUndoneCommitCount());
}

// ── SyncRightCommit 差异映射（命令模式 RightCommit → 段模型）──

TEST(T9UndoModelTest, SyncRightCommit_Scenario13_Li) {
    // 场景13 右选"里"（li commit，partial）：
    //   prev selections=[li,gu,b] → new selections=[gu,b] → commit li 段；
    //   prev/new unassigned 均空 → 无 tail 消费。
    T9UndoModel m;
    for (char d : std::string("54482")) m.DigitPressed(d);
    m.LeftChoice(SyllableOption("li", 2));
    m.LeftChoice(SyllableOption("gu", 2));
    m.LeftChoice(SyllableOption("b", 1));
    T9Buffer prev("54482",
        {SyllableOption("li", 2), SyllableOption("gu", 2), SyllableOption("b", 1)}, 5, 5);
    T9Buffer next("54482", {SyllableOption("gu", 2), SyllableOption("b", 1)}, 5, 5);
    m.SyncRightCommit(prev, next);
    EXPECT_EQ(T9Segment::kCommitted, m.segments()[0].phase);  // li committed
    EXPECT_EQ(T9Segment::kSelected, m.segments()[1].phase);   // gu selected
    EXPECT_EQ(T9Segment::kSelected, m.segments()[2].phase);   // b selected
}

TEST(T9UndoModelTest, SyncRightCommit_Scenario13_Gu) {
    // 场景13 右选"故"（gu commit）：li 已 committed（非活跃），
    //   prev selections=[gu,b] → new=[b] → commit gu 段（idx1）。
    T9UndoModel m;
    for (char d : std::string("54482")) m.DigitPressed(d);
    m.LeftChoice(SyllableOption("li", 2));
    m.LeftChoice(SyllableOption("gu", 2));
    m.LeftChoice(SyllableOption("b", 1));
    m.RightCommit(0);  // 先 commit li（模拟上一步右选"里"）
    T9Buffer prev("54482", {SyllableOption("gu", 2), SyllableOption("b", 1)}, 5, 5);
    T9Buffer next("54482", {SyllableOption("b", 1)}, 5, 5);
    m.SyncRightCommit(prev, next);
    EXPECT_EQ(T9Segment::kCommitted, m.segments()[0].phase);  // li 保持 committed
    EXPECT_EQ(T9Segment::kCommitted, m.segments()[1].phase);  // gu committed
    EXPECT_EQ(T9Segment::kSelected, m.segments()[2].phase);   // b selected
}

TEST(T9UndoModelTest, SyncRightCommit_MultiWord_JiaGe) {
    // 多词 commit（"价格 jia ge"）：prev selections=[j,g] → new=[] →
    //   commit j、g 两段；tail '482' 未被消费。
    T9UndoModel m;
    for (char d : std::string("54482")) m.DigitPressed(d);
    m.LeftChoice(SyllableOption("j", 1));
    m.LeftChoice(SyllableOption("g", 1));
    T9Buffer prev("54482", {SyllableOption("j", 1), SyllableOption("g", 1)}, 2, 5);
    T9Buffer next("54482", {}, 2, 5);
    m.SyncRightCommit(prev, next);
    EXPECT_EQ(T9Segment::kCommitted, m.segments()[0].phase);
    EXPECT_EQ(T9Segment::kCommitted, m.segments()[1].phase);
    EXPECT_EQ("482", m.tail_digits());
}

TEST(T9UndoModelTest, SyncRightCommit_TailConsume_Gu) {
    // tail 消费（右选"咕 gu"）："价格"已 commit（unassigned='482'），
    //   右选"咕"消费 '48' → prev unassigned='482' → next unassigned='2'。
    T9UndoModel m;
    for (char d : std::string("54482")) m.DigitPressed(d);
    m.LeftChoice(SyllableOption("j", 1));
    m.LeftChoice(SyllableOption("g", 1));
    m.RightCommitMulti({0, 1});  // 模拟"价格"已 commit
    T9Buffer prev("54482", {}, 2, 5);  // unassigned='482'
    T9Buffer next("54482", {}, 4, 5);  // 消费 '48' → unassigned='2'
    m.SyncRightCommit(prev, next);
    EXPECT_EQ("2", m.tail_digits());
    EXPECT_TRUE(m.HasPendingCommit());
}

TEST(T9UndoModelTest, ClearAndHasPendingCommit) {
    T9UndoModel m;
    for (char d : std::string("54482")) m.DigitPressed(d);
    m.LeftChoice(SyllableOption("li", 2));
    m.RightCommit(0);
    EXPECT_TRUE(m.HasPendingCommit());
    m.Clear();
    EXPECT_FALSE(m.HasPendingCommit());
    EXPECT_TRUE(m.IsEmpty());
}

// ── 集成一致性：SyncRightCommit（命令模式 diff 映射）与直接 RightCommit
//   的段模型状态 + 完整回退轨迹必须一致 ──

TEST(T9UndoModelTest, SyncRightCommit_MatchesDirectRightCommit_UndoTrace) {
    // 直接路径（段模型原生 RightCommit）
    T9UndoModel direct;
    for (char d : std::string("54482")) direct.DigitPressed(d);
    direct.LeftChoice(SyllableOption("li", 2));
    direct.LeftChoice(SyllableOption("gu", 2));
    direct.LeftChoice(SyllableOption("b", 1));
    direct.RightCommit(0);  // 里
    direct.RightCommit(1);  // 故

    // Sync 路径（命令模式 RightCommit 后的 buffer diff，T9Processor 双写走此路径）
    T9UndoModel synced;
    for (char d : std::string("54482")) synced.DigitPressed(d);
    synced.LeftChoice(SyllableOption("li", 2));
    synced.LeftChoice(SyllableOption("gu", 2));
    synced.LeftChoice(SyllableOption("b", 1));
    synced.SyncRightCommit(
        T9Buffer("54482",
            {SyllableOption("li", 2), SyllableOption("gu", 2), SyllableOption("b", 1)}, 5, 5),
        T9Buffer("54482", {SyllableOption("gu", 2), SyllableOption("b", 1)}, 5, 5));
    synced.SyncRightCommit(
        T9Buffer("54482", {SyllableOption("gu", 2), SyllableOption("b", 1)}, 5, 5),
        T9Buffer("54482", {SyllableOption("b", 1)}, 5, 5));

    // 完整回退轨迹逐位对比（digitSeq / consumed / unassigned / selections）
    for (int step = 0; step < 12; ++step) {
        bool d_ok = direct.Backspace();
        bool s_ok = synced.Backspace();
        ASSERT_EQ(d_ok, s_ok) << "backspace step " << step;
        if (!d_ok) break;
        auto db = direct.ToBuffer();
        auto sb = synced.ToBuffer();
        EXPECT_EQ(db.digit_sequence, sb.digit_sequence) << "digitSeq step " << step;
        EXPECT_EQ(db.consumed_count, sb.consumed_count) << "consumed step " << step;
        EXPECT_EQ(db.unassigned(), sb.unassigned()) << "unassigned step " << step;
        ASSERT_EQ(db.selections.size(), sb.selections.size()) << "selCount step " << step;
        for (size_t i = 0; i < db.selections.size(); ++i) {
            EXPECT_EQ(db.selections[i].pinyin, sb.selections[i].pinyin)
                << "sel " << i << " step " << step;
        }
    }
    // 撤销 commit 计数一致（场景13 undo 故 + undo 里）
    EXPECT_EQ(direct.ConsumeUndoneCommitCount(), synced.ConsumeUndoneCommitCount());
}

// ── 设备日志实证（2026-08-05）：命令模式右选"九/股"的真实 buffer 序列 ──
//
// 之前 bug：HandleRightCommit 的 prev_buf 是引用（const T9Buffer&），策略执行
// 赋值 ctx.input_buffer 后引用失效 → SyncRightCommit 收到两份相同 buffer，
// commitIndices=[]（右选不 commit 任何段）→ 回退直接删数字、无 undo RC。
// 此测试用 adb 日志确认的 buffer 形态验证修复后的映射 + 完整回退：
//   右选"九"：prev={54482,[j,g],2,5} → new={54482,[g],2,5}（j 移除，g 保留）→ commit j(段0)
//   右选"股"：prev={54482,[g],2,5} → new={482,[],0,3}（apostrophe 重建）→ commit g(段1)
// 完整回退：undo 股 → 删 2,8,4 → undo LC(g) → 删 4 → undo 九 → 删 5
TEST(T9UndoModelTest, SyncRightCommit_JiuGu_RealCommandBuffer_UndoTrace) {
    T9UndoModel m;
    for (char d : std::string("54482")) m.DigitPressed(d);
    m.LeftChoice(SyllableOption("j", 1));
    m.LeftChoice(SyllableOption("g", 1));
    // 右选"九"（命令模式真实 buffer 序列）
    m.SyncRightCommit(
        T9Buffer("54482", {SyllableOption("j", 1), SyllableOption("g", 1)}, 2, 5),
        T9Buffer("54482", {SyllableOption("g", 1)}, 2, 5));
    EXPECT_EQ(T9Segment::kCommitted, m.segments()[0].phase);  // j committed
    EXPECT_EQ(T9Segment::kSelected, m.segments()[1].phase);   // g selected
    // 右选"股"（命令模式真实 buffer 序列）
    m.SyncRightCommit(
        T9Buffer("54482", {SyllableOption("g", 1)}, 2, 5),
        T9Buffer("482", {}, 0, 3));
    EXPECT_EQ(T9Segment::kCommitted, m.segments()[1].phase);  // g committed
    EXPECT_EQ("482", m.tail_digits());

    // bs1: undo 股（P1）→ g 回 selected（用户期望的第一步）
    EXPECT_TRUE(m.Backspace());
    EXPECT_EQ(T9Segment::kSelected, m.segments()[1].phase);
    EXPECT_EQ(T9Segment::kCommitted, m.segments()[0].phase);
    EXPECT_EQ("482", m.tail_digits());
    EXPECT_EQ(1, m.ConsumeUndoneCommitCount());

    // bs2-4: 删 2,8,4
    EXPECT_TRUE(m.Backspace());
    EXPECT_EQ("48", m.tail_digits());
    EXPECT_TRUE(m.Backspace());
    EXPECT_EQ("4", m.tail_digits());
    EXPECT_TRUE(m.Backspace());
    EXPECT_EQ("", m.tail_digits());

    // bs5: RC(九) 延后 → undo LC(g)
    EXPECT_TRUE(m.Backspace());
    EXPECT_EQ(T9Segment::kUnassigned, m.segments()[1].phase);
    EXPECT_EQ("4", m.segments()[1].digits);
    EXPECT_EQ(T9Segment::kCommitted, m.segments()[0].phase);

    // bs6: 删 g 段 '4'
    EXPECT_TRUE(m.Backspace());
    EXPECT_EQ("", m.segments()[1].digits);

    // bs7: undo 九（字母段 j 非 merge_first，对标主流输入法 2026-08-06）→ j 回 selected
    EXPECT_TRUE(m.Backspace());
    EXPECT_EQ(T9Segment::kSelected, m.segments()[0].phase);
    EXPECT_EQ("5", m.segments()[0].digits);

    // bs8: undo j（LC0）→ j 回 unassigned
    EXPECT_TRUE(m.Backspace());
    EXPECT_EQ(T9Segment::kUnassigned, m.segments()[0].phase);
    EXPECT_EQ("5", m.segments()[0].digits);

    // bs9: 删 '5'
    EXPECT_TRUE(m.Backspace());
    EXPECT_EQ("", m.segments()[0].digits);

    // 空
    EXPECT_FALSE(m.Backspace());
    EXPECT_TRUE(m.IsEmpty());
    // undo 股 的计数已在 bs1 后消费，此处剩余 undo 九 的 1
    EXPECT_EQ(1, m.ConsumeUndoneCommitCount());
}

// 场景：左选 j、g、g，右选两个单字"建、广"（相同 g 段）→ SyncRightCommit 必须一一对应匹配，
// 不再把中间"广"的 commit 误判为"仍存在"（修复 2026-08-06：场景30 的感/广 commitIndices=[] 漏记）。
TEST(T9UndoModelTest, SyncRightCommit_JGG_TwoSingles_NoLeak) {
    T9UndoModel m;
    for (char d : std::string("54482")) m.DigitPressed(d);
    m.LeftChoice(SyllableOption("j", 1));
    m.LeftChoice(SyllableOption("g", 1));
    m.LeftChoice(SyllableOption("g", 1));
    // 建（jian）：commit j 段（段0）
    m.SyncRightCommit(
        T9Buffer("54482", {SyllableOption("j", 1), SyllableOption("g", 1),
                           SyllableOption("g", 1)}, 3, 5),
        T9Buffer("54482", {SyllableOption("g", 1), SyllableOption("g", 1)}, 3, 5));
    EXPECT_EQ(T9Segment::kCommitted, m.segments()[0].phase);  // j committed（建）
    // 广（guang）：commit 第一个 g 段（段1），不得漏记
    m.SyncRightCommit(
        T9Buffer("54482", {SyllableOption("g", 1), SyllableOption("g", 1)}, 3, 5),
        T9Buffer("54482", {SyllableOption("g", 1)}, 3, 5));
    EXPECT_EQ(T9Segment::kCommitted, m.segments()[1].phase);  // g committed（广）
    EXPECT_EQ(T9Segment::kSelected, m.segments()[2].phase);   // g selected（未右选）
    EXPECT_EQ("82", m.tail_digits());
}

// 场景：左选 j、g、g，右选"价格"（jia ge：commit j、g 两段）+ 右选"公"（gong：commit 段2）→
// SyncRightCommit 一对一台匹配后"价格"必须 commit j、g 两段（旧实现只 commit j）。
TEST(T9UndoModelTest, SyncRightCommit_JGG_JiaGe_Gong_NoLeak) {
    T9UndoModel m;
    for (char d : std::string("54482")) m.DigitPressed(d);
    m.LeftChoice(SyllableOption("j", 1));
    m.LeftChoice(SyllableOption("g", 1));
    m.LeftChoice(SyllableOption("g", 1));
    // 价格（jia ge）：commit j、g 两段（段0、段1），剩段2 g
    m.SyncRightCommit(
        T9Buffer("54482", {SyllableOption("j", 1), SyllableOption("g", 1),
                           SyllableOption("g", 1)}, 3, 5),
        T9Buffer("54482", {SyllableOption("g", 1)}, 3, 5));
    EXPECT_EQ(T9Segment::kCommitted, m.segments()[0].phase);
    EXPECT_EQ(T9Segment::kCommitted, m.segments()[1].phase);  // 价格 commit j、g 两段
    EXPECT_EQ(T9Segment::kSelected, m.segments()[2].phase);
    // 公（gong）：commit 段2 g
    m.SyncRightCommit(
        T9Buffer("54482", {SyllableOption("g", 1)}, 3, 5),
        T9Buffer("82", {}, 0, 3));
    EXPECT_EQ(T9Segment::kCommitted, m.segments()[2].phase);
    EXPECT_EQ("82", m.tail_digits());
}

// ── 设计文档 §5.4 场景驱动测试（两阶段状态机：段撤销优先 + 位置删除）──

// 场景2：54482 左选 li（剩 482）→ 回退 = undo li → 删 2,8,4,4,5（6 次）
TEST(T9UndoModelTest, Scenario2_Li_UndoFirst) {
    auto m = MakeLeftChoiceModel("54482", {{"li", 2}});
    // ⌫1: undo li（段撤销优先于删 tail）
    EXPECT_TRUE(m.Backspace());
    EXPECT_EQ(T9Segment::kUnassigned, m.segments()[0].phase);
    EXPECT_EQ("54", m.segments()[0].digits);
    EXPECT_EQ("482", m.tail_digits());
    // ⌫2-6: 删 2,8,4,4,5
    EXPECT_TRUE(m.Backspace());
    EXPECT_EQ("48", m.tail_digits());
    EXPECT_TRUE(m.Backspace());
    EXPECT_EQ("4", m.tail_digits());
    EXPECT_TRUE(m.Backspace());
    EXPECT_EQ("", m.tail_digits());
    EXPECT_TRUE(m.Backspace());
    EXPECT_EQ("5", m.segments()[0].digits);
    EXPECT_TRUE(m.Backspace());
    EXPECT_EQ("", m.segments()[0].digits);
    EXPECT_FALSE(m.Backspace());
    EXPECT_TRUE(m.IsEmpty());
}

// 场景3：54482 左选 ji、gua（全消费）→ 回退 = undo gua → 删 2,8,4 → undo ji → 删 4,5（7 次）
TEST(T9UndoModelTest, Scenario3_JiGua) {
    auto m = MakeLeftChoiceModel("54482", {{"ji", 2}, {"gua", 3}});
    // ⌫1: undo gua
    EXPECT_TRUE(m.Backspace());
    EXPECT_EQ(T9Segment::kUnassigned, m.segments()[1].phase);
    EXPECT_EQ("482", m.segments()[1].digits);
    // ⌫2-4: 删 2,8,4
    EXPECT_TRUE(m.Backspace());
    EXPECT_EQ("48", m.segments()[1].digits);
    EXPECT_TRUE(m.Backspace());
    EXPECT_EQ("4", m.segments()[1].digits);
    EXPECT_TRUE(m.Backspace());
    EXPECT_EQ("", m.segments()[1].digits);
    // ⌫5: undo ji
    EXPECT_TRUE(m.Backspace());
    EXPECT_EQ(T9Segment::kUnassigned, m.segments()[0].phase);
    // ⌫6-7: 删 4,5
    EXPECT_TRUE(m.Backspace());
    EXPECT_EQ("5", m.segments()[0].digits);
    EXPECT_TRUE(m.Backspace());
    EXPECT_EQ("", m.segments()[0].digits);
    EXPECT_FALSE(m.Backspace());
    EXPECT_TRUE(m.IsEmpty());
}

// 场景4：54482 左选 ji、gu（剩 2）→ 回退 = undo gu → 删 2,8,4 → undo ji → 删 4,5（7 次）
TEST(T9UndoModelTest, Scenario4_JiGu_Tail2) {
    auto m = MakeLeftChoiceModel("54482", {{"ji", 2}, {"gu", 2}});
    EXPECT_EQ("2", m.tail_digits());
    // ⌫1: undo gu（段撤销优先，即使有 tail '2'）
    EXPECT_TRUE(m.Backspace());
    EXPECT_EQ(T9Segment::kUnassigned, m.segments()[1].phase);
    EXPECT_EQ("48", m.segments()[1].digits);
    // ⌫2-4: 删 2（tail）、8、4（gu）
    EXPECT_TRUE(m.Backspace());
    EXPECT_EQ("", m.tail_digits());
    EXPECT_TRUE(m.Backspace());
    EXPECT_EQ("4", m.segments()[1].digits);
    EXPECT_TRUE(m.Backspace());
    EXPECT_EQ("", m.segments()[1].digits);
    // ⌫5: undo ji
    EXPECT_TRUE(m.Backspace());
    EXPECT_EQ(T9Segment::kUnassigned, m.segments()[0].phase);
    // ⌫6-7: 删 4,5
    EXPECT_TRUE(m.Backspace());
    EXPECT_EQ("5", m.segments()[0].digits);
    EXPECT_TRUE(m.Backspace());
    EXPECT_EQ("", m.segments()[0].digits);
    EXPECT_FALSE(m.Backspace());
    EXPECT_TRUE(m.IsEmpty());
}

// 场景5：54482 左选 ji、gu、b（全消费）→ 回退 = undo b → 删 2 → undo gu → 删 8,4 → undo ji → 删 4,5（8 次）
TEST(T9UndoModelTest, Scenario5_JiGuB) {
    auto m = MakeLeftChoiceModel("54482", {{"ji", 2}, {"gu", 2}, {"b", 1}});
    // ⌫1: undo b
    EXPECT_TRUE(m.Backspace());
    EXPECT_EQ(T9Segment::kUnassigned, m.segments()[2].phase);
    EXPECT_EQ("2", m.segments()[2].digits);
    // ⌫2: 删 2
    EXPECT_TRUE(m.Backspace());
    EXPECT_EQ("", m.segments()[2].digits);
    // ⌫3: undo gu
    EXPECT_TRUE(m.Backspace());
    EXPECT_EQ(T9Segment::kUnassigned, m.segments()[1].phase);
    // ⌫4-5: 删 8,4
    EXPECT_TRUE(m.Backspace());
    EXPECT_EQ("4", m.segments()[1].digits);
    EXPECT_TRUE(m.Backspace());
    EXPECT_EQ("", m.segments()[1].digits);
    // ⌫6: undo ji
    EXPECT_TRUE(m.Backspace());
    EXPECT_EQ(T9Segment::kUnassigned, m.segments()[0].phase);
    // ⌫7-8: 删 4,5
    EXPECT_TRUE(m.Backspace());
    EXPECT_EQ("5", m.segments()[0].digits);
    EXPECT_TRUE(m.Backspace());
    EXPECT_EQ("", m.segments()[0].digits);
    EXPECT_FALSE(m.Backspace());
    EXPECT_TRUE(m.IsEmpty());
}

// 场景6：54482 左选 ji、g（剩 82）→ 回退 = undo g → 删 2,8,4 → undo ji → 删 4,5（7 次）
TEST(T9UndoModelTest, Scenario6_JiG_Tail82) {
    auto m = MakeLeftChoiceModel("54482", {{"ji", 2}, {"g", 1}});
    EXPECT_EQ("82", m.tail_digits());
    // ⌫1: undo g（段撤销优先，即使有 tail '82'）
    EXPECT_TRUE(m.Backspace());
    EXPECT_EQ(T9Segment::kUnassigned, m.segments()[1].phase);
    EXPECT_EQ("4", m.segments()[1].digits);
    // ⌫2-4: 删 2、8（tail）、4（g）
    EXPECT_TRUE(m.Backspace());
    EXPECT_EQ("8", m.tail_digits());
    EXPECT_TRUE(m.Backspace());
    EXPECT_EQ("", m.tail_digits());
    EXPECT_TRUE(m.Backspace());
    EXPECT_EQ("", m.segments()[1].digits);
    // ⌫5: undo ji
    EXPECT_TRUE(m.Backspace());
    EXPECT_EQ(T9Segment::kUnassigned, m.segments()[0].phase);
    // ⌫6-7: 删 4,5
    EXPECT_TRUE(m.Backspace());
    EXPECT_EQ("5", m.segments()[0].digits);
    EXPECT_TRUE(m.Backspace());
    EXPECT_EQ("", m.segments()[0].digits);
    EXPECT_FALSE(m.Backspace());
    EXPECT_TRUE(m.IsEmpty());
}

// 场景7：54482 左选 ji、g、t（剩 2）→ 回退 = undo t → 删 2,8 → undo g → 删 4 → undo ji → 删 4,5（8 次）
TEST(T9UndoModelTest, Scenario7_JiGT_Tail2) {
    auto m = MakeLeftChoiceModel("54482", {{"ji", 2}, {"g", 1}, {"t", 1}});
    EXPECT_EQ("2", m.tail_digits());
    // ⌫1: undo t
    EXPECT_TRUE(m.Backspace());
    EXPECT_EQ(T9Segment::kUnassigned, m.segments()[2].phase);
    // ⌫2-3: 删 2（tail）、8（t）
    EXPECT_TRUE(m.Backspace());
    EXPECT_EQ("", m.tail_digits());
    EXPECT_TRUE(m.Backspace());
    EXPECT_EQ("", m.segments()[2].digits);
    // ⌫4: undo g
    EXPECT_TRUE(m.Backspace());
    EXPECT_EQ(T9Segment::kUnassigned, m.segments()[1].phase);
    // ⌫5: 删 4
    EXPECT_TRUE(m.Backspace());
    EXPECT_EQ("", m.segments()[1].digits);
    // ⌫6: undo ji
    EXPECT_TRUE(m.Backspace());
    EXPECT_EQ(T9Segment::kUnassigned, m.segments()[0].phase);
    // ⌫7-8: 删 4,5
    EXPECT_TRUE(m.Backspace());
    EXPECT_EQ("5", m.segments()[0].digits);
    EXPECT_TRUE(m.Backspace());
    EXPECT_EQ("", m.segments()[0].digits);
    EXPECT_FALSE(m.Backspace());
    EXPECT_TRUE(m.IsEmpty());
}

// 场景8：54482 左选 ji、g、t、a（全消费）→ 回退 = undo a → 删 2 → undo t → 删 8 → undo g → 删 4 → undo ji → 删 4,5（9 次）
TEST(T9UndoModelTest, Scenario8_JiGTA) {
    auto m = MakeLeftChoiceModel("54482", {{"ji", 2}, {"g", 1}, {"t", 1}, {"a", 1}});
    // ⌫1: undo a
    EXPECT_TRUE(m.Backspace());
    EXPECT_EQ(T9Segment::kUnassigned, m.segments()[3].phase);
    // ⌫2: 删 2
    EXPECT_TRUE(m.Backspace());
    EXPECT_EQ("", m.segments()[3].digits);
    // ⌫3: undo t
    EXPECT_TRUE(m.Backspace());
    EXPECT_EQ(T9Segment::kUnassigned, m.segments()[2].phase);
    // ⌫4: 删 8
    EXPECT_TRUE(m.Backspace());
    EXPECT_EQ("", m.segments()[2].digits);
    // ⌫5: undo g
    EXPECT_TRUE(m.Backspace());
    EXPECT_EQ(T9Segment::kUnassigned, m.segments()[1].phase);
    // ⌫6: 删 4
    EXPECT_TRUE(m.Backspace());
    EXPECT_EQ("", m.segments()[1].digits);
    // ⌫7: undo ji
    EXPECT_TRUE(m.Backspace());
    EXPECT_EQ(T9Segment::kUnassigned, m.segments()[0].phase);
    // ⌫8-9: 删 4,5
    EXPECT_TRUE(m.Backspace());
    EXPECT_EQ("5", m.segments()[0].digits);
    EXPECT_TRUE(m.Backspace());
    EXPECT_EQ("", m.segments()[0].digits);
    EXPECT_FALSE(m.Backspace());
    EXPECT_TRUE(m.IsEmpty());
}

// 场景9：54482 左选 ji、g、ta（全消费）→ 回退 = undo ta → 删 2,8 → undo g → 删 4 → undo ji → 删 4,5（8 次）
TEST(T9UndoModelTest, Scenario9_JiGTa) {
    auto m = MakeLeftChoiceModel("54482", {{"ji", 2}, {"g", 1}, {"ta", 2}});
    // ⌫1: undo ta
    EXPECT_TRUE(m.Backspace());
    EXPECT_EQ(T9Segment::kUnassigned, m.segments()[2].phase);
    EXPECT_EQ("82", m.segments()[2].digits);
    // ⌫2-3: 删 2,8
    EXPECT_TRUE(m.Backspace());
    EXPECT_EQ("8", m.segments()[2].digits);
    EXPECT_TRUE(m.Backspace());
    EXPECT_EQ("", m.segments()[2].digits);
    // ⌫4: undo g
    EXPECT_TRUE(m.Backspace());
    EXPECT_EQ(T9Segment::kUnassigned, m.segments()[1].phase);
    // ⌫5: 删 4
    EXPECT_TRUE(m.Backspace());
    EXPECT_EQ("", m.segments()[1].digits);
    // ⌫6: undo ji
    EXPECT_TRUE(m.Backspace());
    EXPECT_EQ(T9Segment::kUnassigned, m.segments()[0].phase);
    // ⌫7-8: 删 4,5
    EXPECT_TRUE(m.Backspace());
    EXPECT_EQ("5", m.segments()[0].digits);
    EXPECT_TRUE(m.Backspace());
    EXPECT_EQ("", m.segments()[0].digits);
    EXPECT_FALSE(m.Backspace());
    EXPECT_TRUE(m.IsEmpty());
}

// 5143（k、he、右选拷）：分词键 1 作为位置元素在 undo k 后、删 5 前删除（7 次）
// 场景：5143 左选 k、he，右选"拷"（简拼 kao→k，commit 首字母 k 段）。
// 设备实证（2026-08-06）：SyncRightCommit commitIndices=[k 段]，"拷" commit k 段。
// 简拼模式回退 = undo 拷 → undo he → 删 3,4 → undo k → 删 1(分词键) → 删 5（7 次）。
// 修复：NeedsDefer 收紧为"完整拼音段单段 commit 段0"——k 是字母段（1 位），不延后。
TEST(T9UndoModelTest, Scenario5143_KSepHe_Kao_UndoRCFirst) {
    T9UndoModel m;
    for (char d : std::string("543")) m.DigitPressed(d);
    m.SeparatorPressed(1);  // 分词键 1（'5' 与 '4' 之间）
    m.LeftChoice(SyllableOption("k", 1));
    m.LeftChoice(SyllableOption("he", 2));
    m.RightCommit(0);  // "拷" commit k 段（简拼 k）
    // ⌫1: undo 拷 → k 回 selected（字母段 commit 非 merge_first，直接撤销）
    EXPECT_TRUE(m.Backspace());
    EXPECT_EQ(T9Segment::kSelected, m.segments()[0].phase);
    EXPECT_EQ(T9Segment::kSelected, m.segments()[1].phase);
    // ⌫2: undo he（无未删数字 → 撤销栈顶 selected 段）
    EXPECT_TRUE(m.Backspace());
    EXPECT_EQ(T9Segment::kUnassigned, m.segments()[1].phase);
    EXPECT_EQ("43", m.segments()[1].digits);
    // ⌫3-4: 删 3,4
    EXPECT_TRUE(m.Backspace());
    EXPECT_EQ("4", m.segments()[1].digits);
    EXPECT_TRUE(m.Backspace());
    EXPECT_EQ("", m.segments()[1].digits);
    // ⌫5: undo k
    EXPECT_TRUE(m.Backspace());
    EXPECT_EQ(T9Segment::kUnassigned, m.segments()[0].phase);
    EXPECT_EQ("5", m.segments()[0].digits);
    // ⌫6: 删分词键 1（完整位置 1 > '5' 位置 0）
    EXPECT_TRUE(m.Backspace());
    EXPECT_TRUE(m.separator_positions().empty());
    EXPECT_EQ("5", m.segments()[0].digits);
    // ⌫7: 删 '5'
    EXPECT_TRUE(m.Backspace());
    EXPECT_EQ("", m.segments()[0].digits);
    EXPECT_FALSE(m.Backspace());
    EXPECT_TRUE(m.IsEmpty());
}

// 场景1（文档 L234-248）：5、4、3 左选 k、h（纯简拼字母）→ 回退 = 删 3 → undo h → 删 4 → undo k → 删 5（5 次）。
// 修复（2026-08-06，设备实证"第一步 undo h → 'k'43'"）：简拼模式数字删除优先于撤销段。
TEST(T9UndoModelTest, Scenario543_KH_Tail3_DeleteFirst) {
    T9UndoModel m;
    m.DigitPressed('5');
    m.LeftChoice(SyllableOption("k", 1));
    m.DigitPressed('4');
    m.LeftChoice(SyllableOption("h", 1));
    m.DigitPressed('3');
    // ⌫1: 删 3（tail 数字优先于 undo h）
    EXPECT_TRUE(m.Backspace());
    EXPECT_EQ("", m.tail_digits());
    EXPECT_EQ(T9Segment::kSelected, m.segments()[1].phase);
    // ⌫2: undo h
    EXPECT_TRUE(m.Backspace());
    EXPECT_EQ(T9Segment::kUnassigned, m.segments()[1].phase);
    EXPECT_EQ("4", m.segments()[1].digits);
    // ⌫3: 删 4
    EXPECT_TRUE(m.Backspace());
    EXPECT_EQ("", m.segments()[1].digits);
    // ⌫4: undo k
    EXPECT_TRUE(m.Backspace());
    EXPECT_EQ(T9Segment::kUnassigned, m.segments()[0].phase);
    EXPECT_EQ("5", m.segments()[0].digits);
    // ⌫5: 删 5
    EXPECT_TRUE(m.Backspace());
    EXPECT_EQ("", m.segments()[0].digits);
    EXPECT_FALSE(m.Backspace());
    EXPECT_TRUE(m.IsEmpty());
}

// 场景2（文档 L253-267）：5、1(分词键)、4、3 左选 k（简拼+分词键）→
// 回退 = 删 3 → 删 4 → undo k → 删 1(分词键) → 删 5（5 次）。
TEST(T9UndoModelTest, Scenario5143_KSep_Tail43_DeleteFirst) {
    T9UndoModel m;
    m.DigitPressed('5');
    m.SeparatorPressed(1);  // 分词键 1（'5' 之后）
    m.DigitPressed('4');
    m.DigitPressed('3');
    m.LeftChoice(SyllableOption("k", 1));
    // ⌫1: 删 3（tail 末位）
    EXPECT_TRUE(m.Backspace());
    EXPECT_EQ("4", m.tail_digits());
    EXPECT_EQ(T9Segment::kSelected, m.segments()[0].phase);
    // ⌫2: 删 4
    EXPECT_TRUE(m.Backspace());
    EXPECT_EQ("", m.tail_digits());
    // ⌫3: undo k
    EXPECT_TRUE(m.Backspace());
    EXPECT_EQ(T9Segment::kUnassigned, m.segments()[0].phase);
    EXPECT_EQ("5", m.segments()[0].digits);
    // ⌫4: 删分词键 1
    EXPECT_TRUE(m.Backspace());
    EXPECT_TRUE(m.separator_positions().empty());
    // ⌫5: 删 5
    EXPECT_TRUE(m.Backspace());
    EXPECT_EQ("", m.segments()[0].digits);
    EXPECT_FALSE(m.Backspace());
    EXPECT_TRUE(m.IsEmpty());
}

// 场景4（文档 L397-415）：5、1(分词键)、4、3 左选 k、g、d，右选"跨国 kua guo"（commit k、g）→
// 回退 = undo 跨国 → undo d → 删 3 → undo g → 删 4 → undo k → 删 1 → 删 5（8 次）。
// 修复（2026-08-06，设备实证"第一步 RC defer undo d"）：多段 commit 不延后，直接撤销 RC。
TEST(T9UndoModelTest, Scenario5143_KGD_KuaGuo_UndoRCFirst) {
    T9UndoModel m;
    m.DigitPressed('5');
    m.SeparatorPressed(1);
    m.DigitPressed('4');
    m.DigitPressed('3');
    m.LeftChoice(SyllableOption("k", 1));
    m.LeftChoice(SyllableOption("g", 1));
    m.LeftChoice(SyllableOption("d", 1));
    m.RightCommitMulti({0, 1});  // 跨国 kua guo：commit k、g 段
    // ⌫1: undo 跨国（多段 commit 不延后）→ k、g 回 selected
    EXPECT_TRUE(m.Backspace());
    EXPECT_EQ(T9Segment::kSelected, m.segments()[0].phase);
    EXPECT_EQ(T9Segment::kSelected, m.segments()[1].phase);
    EXPECT_EQ(T9Segment::kSelected, m.segments()[2].phase);
    // ⌫2: undo d
    EXPECT_TRUE(m.Backspace());
    EXPECT_EQ(T9Segment::kUnassigned, m.segments()[2].phase);
    // ⌫3: 删 3
    EXPECT_TRUE(m.Backspace());
    EXPECT_EQ("", m.segments()[2].digits);
    // ⌫4: undo g
    EXPECT_TRUE(m.Backspace());
    EXPECT_EQ(T9Segment::kUnassigned, m.segments()[1].phase);
    // ⌫5: 删 4
    EXPECT_TRUE(m.Backspace());
    EXPECT_EQ("", m.segments()[1].digits);
    // ⌫6: undo k
    EXPECT_TRUE(m.Backspace());
    EXPECT_EQ(T9Segment::kUnassigned, m.segments()[0].phase);
    // ⌫7: 删分词键 1
    EXPECT_TRUE(m.Backspace());
    EXPECT_TRUE(m.separator_positions().empty());
    // ⌫8: 删 5
    EXPECT_TRUE(m.Backspace());
    EXPECT_EQ("", m.segments()[0].digits);
    EXPECT_FALSE(m.Backspace());
    EXPECT_TRUE(m.IsEmpty());
}

// 场景33（文档 L726-746）：5143 左选 k、he，右选"口号 kou hao"（部分消费：口号只消费
// '5'+'4'（k、h），释放 he 段的 '3'（e）），再左选 d → 回退 8 次。
// 修复（2026-08-06，设备实证"左选 d 段空数字 → 左侧候选区白屏 + ⌫1 错删分词键"）：
// SyncRightCommit 处理部分消费——new.unassigned('3') 比 prev.unassigned('') 长 → 释放 1 位，
// he[43] 截为 he[4]，'3' 回 tail → 左选 d 正常消费 '3' → d[3]（不再白屏）。
// 用户裁定（2026-08-06）：543 = k + he，'3' 属于 he（he 的 'e'）——undo 口号 时回收 '3'
// → he 恢复完整 he[43]，随后 undo he → 删 3 → 删 4（'3' 不作为 d 的独立数字先删）。
// 回退 = undo d → undo 口号 → undo he → 删 3 → 删 4 → undo k → 删分词键 → 删 5（8 次）。
TEST(T9UndoModelTest, Scenario33_5143_KHe_KouHao_D_PartialConsume) {
    T9UndoModel m;
    m.DigitPressed('5');
    m.SeparatorPressed(1);  // 分词键 1（'5' 与 '4' 之间）
    m.DigitPressed('4');
    m.DigitPressed('3');
    m.LeftChoice(SyllableOption("k", 1));
    m.LeftChoice(SyllableOption("he", 2));
    // 右选"口号 kou hao"：命令模式只消费 '5'+'4'（k、h），释放 he 段的 '3'（e）
    m.SyncRightCommit(
        T9Buffer("543", {SyllableOption("k", 1), SyllableOption("he", 2)}, 3, 4),
        T9Buffer("543", {}, 2, 4));
    // 部分消费：he[43] → he[4]，'3' 回 tail（不再被困在 he 段 → 左选 d 不白屏）
    ASSERT_EQ(2u, m.segments().size());
    EXPECT_EQ(T9Segment::kCommitted, m.segments()[0].phase);
    EXPECT_EQ("5", m.segments()[0].digits);
    EXPECT_EQ(T9Segment::kCommitted, m.segments()[1].phase);
    EXPECT_EQ("4", m.segments()[1].digits);
    EXPECT_EQ("3", m.tail_digits());
    // 左选 d → 从 tail 消费 '3' → d[3]（段有数字 → 左侧候选不白屏）
    m.LeftChoice(SyllableOption("d", 1));
    ASSERT_EQ(3u, m.segments().size());
    EXPECT_EQ(T9Segment::kSelected, m.segments()[2].phase);
    EXPECT_EQ("3", m.segments()[2].digits);
    // ⌫1: undo d（d 回 unassigned，数字 3 保留 → 候选 e，预编辑"口号e"）
    EXPECT_TRUE(m.Backspace());
    EXPECT_EQ(T9Segment::kUnassigned, m.segments()[2].phase);
    EXPECT_EQ("3", m.segments()[2].digits);
    // ⌫2: undo 口号（栈顶 RC 优先撤销）→ 回收 '3'：he[4] → he[43]，d 的 '3' 归还 he
    EXPECT_TRUE(m.Backspace());
    EXPECT_EQ(T9Segment::kSelected, m.segments()[0].phase);
    EXPECT_EQ(T9Segment::kSelected, m.segments()[1].phase);
    EXPECT_EQ("43", m.segments()[1].digits);   // he 恢复完整拼音段（'3' 是 he 的 'e'）
    EXPECT_EQ("", m.segments()[2].digits);     // d 的 '3' 被回收
    EXPECT_EQ("", m.tail_digits());
    // ⌫3: undo he（he 回 unassigned，数字 43 保留）
    EXPECT_TRUE(m.Backspace());
    EXPECT_EQ(T9Segment::kUnassigned, m.segments()[1].phase);
    EXPECT_EQ("43", m.segments()[1].digits);
    // ⌫4: 删 3（he 的数字区，位置最后）
    EXPECT_TRUE(m.Backspace());
    EXPECT_EQ("4", m.segments()[1].digits);
    // ⌫5: 删 4（he 的数字区）
    EXPECT_TRUE(m.Backspace());
    EXPECT_EQ("", m.segments()[1].digits);
    // ⌫6: undo k
    EXPECT_TRUE(m.Backspace());
    EXPECT_EQ(T9Segment::kUnassigned, m.segments()[0].phase);
    // ⌫7: 删分词键 1（完整位置 1 > '5' 位置 0）
    EXPECT_TRUE(m.Backspace());
    EXPECT_TRUE(m.separator_positions().empty());
    EXPECT_EQ("5", m.segments()[0].digits);
    // ⌫8: 删 '5'
    EXPECT_TRUE(m.Backspace());
    EXPECT_EQ("", m.segments()[0].digits);
    EXPECT_FALSE(m.Backspace());
    EXPECT_TRUE(m.IsEmpty());
}

// 用户实测（2026-08-09）：5143 左选 k → 右选"客户 ke hu"（简拼 k h：commit k 段 +
// TC 消费 tail '4'，linked）→ 左选 e（消费剩余 '3'）→ ⌫1 undo e → ⌫2 undo 客户。
// 修复前：undo 客户 后 tail='4'、e 段 digits='3' 残留 → 派生 unassigned='34'（预编辑
// "k di"，异常字符 'i'）；期望派生 unassigned='43'（"k he/ge"）。
// 根因：UndoOp(kTailConsume) 恢复 tail 时假设"剩余 tail 数字都在 tail_digits_ 中"，
// 但 undo LC 残留的 unassigned 段数字（原输入位置在被恢复数字之后）打破了该假设。
// 修复：undo TC 恢复 tail 时把 undo LC 残留的 unassigned 段数字一并并入 tail。
TEST(T9UndoModelTest, Scenario5143_K_KeHu_E_UndoKeHu_TailOrder) {
    T9UndoModel m;
    for (char d : std::string("543")) m.DigitPressed(d);
    m.SeparatorPressed(1);  // 分词键 1（'5' 与 '4' 之间）
    m.LeftChoice(SyllableOption("k", 1));
    // 右选"客户 ke hu"：commit k 段 + TC 消费 tail '4'（linked）
    m.SyncRightCommit(
        T9Buffer("543", {SyllableOption("k", 1)}, 1, 4),
        T9Buffer("3", {}, 0, 4));
    ASSERT_EQ(1u, m.segments().size());
    EXPECT_EQ(T9Segment::kCommitted, m.segments()[0].phase);
    EXPECT_EQ("3", m.tail_digits());
    // 左选 e → 从 tail 消费剩余 '3'
    m.LeftChoice(SyllableOption("e", 1));
    ASSERT_EQ(2u, m.segments().size());
    EXPECT_EQ(T9Segment::kSelected, m.segments()[1].phase);
    EXPECT_EQ("3", m.segments()[1].digits);
    EXPECT_EQ("", m.tail_digits());
    // ⌫1: undo e（数字保留在段，等待数字区删除）
    EXPECT_TRUE(m.Backspace());
    EXPECT_EQ(T9Segment::kUnassigned, m.segments()[1].phase);
    EXPECT_EQ("3", m.segments()[1].digits);
    // ⌫2: undo 客户（RC+TC linked 整体撤销）→ tail 恢复 '43'（'4' 在 '3' 前）
    EXPECT_TRUE(m.Backspace());
    EXPECT_EQ(T9Segment::kSelected, m.segments()[0].phase);
    EXPECT_EQ("43", m.tail_digits());
    // e 段残留空段（数字已收拢回 tail，保持原始顺序）
    EXPECT_EQ("", m.segments()[1].digits);
    // 派生 buffer：unassigned='43'（修复前 '34' → 预编辑 "k di"）
    T9Buffer buf = m.ToBuffer();
    EXPECT_EQ("543", buf.digit_sequence);
    EXPECT_EQ(1, buf.consumed_count);
    EXPECT_EQ("43", buf.unassigned());
    // 后续回退：删 3,4（tail 末位）→ undo k → 删分词键 → 删 5
    // （e 段数字已收拢回 tail 并删除 → 残留空段无操作可撤销，自然跳过）
    EXPECT_TRUE(m.Backspace());
    EXPECT_EQ("4", m.tail_digits());
    EXPECT_TRUE(m.Backspace());
    EXPECT_EQ("", m.tail_digits());
    EXPECT_TRUE(m.Backspace());  // undo k → k 回 unassigned
    EXPECT_EQ(T9Segment::kUnassigned, m.segments()[0].phase);
    EXPECT_TRUE(m.Backspace());  // 删分词键 1
    EXPECT_TRUE(m.separator_positions().empty());
    EXPECT_TRUE(m.Backspace());  // 删 '5'
    EXPECT_EQ("", m.segments()[0].digits);
    EXPECT_FALSE(m.Backspace());
    EXPECT_TRUE(m.IsEmpty());
}

// 场景：54482 无左选，纯右选"几"（ji 54）、"股"（gu 48）→ 回退 = 7 次。
// 回归（2026-08-05）：undo kTailConsume 曾完整恢复 prev_tail，嵌套消费（两次
// ConsumeTail）时 undo 几 重复恢复已删的 482 → 次数 10 vs 设计 7。
// 修复：只恢复该次消费的数字（prev_tail 开头 n 位）。
TEST(T9UndoModelTest, Scenario_JiGu_TwoTailConsume) {
    T9UndoModel m;
    for (char d : std::string("54482")) m.DigitPressed(d);
    m.ConsumeTail(2);  // "几" ji 消费 '54'
    m.ConsumeTail(2);  // "股" gu 消费 '48'
    EXPECT_EQ("2", m.tail_digits());
    // ⌫1: undo 股 → tail 恢复 '482'（48 + 原 tail 2）
    EXPECT_TRUE(m.Backspace());
    EXPECT_EQ("482", m.tail_digits());
    // ⌫2-4: 删 2,8,4（股数字区 + tail）
    EXPECT_TRUE(m.Backspace());
    EXPECT_EQ("48", m.tail_digits());
    EXPECT_TRUE(m.Backspace());
    EXPECT_EQ("4", m.tail_digits());
    EXPECT_TRUE(m.Backspace());
    EXPECT_EQ("", m.tail_digits());
    // ⌫5: undo 几 → 只恢复 '54'（不重复恢复已删的 482）
    EXPECT_TRUE(m.Backspace());
    EXPECT_EQ("54", m.tail_digits());
    // ⌫6-7: 删 4,5
    EXPECT_TRUE(m.Backspace());
    EXPECT_EQ("5", m.tail_digits());
    EXPECT_TRUE(m.Backspace());
    EXPECT_EQ("", m.tail_digits());
    EXPECT_FALSE(m.Backspace());
    EXPECT_TRUE(m.IsEmpty());
}

// 回归（2026-08-05，设备实证"第二次输入异常"）：回退完后残留空段（digits=''）在
// segments_ 数组中，再次输入左选追加到残留段之后。ToBuffer 派生必须跳过空段，
// 否则残留空段（unassigned）提前 break → consumed=0、selections 丢失、预编辑变全数字。
TEST(T9UndoModelTest, StaleEmptySegments_ToBufferSkips) {
    auto m = MakeLeftChoiceModel("54482", {{"li", 2}, {"gua", 3}});
    // 完整回退到空（残留空段留在数组中）
    while (m.Backspace()) {}
    EXPECT_TRUE(m.IsEmpty());
    // 再次输入 + 左选（新段追加在残留空段之后）
    for (char d : std::string("54482")) m.DigitPressed(d);
    m.LeftChoice(SyllableOption("li", 2));
    m.LeftChoice(SyllableOption("gua", 3));
    auto b = m.ToBuffer();
    EXPECT_EQ("54482", b.digit_sequence);
    EXPECT_EQ(5, b.consumed_count);  // li(2) + gua(3) 全消费
    ASSERT_EQ(2u, b.selections.size());
    EXPECT_EQ("li", b.selections[0].pinyin);
    EXPECT_EQ("gua", b.selections[1].pinyin);
    EXPECT_TRUE(b.unassigned().empty());
    // 再次回退也应正确：undo gua → 删 2,8,4 → undo li → 删 4,5（7 次）
    int steps = 0;
    while (m.Backspace()) ++steps;
    EXPECT_EQ(7, steps);
    EXPECT_TRUE(m.IsEmpty());
}

// 回归（2026-08-05，设备实证"li 正常、ji 异常"）：回退完后 delete_mode_ 残留 true，
// 再次输入左选后回退，第一步必须 undo 段（阶段 A）而非删数字（阶段 B）——
// 输入操作（DigitPressed/LeftChoice）必须重置删除阶段。
TEST(T9UndoModelTest, DeletePhaseReset_SecondInputUndoFirst) {
    auto m = MakeLeftChoiceModel("54482", {{"li", 2}});
    // 完整回退到空（delete_mode_ 残留 true）
    while (m.Backspace()) {}
    EXPECT_TRUE(m.IsEmpty());
    // 再次输入 + 左选 ji（新段追加在残留空段之后）
    for (char d : std::string("54482")) m.DigitPressed(d);
    m.LeftChoice(SyllableOption("ji", 2));
    size_t ji_seg = m.segments().size() - 1;  // 最后追加的段
    // 回退第一步：阶段 A undo ji（而非阶段 B 删 tail '2'）
    EXPECT_TRUE(m.Backspace());
    EXPECT_EQ(T9Segment::kUnassigned, m.segments()[ji_seg].phase);
    EXPECT_EQ("54", m.segments()[ji_seg].digits);
    // 剩余：删 2,8,4,4,5（5 次）→ 共 6 次
    int steps = 1;
    while (m.Backspace()) ++steps;
    EXPECT_EQ(6, steps);
    EXPECT_TRUE(m.IsEmpty());
}

// 场景16（文档 L345-366）：54482 左选 j，右选"结婚后 jie hun hou"（commit j 段 + 消费 tail '44'）→
// 回退 = 联动 undo 结婚后（j 回 selected + tail 恢复 4482）→ 删 2,8,4,4 → undo j → 删 5（7 次）。
// 修复（2026-08-06，设备实证"第一步 g hua 丢失 j"）：
//   ① RC+TC（一次右选）整体撤销（TryUndoLinkedCommit）；② merge_first 加 tail 空条件（有 tail 回 selected）。
TEST(T9UndoModelTest, Scenario16_J_JieHunHou) {
    T9UndoModel m;
    for (char d : std::string("54482")) m.DigitPressed(d);
    m.LeftChoice(SyllableOption("j", 1));
    m.RightCommit(0);        // 结婚后 commit j 段
    m.ConsumeTail(2, true);  // 结婚后消费 tail '44'（h、h，linked_rc）
    EXPECT_EQ("82", m.tail_digits());
    // ⌫1: 联动 undo 结婚后 → j 回 selected（tail 4482 非空 → 非 merge_first）+ tail 恢复
    EXPECT_TRUE(m.Backspace());
    EXPECT_EQ(T9Segment::kSelected, m.segments()[0].phase);
    EXPECT_EQ("4482", m.tail_digits());
    // ⌫2-5: 删 2,8,4,4
    EXPECT_TRUE(m.Backspace());
    EXPECT_EQ("448", m.tail_digits());
    EXPECT_TRUE(m.Backspace());
    EXPECT_EQ("44", m.tail_digits());
    EXPECT_TRUE(m.Backspace());
    EXPECT_EQ("4", m.tail_digits());
    EXPECT_TRUE(m.Backspace());
    EXPECT_EQ("", m.tail_digits());
    // ⌫6: undo j
    EXPECT_TRUE(m.Backspace());
    EXPECT_EQ(T9Segment::kUnassigned, m.segments()[0].phase);
    EXPECT_EQ("5", m.segments()[0].digits);
    // ⌫7: 删 5
    EXPECT_TRUE(m.Backspace());
    EXPECT_EQ("", m.segments()[0].digits);
    EXPECT_FALSE(m.Backspace());
    EXPECT_TRUE(m.IsEmpty());
}

// 场景17（文档 L368-389）：54482 左选 j，右选"价格 jia ge"（commit j 段 + 消费 tail '4'）→ 7 次。
TEST(T9UndoModelTest, Scenario17_J_JiaGe) {
    T9UndoModel m;
    for (char d : std::string("54482")) m.DigitPressed(d);
    m.LeftChoice(SyllableOption("j", 1));
    m.RightCommit(0);        // 价格 commit j 段
    m.ConsumeTail(1, true);  // 价格消费 tail '4'（g，linked_rc）
    EXPECT_EQ("482", m.tail_digits());
    // ⌫1: 联动 undo 价格 → j 回 selected + tail 4482
    EXPECT_TRUE(m.Backspace());
    EXPECT_EQ(T9Segment::kSelected, m.segments()[0].phase);
    EXPECT_EQ("4482", m.tail_digits());
    // ⌫2-5: 删 2,8,4,4
    EXPECT_TRUE(m.Backspace());
    EXPECT_EQ("448", m.tail_digits());
    EXPECT_TRUE(m.Backspace());
    EXPECT_EQ("44", m.tail_digits());
    EXPECT_TRUE(m.Backspace());
    EXPECT_EQ("4", m.tail_digits());
    EXPECT_TRUE(m.Backspace());
    EXPECT_EQ("", m.tail_digits());
    // ⌫6: undo j
    EXPECT_TRUE(m.Backspace());
    EXPECT_EQ(T9Segment::kUnassigned, m.segments()[0].phase);
    EXPECT_EQ("5", m.segments()[0].digits);
    // ⌫7: 删 5
    EXPECT_TRUE(m.Backspace());
    EXPECT_EQ("", m.segments()[0].digits);
    EXPECT_FALSE(m.Backspace());
    EXPECT_TRUE(m.IsEmpty());
}

// 场景18（文档 L391-416）：54482 左选 j，右选"价格"（RC+TC linked）+ 右选"沽 gu"（独立 TC）→ 8 次。
TEST(T9UndoModelTest, Scenario18_J_JiaGe_Gu) {
    T9UndoModel m;
    for (char d : std::string("54482")) m.DigitPressed(d);
    m.LeftChoice(SyllableOption("j", 1));
    m.RightCommit(0);        // 价格 commit j 段
    m.ConsumeTail(1, true);  // 价格消费 '4'（g，linked_rc）
    m.ConsumeTail(2, false); // 沽 gu 消费 '48'（独立 TC）
    EXPECT_EQ("2", m.tail_digits());
    // ⌫1: undo 沽（独立 TC，不联动）→ tail 482
    EXPECT_TRUE(m.Backspace());
    EXPECT_EQ("482", m.tail_digits());
    // ⌫2-4: 删 2,8,4
    EXPECT_TRUE(m.Backspace());
    EXPECT_EQ("48", m.tail_digits());
    EXPECT_TRUE(m.Backspace());
    EXPECT_EQ("4", m.tail_digits());
    EXPECT_TRUE(m.Backspace());
    EXPECT_EQ("", m.tail_digits());
    // ⌫5: 联动 undo 价格 → j 回 selected + tail 恢复 '4'
    EXPECT_TRUE(m.Backspace());
    EXPECT_EQ(T9Segment::kSelected, m.segments()[0].phase);
    EXPECT_EQ("4", m.tail_digits());
    // ⌫6: 删 4
    EXPECT_TRUE(m.Backspace());
    EXPECT_EQ("", m.tail_digits());
    // ⌫7: undo j
    EXPECT_TRUE(m.Backspace());
    EXPECT_EQ(T9Segment::kUnassigned, m.segments()[0].phase);
    EXPECT_EQ("5", m.segments()[0].digits);
    // ⌫8: 删 5
    EXPECT_TRUE(m.Backspace());
    EXPECT_EQ("", m.segments()[0].digits);
    EXPECT_FALSE(m.Backspace());
    EXPECT_TRUE(m.IsEmpty());
}

// 场景29（文档 L412-434）：54482 左选 j、g，右选"九"（单字，commit j 段，无 tail 消费）→ 回退 8 次。
// 修复（2026-08-06，设备实证"第一步 RC defer 撤销 g 而非九"）：本回退序列尚未撤销过 commit
// （last_backspace_undid_commit_=false）时，栈顶 RC 是"最后产生的右选"，直接撤销（不延后）；
// 九/股、场景13 之所以延后，是因为已先撤销过更晚的右选（股/故），当前 RC 不再是最后产生。
TEST(T9UndoModelTest, Scenario29_JG_Jiu_UndoRCFirst) {
    T9UndoModel m;
    for (char d : std::string("54482")) m.DigitPressed(d);
    m.LeftChoice(SyllableOption("j", 1));
    m.LeftChoice(SyllableOption("g", 1));
    m.RightCommit(0);  // 九 commit j 段
    EXPECT_EQ("482", m.tail_digits());
    // ⌫1: undo 九（最后产生的右选，不延后）→ j 回 selected（tail 非空 → 非 merge_first）
    EXPECT_TRUE(m.Backspace());
    EXPECT_EQ(T9Segment::kSelected, m.segments()[0].phase);
    EXPECT_EQ(T9Segment::kSelected, m.segments()[1].phase);
    EXPECT_EQ("482", m.tail_digits());
    // ⌫2-4: 删 2,8,4（tail）
    EXPECT_TRUE(m.Backspace());
    EXPECT_EQ("48", m.tail_digits());
    EXPECT_TRUE(m.Backspace());
    EXPECT_EQ("4", m.tail_digits());
    EXPECT_TRUE(m.Backspace());
    EXPECT_EQ("", m.tail_digits());
    // ⌫5: undo g（LC1）
    EXPECT_TRUE(m.Backspace());
    EXPECT_EQ(T9Segment::kUnassigned, m.segments()[1].phase);
    EXPECT_EQ("4", m.segments()[1].digits);
    // ⌫6: 删 4（g 段）
    EXPECT_TRUE(m.Backspace());
    EXPECT_EQ("", m.segments()[1].digits);
    // ⌫7: undo j（LC0）
    EXPECT_TRUE(m.Backspace());
    EXPECT_EQ(T9Segment::kUnassigned, m.segments()[0].phase);
    EXPECT_EQ("5", m.segments()[0].digits);
    // ⌫8: 删 5
    EXPECT_TRUE(m.Backspace());
    EXPECT_EQ("", m.segments()[0].digits);
    EXPECT_FALSE(m.Backspace());
    EXPECT_TRUE(m.IsEmpty());
}

// 场景30（文档 L435-463）：54482 左选 j、g、g，右选三个单字"九、感、干"（commit 段0/1/2）→ 回退 11 次。
// 修复（2026-08-06，adb 日志实证 + 用户对标主流输入法裁定）：
//   ① SyncRightCommit 一一对应匹配（修复"感"漏 commit：相同 g 段"存在即匹配"误判）；
//   ② NeedsDefer 去"逻辑首段"限制（感（段1）也延后：先撤销其下段2 g 再 undo 感）；
//   ③ merge_first 仅完整拼音段（digit_length >= 2）——字母段 j undo 回 selected，用户单独 undo j
//      （对标主流输入法：九/股 9 次、场景30 11 次，之前 merge_first 一步为错误）。
TEST(T9UndoModelTest, Scenario30_JGG_JiuGanGan) {
    T9UndoModel m;
    for (char d : std::string("54482")) m.DigitPressed(d);
    m.LeftChoice(SyllableOption("j", 1));
    m.LeftChoice(SyllableOption("g", 1));
    m.LeftChoice(SyllableOption("g", 1));
    m.RightCommit(0);  // 九 commit j 段
    m.RightCommit(1);  // 感 commit g 段1
    m.RightCommit(2);  // 干 commit g 段2
    EXPECT_EQ("82", m.tail_digits());
    // ⌫1: undo 干（最后产生的右选，不延后）→ 段2 回 selected
    EXPECT_TRUE(m.Backspace());
    EXPECT_EQ(T9Segment::kSelected, m.segments()[2].phase);
    EXPECT_EQ(T9Segment::kCommitted, m.segments()[0].phase);
    EXPECT_EQ(T9Segment::kCommitted, m.segments()[1].phase);
    // ⌫2-3: 删 2,8（防连击）
    EXPECT_TRUE(m.Backspace());
    EXPECT_EQ("8", m.tail_digits());
    EXPECT_TRUE(m.Backspace());
    EXPECT_EQ("", m.tail_digits());
    // ⌫4: 感延后 → undo g（段2 LC2）
    EXPECT_TRUE(m.Backspace());
    EXPECT_EQ(T9Segment::kUnassigned, m.segments()[2].phase);
    EXPECT_EQ("4", m.segments()[2].digits);
    // ⌫5: 删 4（段2）
    EXPECT_TRUE(m.Backspace());
    EXPECT_EQ("", m.segments()[2].digits);
    // ⌫6: undo 感 → 段1 回 selected
    EXPECT_TRUE(m.Backspace());
    EXPECT_EQ(T9Segment::kSelected, m.segments()[1].phase);
    // ⌫7: undo g（段1 LC1）
    EXPECT_TRUE(m.Backspace());
    EXPECT_EQ(T9Segment::kUnassigned, m.segments()[1].phase);
    // ⌫8: 删 4（段1）
    EXPECT_TRUE(m.Backspace());
    EXPECT_EQ("", m.segments()[1].digits);
    // ⌫9: undo 九（字母段 j 非 merge_first）→ j 回 selected
    EXPECT_TRUE(m.Backspace());
    EXPECT_EQ(T9Segment::kSelected, m.segments()[0].phase);
    EXPECT_EQ("5", m.segments()[0].digits);
    // ⌫10: undo j（LC0）
    EXPECT_TRUE(m.Backspace());
    EXPECT_EQ(T9Segment::kUnassigned, m.segments()[0].phase);
    // ⌫11: 删 5
    EXPECT_TRUE(m.Backspace());
    EXPECT_EQ("", m.segments()[0].digits);
    EXPECT_FALSE(m.Backspace());
    EXPECT_TRUE(m.IsEmpty());
    // undo 干 + undo 感 + undo 九 各 1 个 commit
    EXPECT_EQ(3, m.ConsumeUndoneCommitCount());
}

// 场景31（文档 L463-489）：54482 左选 j、g、g、t，右选两组双字词组"价格"（{0,1}）、"共同"（{2,3}）→ 回退 11 次。
// 修复（2026-08-06，adb 日志实证"⌫3 就撤销了价格"）：NeedsDefer 去掉"多段 commit 不延后"限制——
// 多段词组（价格 {0,1}）也延后：先撤销其下未被本 RC commit 的 selected 段（段3 t、段2 g），
// 再整体撤销价格（对标主流输入法）。
TEST(T9UndoModelTest, Scenario31_JGGT_JiaGe_GongTong) {
    T9UndoModel m;
    for (char d : std::string("54482")) m.DigitPressed(d);
    m.LeftChoice(SyllableOption("j", 1));
    m.LeftChoice(SyllableOption("g", 1));
    m.LeftChoice(SyllableOption("g", 1));
    m.LeftChoice(SyllableOption("t", 1));
    m.RightCommitMulti({0, 1});  // 价格 commit j、g
    m.RightCommitMulti({2, 3});  // 共同 commit g、t
    EXPECT_EQ("2", m.tail_digits());
    // ⌫1: undo 共同（最后产生的右选）→ 段2、3 回 selected
    EXPECT_TRUE(m.Backspace());
    EXPECT_EQ(T9Segment::kSelected, m.segments()[2].phase);
    EXPECT_EQ(T9Segment::kSelected, m.segments()[3].phase);
    EXPECT_EQ(T9Segment::kCommitted, m.segments()[0].phase);
    EXPECT_EQ(T9Segment::kCommitted, m.segments()[1].phase);
    // ⌫2: 删 2（防连击）
    EXPECT_TRUE(m.Backspace());
    EXPECT_EQ("", m.tail_digits());
    // ⌫3: 价格延后 → undo t（段3 LC3）
    EXPECT_TRUE(m.Backspace());
    EXPECT_EQ(T9Segment::kUnassigned, m.segments()[3].phase);
    EXPECT_EQ("8", m.segments()[3].digits);
    // ⌫4: 删 8（段3）
    EXPECT_TRUE(m.Backspace());
    EXPECT_EQ("", m.segments()[3].digits);
    // ⌫5: 价格仍延后 → undo g（段2 LC2）
    EXPECT_TRUE(m.Backspace());
    EXPECT_EQ(T9Segment::kUnassigned, m.segments()[2].phase);
    EXPECT_EQ("4", m.segments()[2].digits);
    // ⌫6: 删 4（段2）
    EXPECT_TRUE(m.Backspace());
    EXPECT_EQ("", m.segments()[2].digits);
    // ⌫7: undo 价格（无其他 selected 段）→ 段0、1 回 selected
    EXPECT_TRUE(m.Backspace());
    EXPECT_EQ(T9Segment::kSelected, m.segments()[0].phase);
    EXPECT_EQ(T9Segment::kSelected, m.segments()[1].phase);
    // ⌫8: undo g（段1 LC1）
    EXPECT_TRUE(m.Backspace());
    EXPECT_EQ(T9Segment::kUnassigned, m.segments()[1].phase);
    // ⌫9: 删 4（段1）
    EXPECT_TRUE(m.Backspace());
    EXPECT_EQ("", m.segments()[1].digits);
    // ⌫10: undo j（段0 LC0）
    EXPECT_TRUE(m.Backspace());
    EXPECT_EQ(T9Segment::kUnassigned, m.segments()[0].phase);
    EXPECT_EQ("5", m.segments()[0].digits);
    // ⌫11: 删 5
    EXPECT_TRUE(m.Backspace());
    EXPECT_EQ("", m.segments()[0].digits);
    EXPECT_FALSE(m.Backspace());
    EXPECT_TRUE(m.IsEmpty());
    // undo 共同 + undo 价格 各 1 个 commit
    EXPECT_EQ(2, m.ConsumeUndoneCommitCount());
}

// 场景：左选 j、g、g、t，右选"结果"（{0,1} 词组）+ "公"（{2} 单字）→ 回退 11 次。
// 与场景31 同模式：undo 公 → 删 2 → 结果延后（撤销段3 t、段2 g）→ undo 结果 → undo g → 删 4 → undo j → 删 5。
TEST(T9UndoModelTest, Scenario31_JGGT_JieGuo_Gong) {
    T9UndoModel m;
    for (char d : std::string("54482")) m.DigitPressed(d);
    m.LeftChoice(SyllableOption("j", 1));
    m.LeftChoice(SyllableOption("g", 1));
    m.LeftChoice(SyllableOption("g", 1));
    m.LeftChoice(SyllableOption("t", 1));
    m.RightCommitMulti({0, 1});  // 结果 commit j、g
    m.RightCommit(2);            // 公 commit g（段2）
    EXPECT_EQ(T9Segment::kSelected, m.segments()[3].phase);  // 段3 t 未右选
    EXPECT_EQ("2", m.tail_digits());
    // ⌫1: undo 公 → 段2 回 selected
    EXPECT_TRUE(m.Backspace());
    EXPECT_EQ(T9Segment::kSelected, m.segments()[2].phase);
    // ⌫2: 删 2（防连击）
    EXPECT_TRUE(m.Backspace());
    EXPECT_EQ("", m.tail_digits());
    // ⌫3: 结果延后 → undo t（段3 LC3）
    EXPECT_TRUE(m.Backspace());
    EXPECT_EQ(T9Segment::kUnassigned, m.segments()[3].phase);
    EXPECT_EQ("8", m.segments()[3].digits);
    // ⌫4: 删 8（段3）
    EXPECT_TRUE(m.Backspace());
    EXPECT_EQ("", m.segments()[3].digits);
    // ⌫5: 结果仍延后 → undo g（段2 LC2）
    EXPECT_TRUE(m.Backspace());
    EXPECT_EQ(T9Segment::kUnassigned, m.segments()[2].phase);
    // ⌫6: 删 4（段2）
    EXPECT_TRUE(m.Backspace());
    EXPECT_EQ("", m.segments()[2].digits);
    // ⌫7: undo 结果 → 段0、1 回 selected
    EXPECT_TRUE(m.Backspace());
    EXPECT_EQ(T9Segment::kSelected, m.segments()[0].phase);
    EXPECT_EQ(T9Segment::kSelected, m.segments()[1].phase);
    // ⌫8: undo g（段1 LC1）
    EXPECT_TRUE(m.Backspace());
    EXPECT_EQ(T9Segment::kUnassigned, m.segments()[1].phase);
    // ⌫9: 删 4（段1）
    EXPECT_TRUE(m.Backspace());
    EXPECT_EQ("", m.segments()[1].digits);
    // ⌫10: undo j（段0 LC0）
    EXPECT_TRUE(m.Backspace());
    EXPECT_EQ(T9Segment::kUnassigned, m.segments()[0].phase);
    // ⌫11: 删 5
    EXPECT_TRUE(m.Backspace());
    EXPECT_EQ("", m.segments()[0].digits);
    EXPECT_FALSE(m.Backspace());
    EXPECT_TRUE(m.IsEmpty());
}

// 场景：左选 j、g、g、t，右选"几个"（{0,1} 词组）+ "官"（{2}）+ "听"（{3}）→ 回退 12 次。
// 同模式：undo 听 → 删 2 → 官延后（撤销段3 t）→ undo 官 → 几个延后（撤销段2 g）→ undo 几个 → undo g → 删 4 → undo j → 删 5。
TEST(T9UndoModelTest, Scenario31_JGGT_JiGe_Guan_Ting) {
    T9UndoModel m;
    for (char d : std::string("54482")) m.DigitPressed(d);
    m.LeftChoice(SyllableOption("j", 1));
    m.LeftChoice(SyllableOption("g", 1));
    m.LeftChoice(SyllableOption("g", 1));
    m.LeftChoice(SyllableOption("t", 1));
    m.RightCommitMulti({0, 1});  // 几个 commit j、g
    m.RightCommit(2);            // 官 commit g（段2）
    m.RightCommit(3);            // 听 commit t（段3）
    EXPECT_EQ("2", m.tail_digits());
    // ⌫1: undo 听（最后产生的右选）→ 段3 回 selected
    EXPECT_TRUE(m.Backspace());
    EXPECT_EQ(T9Segment::kSelected, m.segments()[3].phase);
    // ⌫2: 删 2（防连击）
    EXPECT_TRUE(m.Backspace());
    EXPECT_EQ("", m.tail_digits());
    // ⌫3: 官延后 → undo t（段3 LC3）
    EXPECT_TRUE(m.Backspace());
    EXPECT_EQ(T9Segment::kUnassigned, m.segments()[3].phase);
    // ⌫4: 删 8（段3）
    EXPECT_TRUE(m.Backspace());
    EXPECT_EQ("", m.segments()[3].digits);
    // ⌫5: undo 官（段2 是其 commit 段 → 不延后）→ 段2 回 selected
    EXPECT_TRUE(m.Backspace());
    EXPECT_EQ(T9Segment::kSelected, m.segments()[2].phase);
    // ⌫6: 几个延后 → undo g（段2 LC2）
    EXPECT_TRUE(m.Backspace());
    EXPECT_EQ(T9Segment::kUnassigned, m.segments()[2].phase);
    // ⌫7: 删 4（段2）
    EXPECT_TRUE(m.Backspace());
    EXPECT_EQ("", m.segments()[2].digits);
    // ⌫8: undo 几个 → 段0、1 回 selected
    EXPECT_TRUE(m.Backspace());
    EXPECT_EQ(T9Segment::kSelected, m.segments()[0].phase);
    EXPECT_EQ(T9Segment::kSelected, m.segments()[1].phase);
    // ⌫9: undo g（段1 LC1）
    EXPECT_TRUE(m.Backspace());
    EXPECT_EQ(T9Segment::kUnassigned, m.segments()[1].phase);
    // ⌫10: 删 4（段1）
    EXPECT_TRUE(m.Backspace());
    EXPECT_EQ("", m.segments()[1].digits);
    // ⌫11: undo j（段0 LC0）
    EXPECT_TRUE(m.Backspace());
    EXPECT_EQ(T9Segment::kUnassigned, m.segments()[0].phase);
    // ⌫12: 删 5
    EXPECT_TRUE(m.Backspace());
    EXPECT_EQ("", m.segments()[0].digits);
    EXPECT_FALSE(m.Backspace());
    EXPECT_TRUE(m.IsEmpty());
}

// 场景32（文档 L490-523）：54482 左选 j、g、g、t、b，右选"九宫格"（{0,1,2}）+ "汤"（{3}）→ 回退 12 次。
// SyncRightCommit 映射修复（2026-08-06，adb 日志实证 commitIndices=[0,1,1]）：
//   FindSegmentIndex(sel, skip) 按匹配序号区分相同拼音段（两个 g → 段1、段2），
//   三字词组"九宫格"正确 commit j、g、g 三段（旧实现段2 漏 commit → 回退多余 g + 再次右选消费错乱）。
TEST(T9UndoModelTest, SyncRightCommit_JGGTB_JiuGongGe_NoDup) {
    T9UndoModel m;
    for (char d : std::string("54482")) m.DigitPressed(d);
    m.LeftChoice(SyllableOption("j", 1));
    m.LeftChoice(SyllableOption("g", 1));
    m.LeftChoice(SyllableOption("g", 1));
    m.LeftChoice(SyllableOption("t", 1));
    m.LeftChoice(SyllableOption("b", 1));
    // 九宫格（jiu gong ge = j g g）：commit j、g、g 三段（不得重复段1）
    m.SyncRightCommit(
        T9Buffer("54482", {SyllableOption("j", 1), SyllableOption("g", 1),
                           SyllableOption("g", 1), SyllableOption("t", 1),
                           SyllableOption("b", 1)}, 5, 5),
        T9Buffer("54482", {SyllableOption("t", 1), SyllableOption("b", 1)}, 5, 5));
    EXPECT_EQ(T9Segment::kCommitted, m.segments()[0].phase);  // j（九）
    EXPECT_EQ(T9Segment::kCommitted, m.segments()[1].phase);  // g（宫）
    EXPECT_EQ(T9Segment::kCommitted, m.segments()[2].phase);  // g（格，旧实现漏 commit）
    EXPECT_EQ(T9Segment::kSelected, m.segments()[3].phase);   // t 未右选
    EXPECT_EQ(T9Segment::kSelected, m.segments()[4].phase);   // b 未右选
}

// 场景32 完整回退：九宫格 {0,1,2} + 汤 {3}，段4 b selected → 12 次。
TEST(T9UndoModelTest, Scenario32_JGGTB_JiuGongGe_Tang) {
    T9UndoModel m;
    for (char d : std::string("54482")) m.DigitPressed(d);
    m.LeftChoice(SyllableOption("j", 1));
    m.LeftChoice(SyllableOption("g", 1));
    m.LeftChoice(SyllableOption("g", 1));
    m.LeftChoice(SyllableOption("t", 1));
    m.LeftChoice(SyllableOption("b", 1));
    m.RightCommitMulti({0, 1, 2});  // 九宫格 commit j、g、g
    m.RightCommit(3);               // 汤 commit t
    EXPECT_EQ(T9Segment::kSelected, m.segments()[4].phase);  // b 未右选
    EXPECT_TRUE(m.tail_digits().empty());
    // ⌫1: undo 汤（最后产生的右选）→ 段3 回 selected
    EXPECT_TRUE(m.Backspace());
    EXPECT_EQ(T9Segment::kSelected, m.segments()[3].phase);
    // ⌫2: 九宫格延后 → undo b（段4 LC4）
    EXPECT_TRUE(m.Backspace());
    EXPECT_EQ(T9Segment::kUnassigned, m.segments()[4].phase);
    EXPECT_EQ("2", m.segments()[4].digits);
    // ⌫3: 删 2（段4）
    EXPECT_TRUE(m.Backspace());
    EXPECT_EQ("", m.segments()[4].digits);
    // ⌫4: 九宫格仍延后 → undo t（段3 LC3）
    EXPECT_TRUE(m.Backspace());
    EXPECT_EQ(T9Segment::kUnassigned, m.segments()[3].phase);
    // ⌫5: 删 8（段3）
    EXPECT_TRUE(m.Backspace());
    EXPECT_EQ("", m.segments()[3].digits);
    // ⌫6: undo 九宫格（段0、1、2 是其 commit 段 → 不延后）→ 段0、1、2 回 selected
    EXPECT_TRUE(m.Backspace());
    EXPECT_EQ(T9Segment::kSelected, m.segments()[0].phase);
    EXPECT_EQ(T9Segment::kSelected, m.segments()[1].phase);
    EXPECT_EQ(T9Segment::kSelected, m.segments()[2].phase);
    // ⌫7: undo g（段2 LC2）
    EXPECT_TRUE(m.Backspace());
    EXPECT_EQ(T9Segment::kUnassigned, m.segments()[2].phase);
    // ⌫8: 删 4（段2）
    EXPECT_TRUE(m.Backspace());
    EXPECT_EQ("", m.segments()[2].digits);
    // ⌫9: undo g（段1 LC1）
    EXPECT_TRUE(m.Backspace());
    EXPECT_EQ(T9Segment::kUnassigned, m.segments()[1].phase);
    // ⌫10: 删 4（段1）
    EXPECT_TRUE(m.Backspace());
    EXPECT_EQ("", m.segments()[1].digits);
    // ⌫11: undo j（段0 LC0）
    EXPECT_TRUE(m.Backspace());
    EXPECT_EQ(T9Segment::kUnassigned, m.segments()[0].phase);
    EXPECT_EQ("5", m.segments()[0].digits);
    // ⌫12: 删 5
    EXPECT_TRUE(m.Backspace());
    EXPECT_EQ("", m.segments()[0].digits);
    EXPECT_FALSE(m.Backspace());
    EXPECT_TRUE(m.IsEmpty());
    // undo 汤 + undo 九宫格 各 1 个 commit
    EXPECT_EQ(2, m.ConsumeUndoneCommitCount());
}

// ── 场景 [Bug-2026-08-11]: 826 8426 退格撤销左选 tiao 后，段模型进入"无 selected + 有可分配数字"态 ──
// 复现：输入 8268426 → 左选 tao(826) → 右选"洮"(commit tao 段) → 左选 tiao(8426)
//       → 退格撤销 tiao。退格后 undo_model 应：tao 段 committed、tiao 段 unassigned，
//       HasSelectedSegment()=false、HasSelectableDigits()=true —— 即
//       DeriveStateMachineFromUndoModel 走 HasSelectableDigits 分支（修复点：进入
//       INPUT 前必须 ClearSelectionHistory，否则再次左选 tiao 累积 [tiao,tiao] 残留）。
TEST(T9UndoModelTest, Scenario8268426_Tao_Tiao_UndoLC_NoSelectedSegment) {
    T9UndoModel m;
    for (char d : std::string("8268426")) m.DigitPressed(d);
    m.LeftChoice(SyllableOption("tao", 3));
    // 右选"洮"(tao)：commit tao 段（partial，剩 8426 unassigned）
    m.SyncRightCommit(
        T9Buffer("8268426", {SyllableOption("tao", 3)}, 3, 7),
        T9Buffer("8426", {}, 0, 7));
    ASSERT_EQ(1u, m.segments().size());
    EXPECT_EQ(T9Segment::kCommitted, m.segments()[0].phase);
    EXPECT_EQ("8426", m.tail_digits());
    // 左选 tiao：从 tail 消费 8426
    m.LeftChoice(SyllableOption("tiao", 4));
    ASSERT_EQ(2u, m.segments().size());
    EXPECT_EQ(T9Segment::kSelected, m.segments()[1].phase);
    EXPECT_EQ("8426", m.segments()[1].digits);
    EXPECT_TRUE(m.tail_digits().empty());
    // 退格撤销 tiao（undo LC）
    EXPECT_TRUE(m.Backspace());
    EXPECT_EQ(T9Segment::kUnassigned, m.segments()[1].phase);
    EXPECT_EQ("8426", m.segments()[1].digits);
    // 段模型状态：无 selected 段 + 有可分配数字 → DeriveStateMachine 走修复分支
    EXPECT_FALSE(m.HasSelectedSegment());
    EXPECT_TRUE(m.HasSelectableDigits());
    // 派生 buffer：consumed=3（tao committed），unassigned='8426'
    T9Buffer buf = m.ToBuffer();
    EXPECT_EQ("8268426", buf.digit_sequence);
    EXPECT_EQ(3, buf.consumed_count);
    EXPECT_EQ("8426", buf.unassigned());
    EXPECT_TRUE(buf.selections.empty());
}

// ── 场景34 [Bug-2026-08-11]: 826 8426 右选"提"后回退，undo RC 回收失败 → 段回 unassigned ──
// 复现：输入 8268426 → 左选 tao(826) → 右选"洮"(commit 段0) → 左选 tiao(8426) → 右选"提"(ti=84)
//       → 左选 an(26) → ⌫1 undo an → ⌫2 删 6 → ⌫3 删 2（'26' 全部删除）→ ⌫4 undo RC(提)。
// 根因：右选"提"时 tiao 段被 commit 且截短为 '84'、释放 '26' 到 tail（SyncRightCommit release）。
//       undo RC(提) 时回收 '26' 失败（已被 ⌫2/⌫3 删除）→ 原实现段1 回 selected，
//       option='tiao'(4) 与 digits='84'(2) 失配 → 预编辑错误显示 "洮tiao/tian"（应为 "洮ti"）。
// 修复：回收不完整（digits 长度 != option.digit_length）时，段回 unassigned（合并撤销 LC），
//       digits 保留待删 → 派生 unassigned='84' → RIME 显示 'ti'，后续 ⌫5 删 4、⌫6 删 8。
TEST(T9UndoModelTest, Scenario34_8268426_TiaoTi_UndoRC_ReleaseLost_GoesUnassigned) {
    T9UndoModel m;
    for (char d : std::string("8268426")) m.DigitPressed(d);
    m.LeftChoice(SyllableOption("tao", 3));  // 段0: tao(826)
    // 右选"洮"：commit 段0（partial，剩 8426 unassigned）
    m.SyncRightCommit(
        T9Buffer("8268426", {SyllableOption("tao", 3)}, 3, 7),
        T9Buffer("8426", {}, 0, 7));
    ASSERT_EQ(1u, m.segments().size());
    EXPECT_EQ(T9Segment::kCommitted, m.segments()[0].phase);
    EXPECT_EQ("8426", m.tail_digits());
    // 左选 tiao(8426)：段1
    m.LeftChoice(SyllableOption("tiao", 4));
    ASSERT_EQ(2u, m.segments().size());
    EXPECT_EQ(T9Segment::kSelected, m.segments()[1].phase);
    EXPECT_EQ("8426", m.segments()[1].digits);
    // 右选"提"(ti=84)：commit 段1 + 截短 digits '8426'→'84'，释放 '26' 回 tail
    m.SyncRightCommit(
        T9Buffer("8426", {SyllableOption("tiao", 4)}, 4, 7),
        T9Buffer("26", {}, 0, 7));
    ASSERT_EQ(2u, m.segments().size());
    EXPECT_EQ(T9Segment::kCommitted, m.segments()[1].phase);
    EXPECT_EQ("84", m.segments()[1].digits);
    EXPECT_EQ("26", m.tail_digits());
    // 左选 an(26)：段2
    m.LeftChoice(SyllableOption("an", 2));
    ASSERT_EQ(3u, m.segments().size());
    EXPECT_EQ(T9Segment::kSelected, m.segments()[2].phase);
    EXPECT_EQ("26", m.segments()[2].digits);
    EXPECT_TRUE(m.tail_digits().empty());
    // ⌫1: undo an（段2 回 unassigned，digits 保留）
    EXPECT_TRUE(m.Backspace());
    EXPECT_EQ(T9Segment::kUnassigned, m.segments()[2].phase);
    EXPECT_EQ("26", m.segments()[2].digits);
    // ⌫2: 删 '6'
    EXPECT_TRUE(m.Backspace());
    EXPECT_EQ("2", m.segments()[2].digits);
    // ⌫3: 删 '2'（'26' 全部删除）
    EXPECT_TRUE(m.Backspace());
    EXPECT_EQ("", m.segments()[2].digits);
    // ⌫4: undo RC(提) → 回收 '26' 失败 → 段1 回 unassigned（合并撤销 LC）
    EXPECT_TRUE(m.Backspace());
    EXPECT_EQ(T9Segment::kUnassigned, m.segments()[1].phase);
    EXPECT_FALSE(m.segments()[1].has_lc);
    EXPECT_EQ("84", m.segments()[1].digits);
    // 派生 buffer：consumed=3（段0 committed），unassigned='84' → RIME 显示 'ti'
    T9Buffer buf = m.ToBuffer();
    EXPECT_EQ(3, buf.consumed_count);
    EXPECT_EQ("84", buf.unassigned());
    EXPECT_TRUE(buf.selections.empty());
    // ⌫5: 删 '4'
    EXPECT_TRUE(m.Backspace());
    EXPECT_EQ("8", m.segments()[1].digits);
    // ⌫6: 删 '8'
    EXPECT_TRUE(m.Backspace());
    EXPECT_EQ("", m.segments()[1].digits);
    // ⌫7: undo RC(洮) → 段0 满足 merge_first（单段commit+逻辑首段+完整拼音+无活跃段+tail空）
    //     合并撤销 LC → 段0 回 unassigned（digits 保留待删），与场景13 undo 里 行为一致
    EXPECT_TRUE(m.Backspace());
    EXPECT_EQ(T9Segment::kUnassigned, m.segments()[0].phase);
    EXPECT_EQ("826", m.segments()[0].digits);
    // 撤销"提"+"洮"两个 commit
    EXPECT_EQ(2, m.ConsumeUndoneCommitCount());
}
