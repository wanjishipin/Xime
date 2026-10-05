#ifndef T9_DATE_TRANSLATOR_H_
#define T9_DATE_TRANSLATOR_H_

#include <string>
#include <vector>

#ifndef T9_ALGO_ONLY_BUILD
#include <rime/translator.h>
#include <rime/translation.h>
#include <rime/candidate.h>
#include <rime/common.h>
#endif

namespace rime {

#ifndef T9_ALGO_ONLY_BUILD

// T9DateTranslation — 自定义 Translation，通过覆盖 Compare() 实现精确位置控制。
//
// 排序原理（librime MergedTranslation::Elect()）：
//   Elect() 遍历 translations_，找到第一个 Compare(next) <= 0 的翻译器。
//   如果 t9_date_translator 在 translators 列表第一位，k=0 时调用我们的 Compare()。
//
// Compare() 逻辑：
//   candidates.size() < idx-1 → 返回 1（让位，其他翻译器优先）
//   candidates.size() >= idx-1 → 返回 -1（我们优先）
//
// 效果（idx=2）：
//   第 1 位：script_translator 的 top 候选
//   第 2 位：t9_date_translator 的候选
//   第 3+位：script_translator 的其他候选
class T9DateTranslation : public FifoTranslation {
 public:
  explicit T9DateTranslation(int idx) : idx_(idx) {}

  int Compare(an<Translation> other,
              const CandidateList& candidates) override;

 private:
  int idx_;
};

// T9 日期翻译器 — 九宫格日期/时间/农历候选生成。
//
// 功能：
// 1. 系统触发词（rq/sj/xq/dt/ts/nl）→ 生成日期/时间/星期/ISO 8601/时间戳/农历候选
// 2. 中文触发词（今天/今日/昨天/昨日/明天/明日）→ 生成复合候选 "今天(1月15日)"
//
// 用法（schema 配置）：
//   t9/date_translator/date: rq
//   t9/date_translator/time: sj
//   t9/date_translator/week: xq
//   t9/date_translator/datetime: dt
//   t9/date_translator/timestamp: ts
//   t9/date_translator/lunar: nl
//   t9/date_translator/idx: 2    # 插入位置（默认 2，即第二位）
//
// 注意：t9_date_translator 必须在 engine/translators 列表的第一位，
// 否则 Compare() 不会被调用（Elect() 中 k=0 是其他翻译器调用 Compare）。
class T9DateTranslator : public Translator {
 public:
  explicit T9DateTranslator(const Ticket& ticket);
  an<Translation> Query(const string& input,
                        const Segment& segment) override;

 private:
  // 系统触发词配置（从 schema 读取）
  struct Trigger {
    string keyword;     // "rq"
    string digit_code;  // "77"
    string type;        // "date", "time", "week", "datetime", "timestamp", "lunar"
  };

  // 中文触发词配置（硬编码，拼音固定）
  struct ChineseTrigger {
    string text;              // "今天"
    string preedit;           // "j t" (拼音声母，显示在预编辑区)
    string digit_code;        // "5468426" (jin tian 全拼)
    string short_code;        // "518" (j t 简拼，含 1 分隔符)
    string short_code_delim;  // "5'8" (简拼，T9 中 1 被转成分隔符后的实际输入)
    int offset_days;          // 0=今天/今日, 1=明天/明日, -1=昨天/昨日
  };

  // 农历转换结果
  struct LunarDate {
    int year;          // 农历年
    int month;         // 农历月 (1-12)
    int day;           // 农历日 (1-29/30)
    bool is_leap;      // 是否闰月
    int gan;           // 天干索引 (0-9)
    int zhi;           // 地支索引 (0-11)
    int animal;        // 生肖索引 (0-11)
  };

  int idx_ = 2;  // 候选插入位置（默认第二位）
  bool enabled_ = true;  // 日期翻译器开关
  std::vector<Trigger> triggers_;
  std::vector<ChineseTrigger> chinese_triggers_;

  void InitTriggers();
  void InitChineseTriggers();

  // 拼音转数字码
  static string PinyinToDigitCode(const string& pinyin);
  static char LetterToDigit(char c);

  // 拼音转声母缩写（"jin tian" → "j t"）
  static string PinyinToInitials(const string& pinyin);

  // 日期格式化
  static string FormatDate(time_t t);
  static string FormatTime(time_t t);
  static string FormatWeek(time_t t);
  static string FormatDateTime(time_t t);
  static string FormatTimestamp(time_t t);
  static string FormatChineseDate(time_t t);
  static string FormatEnglishDate(time_t t);
  static string FormatCompactDate(time_t t);

  // 农历
  static LunarDate GregorianToLunar(int year, int month, int day);
  static string FormatLunarDateShort(const LunarDate& ld);
  static string FormatLunarDateLong(const LunarDate& ld);

  // 生成候选
  double GetQuality() const;  // 根据 idx_ 计算 quality
  an<Translation> GenerateDateCandidates(const Segment& segment,
                                          const string& type,
                                          const string& preedit);
  an<Translation> GenerateChineseDateCandidate(
      const Segment& segment,
      const ChineseTrigger& trigger);
  an<Translation> GenerateLunarCandidates(const Segment& segment,
                                           const string& preedit);
};

#endif  // T9_ALGO_ONLY_BUILD

}  // namespace rime

#endif  // T9_DATE_TRANSLATOR_H_