#ifndef T9_DIGIT_USERDICT_H_
#define T9_DIGIT_USERDICT_H_

#include <stdint.h>
#include <string>
#include <vector>

#include "t9_digit_userdict_core.h"

namespace rime {

class LevelDb;

// 九键数字序列用户词典（数字序列 → 用户词召回层）。
// 与 RIME pinyin 用户词典并存：场景A 走 RIME userdb，场景B/C 走 T9 侧。
// 存储：librime LevelDb，路径 <user_data_dir>/t9_digit.userdb。
// 数据格式见 T9DigitUserDictCore（纯算法核心，独立单测）。
class T9DigitUserDict {
 public:
  static T9DigitUserDict& Instance();

  // 设置持久化路径并打开 leveldb（仅首次调用生效）。
  static void SetFilePath(const std::string& path);

  // 从 RIME input 构建查找键（"54482" / "54482:ji"）。
  static std::string BuildLookupKey(const std::string& rime_input);

  // 关闭并重新打开 leveldb，将 memtable 刷为 .ldb 文件。
  static void CompactDb();

  void Memorize(const std::string& digits, const std::string& text,
                const std::string& pinyin);
  void Forget(const std::string& digits, const std::string& text);
  std::vector<T9DigitUserDictCore::Entry> Lookup(const std::string& digits,
                                                 size_t limit);

  bool loaded() const { return loaded_; }
  // 全局 tick（权重公式 FormulaP 的 present_tick 口径，与 RIME CreateDictEntry 对齐）。
  TickCount tick() const { return core_.tick(); }

 private:
  T9DigitUserDict() = default;
  T9DigitUserDict(const T9DigitUserDict&) = delete;
  T9DigitUserDict& operator=(const T9DigitUserDict&) = delete;

  bool OpenDb(const std::string& path);
  void LoadFromDb();
  void ApplyOps(const std::vector<T9DigitUserDictCore::StorageOp>& ops);
  // 关闭并重新打开 leveldb，刷 memtable 为 .ldb 文件。
  void Compact();

  T9DigitUserDictCore core_;
  LevelDb* db_ = nullptr;
  bool loaded_ = false;
};

}  // namespace rime

#endif  // T9_DIGIT_USERDICT_H_