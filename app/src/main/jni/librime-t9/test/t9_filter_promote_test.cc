// T9BuildSingleCharPromotionOrder 单元测试（首音节单字提权纯函数）
//
// 覆盖场景：长输入提权、无单字原序、插入点前单字保持原位（短输入回归）、
// 插入点 0（后缀窗口用法）、多单字相对顺序、插入点越界、空输入
#include <gtest/gtest.h>

#include <vector>

#include "t9_filter.h"            // T9BuildSingleCharPromotionOrder

using rime::T9BuildSingleCharPromotionOrder;

namespace {

std::vector<size_t> Identity(size_t n) {
    std::vector<size_t> order(n);
    for (size_t i = 0; i < n; ++i) order[i] = i;
    return order;
}

// 便捷构造：marks 字符串中 'S' = 首音节单字，'.' = 其他候选
std::vector<bool> Marks(const std::string& marks) {
    std::vector<bool> is_single(marks.size());
    for (size_t i = 0; i < marks.size(); ++i) {
        is_single[i] = (marks[i] == 'S');
    }
    return is_single;
}

}  // namespace

// ── 长输入典型场景：单字在长尾，提升到插入点 ──

TEST(T9FilterPromoteTest, LiftTrailingSinglesAfterInsertionPoint) {
    // 整句(0) + 组合词(1-8) + 单字(9-11)：插入点 5 → 单字搬到 5、组合词顺延
    auto order = T9BuildSingleCharPromotionOrder(Marks(".........SSS"), 5);
    EXPECT_EQ(order, (std::vector<size_t>{0, 1, 2, 3, 4, 9, 10, 11, 5, 6, 7, 8}));
}

TEST(T9FilterPromoteTest, MultipleSinglesKeepRelativeOrder) {
    // 被提升单字之间的相对顺序保持（按原下标升序）
    auto order = T9BuildSingleCharPromotionOrder(Marks("......S.S.S"), 3);
    EXPECT_EQ(order, (std::vector<size_t>{0, 1, 2, 6, 8, 10, 3, 4, 5, 7, 9}));
}

// ── 无需提权的场景：原顺序 ──

TEST(T9FilterPromoteTest, NoSinglesKeepsIdentity) {
    auto order = T9BuildSingleCharPromotionOrder(Marks(".........."), 5);
    EXPECT_EQ(order, Identity(10));
}

TEST(T9FilterPromoteTest, EmptyInputReturnsEmpty) {
    auto order = T9BuildSingleCharPromotionOrder({}, 5);
    EXPECT_TRUE(order.empty());
}

TEST(T9FilterPromoteTest, InsertionPointBeyondListIsIdentity) {
    // 列表比插入点短（promote_after >= n）：无可提升单字，原顺序
    auto order = T9BuildSingleCharPromotionOrder(Marks("....S"), 7);
    EXPECT_EQ(order, Identity(5));
}

// ── 短输入回归保护：插入点之前的单字不降权 ──

TEST(T9FilterPromoteTest, SinglesBeforeInsertionPointKeepPlace) {
    // 短输入（如 "968"）单字桶即最长桶、天然排前：不得被提权逻辑降位
    auto order = T9BuildSingleCharPromotionOrder(Marks("S....S"), 5);
    EXPECT_EQ(order, Identity(6));
}

TEST(T9FilterPromoteTest, LeadingSingleStaysWhileTrailingLifted) {
    // 首位单字保持原位，尾随单字正常提升
    auto order = T9BuildSingleCharPromotionOrder(Marks("S.....SS"), 3);
    EXPECT_EQ(order, (std::vector<size_t>{0, 1, 2, 6, 7, 3, 4, 5}));
}

// ── 插入点 0：后缀窗口用法（窗口内单字全部提到窗口头部）──

TEST(T9FilterPromoteTest, ZeroInsertionPointLiftsAllSinglesToFront) {
    auto order = T9BuildSingleCharPromotionOrder(Marks(".....SS"), 0);
    EXPECT_EQ(order, (std::vector<size_t>{5, 6, 0, 1, 2, 3, 4}));
}

TEST(T9FilterPromoteTest, ZeroInsertionPointWithInterleavedSingles) {
    // 理论上单字不会散布中部（桶按长度降序），防御性验证规则一致性
    auto order = T9BuildSingleCharPromotionOrder(Marks(".S.S."), 0);
    EXPECT_EQ(order, (std::vector<size_t>{1, 3, 0, 2, 4}));
}

// ── 提升数量上限（max_lift）──

TEST(T9FilterPromoteTest, MaxLiftCapsLiftedSingles) {
    // 12 项：9 个多音节词 + 3 个单字；上限 2 → 只提下标 9、10，下标 11 留原位
    auto order = T9BuildSingleCharPromotionOrder(Marks(".........SSS"), 5, 2);
    EXPECT_EQ(order, (std::vector<size_t>{0, 1, 2, 3, 4, 9, 10, 5, 6, 7, 8, 11}));
}

TEST(T9FilterPromoteTest, MaxLiftZeroMeansUnlimited) {
    auto capped = T9BuildSingleCharPromotionOrder(Marks(".........SSS"), 5, 0);
    auto unlimited = T9BuildSingleCharPromotionOrder(Marks(".........SSS"), 5);
    EXPECT_EQ(capped, unlimited);
    EXPECT_EQ(capped, (std::vector<size_t>{0, 1, 2, 3, 4, 9, 10, 11, 5, 6, 7, 8}));
}

TEST(T9FilterPromoteTest, MaxLiftBeyondSingleCountLiftsAll) {
    auto order = T9BuildSingleCharPromotionOrder(Marks(".........SSS"), 5, 10);
    EXPECT_EQ(order, (std::vector<size_t>{0, 1, 2, 3, 4, 9, 10, 11, 5, 6, 7, 8}));
}

TEST(T9FilterPromoteTest, MaxLiftWithZeroInsertionPoint) {
    // 后缀窗口用法 + 上限 1：只提窗口首个单字
    auto order = T9BuildSingleCharPromotionOrder(Marks(".....SSS"), 0, 1);
    EXPECT_EQ(order, (std::vector<size_t>{5, 0, 1, 2, 3, 4, 6, 7}));
}
