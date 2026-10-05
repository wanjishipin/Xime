// T9StateMachine 单元测试
//
// 对应 Kotlin T9StateMachineTest.kt
// 覆盖三态状态机（IDLE/INPUT/SELECTION）的所有状态转换规则
#include <gtest/gtest.h>

#include "t9_state_machine.h"

using rime::SyllableOption;
using rime::T9StateMachine;

// ── 初始状态 ──

TEST(T9StateMachineTest, InitialState_IsIdle) {
    T9StateMachine sm;
    EXPECT_EQ(T9StateMachine::State::kIdle, sm.state());
    EXPECT_TRUE(sm.is_idle());
    EXPECT_FALSE(sm.is_input());
    EXPECT_FALSE(sm.is_selection());
    EXPECT_FALSE(sm.has_selection());
}

// ── EnterInput ──

TEST(T9StateMachineTest, EnterInput_TransitionsIdleToInput) {
    T9StateMachine sm;
    sm.EnterInput();
    EXPECT_EQ(T9StateMachine::State::kInput, sm.state());
    EXPECT_TRUE(sm.is_input());
    EXPECT_FALSE(sm.is_idle());
    EXPECT_FALSE(sm.is_selection());
    EXPECT_FALSE(sm.selected_option().has_value());
    EXPECT_FALSE(sm.selection_candidate_digits().has_value());
}

TEST(T9StateMachineTest, EnterInput_FromInput_StaysInput) {
    T9StateMachine sm;
    sm.EnterInput();
    sm.EnterInput();
    EXPECT_EQ(T9StateMachine::State::kInput, sm.state());
}

// ── EnterSelection ──

TEST(T9StateMachineTest, EnterSelection_TransitionsToSelection) {
    T9StateMachine sm;
    sm.EnterInput();
    SyllableOption option("ji", 2);
    sm.EnterSelection(option, "54");
    EXPECT_EQ(T9StateMachine::State::kSelection, sm.state());
    EXPECT_TRUE(sm.is_selection());
    EXPECT_TRUE(sm.has_selection());
    ASSERT_TRUE(sm.selected_option().has_value());
    EXPECT_EQ("ji", sm.selected_option()->pinyin);
    ASSERT_TRUE(sm.selection_candidate_digits().has_value());
    EXPECT_EQ("54", *sm.selection_candidate_digits());
}

TEST(T9StateMachineTest, EnterSelection_ReplacesPreviousSelection) {
    T9StateMachine sm;
    sm.EnterInput();
    sm.EnterSelection(SyllableOption("ji", 2), "54");
    SyllableOption new_option("li", 2);
    sm.EnterSelection(new_option, "54");
    ASSERT_TRUE(sm.selected_option().has_value());
    EXPECT_EQ("li", sm.selected_option()->pinyin);
    ASSERT_TRUE(sm.selection_candidate_digits().has_value());
    EXPECT_EQ("54", *sm.selection_candidate_digits());
}

TEST(T9StateMachineTest, EnterSelection_AddsToSelectionHistory) {
    T9StateMachine sm;
    sm.EnterInput();
    sm.EnterSelection(SyllableOption("ji", 2), "54");
    ASSERT_EQ(1u, sm.selection_history().size());
    sm.EnterSelection(SyllableOption("li", 2), "54");
    ASSERT_EQ(2u, sm.selection_history().size());
    EXPECT_EQ("li", sm.selection_history()[1].pinyin);
}

// ── EnterIdle ──

TEST(T9StateMachineTest, EnterIdle_TransitionsAnyStateToIdle) {
    T9StateMachine sm;
    sm.EnterInput();
    sm.EnterSelection(SyllableOption("ji", 2), "54");
    sm.EnterIdle();
    EXPECT_EQ(T9StateMachine::State::kIdle, sm.state());
    EXPECT_FALSE(sm.selected_option().has_value());
    EXPECT_FALSE(sm.selection_candidate_digits().has_value());
}

TEST(T9StateMachineTest, EnterIdle_FromInput_TransitionsToIdle) {
    T9StateMachine sm;
    sm.EnterInput();
    sm.EnterIdle();
    EXPECT_EQ(T9StateMachine::State::kIdle, sm.state());
}

TEST(T9StateMachineTest, EnterIdle_ClearsSelectionHistory) {
    T9StateMachine sm;
    sm.EnterInput();
    sm.EnterSelection(SyllableOption("ji", 2), "54");
    sm.EnterIdle();
    EXPECT_TRUE(sm.selection_history().empty());
}

// ── Snapshot / RestoreFrom ──

TEST(T9StateMachineTest, Snapshot_CapturesCurrentState) {
    T9StateMachine sm;
    sm.EnterInput();
    sm.EnterSelection(SyllableOption("ji", 2), "54");
    auto snap = sm.Snapshot();
    EXPECT_EQ(T9StateMachine::State::kSelection, snap.state);
    ASSERT_TRUE(snap.selected_option.has_value());
    EXPECT_EQ("ji", snap.selected_option->pinyin);
    ASSERT_TRUE(snap.selection_candidate_digits.has_value());
    EXPECT_EQ("54", *snap.selection_candidate_digits);
}

TEST(T9StateMachineTest, RestoreFrom_RecoversStateFromRawValues) {
    T9StateMachine sm;
    sm.EnterIdle();
    SyllableOption option("gua", 3);
    sm.RestoreFrom(T9StateMachine::State::kSelection, option, "482");
    EXPECT_EQ(T9StateMachine::State::kSelection, sm.state());
    ASSERT_TRUE(sm.selected_option().has_value());
    EXPECT_EQ("gua", sm.selected_option()->pinyin);
    ASSERT_TRUE(sm.selection_candidate_digits().has_value());
    EXPECT_EQ("482", *sm.selection_candidate_digits());
}

// ── 便捷查询 ──

TEST(T9StateMachineTest, HasSelection_FalseWhenSelectionButNoSelectedOption) {
    T9StateMachine sm;
    sm.EnterInput();
    sm.RestoreFrom(T9StateMachine::State::kSelection,
                   std::nullopt, std::nullopt);
    EXPECT_TRUE(sm.is_selection());
    EXPECT_FALSE(sm.has_selection());
}

// ── SelectionHistory 维护 ──

TEST(T9StateMachineTest, RemoveLastSelectionHistoryEntry) {
    T9StateMachine sm;
    sm.EnterInput();
    sm.EnterSelection(SyllableOption("ji", 2), "54");
    sm.EnterSelection(SyllableOption("li", 2), "54");
    ASSERT_EQ(2u, sm.selection_history().size());
    sm.RemoveLastSelectionHistoryEntry();
    EXPECT_EQ(1u, sm.selection_history().size());
    EXPECT_EQ("ji", sm.selection_history()[0].pinyin);
}

TEST(T9StateMachineTest, ClearSelectionHistory) {
    T9StateMachine sm;
    sm.EnterInput();
    sm.EnterSelection(SyllableOption("ji", 2), "54");
    sm.ClearSelectionHistory();
    EXPECT_TRUE(sm.selection_history().empty());
}

// ── RemoveConsumedHistoryEntries（设计稿 §6.4） ──

TEST(T9StateMachineTest, RemoveConsumedHistoryEntries_ConsumesMatching) {
    T9StateMachine sm;
    sm.EnterInput();
    sm.EnterSelection(SyllableOption("li", 2), "54");
    sm.EnterSelection(SyllableOption("gua", 3), "482");
    // 消费 "ligua" → 两个条目都被移除
    sm.RemoveConsumedHistoryEntries("ligua");
    EXPECT_TRUE(sm.selection_history().empty());
}

TEST(T9StateMachineTest, RemoveConsumedHistoryEntries_PartialConsume) {
    T9StateMachine sm;
    sm.EnterInput();
    sm.EnterSelection(SyllableOption("li", 2), "54");
    sm.EnterSelection(SyllableOption("gua", 3), "482");
    // 仅消费 "li" → 第一个条目被移除，第二个保留
    sm.RemoveConsumedHistoryEntries("li");
    EXPECT_EQ(1u, sm.selection_history().size());
    EXPECT_EQ("gua", sm.selection_history()[0].pinyin);
}

TEST(T9StateMachineTest, RemoveConsumedHistoryEntries_NoMatch_KeepsAll) {
    T9StateMachine sm;
    sm.EnterInput();
    sm.EnterSelection(SyllableOption("ji", 2), "54");
    // 不匹配 → 保留
    sm.RemoveConsumedHistoryEntries("gua");
    EXPECT_EQ(1u, sm.selection_history().size());
}

// ── LeftSelection 上下文 ──

TEST(T9StateMachineTest, LeftSelection_ReturnsContextInSelectionState) {
    T9StateMachine sm;
    sm.EnterInput();
    sm.EnterSelection(SyllableOption("ji", 2), "54", "prefix");
    auto ls = sm.left_selection();
    ASSERT_TRUE(ls.has_value());
    EXPECT_EQ("ji", ls->pinyin());
    EXPECT_EQ(2, ls->digit_length());
    EXPECT_EQ("54", ls->selection_digits);
    EXPECT_EQ("prefix", ls->pre_selected_pinyin);
}

TEST(T9StateMachineTest, LeftSelection_ReturnsNulloptInInputState) {
    T9StateMachine sm;
    sm.EnterInput();
    EXPECT_FALSE(sm.left_selection().has_value());
}
