// T9DigitUserDictCore 单元测试
// 覆盖：BuildLookupKey、PinyinToFullCode、序列化 roundtrip、
// Memorize/Forget 索引维护、调频、召回排序、InsertFromStorage/Reset。
#include <gtest/gtest.h>

#include "t9_digit_userdict_core.h"

#include <cmath>
#include <string>
#include <vector>

using rime::T9DigitUserDictCore;

namespace {

// 便捷：取 Lookup 结果文本列表
std::vector<std::string> LookupTexts(T9DigitUserDictCore* core,
                                     const std::string& key) {
  std::vector<std::string> texts;
  for (const auto& e : core->Lookup(key, 0))
    texts.push_back(e.text);
  return texts;
}

std::string GetValueOf(const std::vector<T9DigitUserDictCore::StorageOp>& ops,
                       const std::string& key) {
  for (const auto& op : ops) {
    if (op.kind == T9DigitUserDictCore::StorageOp::kUpdate &&
        op.key == key)
      return op.value;
  }
  return {};
}

bool HasEraseOf(const std::vector<T9DigitUserDictCore::StorageOp>& ops,
                const std::string& key) {
  for (const auto& op : ops) {
    if (op.kind == T9DigitUserDictCore::StorageOp::kErase &&
        op.key == key)
      return true;
  }
  return false;
}

// 找同 key 下某 text 的条目
bool FindEntry(const std::vector<T9DigitUserDictCore::Entry>& entries,
               const std::string& text,
               T9DigitUserDictCore::Entry* out) {
  for (const auto& e : entries) {
    if (e.text == text) {
      *out = e;
      return true;
    }
  }
  return false;
}

}  // namespace

// ── BuildLookupKey ──

TEST(T9DigitUserDictCoreTest, BuildLookupKey_PureDigits) {
  EXPECT_EQ(T9DigitUserDictCore::BuildLookupKey("54482"), "54482");
  EXPECT_EQ(T9DigitUserDictCore::BuildLookupKey(""), "");
}

TEST(T9DigitUserDictCoreTest, BuildLookupKey_WithLeftSelect) {
  EXPECT_EQ(T9DigitUserDictCore::BuildLookupKey("j'4482"), "4482:j");
  EXPECT_EQ(T9DigitUserDictCore::BuildLookupKey("ji'4482"), "4482:ji");
  EXPECT_EQ(T9DigitUserDictCore::BuildLookupKey("JI'4482"), "4482:ji");
}

TEST(T9DigitUserDictCoreTest, BuildLookupKey_NoDigits) {
  EXPECT_EQ(T9DigitUserDictCore::BuildLookupKey("km"), "km");
  EXPECT_EQ(T9DigitUserDictCore::BuildLookupKey("k'm'"), "km");
  EXPECT_EQ(T9DigitUserDictCore::BuildLookupKey("'"), "");
}

// ── PinyinToFullCode ──

TEST(T9DigitUserDictCoreTest, PinyinToFullCode_MultiSyllable) {
  EXPECT_EQ(T9DigitUserDictCore::PinyinToFullCode("ji hu biao"),
            "54482426");
  EXPECT_EQ(T9DigitUserDictCore::PinyinToFullCode("ji hua"), "54482");
}

TEST(T9DigitUserDictCoreTest, PinyinToFullCode_Invalid) {
  EXPECT_EQ(T9DigitUserDictCore::PinyinToFullCode(""), "");
}

// ── 序列化 roundtrip ──

TEST(T9DigitUserDictCoreTest, KeyValueRoundtrip) {
  T9DigitUserDictCore::Entry e;
  e.text = "几户表";
  e.pinyin = "ji hu biao";
  e.dee = 3.14159265358979;
  e.commits = 7;
  e.last_tick = 123456789;
  e.full_code = "54482426";
  e.input_digits = "54482";

  std::string key =
      T9DigitUserDictCore::BuildKey(e.input_digits, e.full_code, e.text);
  EXPECT_EQ(key, "54482\t54482426\t几户表");

  std::string input_digits, full_code, text;
  ASSERT_TRUE(T9DigitUserDictCore::ParseKey(key, &input_digits, &full_code,
                                            &text));
  EXPECT_EQ(input_digits, "54482");
  EXPECT_EQ(full_code, "54482426");
  EXPECT_EQ(text, "几户表");

  // 复合键（含左选）也可解析
  std::string compound_key =
      T9DigitUserDictCore::BuildKey("54482:ji", "54482426", "几户表");
  std::string d2, c2, t2;
  ASSERT_TRUE(T9DigitUserDictCore::ParseKey(compound_key, &d2, &c2, &t2));
  EXPECT_EQ(d2, "54482:ji");

  std::string value = T9DigitUserDictCore::PackValue(e);
  T9DigitUserDictCore::Entry unpacked;
  ASSERT_TRUE(T9DigitUserDictCore::UnpackValue(value, &unpacked));
  EXPECT_EQ(unpacked.pinyin, "ji hu biao");
  EXPECT_EQ(unpacked.commits, 7);
  EXPECT_EQ(unpacked.last_tick, 123456789u);
  EXPECT_DOUBLE_EQ(unpacked.dee, 3.14159265358979);
}

TEST(T9DigitUserDictCoreTest, UnpackValue_Invalid) {
  T9DigitUserDictCore::Entry e;
  EXPECT_FALSE(T9DigitUserDictCore::UnpackValue("garbage", &e));
  EXPECT_FALSE(T9DigitUserDictCore::UnpackValue("p=x c=bad d=1.0 t=1", &e));
}

// ── Memorize / Lookup ──

TEST(T9DigitUserDictCoreTest, Memorize_NewWord_LookupByDee) {
  T9DigitUserDictCore core;
  auto ops = core.Memorize("54482", "几户表", "ji hu biao");
  ASSERT_EQ(ops.size(), 1u);
  EXPECT_EQ(ops[0].kind, T9DigitUserDictCore::StorageOp::kUpdate);
  EXPECT_EQ(ops[0].key, "54482\t54482426\t几户表");
  EXPECT_FALSE(ops[0].value.empty());

  auto entries = core.Lookup("54482", 0);
  ASSERT_EQ(entries.size(), 1u);
  EXPECT_EQ(entries[0].text, "几户表");
  EXPECT_EQ(entries[0].commits, 1);
  EXPECT_DOUBLE_EQ(entries[0].dee, 1.0);

  auto again = core.Lookup("54482", 0);
  ASSERT_EQ(again.size(), 1u);
  EXPECT_EQ(again[0].text, "几户表");
}

TEST(T9DigitUserDictCoreTest, Lookup_EmptyForUnmatchedKey) {
  T9DigitUserDictCore core;
  core.Memorize("54482", "几户表", "ji hu biao");

  // 无关序列的 Lookup：不命中
  EXPECT_TRUE(core.Lookup("5432", 0).empty());
  EXPECT_TRUE(core.Lookup("abc555", 0).empty());

  // 命中原序列
  auto entries = core.Lookup("54482", 0);
  ASSERT_EQ(entries.size(), 1u);
  EXPECT_EQ(entries[0].text, "几户表");
}

TEST(T9DigitUserDictCoreTest, Memorize_Repeat_UpdatesCommitsAndDee) {
  T9DigitUserDictCore core;
  core.Memorize("54482", "几户表", "ji hu biao");
  auto ops = core.Memorize("54482", "几户表", "ji hu biao");
  // 同 key 更新：仅 1 条 kUpdate（无旧 key erase）
  ASSERT_EQ(ops.size(), 1u);
  EXPECT_EQ(ops[0].kind, T9DigitUserDictCore::StorageOp::kUpdate);

  auto entries = core.Lookup("54482", 0);
  ASSERT_EQ(entries.size(), 1u);
  EXPECT_EQ(entries[0].commits, 2);
  // dee = 1 + 1*exp((t1-t2)/200) = 1 + exp(-0.005) ≈ 1.99501
  EXPECT_NEAR(entries[0].dee, 1.0 + std::exp(-0.005), 1e-9);
}

TEST(T9DigitUserDictCoreTest, Memorize_InputDigitsMigration) {
  T9DigitUserDictCore core;
  core.Memorize("54482", "几户表", "ji hu biao");
  // 之后用完整码上屏同一词 → input_digits 从 54482 迁移到 54482426
  auto ops = core.Memorize("54482426", "几户表", "ji hu biao");
  ASSERT_EQ(ops.size(), 2u);
  EXPECT_TRUE(HasEraseOf(ops, "54482\t54482426\t几户表"));
  EXPECT_FALSE(GetValueOf(ops, "54482426\t54482426\t几户表").empty());

  // 旧键不可召回，新键可召回
  EXPECT_TRUE(core.Lookup("54482", 0).empty());
  EXPECT_EQ(LookupTexts(&core, "54482426").size(), 1u);
}

TEST(T9DigitUserDictCoreTest, Lookup_SortByDeeThenCommits) {
  T9DigitUserDictCore core;
  // 几户表：使用两次（dee≈1.995, commits=2）
  core.Memorize("54482", "几户表", "ji hu biao");
  core.Memorize("54482", "几户表", "ji hu biao");
  // 几户啊：使用一次（dee=1, commits=1）
  core.Memorize("54482", "几户啊", "ji hu a");

  auto texts = LookupTexts(&core, "54482");
  ASSERT_EQ(texts.size(), 2u);
  EXPECT_EQ(texts[0], "几户表");  // dee 更高者在前
  EXPECT_EQ(texts[1], "几户啊");
}

// ── 左选复合键还原前缀筛选（场景 B 词经左选路径召回）──

TEST(T9DigitUserDictCoreTest, Lookup_LeftSelectRestoresFullDigits) {
  T9DigitUserDictCore core;
  core.Memorize("54482", "几户啊", "ji hu a");
  core.Memorize("54482", "梨花", "li hua");
  core.Memorize("482:ji", "里股草", "li gu cao");

  // 左选 ji：精确匹配 "482:ji"（里股草，rank=0）+ 还原 "54482" 命中场景 B 词
  auto texts = LookupTexts(&core, "482:ji");
  ASSERT_EQ(texts.size(), 2u);
  EXPECT_EQ(texts[0], "里股草");
  EXPECT_EQ(texts[1], "几户啊");

  // 左选 j（声母简拼，长度=1）：不做完整首音节还原，无精确匹配 → 命中 0
  auto texts2 = LookupTexts(&core, "4482:j");
  EXPECT_EQ(texts2.size(), 0u);

  core.Memorize("4482:j", "三户地", "san hu di");
  auto texts2b = LookupTexts(&core, "4482:j");
  ASSERT_EQ(texts2b.size(), 1u);
  EXPECT_EQ(texts2b[0], "三户地");

  // 左选 li：还原 "54482"，首音节过滤：梨花(li)保留，几户啊(ji)过滤
  auto texts3 = LookupTexts(&core, "482:li");
  ASSERT_EQ(texts3.size(), 1u);
  EXPECT_EQ(texts3[0], "梨花");
}

TEST(T9DigitUserDictCoreTest, Lookup_LeftSelectNoRestoreForPureDigits) {
  T9DigitUserDictCore core;
  core.Memorize("54482", "几户啊", "ji hu a");

  // 纯数字 key（无冒号）：不触发左选还原，精确匹配
  auto texts = LookupTexts(&core, "54482");
  ASSERT_EQ(texts.size(), 1u);
  EXPECT_EQ(texts[0], "几户啊");

  // 纯左选 key（场景 D，无冒号）：精确匹配场景 D 词，不触发还原
  core.Memorize("km", "昆明", "kun ming");
  auto texts2 = LookupTexts(&core, "km");
  ASSERT_EQ(texts2.size(), 1u);
  EXPECT_EQ(texts2[0], "昆明");
}

// ── Forget ──

TEST(T9DigitUserDictCoreTest, Forget_DecrementAndRemove) {
  T9DigitUserDictCore core;
  core.Memorize("54482", "几户表", "ji hu biao");
  core.Memorize("54482", "几户表", "ji hu biao");  // commits=2

  // 撤销一次：未归零，kUpdate + dee 下降
  auto ops = core.Forget("54482", "几户表");
  ASSERT_EQ(ops.size(), 1u);
  EXPECT_EQ(ops[0].kind, T9DigitUserDictCore::StorageOp::kUpdate);
  auto entries = core.Lookup("54482", 0);
  ASSERT_EQ(entries.size(), 1u);
  EXPECT_EQ(entries[0].commits, 1);
  // dee = 0 + dee_old * exp((t_old - t_new)/200)：
  //   dee_old（2次使用后）≈ 1.995，t_old=2 → t_new=3
  double dee_after_two =
      1.0 + std::exp(-0.005);  // 两次 Memorize 后的 dee
  EXPECT_NEAR(entries[0].dee, dee_after_two * std::exp(-0.005), 1e-9);

  // 再撤销一次：归零，kErase 删除
  auto ops2 = core.Forget("54482", "几户表");
  ASSERT_EQ(ops2.size(), 1u);
  EXPECT_EQ(ops2[0].kind, T9DigitUserDictCore::StorageOp::kErase);
  EXPECT_TRUE(core.Lookup("54482", 0).empty());
}

TEST(T9DigitUserDictCoreTest, Forget_UnknownKeyNoop) {
  T9DigitUserDictCore core;
  EXPECT_TRUE(core.Forget("99999", "不存在").empty());
}

// ── FormulaP quality 计算 ──

TEST(T9DigitUserDictCoreTest, FormulaP_MatchesRIMEFormula) {
  // 首次造词 commits=1, dee=1.0, tick=1, present_tick=2
  double dee = T9DigitUserDictCore::FormulaD(1.0, 1.0, 0.0, 0.0);
  EXPECT_NEAR(dee, 1.0, 0.001);
  double adj_dee = T9DigitUserDictCore::FormulaD(0.0, 2.0, dee, 1.0);
  double u = 1.0 / 2.0;
  double p = T9DigitUserDictCore::FormulaP(0.0, u, 2.0, adj_dee);
  double quality = p + 1.2 + 1.0;
  EXPECT_GT(quality, 2.19);
  EXPECT_LT(quality, 2.21);

  // 点击 10 次 commits=10, dee≈9.78, tick=10, present_tick=11
  dee = 0.0;
  for (int i = 1; i <= 10; ++i) {
    dee = T9DigitUserDictCore::FormulaD(1.0, (double)i, dee, (double)(i - 1));
  }
  adj_dee = T9DigitUserDictCore::FormulaD(0.0, 11.0, dee, 10.0);
  u = 10.0 / 11.0;
  p = T9DigitUserDictCore::FormulaP(0.0, u, 11.0, adj_dee);
  quality = p + 1.2 + 1.0;
  EXPECT_GT(quality, 2.21);
  EXPECT_LT(quality, 2.23);
}

TEST(T9DigitUserDictCoreTest, FormulaP_QualityScaleAlignment) {
  // T9 数字词典词与 RIME 候选在同一 quality 尺度内竞争：
  // 点击次数越多 quality 越高，两者可互相超越。
  auto compute_quality = [](int commits, int tick) {
    double dee = 0.0;
    for (int i = 1; i <= commits; ++i) {
      dee = T9DigitUserDictCore::FormulaD(1.0, (double)i, dee, (double)(i - 1));
    }
    double present = (double)tick + 1;
    double adj = T9DigitUserDictCore::FormulaD(0.0, present, dee, (double)tick);
    double u = (double)commits / present;
    double p = T9DigitUserDictCore::FormulaP(0.0, u, present, adj);
    return p + 1.2 + 1.0;
  };

  // 几户表 commits=1 < 激化 commits=2 → 激化 quality 更高
  double q_jihubiao_1 = compute_quality(1, 1);
  double q_jihua_2 = compute_quality(2, 2);
  EXPECT_GT(q_jihua_2, q_jihubiao_1);

  // 几户表 commits=3 > 激化 commits=2 → 几户表 quality 更高
  double q_jihubiao_3 = compute_quality(3, 3);
  EXPECT_GT(q_jihubiao_3, q_jihua_2);
}

// ── InsertFromStorage / Reset（持久化加载路径） ──

TEST(T9DigitUserDictCoreTest, InsertFromStorage_RebuildIndex) {
  T9DigitUserDictCore core;
  // 模拟 LevelDb 里已存在的两条记录
  T9DigitUserDictCore::Entry e1;
  e1.text = "几户表";
  e1.pinyin = "ji hu biao";
  e1.dee = 1.995;
  e1.commits = 2;
  e1.last_tick = 5;
  e1.full_code = "54482426";
  e1.input_digits = "54482";
  core.InsertFromStorage(
      T9DigitUserDictCore::BuildKey(e1.input_digits, e1.full_code, e1.text),
      T9DigitUserDictCore::PackValue(e1));

  T9DigitUserDictCore::Entry e2;
  e2.text = "几户啊";
  e2.pinyin = "ji hu a";
  e2.dee = 1.0;
  e2.commits = 1;
  e2.last_tick = 3;
  e2.full_code = "544822";  // ji hu a = 54 48 2
  e2.input_digits = "54482";
  core.InsertFromStorage(
      T9DigitUserDictCore::BuildKey(e2.input_digits, e2.full_code, e2.text),
      T9DigitUserDictCore::PackValue(e2));

  // 可召回，且按 dee 降序
  auto texts = LookupTexts(&core, "54482");
  ASSERT_EQ(texts.size(), 2u);
  EXPECT_EQ(texts[0], "几户表");
  EXPECT_EQ(texts[1], "几户啊");

  // Reset 后清空
  core.Reset();
  EXPECT_TRUE(core.Lookup("54482", 0).empty());
  EXPECT_EQ(core.tick(), 0u);
}

TEST(T9DigitUserDictCoreTest, InsertFromStorage_IgnoresBadValue) {
  T9DigitUserDictCore core;
  core.InsertFromStorage("54482\t54482426\t几户表", "not-a-value");
  EXPECT_TRUE(core.Lookup("54482", 0).empty());
}