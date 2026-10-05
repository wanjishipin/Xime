#include "t9_date_translator.h"

#ifndef T9_ALGO_ONLY_BUILD

#include <algorithm>
#include <cctype>
#include <cmath>
#include <cstring>
#include <ctime>
#include <string>
#include <vector>

#include <rime/config.h>
#include <rime/context.h>
#include <rime/engine.h>
#include <rime/schema.h>

using namespace rime;

// ── 农历数据表 ──
// 来源于 lunar.lua，7 字符 hex 编码
// 索引 0 对应 1899 年（1900 年春节前计算用）
// 索引 1 对应 1900 年，依此类推至索引 201 对应 2100 年
static const char* kLunarData[] = {
    "AB500D2", "4BD0883", "4AE00DB", "A5700D0", "54D0581",
    "D2600D8", "D9500CC", "655147D", "56A00D5", "9AD00CA",
    "55D027A", "4AE00D2", "A5B0682", "A4D00DA", "D2500CE",
    "D25157E", "B5500D6", "56A00CC", "ADA027B", "95B00D3",
    "49717C9", "49B00DC", "A4B00D0", "B4B0580", "6A500D8",
    "6D400CD", "AB5147C", "2B600D5", "95700CA", "52F027B",
    "49700D2", "6560682", "D4A00D9", "EA500CE", "6A9157E",
    "5AD00D6", "2B600CC", "86E137C", "92E00D3", "C8D1783",
    "C9500DB", "D4A00D0", "D8A167F", "B5500D7", "56A00CD",
    "A5B147D", "25D00D5", "92D00CA", "D2B027A", "A9500D2",
    "B550781", "6CA00D9", "B5500CE", "535157F", "4DA00D6",
    "A5B00CB", "457037C", "52B00D4", "A9A0883", "E9500DA",
    "6AA00D0", "AEA0680", "AB500D7", "4B600CD", "AAE047D",
    "A5700D5", "52600CA", "F260379", "D9500D1", "5B50782",
    "56A00D9", "96D00CE", "4DD057F", "4AD00D7", "A4D00CB",
    "D4D047B", "D2500D3", "D550883", "B5400DA", "B6A00CF",
    "95A1680", "95B00D8", "49B00CD", "A97047D", "A4B00D5",
    "B270ACA", "6A500DC", "6D400D1", "AF40681", "AB600D9",
    "93700CE", "4AF057F", "49700D7", "64B00CC", "74A037B",
    "EA500D2", "6B50883", "5AC00DB", "AB600CF", "96D0580",
    "92E00D8", "C9600CD", "D95047C", "D4A00D4", "DA500C9",
    "755027A", "56A00D1", "ABB0781", "25D00DA", "92D00CF",
    "CAB057E", "A9500D6", "B4A00CB", "BAA047B", "AD500D2",
    "55D0983", "4BA00DB", "A5B00D0", "5171680", "52B00D8",
    "A9300CD", "795047D", "6AA00D4", "AD500C9", "5B5027A",
    "4B600D2", "96E0681", "A4E00D9", "D2600CE", "EA6057E",
    "D5300D5", "5AA00CB", "76A037B", "96D00D3", "4AB0B83",
    "4AD00DB", "A4D00D0", "D0B1680", "D2500D7", "D5200CC",
    "DD4057C", "B5A00D4", "56D00C9", "55B027A", "49B00D2",
    "A570782", "A4B00D9", "AA500CE", "B25157E", "6D200D6",
    "ADA00CA", "4B6137B", "93700D3", "49F08C9", "49700DB",
    "64B00D0", "68A1680", "EA500D7", "6AA00CC", "A6C147C",
    "AAE00D4", "92E00CA", "D2E0379", "C9600D1", "D550781",
    "D4A00D9", "DA400CD", "5D5057E", "56A00D6", "A6C00CB",
    "55D047B", "52D00D3", "A9B0883", "A9500DB", "B4A00CF",
    "B6A067F", "AD500D7", "55A00CD", "ABA047C", "A5A00D4",
    "52B00CA", "B27037A", "69300D1", "7330781", "6AA00D9",
    "AD500CE", "4B5157E", "4B600D6", "A5700CB", "54E047C",
    "D1600D2", "E960882", "D5200DA", "DAA00CF", "6AA167F",
    "56D00D7", "4AE00CD", "A9D047D", "A2D00D4", "D1500C9",
    "F250279", "D5200D1",
};

static constexpr size_t kLunarDataBase = 1899;

// ── 农历名称表 ──

static const char* kTianGan[] = {
    "甲", "乙", "丙", "丁", "戊", "己", "庚", "辛", "壬", "癸",
};

static const char* kDiZhi[] = {
    "子", "丑", "寅", "卯", "辰", "巳",
    "午", "未", "申", "酉", "戌", "亥",
};

static const char* kShuXiang[] = {
    "鼠", "牛", "虎", "兔", "龙", "蛇",
    "马", "羊", "猴", "鸡", "狗", "猪",
};

static const char* kLunarDayName[] = {
    "初一", "初二", "初三", "初四", "初五", "初六", "初七", "初八", "初九", "初十",
    "十一", "十二", "十三", "十四", "十五", "十六", "十七", "十八", "十九", "二十",
    "廿一", "廿二", "廿三", "廿四", "廿五", "廿六", "廿七", "廿八", "廿九", "三十",
};

static const char* kLunarMonName[] = {
    "正月", "二月", "三月", "四月", "五月", "六月",
    "七月", "八月", "九月", "十月", "冬月", "腊月",
};

static const char* kChineseDigits[] = {
    "〇", "一", "二", "三", "四", "五", "六", "七", "八", "九",
};

// ── 农历辅助函数 ──

// 将 hex 字符串转为指定位数的二进制字符串，去掉前导零
static string HexToBin(const string& hex, int bits) {
    unsigned int val = std::stoul(hex, nullptr, 16);
    string bin;
    for (int i = bits - 1; i >= 0; i--) {
        bin += (val & (1 << i)) ? '1' : '0';
    }
    size_t pos = bin.find_first_not_of('0');
    if (pos == string::npos) return "0";
    return bin.substr(pos);
}

// 将 hex 字符串转为十进制整数
static int HexToDec(const string& hex) {
    return static_cast<int>(std::stoul(hex, nullptr, 16));
}

// 解析农历数据（7 字符 hex 编码）
// 返回 {month_info, leap_info, leap, newyear}
// month_info: 12-bit 二进制字符串，各月大小（0=29, 1=30）
// leap_info: 闰月信息
// leap: 闰月大小
// newyear: 新年日期（月日，如 "0210" 表示 2月10日）
static vector<string> AnalyzeLunarData(const string& data) {
    vector<string> result;
    // 前 3 字符：月大小信息
    string rtn1 = HexToBin(data.substr(0, 3), 12);
    result.push_back(rtn1);
    // 第 4 字符：闰月信息
    result.push_back(string(1, data[3]));
    // 第 5 字符：闰月大小
    result.push_back(std::to_string(HexToDec(data.substr(4, 1))));
    // 后 2 字符：新年偏移
    string rtn4 = std::to_string(HexToDec(data.substr(5, 2)));
    if (rtn4.length() == 3) {
        rtn4 = "0" + rtn4;
    }
    result.push_back(rtn4);
    return result;
}

// 判断闰年，返回天数（365 或 366）
static int IsLeapYear(int year) {
    if ((year % 4 == 0 && year % 100 != 0) || (year % 400 == 0))
        return 366;
    return 365;
}

// 返回当年过了多少天
static int LeaveDate(const string& yyyymmdd) {
    int year = std::stoi(yyyymmdd.substr(0, 4));
    int month = std::stoi(yyyymmdd.substr(4, 2));
    int day = std::stoi(yyyymmdd.substr(6, 2));
    int days_in_month[] = {31, 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31};
    if (IsLeapYear(year) == 366)
        days_in_month[1] = 29;
    int total = 0;
    for (int i = 0; i < month - 1; i++) {
        total += days_in_month[i];
    }
    total += day;
    return total;
}

// 计算两个日期之间的天数差（date2 - date1），date2 >= date1
// 日期格式：YYYYMMDD
static int DiffDate(int date1, int date2) {
    if (date2 < date1) return -1;
    if (date2 == date1) return 0;
    string s1 = std::to_string(date1);
    string s2 = std::to_string(date2);
    int y1 = std::stoi(s1.substr(0, 4));
    int y2 = std::stoi(s2.substr(0, 4));
    int n = y2 - y1;
    int total = 0;
    if (n > 1) {
        for (int i = 1; i < n; i++) {
            total += IsLeapYear(y1 + i);
        }
        total += IsLeapYear(y1) - LeaveDate(s1) + LeaveDate(s2);
    } else if (n == 1) {
        total = IsLeapYear(y1) - LeaveDate(s1) + LeaveDate(s2);
    } else {
        total = LeaveDate(s2) - LeaveDate(s1);
    }
    return total;
}

// 中文数字转阿拉伯数字（"一" → 1），用于辅助函数
static int ChineseDigitToInt(const string& ch) {
    for (int i = 0; i < 10; i++) {
        if (ch == kChineseDigits[i]) return i;
    }
    return -1;
}

// 数字转中文
static string IntToChinese(int n) {
    if (n < 0 || n > 9) return "";
    return kChineseDigits[n];
}

// 将 4 位数字年份转为中文（如 2023 → "二〇二三"）
static string YearToChinese(int year) {
    string s = std::to_string(year);
    string result;
    for (size_t i = 0; i < s.size(); i++) {
        int d = s[i] - '0';
        result += kChineseDigits[d];
    }
    return result;
}

// ── 公历转农历 ──
// 支持 1900-2100 年
// 参考 lunar.lua 的 Date2LunarDate 实现
T9DateTranslator::LunarDate T9DateTranslator::GregorianToLunar(
    int year, int month, int day) {
    LunarDate ld = {};
    if (year > 2100 || year < 1899 || month < 1 || month > 12 ||
        day < 1 || day > 31) {
        return ld;
    }

    // 构建公历日期字符串 YYYYMMDD
    char buf[16];
    snprintf(buf, sizeof(buf), "%04d%02d%02d", year, month, day);
    string gregorian = buf;

    // 获取农历数据
    int pos = year - 1900 + 2;  // Lua 1-indexed 位置
    // C++ 数组索引：kLunarData[0] = 1899, kLunarData[1] = 1900, ...
    // Lua 中 wNongliData[pos] 对应 C++ 中 kLunarData[pos - 1]
    // 但 pos 最小为 2（Year=1900），所以 wNongliData[2] → kLunarData[1]
    // 所以索引 = pos - 1
    int idx1 = pos - 1;      // 对应 Lua wNongliData[pos]
    int idx0 = idx1 - 1;     // 对应 Lua wNongliData[pos - 1]

    string data1 = kLunarData[idx1];
    vector<string> tb1 = AnalyzeLunarData(data1);
    string month_info = tb1[0];
    string leap_info = tb1[1];
    int leap = std::stoi(tb1[2]);
    string newyear = tb1[3];

    string date1 = std::to_string(year) + newyear;
    string date2 = gregorian;
    int date1_int = std::stoi(date1);
    int date2_int = std::stoi(date2);
    int date3 = DiffDate(date1_int, date2_int);

    int lunar_year = year;
    if (date3 < 0) {
        // 在农历新年之前，使用上一年的数据
        data1 = kLunarData[idx0];
        tb1 = AnalyzeLunarData(data1);
        month_info = tb1[0];
        leap_info = tb1[1];
        leap = std::stoi(tb1[2]);
        newyear = tb1[3];
        lunar_year = year - 1;
        date1 = std::to_string(lunar_year) + newyear;
        date1_int = std::stoi(date1);
        date3 = DiffDate(date1_int, date2_int);
    }

    date3 = date3 + 1;

    // 构建月信息
    string this_month_info;
    if (leap > 0) {
        // 有闰月：前 leap 个月 + 闰月信息 + 剩余月
        this_month_info = month_info.substr(0, leap) + leap_info +
                          month_info.substr(leap);
    } else {
        this_month_info = month_info;
    }

    int lmonth = 0, lday = 0;
    bool isleap = false;
    for (int i = 0; i < 13 && i < static_cast<int>(this_month_info.size()); i++) {
        int this_month = this_month_info[i] - '0';
        int this_days = 29 + this_month;
        if (date3 > this_days) {
            date3 -= this_days;
        } else {
            if (leap > 0) {
                if (leap >= i + 1) {
                    lmonth = i + 1;
                    isleap = false;
                } else {
                    lmonth = i;  // i 从 0 开始，但 lmonth 从 1 开始
                    if (i - leap == 0) {
                        isleap = true;
                    } else {
                        isleap = false;
                    }
                }
            } else {
                lmonth = i + 1;
                isleap = false;
            }
            lday = date3;
            break;
        }
    }

    ld.year = lunar_year;
    ld.month = lmonth;
    ld.day = lday;
    ld.is_leap = isleap;
    ld.gan = (lunar_year - 4) % 10;
    ld.zhi = (lunar_year - 4) % 12;
    ld.animal = (lunar_year - 4) % 12;

    return ld;
}

// ── 农历格式化 ──

string T9DateTranslator::FormatLunarDateShort(const LunarDate& ld) {
    // 格式：二〇二三年冬月二十
    string year_str = YearToChinese(ld.year) + "年";
    string month_str;
    if (ld.is_leap) {
        month_str = "闰";
    }
    if (ld.month >= 1 && ld.month <= 12) {
        month_str += kLunarMonName[ld.month - 1];
    }
    string day_str;
    if (ld.day >= 1 && ld.day <= 30) {
        day_str = kLunarDayName[ld.day - 1];
    }
    return year_str + month_str + day_str;
}

string T9DateTranslator::FormatLunarDateLong(const LunarDate& ld) {
    // 格式：癸卯年（兔）冬月二十
    string gan_str = kTianGan[ld.gan];
    string zhi_str = kDiZhi[ld.zhi];
    string animal_str = kShuXiang[ld.animal];
    string month_str;
    if (ld.is_leap) {
        month_str = "闰";
    }
    if (ld.month >= 1 && ld.month <= 12) {
        month_str += kLunarMonName[ld.month - 1];
    }
    string day_str;
    if (ld.day >= 1 && ld.day <= 30) {
        day_str = kLunarDayName[ld.day - 1];
    }
    return gan_str + zhi_str + "年（" + animal_str + "）" + month_str + day_str;
}

// ── 字母到九宫格数字键映射 ──
// 2:abc  3:def  4:ghi  5:jkl  6:mno  7:pqrs  8:tuv  9:wxyz
char T9DateTranslator::LetterToDigit(char c) {
  static const char kMap[] = {
      '2', '2', '2', '3', '3', '3',
      '4', '4', '4', '5', '5', '5',
      '6', '6', '6', '7', '7', '7', '7',
      '8', '8', '8', '9', '9', '9', '9',
  };
  char lower = std::tolower(static_cast<unsigned char>(c));
  if (lower >= 'a' && lower <= 'z') {
    return kMap[static_cast<size_t>(lower - 'a')];
  }
  return c;
}

string T9DateTranslator::PinyinToDigitCode(const string& pinyin) {
  string code;
  for (char c : pinyin) {
    if (c == ' ' || c == '\'') {
      continue;
    }
    code += LetterToDigit(c);
  }
  return code;
}

string T9DateTranslator::PinyinToInitials(const string& pinyin) {
  string initials;
  bool start_of_syllable = true;
  for (size_t i = 0; i < pinyin.size(); ++i) {
    char c = pinyin[i];
    if (c == ' ') {
      start_of_syllable = true;
      initials += ' ';
    } else if (start_of_syllable && c >= 'a' && c <= 'z') {
      initials += c;
      start_of_syllable = false;
    }
  }
  return initials;
}

// ── 构造与初始化 ──

T9DateTranslator::T9DateTranslator(const Ticket& ticket)
    : Translator(ticket) {
  InitTriggers();
  if (enabled_) {
    InitChineseTriggers();
  }
}

void T9DateTranslator::InitTriggers() {
  auto* schema = engine_->schema();
  auto* config = schema ? schema->config() : nullptr;

  // 读取开关（默认开启）
  enabled_ = true;
  if (config) {
    config->GetBool("t9/enable_date_translator", &enabled_);
  }
  if (!enabled_) {
    return;
  }

  // 读取 idx 参数（候选插入位置，默认 2）
  idx_ = 2;
  if (config) {
    int idx_val = 0;
    if (config->GetInt("t9/date_translator/idx", &idx_val) && idx_val > 0) {
      idx_ = idx_val;
    }
  }

  // 读取日期翻译器触发词配置
  struct DefaultTrigger {
    const char* type;
    const char* default_keyword;
    const char* config_path;
  };

  static const DefaultTrigger kDefaults[] = {
      {"date",      "rq",   "t9/date_translator/date"},
      {"time",      "sj",   "t9/date_translator/time"},
      {"week",      "xq",   "t9/date_translator/week"},
      {"datetime",  "dt",   "t9/date_translator/datetime"},
      {"timestamp", "ts",   "t9/date_translator/timestamp"},
      {"lunar",     "nl",   "t9/date_translator/lunar"},
  };

  for (const auto& def : kDefaults) {
    string keyword = def.default_keyword;
    if (config) {
      config->GetString(def.config_path, &keyword);
    }
    if (keyword.empty()) {
      continue;
    }
    Trigger t;
    t.keyword = keyword;
    t.digit_code = PinyinToDigitCode(keyword);
    t.type = def.type;
    triggers_.push_back(std::move(t));
  }
}

void T9DateTranslator::InitChineseTriggers() {
  auto make_delim = [](const string& code) -> string {
    string s = code;
    for (auto& c : s) {
      if (c == '1') c = '\'';
    }
    return s;
  };
  chinese_triggers_ = {
      // 今天/今日 (offset=0)
      {"今天", PinyinToInitials("jin tian"), PinyinToDigitCode("jin tian"),  "518", make_delim("518"), 0},
      {"今日", PinyinToInitials("jin ri"),    PinyinToDigitCode("jin ri"),     "517", make_delim("517"), 0},
      // 昨天/昨日 (offset=-1)
      {"昨天", PinyinToInitials("zuo tian"), PinyinToDigitCode("zuo tian"),  "918", make_delim("918"), -1},
      {"昨日", PinyinToInitials("zuo ri"),   PinyinToDigitCode("zuo ri"),    "917", make_delim("917"), -1},
      // 明天/明日 (offset=1)
      {"明天", PinyinToInitials("ming tian"),PinyinToDigitCode("ming tian"), "618", make_delim("618"), 1},
      {"明日", PinyinToInitials("ming ri"),  PinyinToDigitCode("ming ri"),   "617", make_delim("617"), 1},
  };
}

// ── T9DateTranslation::Compare() ──
// 通过已选候选数量精确控制插入位置。
// 必须配合 translators 列表中 t9_date_translator 排在第一位使用。
int T9DateTranslation::Compare(an<Translation> other,
                                const CandidateList& candidates) {
  if (exhausted())
    return 1;
  // idx 控制：已选候选数 < idx-1 时，让其他翻译器优先
  if (static_cast<int>(candidates.size()) < idx_ - 1) {
    return 1;  // 让位
  }
  // 已选候选数 >= idx-1 时，我们优先
  return -1;
}

// ── 日期格式化 ──

string T9DateTranslator::FormatDate(time_t t) {
  struct tm result;
  localtime_r(&t, &result);
  char buf[32];
  strftime(buf, sizeof(buf), "%Y-%m-%d", &result);
  return buf;
}

string T9DateTranslator::FormatTime(time_t t) {
  struct tm result;
  localtime_r(&t, &result);
  char buf[16];
  strftime(buf, sizeof(buf), "%H:%M", &result);
  return buf;
}

string T9DateTranslator::FormatWeek(time_t t) {
  struct tm result;
  localtime_r(&t, &result);
  static const char* kWeekDays[] = {
      "日", "一", "二", "三", "四", "五", "六",
  };
  return string("星期") + kWeekDays[result.tm_wday];
}

string T9DateTranslator::FormatDateTime(time_t t) {
  struct tm result;
  localtime_r(&t, &result);
  char buf[48];
  strftime(buf, sizeof(buf), "%Y-%m-%dT%H:%M:%S%z", &result);
  return buf;
}

string T9DateTranslator::FormatTimestamp(time_t t) {
  return std::to_string(static_cast<long long>(t));
}

string T9DateTranslator::FormatChineseDate(time_t t) {
  struct tm result;
  localtime_r(&t, &result);
  // 年
  int year = result.tm_year + 1900;
  string year_str;
  year_str += kChineseDigits[year / 1000];
  year_str += kChineseDigits[(year / 100) % 10];
  year_str += kChineseDigits[(year / 10) % 10];
  year_str += kChineseDigits[year % 10];
  year_str += "年";
  // 月
  int month = result.tm_mon + 1;
  string month_str;
  if (month >= 10) month_str += "十";
  if (month % 10 != 0) month_str += kChineseDigits[month % 10];
  month_str += "月";
  // 日
  int day = result.tm_mday;
  string day_str;
  if (day >= 20) {
    day_str += kChineseDigits[day / 10];
    day_str += "十";
  } else if (day >= 10) {
    day_str += "十";
  }
  if (day % 10 != 0) day_str += kChineseDigits[day % 10];
  day_str += "日";
  return year_str + month_str + day_str;
}

string T9DateTranslator::FormatEnglishDate(time_t t) {
  struct tm result;
  localtime_r(&t, &result);
  static const char* kMonths[] = {
      "January", "February", "March", "April", "May", "June",
      "July", "August", "September", "October", "November", "December",
  };
  char buf[64];
  int n = snprintf(buf, sizeof(buf), "%s %d, %d",
                   kMonths[result.tm_mon], result.tm_mday,
                   result.tm_year + 1900);
  return string(buf, n);
}

string T9DateTranslator::FormatCompactDate(time_t t) {
  struct tm result;
  localtime_r(&t, &result);
  char buf[16];
  strftime(buf, sizeof(buf), "%m月%d日", &result);
  string s = buf;
  if (s.size() >= 2 && s[0] == '0') {
    s = s.substr(1);
  }
  size_t day_pos = s.find("日");
  if (day_pos >= 2 && s[day_pos - 2] == '0') {
    s.erase(day_pos - 2, 1);
  }
  return s;
}

// ── 候选生成 ──

an<Translation> T9DateTranslator::GenerateDateCandidates(
    const Segment& segment, const string& type, const string& preedit) {
  auto result = New<T9DateTranslation>(idx_);
  time_t now = time(nullptr);

  auto add_candidate = [&](const string& type, const string& text) {
    auto cand = New<SimpleCandidate>(type, segment.start, segment.end,
                                     text, "", preedit);
    result->Append(cand);
  };

  if (type == "date") {
    add_candidate("date", FormatDate(now));
    add_candidate("date", FormatChineseDate(now));
    add_candidate("date", FormatEnglishDate(now));
  } else if (type == "time") {
    add_candidate("time", FormatTime(now));
  } else if (type == "week") {
    add_candidate("week", FormatWeek(now));
  } else if (type == "datetime") {
    add_candidate("datetime", FormatDateTime(now));
  } else if (type == "timestamp") {
    add_candidate("timestamp", FormatTimestamp(now));
  }

  return result;
}

an<Translation> T9DateTranslator::GenerateChineseDateCandidate(
    const Segment& segment, const ChineseTrigger& trigger) {
  time_t now = time(nullptr);
  time_t target = now + trigger.offset_days * 86400;
  string date_str = FormatCompactDate(target);
  string combined = trigger.text + "(" + date_str + ")";

  auto result = New<T9DateTranslation>(idx_);
  auto cand = New<SimpleCandidate>("date_hint", segment.start, segment.end,
                                   combined, "", trigger.preedit);
  result->Append(cand);
  return result;
}

an<Translation> T9DateTranslator::GenerateLunarCandidates(
    const Segment& segment, const string& preedit) {
  auto result = New<T9DateTranslation>(idx_);
  time_t now = time(nullptr);
  struct tm tm_now;
  localtime_r(&now, &tm_now);
  int year = tm_now.tm_year + 1900;
  int month = tm_now.tm_mon + 1;
  int day = tm_now.tm_mday;

  LunarDate ld = GregorianToLunar(year, month, day);

  auto add_candidate = [&](const string& type, const string& text) {
    auto cand = New<SimpleCandidate>(type, segment.start, segment.end,
                                     text, "", preedit);
    result->Append(cand);
  };

  // 输出三个格式：中文数字格式、干支纪年格式、公历日期
  add_candidate("lunar", FormatLunarDateShort(ld));   // 二〇二三年冬月二十
  add_candidate("lunar", FormatLunarDateLong(ld));    // 癸卯年（兔）冬月二十

  return result;
}

// ── Query 入口 ──

an<Translation> T9DateTranslator::Query(const string& input,
                                        const Segment& segment) {
  if (!enabled_ || input.empty()) {
    return nullptr;
  }

  // 1. 匹配系统触发词（全键盘模式：input == keyword）
  for (const auto& t : triggers_) {
    if (input == t.keyword) {
      if (t.type == "lunar") {
        return GenerateLunarCandidates(segment, t.keyword);
      }
      return GenerateDateCandidates(segment, t.type, t.keyword);
    }
  }

  // 2. 匹配系统触发词数字码（T9 模式：input == digit_code）
  // 注意：农历（lunar）不使用 digit_code（65）匹配，改用 615 简拼和全拼
  for (const auto& t : triggers_) {
    if (t.type == "lunar") continue;
    if (input == t.digit_code) {
      return GenerateDateCandidates(segment, t.type, t.keyword);
    }
  }

  // 3. 农历额外匹配：
  //    简拼 "n l"（全键盘）/ "6'5"（T9 中 615 的实际输入，1 被转分隔符）
  //    全拼 "nongli"（全键盘）/ "666454"（T9）
  if (input == "n l" || input == "6'5" ||
      input == "nongli" || input == "666454") {
    return GenerateLunarCandidates(segment, "nl");
  }

  // 4. 匹配中文触发词（全拼 + 简拼 + T9 分隔符简拼）
  for (const auto& ct : chinese_triggers_) {
    if (input == ct.digit_code || input == ct.short_code ||
        input == ct.short_code_delim) {
      return GenerateChineseDateCandidate(segment, ct);
    }
  }

  return nullptr;
}

#endif  // T9_ALGO_ONLY_BUILD