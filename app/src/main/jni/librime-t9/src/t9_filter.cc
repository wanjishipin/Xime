#include "t9_filter.h"

#include <cctype>
#include <sstream>
#include <vector>

#include "t9_digit_userdict.h"
#include "t9_log.h"
#include "t9_pinyin_map.h"  // NormalizePinyinComment（声调归一化，统一入口）

#ifndef T9_ALGO_ONLY_BUILD
#include <rime/context.h>
#include <rime/engine.h>
#include <rime/schema.h>
#include <rime/config.h>
#include <rime/common.h>
#include <rime/gear/translator_commons.h>  // Phrase（Phrase 码缓存）
#include "t9_digit_userdict.h"  // 去重集合（T9 用户词文本）
#include "t9_processor.h"  // T9ProcessorRequire / CachePhraseCode（Phrase 码缓存）
#endif

namespace rime {

// ════════════════════════════════════════════════════════════════
// T9 Preedit Converter（无 RIME 依赖，纯字符串算法）
// ════════════════════════════════════════════════════════════════
// 原 t9_preedit_converter.cc 合并至此。Converter 逻辑无任何 RIME 依赖，
// 可在纯算法测试（t9-algo-objs + T9_ALGO_ONLY_BUILD）中独立编译。

// ── UTF-8 辅助 ──

static uint32_t DecodeUtf8(const char*& p, const char* end) {
    if (p >= end) return 0;
    unsigned char c = static_cast<unsigned char>(*p);
    if (c < 0x80) {
        uint32_t cp = c;
        ++p;
        return cp;
    }
    if ((c & 0xE0) == 0xC0) {
        if (p + 1 >= end) { ++p; return 0; }
        uint32_t cp = ((c & 0x1F) << 6) | (static_cast<unsigned char>(p[1]) & 0x3F);
        p += 2;
        return cp;
    }
    if ((c & 0xF0) == 0xE0) {
        if (p + 2 >= end) { ++p; return 0; }
        uint32_t cp = ((c & 0x0F) << 12)
                    | ((static_cast<unsigned char>(p[1]) & 0x3F) << 6)
                    | (static_cast<unsigned char>(p[2]) & 0x3F);
        p += 3;
        return cp;
    }
    if ((c & 0xF8) == 0xF0) {
        if (p + 3 >= end) { ++p; return 0; }
        uint32_t cp = ((c & 0x07) << 18)
                    | ((static_cast<unsigned char>(p[1]) & 0x3F) << 12)
                    | ((static_cast<unsigned char>(p[2]) & 0x3F) << 6)
                    | (static_cast<unsigned char>(p[3]) & 0x3F);
        p += 4;
        return cp;
    }
    ++p;
    return 0;
}

static bool IsChinese(uint32_t cp) {
    return cp >= 0x4E00 && cp <= 0x9FFF;
}

static bool IsDigit(char c) {
    return c >= '0' && c <= '9';
}

// ── 输入分段 ──

struct InputPart {
    std::string text;
    bool is_separator;
    bool is_all_digits;
    bool is_chinese;
};

static std::vector<InputPart> SplitPreedit(const std::string& preedit) {
    std::vector<InputPart> parts;
    const char* p = preedit.c_str();
    const char* end = p + preedit.size();

    std::string buf;
    bool buf_is_chinese = false;
    bool buf_has_digit = false;

    auto flush = [&]() {
        if (!buf.empty()) {
            InputPart part;
            part.text = buf;
            part.is_separator = false;
            part.is_all_digits = buf_has_digit && !buf_is_chinese;
            if (part.is_all_digits) {
                for (char c : buf) {
                    if (!IsDigit(c)) {
                        part.is_all_digits = false;
                        break;
                    }
                }
            }
            part.is_chinese = buf_is_chinese;
            parts.push_back(std::move(part));
            buf.clear();
            buf_is_chinese = false;
            buf_has_digit = false;
        }
    };

    while (p < end) {
        if (*p == ' ' || *p == '\'') {
            flush();
            InputPart sep;
            sep.text = std::string(1, *p);
            sep.is_separator = true;
            sep.is_all_digits = false;
            sep.is_chinese = false;
            parts.push_back(std::move(sep));
            ++p;
            continue;
        }

        const char* prev = p;
        uint32_t cp = DecodeUtf8(p, end);
        if (cp == 0) continue;

        bool is_chi = IsChinese(cp);
        std::string ch = std::string(prev, p);

        if (!buf.empty() && buf_is_chinese != is_chi) {
            flush();
        }

        if (is_chi) buf_is_chinese = true;
        if (cp >= '0' && cp <= '9') buf_has_digit = true;
        buf += ch;
    }
    flush();
    return parts;
}

// ── ConvertPreedit 算法 ──
// 移植自 T9PreeditConverter.kt 的 convertT9PreeditToPinyin()

std::string T9ConvertPreedit(const std::string& preedit,
                               const std::string& comment) {
    if (preedit.empty() || comment.empty()) return preedit;

    std::vector<std::string> pinyin_parts;
    {
        std::istringstream iss(comment);
        std::string word;
        while (iss >> word) {
            if (!word.empty()) {
                // 过滤 comment_format 引入的非字母字符（如「」括号），
                // 同时归一化带声调的预组合字符（如带声调方案的 jī huà），
                // 确保 pinyin_parts 只包含纯 ASCII 拼音。
                std::string filtered = NormalizePinyinComment(word);
                if (!filtered.empty()) {
                    pinyin_parts.push_back(filtered);
                }
            }
        }
    }
    if (pinyin_parts.empty()) return preedit;

    bool has_digit_or_separator = false;
    for (char c : preedit) {
        if (IsDigit(c) || c == '\'' || c == ' ') {
            has_digit_or_separator = true;
            break;
        }
    }
    if (!has_digit_or_separator) return preedit;

    auto input_parts = SplitPreedit(preedit);

    size_t pi = 0;
    for (size_t i = 0; i < input_parts.size(); ++i) {
        InputPart& part = input_parts[i];
        if (part.is_separator) {
            part.text = " ";
        } else if (part.is_all_digits) {
            if (pi < pinyin_parts.size()) {
                const std::string& py = pinyin_parts[pi];
                bool single_segment_multiple_pinyins =
                    (input_parts.size() == 1 && pinyin_parts.size() > 1);

                if (part.text.size() == 1) {
                    // 单数字段 → 判断是否要触发简拼
                    // 触发简拼条件：是末尾段（最后非分隔符段），或后面紧跟分隔符
                    bool is_last_non_separator = true;
                    bool next_is_separator = false;
                    for (size_t j = i + 1; j < input_parts.size(); ++j) {
                        if (input_parts[j].is_separator) {
                            next_is_separator = true;
                        } else {
                            is_last_non_separator = false;
                            break;
                        }
                    }
                    if (is_last_non_separator || next_is_separator) {
                        // 末尾单数字段或分隔符前的单数字段 → 使用首字母作为简拼
                        // 如 "5" → "j" (从 "jia" 取首字母)
                        std::string prefix = py.substr(0, 2);
                        for (auto& c : prefix) c = static_cast<char>(tolower(c));
                        if (prefix == "zh" || prefix == "ch" || prefix == "sh") {
                            part.text = prefix;
                        } else {
                            part.text = std::string(1, static_cast<char>(tolower(py[0])));
                        }
                    } else {
                        // 中间段单数字 → 使用完整拼音（如 "7公民" 中的 "7"→"shen"）
                        std::string lower = py;
                        for (auto& c : lower) c = static_cast<char>(tolower(c));
                        part.text = lower;
                    }
                } else if (single_segment_multiple_pinyins) {
                    std::string joined;
                    for (const auto& p : pinyin_parts) {
                        std::string lower = p;
                        for (auto& c : lower) c = static_cast<char>(tolower(c));
                        joined += lower;
                    }
                    part.text = joined;
                } else {
                    std::string lower = py;
                    for (auto& c : lower) c = static_cast<char>(tolower(c));
                    part.text = lower;
                }
                ++pi;
            }
        } else if (part.is_chinese) {
            // 中文 = 已提交文本，原样保留，不消耗拼音索引
        } else {
            ++pi;
        }
    }

    std::string result;
    for (const auto& part : input_parts) {
        result += part.text;
    }
    return result;
}

// ── 候选级 preedit 转换 ──
// 英文九键方案（如 melt_eng_t9，table_translator）候选无拼音注释：
//   - comment 为空 → 直接显示候选词文本（"8378" → "test"）
//   - comment 以 '~' 开头（librime 统一编码后缀标记 '~s'/'~ed'）→ 同上
// 中文九键方案（t9_pinyin，script_translator）候选带拼音注释 → 沿用数字→拼音转换。

std::string T9ConvertCandidatePreedit(const std::string& preedit,
                                      const std::string& comment,
                                      const std::string& candidate_text) {
    if (comment.empty() || comment[0] == '~') {
        return candidate_text;
    }
    return T9ConvertPreedit(preedit, comment);
}

std::vector<size_t> T9BuildSingleCharPromotionOrder(
    const std::vector<bool>& is_first_syllable_single,
    size_t promote_after,
    size_t max_lift) {
    const size_t n = is_first_syllable_single.size();
    std::vector<size_t> identity(n);
    for (size_t i = 0; i < n; ++i) identity[i] = i;
    if (n == 0) return identity;

    // 只提升位于插入点之后的单字；插入点之前出现的单字（短输入下单字桶
    // 即最长桶、天然排前）保持原位，不因提权被降位。
    // max_lift > 0 时最多提升前 max_lift 个（按下标升序，即权重最高的那批），
    // 其余单字留在原位——避免把多音节词整批压到提权单字之后。
    std::vector<size_t> promote;
    for (size_t i = promote_after < n ? promote_after : n; i < n; ++i) {
        if (!is_first_syllable_single[i]) continue;
        if (max_lift > 0 && promote.size() >= max_lift) break;
        promote.push_back(i);
    }
    if (promote.empty()) return identity;

    std::vector<bool> lifted(n, false);
    for (size_t i : promote) lifted[i] = true;

    // 自然顺序输出；到达插入点时按原相对顺序插入全部被提升单字。
    std::vector<size_t> order;
    order.reserve(n);
    size_t next_lifted = 0;
    for (size_t i = 0; i < n; ++i) {
        if (i == promote_after) {
            while (next_lifted < promote.size()) {
                order.push_back(promote[next_lifted++]);
            }
        }
        if (!lifted[i]) order.push_back(i);
    }
    return order;
}

// ════════════════════════════════════════════════════════════════
// T9Filter / T9Translation（RIME 依赖）
// ════════════════════════════════════════════════════════════════
// 编译守卫：纯算法测试（T9_ALGO_ONLY_BUILD）仅编译上方 converter，
// 不编译 RIME 依赖的 Filter/Translation 代码。

#ifndef T9_ALGO_ONLY_BUILD

// ── T9Translation ──

void T9Translation::ConvertCurrent() {
    T9_PERF_SCOPED_TIMER("[T9Filter] ConvertCurrent");
    if (!cand_) return;
    auto genuine = Candidate::GetGenuineCandidate(cand_);
    // 缓存 Phrase 真实码（含声调真相）：t9_filter 位于 filters 最前，候选尚为
    // 带调 Phrase；后续 lua filter 链可能重建为非 Phrase，导致右选调频无法从
    // 候选取码。缓存由 T9Processor 在每次 flush 重建、右选时按 (text, 归一化
    // comment) 匹配兜底。
    if (auto phrase = As<Phrase>(genuine)) {
        if (auto* proc = T9ProcessorRequire()) {
            proc->CachePhraseCode(genuine->text(), genuine->comment(), phrase->code());
        }
    }
    if (!convert_preedit_) return;  // 透传：仅缓存，不改 preedit
    std::string converted = T9ConvertCandidatePreedit(genuine->preedit(),
                                                       genuine->comment(),
                                                       genuine->text());
    T9FLOG("ConvertCurrent: \"%s\" -> \"%s\"",
          genuine->preedit().c_str(), converted.c_str());
    if (converted != genuine->preedit()) {
        cand_ = New<T9PreeditCandidate>(cand_, converted);
    }
}

T9Translation::T9Translation(an<Translation> translation,
                               char auto_delim,
                               char manual_delim,
                               bool convert_preedit,
                               int single_char_promote_after,
                               int single_char_promote_max)
    : translation_(translation),
      auto_delim_(auto_delim),
      manual_delim_(manual_delim),
      convert_preedit_(convert_preedit),
      promote_after_(single_char_promote_after > 0 ? single_char_promote_after : 0),
      promote_max_(single_char_promote_max > 0
                       ? static_cast<size_t>(single_char_promote_max)
                       : 0) {
    if (promote_after_ > 0) phase_ = Phase::kHeadStream;
    // 定位到第一个候选（构造时 translation 已定位在第一个候选）。
    Advance();
    if (phase_ == Phase::kHeadStream && !exhausted()) head_served_ = 1;
}

bool T9Translation::Next() {
    if (exhausted()) return false;
    if (phase_ == Phase::kHeadStream) {
        // 头部流式：前 promote_after_ 个候选零额外开销（与关闭提权时逐字节同路径）。
        if (head_served_ < static_cast<size_t>(promote_after_)) {
            if (!translation_->Next()) {
                set_exhausted(true);
                return false;
            }
            Advance();
            ++head_served_;
            return !exhausted();
        }
        // 头部已满，消费者越过插入点 → 物化后缀窗口并按计划出候选。
        MaterializeSuffixForPromotion();
        if (plan_order_.empty()) {
            set_exhausted(true);
            return false;
        }
        phase_ = Phase::kPlan;
        plan_pos_ = 0;
        cand_ = plan_items_[plan_order_[0]];
        return true;
    }
    if (phase_ == Phase::kPlan) {
        // 按计划出候选：物化时 translation_ 游标已越过窗口，此处不动它。
        ++plan_pos_;
        if (plan_pos_ < plan_order_.size()) {
            cand_ = plan_items_[plan_order_[plan_pos_]];
            return true;
        }
        phase_ = Phase::kTailStream;
        Advance();  // 计划耗尽 → 尾部流式（translation_ 已定位在首个未物化候选）
        return !exhausted();
    }
    // 尾部流式（含提权关闭的全部路径）。
    if (!translation_->Next()) {
        set_exhausted(true);
        return false;
    }
    Advance();
    return !exhausted();
}

void T9Translation::Advance() {
    if (phase_ == Phase::kPlan) {
        if (plan_pos_ < plan_order_.size()) {
            cand_ = plan_items_[plan_order_[plan_pos_]];
            return;
        }
        phase_ = Phase::kTailStream;  // 防御直达（计划耗尽正常由 Next 处理）
    }
    while (!translation_->exhausted()) {
        cand_ = translation_->Peek();
        ConvertCurrent();
        return;
    }
    cand_ = nullptr;
    set_exhausted(true);
}

// 物化扫描上限：候选桶在 ScriptTranslation::Evaluate 时已查好且每桶封顶
// （max_homophones），物化只是遍历已入桶条目并逐个 Peek（无新词典查询），
// 上限兜底防异常长列表；超出部分照旧流式供给，不截断候选列表。
static const size_t kPromoteScanCap = 100;

void T9Translation::MaterializeSuffixForPromotion() {
    T9_PERF_SCOPED_TIMER("[T9Filter] MaterializeSuffixForPromotion");
    std::vector<bool> is_single;
    is_single.reserve(kPromoteScanCap);
    plan_items_.reserve(kPromoteScanCap);
    for (size_t i = 0; i < kPromoteScanCap && !translation_->exhausted(); ++i) {
        cand_ = translation_->Peek();
        ConvertCurrent();
        plan_items_.push_back(cand_);
        is_single.push_back(IsPromotableSingle(cand_));
        if (!translation_->Next()) break;
    }
    // 后缀窗口插入点 = 0：窗口内首音节单字提到窗口头部（受 promote_max_ 限制）。
    plan_order_ = T9BuildSingleCharPromotionOrder(is_single, 0, promote_max_);
    size_t lifted = 0;
    for (bool b : is_single) {
        if (b) ++lifted;
    }
    T9FLOG("MaterializeSuffixForPromotion: %zu candidates, %zu singles (lift cap=%zu)",
           plan_items_.size(), lifted, promote_max_);
}

// 候选文本是否恰好一个汉字（UTF-8 单码点且非 ASCII）。
// 用于排除 abbrev 简拼命中的多字词——它们的 Phrase 码也只有 1 个音节。
static bool IsSingleHanCharacter(const std::string& text) {
    if (text.empty()) return false;
    const char* p = text.c_str();
    const char* end = p + text.size();
    uint32_t cp = DecodeUtf8(p, end);   // 上文 UTF-8 解码（无 RIME 依赖）
    if (cp == 0 || p != end) return false;
    return cp >= 0x3400;  // CJK 扩展 A 及以后（含基本区/扩展区汉字）
}

bool T9Translation::IsPromotableSingle(const an<Candidate>& cand) const {
    auto genuine = Candidate::GetGenuineCandidate(cand);
    if (!genuine || genuine->start() != 0) return false;
    // completion = 词末联想（简拼补全出长词），不是首音节全拼单字。
    if (genuine->type() == "completion") return false;
    // 整句（Sentence）/日期/标点候选非 Phrase，不在提权范围。
    auto phrase = As<Phrase>(genuine);
    if (!phrase) return false;
    if (phrase->code().size() != 1) return false;
    // code size 1 也可能是 abbrev 简拼匹配出的多字词（如整词用单字母码召回），
    // 只有候选文本本身是单个汉字才算「首音节单字」。
    return IsSingleHanCharacter(genuine->text());
}

// ── T9Filter ──

// 首音节单字提权默认插入点：第 5 个候选之后（保留开头的多音节组合词，
// 又让首屏就能看到首音节单字）。
static const int kDefaultSingleCharPromoteAfter = 5;

T9Filter::T9Filter(const Ticket& ticket) : Filter(ticket) {
    if (auto* schema = ticket.schema) {
        if (auto* config = schema->config()) {
            bool display_original = false;
            config->GetBool("t9/isDisplayOriginalPreedit", &display_original);
            convert_preedit_ = !display_original;

            // 提权默认行为内置于插件，第三方九键方案无需任何配置：
            //   拼音九键（engine/translators 含 script_translator，与左栏
            //   ResolveLeftPanelMode 的 auto 判定同源）默认开启——桶按匹配
            //   长度降序出词导致单字沉底是其固有排序；
            //   英文九键（table_translator，如 melt_eng_t9）按词频排序、
            //   无此问题，默认关闭。
            // t9/single_char_promote_after 仅作覆盖（0 = 关闭，N = 插入点）。
            bool has_script_translator = false;
            if (auto translators = config->GetList("engine/translators")) {
                for (auto it = translators->begin(); it != translators->end(); ++it) {
                    auto value = As<ConfigValue>(*it);
                    if (value && value->str() == "script_translator") {
                        has_script_translator = true;
                        break;
                    }
                }
            }
            single_char_promote_after_ =
                has_script_translator ? kDefaultSingleCharPromoteAfter : 0;
            config->GetInt("t9/single_char_promote_after",
                           &single_char_promote_after_);
            // 单次最多提升的单字数（0/负数 = 不限制）。未显式配置时默认「一页」：
            // 只把约一页量的单字提到插入点之后，多音节词随后即可恢复，不会被
            // 整批同音单字压到列表尾部（长输入单字桶可达成百条）。
            int promote_max = 0;
            if (config->GetInt("t9/single_char_promote_max", &promote_max)) {
                single_char_promote_max_ = promote_max;
            } else {
                int page_size = schema->page_size();
                single_char_promote_max_ = page_size > 0 ? page_size : 10;
            }

            std::string delimiter;
            if (config->GetString("speller/delimiter", &delimiter)
                && delimiter.size() >= 2) {
                auto_delimiter_ = delimiter[0];
                manual_delimiter_ = delimiter[1];
            }
        }
    }
}

an<Translation> T9Filter::Apply(an<Translation> translation,
                                 CandidateList* candidates) {
    T9_PERF_SCOPED_TIMER("[T9Filter] Apply");
    if (!translation) return translation;
    // 单字提权仅对「全数字」输入启用：数字键输入（未左选拼音、未按分词键）
    // 时 rime 输入串只含 2-9；一旦左选拼音（"you'942…"）或按分词键
    // （"968'942…"）就会出现字母/撇号。这两种情况下首音节已被用户显式
    // 锁定或收敛，候选列表本来就以目标词优先——继续提权反而把用户想要的
    // 多音节词挤出首页（实测 SegmentedInputRecallsWord 53 例回归）。
    int promote_after = 0;
    if (single_char_promote_after_ > 0 && engine_ && engine_->context()) {
        const std::string& input = engine_->context()->input();
        bool all_digits = !input.empty();
        for (char c : input) {
            if (c < '0' || c > '9') {
                all_digits = false;
                break;
            }
        }
        if (all_digits) promote_after = single_char_promote_after_;
    }
    // 去重由 filter 链末尾的 uniquifier 兜底，t9_filter 做 preedit 转换 +
    // （开启时）首音节单字提权。
    return New<T9Translation>(translation, auto_delimiter_, manual_delimiter_,
                              convert_preedit_, promote_after,
                              single_char_promote_max_);
}

#endif  // T9_ALGO_ONLY_BUILD

}  // namespace rime
