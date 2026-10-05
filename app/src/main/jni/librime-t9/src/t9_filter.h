#ifndef RIME_T9_FILTER_H_
#define RIME_T9_FILTER_H_

#include <cstddef>
#include <string>
#include <vector>

#ifndef T9_ALGO_ONLY_BUILD
#include <rime/filter.h>
#include <rime/translation.h>
#include <rime/candidate.h>
#include <rime/common.h>
#endif

namespace rime {

// 将 T9 九键的 preedit 从数字序列转换为拼音显示。
// 移植自 Kotlin T9PreeditConverter.kt 的 convertT9PreeditToPinyin()。
//
// 例如 "54482" + comment "ji gua" → "ji hua"
// "ji'43" + comment "ji kan" → "ji k"
// "5" + comment "le" → "l"
//
// 原 t9_preedit_converter.h 已合并至此，converter 实现在 t9_filter.cc 中。
std::string T9ConvertPreedit(const std::string& preedit,
                              const std::string& comment);

// 候选级 preedit 转换（英文九键适配，2026-08-07）：
//   - comment 为有效拼音（非空且不以 '~' 开头）→ 数字 → 拼音（T9ConvertPreedit）
//     '~' 是 librime 统一编码（unity encoder）后缀标记（如 melt_eng 的 '~s'/'~ed'），
//     非拼音，不应触发数字→拼音转换。
//   - 否则（英文九键方案无拼音注释，如 melt_eng_t9 的 "test"）→ 直接显示候选词文本。
std::string T9ConvertCandidatePreedit(const std::string& preedit,
                                      const std::string& comment,
                                      const std::string& candidate_text);

// 首音节单字提权顺序（纯函数，无 RIME 依赖，可在 T9_ALGO_ONLY_BUILD 下单测）。
//
// 背景：script_translator 的候选桶按匹配长度降序遍历（rbegin），纯数字长输入时
// 首音节单字桶（code size 1）排在所有多音节词桶之后，单字候选被压在长尾，
// 用户需翻数页或先去左侧栏锁定拼音才能选到单字。
//
// 规则：promote_after 为插入点。下标 ≥ promote_after 的单字按原相对顺序提升到
// 第 promote_after 个位置处；下标 < promote_after 的单字保持原位（短输入时单字桶
// 即最长桶、天然排前，不得降权）。无单字时返回原顺序。
// max_lift > 0 时最多提升 max_lift 个（按下标升序取前 max_lift 个），其余单字留在
// 原位；max_lift == 0 表示不限制。
// T9Translation 的后缀窗口用法传 0（窗口内单字全部提到窗口头部）。
// 返回值为重排后的源下标序列。
std::vector<size_t> T9BuildSingleCharPromotionOrder(
    const std::vector<bool>& is_first_syllable_single,
    size_t promote_after,
    size_t max_lift = 0);

#ifndef T9_ALGO_ONLY_BUILD
// 包装候选，覆盖 preedit() 返回转换后的拼音，
// 不修改原始候选对象，避免跨 .so 边界的 dynamic_cast 失效问题。
// 继承 SimpleCandidate 而非 Candidate，使得 Lua 绑定的
// dynamic_cast<SimpleCandidate*>(&c) 成功，从而 set_preedit()
// 可被后续 filter（如 super_comment_preedit）通过 cand.preedit = val
// 覆盖 preedit 值，避免锁死 bug。
// 同时实现 genuine() 虚解包协议：GetGenuineCandidate 经此拿到内层
// 原始候选，使长按删词（Memory::OnDeleteEntry）能取到底层 Phrase——
// 之前继承链断在 SimpleCandidate，T9 删词静默无效（全键盘无 t9_filter 不受影响）。
class T9PreeditCandidate : public SimpleCandidate {
public:
    T9PreeditCandidate(an<Candidate> item, const string& preedit)
        : SimpleCandidate(item->type() + "'t9",
                          item->start(), item->end(),
                          item->text(), item->comment(), preedit),
          item_(item) {
        set_quality(item->quality());
    }

    // text()/comment()/preedit() 均继承自 SimpleCandidate（存 item 副本）；
    // set_preedit(v) 供后续 Lua filter（super_comment_preedit）覆盖 preedit。

    an<Candidate> item() const { return item_; }
    an<Candidate> genuine() const override { return item_; }

private:
    an<Candidate> item_;
};

class T9Translation : public Translation {
public:
    // single_char_promote_after：首音节单字提权插入点（≤0 = 关闭，仅流式转换）。
    // single_char_promote_max：单次最多提升的单字数（≤0 = 不限制）。
    T9Translation(an<Translation> translation,
                   char auto_delim,
                   char manual_delim,
                   bool convert_preedit,
                   int single_char_promote_after = 0,
                   int single_char_promote_max = 0);
    bool Next() override;
    an<Candidate> Peek() override { return cand_; }

private:
    // 定位到下一个可接受的候选。
    void Advance();
    // 转换/缓存当前候选的 preedit（原逻辑）。
    void ConvertCurrent();
    // 单字提权：越过插入点时物化其后至多 kPromoteScanCap 个候选（后缀窗口），
    // 按 T9BuildSingleCharPromotionOrder(is_single, 0, promote_max_) 把窗口内
    // 首音节单字提到窗口头部；窗口外的候选照旧流式供给（不截断候选列表）。
    // 惰性触发——候选栏只消费第一页时零额外开销。
    void MaterializeSuffixForPromotion();
    // 首音节单字判定：start==0（输入起点）、code size 1、文本恰为一个汉字
    // 且为 Phrase（词/用户词，排除整句/日期/标点/简拼多字词）。
    bool IsPromotableSingle(const an<Candidate>& cand) const;

    an<Translation> translation_;
    an<Candidate> cand_;
    char auto_delim_;
    char manual_delim_;
    // false（isDisplayOriginalPreedit: true）时透传 preedit，仅缓存 Phrase 码供调频。
    bool convert_preedit_ = false;
    // ── 单字提权状态（三相：头部流式 → 计划 → 尾部流式）──
    enum class Phase { kHeadStream, kPlan, kTailStream };
    int promote_after_ = 0;                // 插入点（≤0 = 关闭，恒为尾部流式）
    size_t promote_max_ = 0;               // 最多提升数（0 = 不限制）
    Phase phase_ = Phase::kTailStream;     // promote_after_>0 时构造后为头部流式
    size_t head_served_ = 0;               // 头部已出候选数
    std::vector<an<Candidate>> plan_items_;  // 后缀窗口候选（已转换 preedit + 缓存码）
    std::vector<size_t> plan_order_;         // 窗口输出顺序（plan_items_ 下标）
    size_t plan_pos_ = 0;
};

class T9Filter : public Filter {
public:
    explicit T9Filter(const Ticket& ticket);
    an<Translation> Apply(an<Translation> translation,
                           CandidateList* candidates) override;
private:
    bool convert_preedit_ = false;
    char auto_delimiter_ = ' ';
    char manual_delimiter_ = '\'';
    // 首音节单字提权插入点。默认行为内置于插件（拼音九键 = 5，英文九键 = 0
    // 关闭，按 engine/translators 是否含 script_translator 判定）；第三方方案
    // 无需配置即获默认行为，t9/single_char_promote_after 仅作覆盖（0 = 关闭）。
    // 仅当本次输入为纯数字（未左选、未分词）时才实际启用。
    int single_char_promote_after_ = 0;
    // 单次最多提升的单字数（0 = 不限制）。默认取 schema 的 menu/page_size
    // （约一页），可由 t9/single_char_promote_max 覆盖。
    int single_char_promote_max_ = 0;
};
#endif  // T9_ALGO_ONLY_BUILD

}  // namespace rime

#endif  // RIME_T9_FILTER_H_
