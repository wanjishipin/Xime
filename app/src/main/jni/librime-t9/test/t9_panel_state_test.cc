// T9PanelState 单元测试
//
// 覆盖 GetLeftPanelState 的分隔符场景和锁定场景
#include <gtest/gtest.h>

#include "t9_buffer.h"
#include "t9_panel_state.h"
#include "t9_state_machine.h"

using rime::SyllableOption;
using rime::T9Buffer;
using rime::T9StateMachine;
using rime::LeftPanelStateData;
using rime::T9PanelStateContext;
using rime::t9_panel_state::GetLeftPanelState;

// ── 辅助：构造 T9PanelStateContext ──
static T9PanelStateContext MakeContext(
    const T9Buffer& buf,
    const T9StateMachine& sm,
    bool locked,
    const std::optional<std::string>& sep_digits) {
    return T9PanelStateContext(buf, sm, locked, sep_digits);
}

// ════════════════════════════════════════
// 场景：54482 → 左选 ji → 左选 g → 分词键
// ════════════════════════════════════════

// 修复前：left_column_locked=true 时 panel_digits 错误使用
// separator_consumed_digits ("54482")，导致左侧候选区显示错误的 "ji,li,..."
// 修复后：应使用 unassigned() ("482")，显示正确的 "gua,hua,..."
TEST(T9PanelStateTest, SeparatorKey_LeftColumnLocked_UsesUnassigned) {
    // 输入 54482，左选 ji(5→"ji", 2 digits)，剩余 "482" 未分配
    // 用户描述正确左面板应为 "gua, hua, gu, hu, g, h, i"（对应"482"的首音节候选）
    T9Buffer buf("54482",
                 {SyllableOption("ji", 2)},
                 2, 5);
    T9StateMachine sm;
    sm.EnterInput();

    // 模拟 HandleSeparatorKey 后的状态
    // separator_positions={5}, separator_consumed_digits="54482", left_column_locked=true
    buf.separator_positions = {5};
    std::optional<std::string> sep_digits = "54482";
    bool locked = true;

    T9PanelStateContext ctx = MakeContext(buf, sm, locked, sep_digits);
    LeftPanelStateData data;
    GetLeftPanelState(ctx, data);

    // 验证：panel_digits 应为 unassigned() = "482"，而非 separator_consumed_digits = "54482"
    // 修复前：panel_digits="54482" → 错误候选 "ji, li, ..."
    // 修复后：panel_digits="482"  → 正确候选 "gua, hua, gu, hu, ..."
    EXPECT_EQ("482", data.panel_digits);
    EXPECT_TRUE(data.left_locked);
    EXPECT_EQ(LeftPanelStateData::State::kInput, data.state);
}

// ── 场景：left_column_locked=false 时 panel_digits 使用 unassigned ──
TEST(T9PanelStateTest, LeftColumnNotLocked_UsesUnassigned) {
    T9Buffer buf("54482",
                 {SyllableOption("ji", 2)},
                 2, 5);
    T9StateMachine sm;
    sm.EnterInput();

    std::optional<std::string> sep_digits = std::nullopt;
    bool locked = false;

    T9PanelStateContext ctx = MakeContext(buf, sm, locked, sep_digits);
    LeftPanelStateData data;
    GetLeftPanelState(ctx, data);

    EXPECT_EQ("482", data.panel_digits);
    EXPECT_FALSE(data.left_locked);
}

// ── 场景：left_column_locked=true 但 unassigned 为空 → 回退到 separator_consumed_digits ──
// 对应 Apostrophe branch2 场景：右选消费后剩余 selections，锁定左侧为剩余数字段
TEST(T9PanelStateTest, LeftColumnLocked_UnassignedEmpty_FallsBackToSepDigits) {
    // 全部被消费，unassigned 为空
    T9Buffer buf("54482",
                 {SyllableOption("ji", 2), SyllableOption("hua", 3)},
                 5, 5);
    T9StateMachine sm;
    sm.EnterInput();

    std::optional<std::string> sep_digits = "482";  // 剩余 selection 数字段
    bool locked = true;

    T9PanelStateContext ctx = MakeContext(buf, sm, locked, sep_digits);
    LeftPanelStateData data;
    GetLeftPanelState(ctx, data);

    // unassigned 为空 → 回退到 separator_consumed_digits
    EXPECT_EQ("482", data.panel_digits);
    EXPECT_TRUE(data.left_locked);
}

// ── 场景：Idle 状态 → panel_digits 应为空 ──
TEST(T9PanelStateTest, IdleState_ReturnsEmptyPanelDigits) {
    T9Buffer buf;
    T9StateMachine sm;
    // 默认状态为 kIdle

    std::optional<std::string> sep_digits = std::nullopt;
    bool locked = false;

    T9PanelStateContext ctx = MakeContext(buf, sm, locked, sep_digits);
    LeftPanelStateData data;
    GetLeftPanelState(ctx, data);

    EXPECT_TRUE(data.panel_digits.empty());
    EXPECT_EQ(LeftPanelStateData::State::kIdle, data.state);
    EXPECT_FALSE(data.left_locked);
}

// ════════════════════════════════════════
// 场景：分词键分隔简拼+全拼混合输入（5143）
// 修复：左侧锁定 + 无 selections 时应使用 separator_consumed_digits
// ════════════════════════════════════════
//
// 复现步骤：
//   1. 输入 "5" → digitSeq="5", 候选 [j,k,l]
//   2. 输入分词键 "1" → separator_position=1, left_locked=true, sepDigits="5"
//   3. 输入 "4" → digitSeq="54"
//   4. 输入 "3" → digitSeq="543"
// 此时预编辑为 "j'ge"，左侧候选区本应显示 [j,k,l]（锁定段"5"），
// 但修复前 panel_digits 错误使用 unassigned()="543"，显示 [jie,lie,ji,li,j,k,l]。
//
// 修复后：selections 为空时使用 separator_consumed_digits="5"，
//         左侧候选区正确显示 [j,k,l]。
TEST(T9PanelStateTest, Separator_NoSelections_UseSeparatorConsumedDigits) {
    // 模拟输入 5143：digitSeq="543", separator_positions={1}, 无 selections
    T9Buffer buf("543");
    buf.separator_positions = {1};
    // consumed_count=0, selections=[], unassigned="543"

    T9StateMachine sm;
    sm.EnterInput();

    std::optional<std::string> sep_digits = "5";  // separator_consumed_digits
    bool locked = true;

    T9PanelStateContext ctx = MakeContext(buf, sm, locked, sep_digits);
    LeftPanelStateData data;
    GetLeftPanelState(ctx, data);

    // 验证：panel_digits 应为 "5"（锁定段），而非 unassigned="543"
    EXPECT_EQ("5", data.panel_digits)
        << "panel_digits 应为锁定段 '5'，而非完整 digit_sequence '543'";
    EXPECT_TRUE(data.left_locked);
    EXPECT_EQ(LeftPanelStateData::State::kInput, data.state);
}

// 分词键 + 已左选场景：有 selections 时应使用 unassigned
// 对应 SeparatorKey_LeftColumnLocked_UsesUnassigned 的变体：
// 输入 5143 → 左选 j → digitSeq="543", selections=[j(1)], consumed=1, unassigned="43"
TEST(T9PanelStateTest, Separator_WithSelections_UseUnassigned) {
    // 模拟 5143 后左选 "j"：consumed=1, selections=[j(1)], digitSeq="543"
    T9Buffer buf("543",
                 {SyllableOption("j", 1)},
                 1, 3);  // consumed=1 (j 消费 1 位), totalDigitsEntered=3
    buf.separator_positions = {1};

    T9StateMachine sm;
    sm.EnterInput();

    std::optional<std::string> sep_digits = "5";
    bool locked = true;

    T9PanelStateContext ctx = MakeContext(buf, sm, locked, sep_digits);
    LeftPanelStateData data;
    GetLeftPanelState(ctx, data);

    // 验证：有 selections 时使用 unassigned()="43"，而非 separator_consumed_digits
    EXPECT_EQ("43", data.panel_digits)
        << "有 selections 时应使用 unassigned()='43'";
    EXPECT_TRUE(data.left_locked);
    EXPECT_EQ(LeftPanelStateData::State::kInput, data.state);
}

// ════════════════════════════════════════
// 左侧候选区模式解析（英文九键适配，2026-08-07）
// ════════════════════════════════════════
//
// auto 判定：engine/translators 含 script_translator（拼音方案）→ kPinyin；
//            无（英文 table_translator，如 melt_eng_t9）→ kNone。
// 显式 t9/left_panel_mode: pinyin|none 覆盖 auto 判定。

TEST(T9LeftPanelModeTest, Auto_WithScriptTranslator_ReturnsPinyin) {
    EXPECT_EQ(rime::t9_panel_state::ResolveLeftPanelMode(true, ""),
              rime::t9_panel_state::LeftPanelMode::kPinyin);
    EXPECT_EQ(rime::t9_panel_state::ResolveLeftPanelMode(true, "auto"),
              rime::t9_panel_state::LeftPanelMode::kPinyin);
}

TEST(T9LeftPanelModeTest, Auto_WithoutScriptTranslator_ReturnsNone) {
    // melt_eng_t9（table_translator）→ 无左栏候选
    EXPECT_EQ(rime::t9_panel_state::ResolveLeftPanelMode(false, ""),
              rime::t9_panel_state::LeftPanelMode::kNone);
    EXPECT_EQ(rime::t9_panel_state::ResolveLeftPanelMode(false, "auto"),
              rime::t9_panel_state::LeftPanelMode::kNone);
}

TEST(T9LeftPanelModeTest, ExplicitNone_OverridesAuto) {
    // rime_frost_t9 等混合方案作者可显式关闭左栏
    EXPECT_EQ(rime::t9_panel_state::ResolveLeftPanelMode(true, "none"),
              rime::t9_panel_state::LeftPanelMode::kNone);
    EXPECT_EQ(rime::t9_panel_state::ResolveLeftPanelMode(false, "none"),
              rime::t9_panel_state::LeftPanelMode::kNone);
}

TEST(T9LeftPanelModeTest, ExplicitPinyin_OverridesAuto) {
    // 英文方案作者可显式开启拼音左栏（罕见，但保留口子）
    EXPECT_EQ(rime::t9_panel_state::ResolveLeftPanelMode(false, "pinyin"),
              rime::t9_panel_state::LeftPanelMode::kPinyin);
    EXPECT_EQ(rime::t9_panel_state::ResolveLeftPanelMode(true, "pinyin"),
              rime::t9_panel_state::LeftPanelMode::kPinyin);
}