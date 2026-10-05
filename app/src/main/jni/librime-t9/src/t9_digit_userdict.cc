#include "t9_digit_userdict.h"

#include <sstream>
#include <sys/stat.h>
#include <sys/types.h>

#include <rime/dict/level_db.h>

#include "t9_log.h"

namespace rime {

T9DigitUserDict& T9DigitUserDict::Instance() {
  static T9DigitUserDict instance;
  return instance;
}

// static
std::string T9DigitUserDict::BuildLookupKey(const std::string& rime_input) {
  return T9DigitUserDictCore::BuildLookupKey(rime_input);
}

// static
void T9DigitUserDict::SetFilePath(const std::string& path) {
  auto& self = Instance();
  if (self.loaded_)
    return;
  if (self.OpenDb(path)) {
    self.LoadFromDb();
    self.loaded_ = true;
    T9_LOG_DEBUG("T9DigitDict", ">> SetFilePath '%s' db opened, tick=%llu codes=%zu",
                 path.c_str(), (unsigned long long)self.core_.tick(),
                 self.core_.size());
  } else {
    T9_LOG_DEBUG("T9DigitDict", ">> SetFilePath '%s' OPEN FAILED (fallback: 内存模式)",
                 path.c_str());
    self.loaded_ = true;
  }
}

bool T9DigitUserDict::OpenDb(const std::string& path) {
  if (db_)
    return true;
  // 确保父目录存在（user_data_dir 恒存在，此处防御性处理）。
  size_t slash = path.rfind('/');
  if (slash != std::string::npos) {
    std::string dir = path.substr(0, slash);
    struct stat st;
    if (stat(dir.c_str(), &st) != 0 || !S_ISDIR(st.st_mode)) {
      if (mkdir(dir.c_str(), 0700) != 0) {
        T9_LOG_DEBUG("T9DigitDict", ">> OpenDb: mkdir failed for '%s'",
                     dir.c_str());
        return false;
      }
    }
  }
  // rime::path 的 string 构造为 explicit，需显式转换。
  db_ = new LevelDb(rime::path(path), "t9_digit_user", "userdb");
  if (db_->Open())
    return true;
  T9_LOG_DEBUG("T9DigitDict", ">> OpenDb: open failed for '%s', trying Recover",
               path.c_str());
  if (db_->Recover() && db_->Open())
    return true;
  delete db_;
  db_ = nullptr;
  return false;
}

void T9DigitUserDict::LoadFromDb() {
  core_.Reset();
  std::string tick_s;
  if (db_->MetaFetch("/tick", &tick_s)) {
    try {
      core_.SetTick(std::stoull(tick_s));
    } catch (...) {
      core_.SetTick(0);
    }
  }
  auto accessor = db_->QueryAll();
  std::string key, value;
  while (accessor && accessor->GetNextRecord(&key, &value)) {
    core_.InsertFromStorage(key, value);
  }
  T9_LOG_DEBUG("T9DigitDict", ">> LoadFromDb: loaded codes=%zu tick=%llu",
               core_.size(), (unsigned long long)core_.tick());
}

void T9DigitUserDict::ApplyOps(
    const std::vector<T9DigitUserDictCore::StorageOp>& ops) {
  if (ops.empty())
    return;
  if (!db_ || !db_->loaded()) {
    T9_LOG_DEBUG("T9DigitDict", ">> ApplyOps: db unavailable, memory-only");
    return;
  }
  db_->BeginTransaction();
  for (const auto& op : ops) {
    if (op.kind == T9DigitUserDictCore::StorageOp::kErase) {
      db_->Erase(op.key);
    } else {
      db_->Update(op.key, op.value);
    }
  }
  std::ostringstream os;
  os << core_.tick();
  db_->MetaUpdate("/tick", os.str());
  db_->CommitTransaction();
}

void T9DigitUserDict::Memorize(const std::string& digits,
                               const std::string& text,
                               const std::string& pinyin) {
  auto ops = core_.Memorize(digits, text, pinyin);
  ApplyOps(ops);
  // 每次写入后 compact（close+reopen），flush memtable 为 .ldb 文件。
  // 多 .ldb 文件是 leveldb 的正常行为（rime_ice.userdb 同样有多个）。
  Compact();
}

void T9DigitUserDict::Forget(const std::string& digits,
                             const std::string& text) {
  auto ops = core_.Forget(digits, text);
  ApplyOps(ops);
}

void T9DigitUserDict::Compact() {
  if (!db_ || !db_->loaded())
    return;
  db_->Close();
  if (!db_->Open()) {
    T9_LOG_DEBUG("T9DigitDict", ">> Compact: reopen failed, trying Recover");
    if (!db_->Recover() || !db_->Open()) {
      T9_LOG_DEBUG("T9DigitDict", ">> Compact: reopen FAILED (data in memory only)");
      return;
    }
  }
  T9_LOG_DEBUG("T9DigitDict", ">> Compact: done (memtable flushed to .ldb)");
}

// static
void T9DigitUserDict::CompactDb() {
  Instance().Compact();
}

std::vector<T9DigitUserDictCore::Entry> T9DigitUserDict::Lookup(
    const std::string& digits, size_t limit) {
  return core_.Lookup(digits, limit);
}

}  // namespace rime