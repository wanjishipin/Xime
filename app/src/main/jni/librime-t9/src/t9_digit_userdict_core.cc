#include "t9_digit_userdict_core.h"

#include <algorithm>
#include <cmath>
#include <cctype>
#include <iomanip>
#include <sstream>

#include "t9_log.h"
#include "t9_pinyin_map.h"

namespace rime {

// 衰减公式：d_new = d_delta + d_old * exp((t_old - t_new) / 200)
// 与 librime/src/rime/algo/dynamics.h 的 formula_d 一致，内联实现以免引入 RIME 头依赖。
// static
double T9DigitUserDictCore::FormulaD(double delta, double now,
                                     double old_dee, double old_tick) {
  return delta + old_dee * exp((old_tick - now) / 200);
}

// 排序权重公式（与 dynamics.h 的 formula_p 一致，内联实现）。
// static
double T9DigitUserDictCore::FormulaP(double s, double u, double t, double d) {
  const double kM = 1.0 / (1.0 - exp(-0.005));
  double m = s - (s - u) * pow((1.0 - exp(-t / 10000.0)), 10);
  return (d < 20) ? m + (0.5 - m) * (d / kM)
                  : m + (1.0 - m) * (pow(4.0, (d / kM)) - 1.0) / 3.0;
}

// static
std::string T9DigitUserDictCore::BuildLookupKey(const std::string& rime_input) {
  std::string left_pinyin;
  std::string digits;
  for (char c : rime_input) {
    if (c >= '2' && c <= '9') {
      digits += c;
    } else if (std::isalpha(static_cast<unsigned char>(c))) {
      left_pinyin += static_cast<char>(std::tolower(static_cast<unsigned char>(c)));
    }
  }
  // key = <digits>[:<left_pinyin>]，两部分皆可为空（全空时返回空 = 无 key）。
  // 场景 A/B：纯数字 "54482"；场景 C：数字+左选 "4482:j"；
  // 场景 D（左选完全消费数字）：纯左选 "km"。
  if (digits.empty() && left_pinyin.empty())
    return {};
  if (digits.empty())
    return left_pinyin;
  if (left_pinyin.empty())
    return digits;
  return digits + ":" + left_pinyin;
}

// static
std::string T9DigitUserDictCore::PinyinToFullCode(const std::string& pinyin) {
  if (pinyin.empty())
    return {};
  std::string result;
  std::istringstream in(pinyin);
  std::string syllable;
  while (in >> syllable) {
    auto code = T9PinyinMap::Instance().PinyinToDigitCode(syllable);
    if (!code.has_value())
      return {};
    result += *code;
  }
  return result;
}

// static
std::string T9DigitUserDictCore::BuildKey(const std::string& input_digits,
                                          const std::string& full_code,
                                          const std::string& text) {
  return input_digits + "\t" + full_code + "\t" + text;
}

// static
bool T9DigitUserDictCore::ParseKey(const std::string& key,
                                   std::string* input_digits,
                                   std::string* full_code,
                                   std::string* text) {
  size_t t1 = key.find('\t');
  if (t1 == std::string::npos)
    return false;
  size_t t2 = key.find('\t', t1 + 1);
  if (t2 == std::string::npos)
    return false;
  if (input_digits)
    *input_digits = key.substr(0, t1);
  if (full_code)
    *full_code = key.substr(t1 + 1, t2 - t1 - 1);
  if (text)
    *text = key.substr(t2 + 1);
  return true;
}

// static
std::string T9DigitUserDictCore::PackValue(const Entry& entry) {
  std::ostringstream out;
  out << "p=" << entry.pinyin << '\t'
      << "c=" << entry.commits << '\t'
      << "d=" << std::setprecision(17) << entry.dee << '\t'
      << "t=" << entry.last_tick;
  return out.str();
}

// static
bool T9DigitUserDictCore::UnpackValue(const std::string& value, Entry* entry) {
  if (!entry)
    return false;
  size_t p0 = 0;
  auto read_field = [&](size_t* pos, const std::string& prefix,
                        std::string* out) -> bool {
    if (*pos == std::string::npos)
      return false;
    size_t start = *pos;
    size_t end = value.find('\t', start);
    if (end == std::string::npos)
      end = value.size();
    *pos = (end == value.size()) ? std::string::npos : end + 1;
    if (value.compare(start, prefix.size(), prefix) != 0)
      return false;
    *out = value.substr(start + prefix.size(), end - start - prefix.size());
    return true;
  };
  std::string pinyin, commits_s, dee_s, tick_s;
  if (!read_field(&p0, "p=", &pinyin) ||
      !read_field(&p0, "c=", &commits_s) ||
      !read_field(&p0, "d=", &dee_s) ||
      !read_field(&p0, "t=", &tick_s))
    return false;
  try {
    entry->pinyin = pinyin;
    entry->commits = std::stoi(commits_s);
    entry->dee = std::stod(dee_s);
    entry->last_tick = std::stoull(tick_s);
  } catch (...) {
    return false;
  }
  return true;
}

std::vector<T9DigitUserDictCore::StorageOp>
T9DigitUserDictCore::Memorize(const std::string& digits,
                              const std::string& text,
                              const std::string& pinyin) {
  std::vector<StorageOp> ops;
  if (digits.empty() || text.empty() || pinyin.empty())
    return ops;

  // 计算完整拼音数字码 C(W)
  // PinyinToDigitCode 内部已做声调归一化，带调拼音（如 "lì hù"）能正确映射。
  std::string full_code = PinyinToFullCode(pinyin);
  if (full_code.empty())
    return ops;

  // 递增全局 tick
  ++tick_;

  // 写入主存储：C(W) → Entry
  auto& vec = entries_by_code_[full_code];
  for (auto& e : vec) {
    if (e.text == text) {
      // 已有条目：更新 dee、commits，迁移 input_digits
      e.commits += 1;
      e.dee = FormulaD(1.0, (double)tick_, e.dee, (double)e.last_tick);
      e.last_tick = tick_;
      if (e.pinyin.empty())
        e.pinyin = pinyin;
      // 输入序列变化时迁移索引到新 key
      if (e.input_digits != digits) {
        auto& old_list = digit_to_code_[e.input_digits];
        old_list.erase(std::remove(old_list.begin(), old_list.end(), full_code),
                       old_list.end());
        if (old_list.empty())
          digit_to_code_.erase(e.input_digits);
        ops.push_back(StorageOp{StorageOp::kErase,
                                BuildKey(e.input_digits, full_code, text), {}});
        e.input_digits = digits;
        auto& new_list = digit_to_code_[digits];
        if (std::find(new_list.begin(), new_list.end(), full_code) ==
            new_list.end()) {
          new_list.push_back(full_code);
        }
      }
      ops.push_back(StorageOp{StorageOp::kUpdate,
                              BuildKey(e.input_digits, full_code, text),
                              PackValue(e)});
      T9_LOG_DEBUG("T9DigitDict", ">> Memorize: update dee=%.4f commits=%d tick=%llu",
                   e.dee, e.commits, (unsigned long long)tick_);
      return ops;
    }
  }

  // 新条目
  vec.push_back(Entry{
      text, pinyin,                         // text, pinyin
      FormulaD(1.0, (double)tick_, 0.0, 0.0),  // dee: 首次 = 1.0
      1, tick_,                             // commits=1, last_tick
      full_code, digits                     // full_code, input_digits
  });

  // 更新辅助索引
  auto& code_list = digit_to_code_[digits];
  if (std::find(code_list.begin(), code_list.end(), full_code) == code_list.end()) {
    code_list.push_back(full_code);
  }

  T9_LOG_DEBUG("T9DigitDict",
               ">> Memorize: new digits='%s' text='%s' dee=%.4f commits=1 "
               "tick=%llu",
               digits.c_str(), text.c_str(), vec.back().dee,
               (unsigned long long)tick_);
  ops.push_back(StorageOp{StorageOp::kUpdate, BuildKey(digits, full_code, text),
                          PackValue(vec.back())});
  return ops;
}

std::vector<T9DigitUserDictCore::StorageOp>
T9DigitUserDictCore::Forget(const std::string& digits,
                            const std::string& text) {
  std::vector<StorageOp> ops;
  auto idx_it = digit_to_code_.find(digits);
  if (idx_it == digit_to_code_.end())
    return ops;

  ++tick_;
  bool changed = false;
  std::vector<std::string> remaining_codes;
  for (const auto& code : idx_it->second) {
    auto entry_it = entries_by_code_.find(code);
    if (entry_it == entries_by_code_.end())
      continue;
    auto& vec = entry_it->second;
    bool code_has_remaining = false;
    for (auto e = vec.begin(); e != vec.end();) {
      if (e->text == text) {
        e->commits -= 1;
        if (e->commits <= 0) {
          ops.push_back(StorageOp{StorageOp::kErase,
                                  BuildKey(digits, code, text), {}});
          e = vec.erase(e);
          changed = true;
          continue;
        }
        e->dee = FormulaD(0.0, (double)tick_, e->dee, (double)e->last_tick);
        e->last_tick = tick_;
        ops.push_back(StorageOp{StorageOp::kUpdate,
                                BuildKey(digits, code, text), PackValue(*e)});
        changed = true;
      }
      if (e->commits > 0)
        code_has_remaining = true;
      ++e;
    }
    if (code_has_remaining)
      remaining_codes.push_back(code);
    if (vec.empty())
      entries_by_code_.erase(entry_it);
  }

  if (remaining_codes.empty()) {
    digit_to_code_.erase(idx_it);
  } else {
    idx_it->second = std::move(remaining_codes);
  }

  return ops;
}

std::vector<T9DigitUserDictCore::Entry>
T9DigitUserDictCore::Lookup(const std::string& digits, size_t limit) {
  std::vector<Entry> result;

  // 路径 1：精确匹配 lookup_key（含左选复合键）——命中场景 C/D 词。
  // 这些词代表"用户真实用过的输入路径"，直接返回不过滤（rank=0 优先）。
  std::vector<std::pair<int, Entry>> ranked;  // (rank, entry): 0=精确, 1=还原
  auto idx_it = digit_to_code_.find(digits);
  if (idx_it != digit_to_code_.end()) {
    for (const auto& code : idx_it->second) {
      auto entry_it = entries_by_code_.find(code);
      if (entry_it == entries_by_code_.end())
        continue;
      for (auto& e : entry_it->second) {
        if (e.commits > 0) {
          ranked.emplace_back(0, e);
        }
      }
    }
  }

  // 路径 2：左选是完整首音节（长度 >= 2）时，还原完整数字序列做前缀筛选。
  // 如 left_pinyin="ji"→"54"+"482"→"54482"，命中场景 B 词（key=纯数字序列），
  // 按首音节匹配过滤（左选 ji 时筛掉首音节 li 的词）。
  // 左选声母简拼（长度=1，如 j/k/l）跳过还原（精确匹配已覆盖场景 C 词）。
  size_t colon_pos = digits.find(':');
  if (colon_pos != std::string::npos && colon_pos + 1 < digits.size()) {
    std::string remaining_digits = digits.substr(0, colon_pos);
    std::string left_pinyin = digits.substr(colon_pos + 1);
    if (left_pinyin.size() >= 2) {
      auto left_code = T9PinyinMap::Instance().PinyinToDigitCode(left_pinyin);
      if (left_code.has_value()) {
        std::string full_digits = *left_code + remaining_digits;
        if (full_digits != digits) {  // 防自身命中重复
        auto full_it = digit_to_code_.find(full_digits);
        if (full_it != digit_to_code_.end()) {
          for (const auto& code : full_it->second) {
            auto entry_it = entries_by_code_.find(code);
            if (entry_it == entries_by_code_.end())
              continue;
            for (auto& e : entry_it->second) {
              if (e.commits <= 0)
                continue;
              // 首音节匹配：词拼音首音节以左选拼音开头
              size_t sp = e.pinyin.find(' ');
              std::string first_syllable =
                  e.pinyin.substr(0, sp == std::string::npos ? e.pinyin.size() : sp);
              if (first_syllable.size() >= left_pinyin.size() &&
                  first_syllable.compare(0, left_pinyin.size(), left_pinyin) == 0) {
                bool dup = false;
                for (const auto& r : ranked) {
                  if (r.second.text == e.text && r.second.full_code == e.full_code) {
                    dup = true;
                    break;
                  }
                }
                if (!dup)
                  ranked.emplace_back(1, e);
              }
            }
          }
        }
        }
        }
      }
  }

  // 排序：rank 优先（精确匹配 > 还原），同 rank 按 dee 降序 → commits 降序。
  std::sort(ranked.begin(), ranked.end(),
            [](const std::pair<int, Entry>& a, const std::pair<int, Entry>& b) {
              if (a.first != b.first)
                return a.first < b.first;
              if (a.second.dee != b.second.dee)
                return a.second.dee > b.second.dee;
              return a.second.commits > b.second.commits;
            });
  result.clear();
  result.reserve(ranked.size());
  for (auto& r : ranked)
    result.push_back(r.second);

  if (limit && result.size() > limit)
    result.resize(limit);
  T9_LOG_DEBUG("T9DigitDict", ">> Lookup digits='%s' hits=%zu",
               digits.c_str(), result.size());
  return result;
}

void T9DigitUserDictCore::InsertFromStorage(const std::string& key,
                                            const std::string& value) {
  std::string input_digits, full_code, text;
  if (!ParseKey(key, &input_digits, &full_code, &text))
    return;
  Entry entry;
  entry.full_code = full_code;
  entry.input_digits = input_digits;
  if (!UnpackValue(value, &entry))
    return;
  entry.text = text;
  // 防御性覆盖，避免崩溃恢复后出现重复词条。
  auto& vec = entries_by_code_[full_code];
  bool updated = false;
  for (auto& e : vec) {
    if (e.text == text) {
      e.pinyin = entry.pinyin;
      e.dee = entry.dee;
      e.commits = entry.commits;
      e.last_tick = entry.last_tick;
      e.input_digits = input_digits;
      updated = true;
      break;
    }
  }
  if (!updated) {
    vec.push_back(entry);
  }
  auto& code_list = digit_to_code_[input_digits];
  if (std::find(code_list.begin(), code_list.end(), full_code) == code_list.end())
    code_list.push_back(full_code);
}

void T9DigitUserDictCore::Reset() {
  entries_by_code_.clear();
  digit_to_code_.clear();
  tick_ = 0;
}

}  // namespace rime