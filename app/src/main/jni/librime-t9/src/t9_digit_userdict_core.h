#ifndef T9_DIGIT_USERDICT_CORE_H_
#define T9_DIGIT_USERDICT_CORE_H_

#include <stdint.h>
#include <string>
#include <unordered_map>
#include <vector>

namespace rime {

using TickCount = uint64_t;

// 九键数字序列用户词典——纯算法核心（无 RIME 依赖，可独立单测）。
// 双索引：entries_by_code_（full_code → Entry）与 digit_to_code_（lookup_key → [full_code]）。
// 调频模型：对齐 RIME userdb 的 c/d/t 三元组，排序按 dee 降序 → commits 降序。
// 序列化：与 LevelDb 解耦，Memorize/Forget 返回 StorageOp 列表由外层应用。
// 键值格式：key=<input_digits>\t<full_code>\t<text>, value=p=<pinyin>\t<c=commits>\t<d=dee>\t<t=tick>
class T9DigitUserDictCore {
 public:
  struct Entry {
    std::string text;
    std::string pinyin;  // 空格分隔拼音（供候选 comment）
    double dee = 0.0;    // 衰减权重（RIME formula_d 计算）
    int commits = 0;     // 总使用次数
    TickCount last_tick = 0;  // 最近更新时的全局 tick
    std::string full_code;    // C(W) 完整拼音数字码
    std::string input_digits; // 组词时实际输入序列（lookup_key，含左选复合键）
  };

  // 落盘操作：Memorize/Forget 产生的持久化变更，由外层应用到 LevelDb。
  struct StorageOp {
    enum Kind { kUpdate, kErase };
    Kind kind;
    std::string key;
    std::string value;
  };

  // 从 RIME input 构建查找键。格式：无左选 "54482"；左选 j "j'4482" → "54482:j"。
  static std::string BuildLookupKey(const std::string& rime_input);

  // 拼音串（如 "ji hu biao"）→ 完整数字码（如 "54482424"），含无法映射的拼音时返回空串。
  static std::string PinyinToFullCode(const std::string& pinyin);

  // key/value 序列化。
  static std::string BuildKey(const std::string& input_digits,
                              const std::string& full_code,
                              const std::string& text);
  static bool ParseKey(const std::string& key,
                       std::string* input_digits,
                       std::string* full_code,
                       std::string* text);
  static std::string PackValue(const Entry& entry);
  static bool UnpackValue(const std::string& value, Entry* entry);

  // 上屏记忆：写入/更新条目，返回需落盘的变更。
  std::vector<StorageOp> Memorize(const std::string& digits,
                                  const std::string& text,
                                  const std::string& pinyin);
  // 撤销：commits -1，归零时删除。
  std::vector<StorageOp> Forget(const std::string& digits,
                                const std::string& text);
  // 召回：匹配 lookup_key，精确匹配优先，左选复合键时还原完整数字序列做前缀筛选。
  std::vector<Entry> Lookup(const std::string& digits, size_t limit);

  // 从持久化层灌入一条记录重建索引。
  void InsertFromStorage(const std::string& key, const std::string& value);
  // 清空全部索引与计数。
  void Reset();

  // 衰减公式：d_new = d_delta + d_old * exp((t_old - t_new) / 200)
  // 内联实现以免引入 RIME 引擎头依赖。
  static double FormulaD(double delta, double now, double old_dee, double old_tick);

  // 排序权重公式（与 dynamics.h 的 formula_p 一致，内联实现）。
  // s=0, u=commits/present_tick, t=present_tick, d=adj_dee
  static double FormulaP(double s, double u, double t, double d);

  TickCount tick() const { return tick_; }
  void SetTick(TickCount tick) { tick_ = tick; }
  size_t size() const { return entries_by_code_.size(); }

 private:
  // 主存储：C(W) → entries
  std::unordered_map<std::string, std::vector<Entry>> entries_by_code_;
  // 辅助索引：lookup_key → [C(W), ...]
  std::unordered_map<std::string, std::vector<std::string>> digit_to_code_;

  TickCount tick_ = 0;          // 全局 tick（Memorize/Forget 递增，持久化于 meta）
};

}  // namespace rime

#endif  // T9_DIGIT_USERDICT_CORE_H_