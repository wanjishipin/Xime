#ifndef T9_SEGMENT_MODEL_H_
#define T9_SEGMENT_MODEL_H_

#include <cstdint>
#include <optional>
#include <string>
#include <utility>
#include <vector>

#include "t9_buffer.h"
#include "t9_pinyin_map.h"

namespace rime {

// 音节码的 RIME 无关表示（int32_t 与 rime::SyllableId 同型）：
// 段模型属纯算法层（T9_ALGO_ONLY_BUILD），不依赖 RIME 头文件；
// 处理器在边界与 rime::Code 互转（迭代器拷贝即可）。
using T9SyllableCode = std::vector<int32_t>;

// 段（Segment）：一个拼音音节的完整生命周期（设计文档 §3）。
//
// 回退的第一性原理单元是"段"而非"命令"：
//   unassigned --左选(LC)--> selected --右选(RC)--> committed(汉字)
//       ↑                         │                      │
//       └──────── undo ───────────┴──────── undo ────────┘
//
// 段 digits 独立管理（逐位删除），undo 其他段不触碰本段 digits
// （消除"prev_buf 快照过期"——已删除数字保持删除）。
struct T9Segment {
    enum Phase {
        kUnassigned,   // 纯数字，未选中
        kSelected,     // 已左选（拼音段）
        kCommitted,    // 已右选（汉字上屏）
    };

    SyllableOption option;   // 拼音 + digit_length（li/2、j/1）
    std::string digits;      // 对应数字（"54"/"5"），可被逐位删除
    Phase phase = kUnassigned;
    bool has_lc = false;     // 有左选记录（kSelected/kCommitted）
    bool has_rc = false;     // 有右选记录（kCommitted，partial-commit）

    bool IsActive() const {
        // 活跃 = 还有操作可撤销（未回退完）
        return phase != kUnassigned || !digits.empty();
    }
};

// 操作记录（LIFO 栈）。回退顺序 = 操作栈 LIFO（设计文档 §5）：
// 命令模式的经验表明文档回退顺序【故,b,2,gu,8,4,里,4,5】是
// "操作 LIFO + partial-commit RC 延后"的产物，不是纯段反向。
struct T9SegmentOp {
    enum Kind { kLC, kRC, kTailConsume, kSeparator } kind;
    int segment_index = -1;            // kLC：关联段（-1 = tail）
    std::vector<int> commit_indices;   // kRC：一次 commit 涉及的段
    // kRC/kTailConsume：执行后 digit_sequence 长度（P1 判定"digitSeq 未变"）。
    // 被置 -1 表示防连击（undo 一个 commit 后阻止下一 commit 连续 P1，
    // 等价命令模式 UpdateTopRightCommitRemaining(-1)）。
    int rc_digit_seq_len = -1;
    // kTailConsume：消费的 tail 位数 + 消费前 tail（undo 恢复）
    int tail_consumed = 0;
    std::string prev_tail;
    // kTailConsume：是否与 kRC 同一次右选（SyncRightCommit 同时产生 commit + tail 消费，
    // 如"价格/结婚后" commit j 段 + 消费 tail 字母）。undo 时联动撤销 RC+TC（一次右选整体回退）。
    bool linked_rc = false;
    // kRC：部分消费释放的数字（SyncRightCommit 截短提交段时记录）。
    // released_seg = 被截短的段（原 digits 尾部被释放），released_count = 释放位数。
    // undo RC 时回收：释放的数字按原顺序位于 tail 开头或其后段 digits 头部（被左选借用），
    // 收回段中使拼音段恢复完整（场景33：口号 kou hao 只消费 he 的 'h'，'3'（e）回收 → he[43]，
    // 随后 undo he → 删 3 → 删 4——'3' 是 he 的 'e'，不是独立数字）。
    int released_seg = -1;
    int released_count = 0;
    // kSeparator：分词键在 digitSeq 中的插入位置（分隔符位置）
    int pos = -1;

    static T9SegmentOp LC(int idx) { return {kLC, idx, {}}; }
    static T9SegmentOp RC(const std::vector<int>& idxs) { return {kRC, -1, idxs}; }
    static T9SegmentOp TailConsume(int n, std::string prev) {
        T9SegmentOp op;
        op.kind = kTailConsume;
        op.tail_consumed = n;
        op.prev_tail = std::move(prev);
        return op;
    }
    static T9SegmentOp Separator(int p) {
        T9SegmentOp op;
        op.kind = kSeparator;
        op.pos = p;
        return op;
    }
};

// 段模型回退（设计文档 §4/§5）。
//
// 与命令模式的本质差异：
// 1. 段状态显式（phase），undo 结果由段状态机决定 → 消除 prev_buf 快照过期
// 2. 段 digits 独立 → 删除保留内建
// 3. 左侧候选 = f(段状态) → 空闲态天然（HandleZombieRCState 可删除）
class T9UndoModel {
public:
    // ── 输入操作（由处理器调用）──

    // 输入数字：追加到尾数字（tail）
    void DigitPressed(char d);

    // 左选：从 tail 消费 option.digit_length 位作为段 digits，段 selected
    void LeftChoice(const SyllableOption& option);

    // 右选（partial/full commit）：把 segments 中从 first_idx 开始的连续
    // digit_length 匹配的段设为 committed（多词 commit 一次涉及多段）。
    // 简化版：commit 单个段（由调用方保证该段已 selected）。
    void RightCommit(int segment_index);

    // 多词 commit：一次右选 commit 多个段（如"价格 jia ge"commit j、g）。
    void RightCommitMulti(const std::vector<int>& indices);

    // 从未分配尾数字消费（如右选"咕 gu"从 unassigned '482' 消费 '48'）。
    // linked_rc=true：与 kRC 同一次右选（SyncRightCommit 同时 commit 段 + 消费 tail，
    // 如"价格/结婚后" commit j 段 + 消费 tail 字母）→ undo 时联动整体撤销。
    void ConsumeTail(int n, bool linked_rc = false);

    // 分词键（分隔符）：不消费数字，在 digitSeq 中标记插入位置（完整输入位置）。
    // 作为独立操作入栈（kSeparator），回退时按位置从后往前删除。
    void SeparatorPressed(int pos);

    // ── 集成辅助（T9Processor / T9RightCommitHandler 渐进集成用）──

    // 命令模式 RightCommit 完成后的段模型同步（差异推导）：
    // 对比 prev/new buffer，将被移除的 selections 对应段置 committed（RightCommitMulti），
    // 将被消费的 unassigned 前缀按 ConsumeTail 记录。
    // 调用时机：T9RightCommitHandler 三层策略执行后（ctx.undo_model 非空时）。
    // 前提：输入操作（DigitPressed/LeftChoice）已双写到本模型。
    void SyncRightCommit(const T9Buffer& prev_buf, const T9Buffer& new_buf);

    // 替换最后 selected 段（SELECTION 态筛选层替换，对应 ReplaceLastSelection）。
    // 仅替换 option（同位数替换），段 digits 不变。
    void ReplaceLastSelection(const SyllableOption& option);

    // 清空全部状态（对应 EnterIdle / ClearComposition 的 undo_model 同步）
    void Clear();

    // ── 右选序列的调频捕获（text+code，与段模型同生命周期）──
    // 每次右选 push 一条；码含声调真相（Phrase::code），供调频保留声调
    // （无声调拼音解析会命中轻声音节，如带声调方案 计划→ji/hua 轻声，导致丢声调）。
    // Clear() 一并清空；MemorizeEntry 全量消费、ForgetEntry 弹栈回滚。
    void PushCommitCapture(const std::string& text, const T9SyllableCode& code);
    // 全部捕获（按选择顺序）；MemorizeEntry 校验文本拼接/音节数后消费。
    const std::vector<std::pair<std::string, T9SyllableCode>>& commit_captures() const {
        return commit_captures_;
    }
    // 弹出最近一次右选捕获（撤销段时由 ForgetEntry 消费）；空栈返回 nullopt。
    std::optional<std::pair<std::string, T9SyllableCode>> PopLastCommitCapture();
    // 仅清空捕获（MemorizeEntry 消费后调用；Clear() 内部也调用）。
    void ClearCommitCaptures() { commit_captures_.clear(); }

    // 是否存在未撤销的 commit 操作（kRC/kTailConsume）——对应命令模式
    // HasPendingRightCommit（EnterIdle 时决定是否清空撤销栈）。
    bool HasPendingCommit() const;

    // 撤销的 commit 操作计数（kRC/kTailConsume 各计 1）——供 Kotlin 同步
    // t9PartialCommitTexts（上屏文本回退，对应命令模式 undone_right_commit_count）。
    // 读取后清零（GetAndConsume 模式）。
    int ConsumeUndoneCommitCount() {
        int n = undone_commit_count_;
        undone_commit_count_ = 0;
        return n;
    }

    // ── 回退 ──

    // 回退一步（一个 backspace）。两阶段状态机（设计文档 §5）：
    //   阶段 A（段撤销，优先）：按操作 LIFO undo 最后操作（含 partial commit 延后）
    //   阶段 B（元素删除）：undo 段后强制删除"位置 ≥ 该段起始"的未删元素
    //     （unassigned 数字 + 分词键，按完整输入位置从后往前），删空后回阶段 A
    bool Backspace();

    // ── 查询（供 UI / rime 层派生 T9Buffer）──

    const std::vector<T9Segment>& segments() const { return segments_; }
    const std::string& tail_digits() const { return tail_digits_; }
    const std::vector<int>& separator_positions() const { return separator_positions_; }
    int total_digits_entered() const { return total_digits_entered_; }
    // 空 = 无 tail、无分隔符且所有段非活跃（digits 删空且 phase == unassigned）
    bool IsEmpty() const {
        if (!tail_digits_.empty()) return false;
        if (!separator_positions_.empty()) return false;
        for (const auto& seg : segments_) {
            if (seg.IsActive()) return false;
        }
        return true;
    }

    // 左侧候选规则（产品决策：committed 段不参与候选）：
    //   SELECTION：存在 selected 段 → 高亮最后选中段（优先于 INPUT）
    //   INPUT：无 selected 但存在 unassigned 段/tail → 显示其候选
    //   IDLE：全部 committed 或已删 → 空闲态
    bool HasSelectedSegment() const;    // 有 selected 段（SELECTION 优先）
    bool HasSelectableDigits() const;   // 有 unassigned 段或 tail（INPUT）

    // 派生 T9Buffer（设计文档 §8：T9Buffer 由段模型派生，rime 交互层）。
    // 消费段 = 前缀连续（回退逆序保证：未消费段恒在已消费段之后）：
    //   digit_sequence = 各段 digits 顺序拼接 + tail
    //   consumed       = 首个 unassigned 段之前的 digits 总长
    //   selections     = 前缀中 selected 段
    T9Buffer ToBuffer() const;

private:
    bool UndoOp(const T9SegmentOp& op);
    bool DeleteLastActiveDigit();
    bool NeedsDefer(const T9SegmentOp& op) const;
    // 一次右选（SyncRightCommit）可能产生 kRC + kTailConsume(linked_rc) 两个 op：
    // 栈顶是 linked TC 且其下是 RC → 联动整体撤销（TC 恢复 tail + RC 段回 selected）。
    // 返回是否执行了联动（供 Backspace/AbbrevBackspace 入口调用）。
    bool TryUndoLinkedCommit();
    // 段 idx 是否为"第一个非空段"（前面所有段 digits 均空）。
    // merge_first/NeedsDefer 用其替代硬编码 idx==0——残留空段（回退后不物理清除）
    // 会使后续输入的新段 idx 偏移，硬编码 idx==0 不稳定（场景16 j=seg0 vs 场景17 j=seg1）。
    bool IsFirstNonEmptySegment(int idx) const;
    // 找 phase==kSelected 且匹配 option 的段；skip = 跳过前 skip 个匹配（相同拼音段
    // 区分，如 54482 左选 j、g、g、t、b 中两个 g：第 0 个 g → 段1、第 1 个 g → 段2）。
    int FindSegmentIndex(const SyllableOption& option, int skip = 0) const;
    // 阶段 B：删除"完整输入位置最后的未删元素"（unassigned 数字 或 分词键），
    // 且位置 ≥ delete_min_pos_（当前删除区起始）。返回是否有删除。
    bool DeleteLastActiveElement();
    // 删最后分词键（位置元素）：移除 separator_positions 末尾 + 栈中对应 kSeparator op。
    // 供简拼模式（AbbrevBackspace）在段全部撤销后按位置删除分词键。
    bool DeleteLastSeparator();
    // 新输入操作打断删除阶段（delete_mode_/delete_min_pos_ 重置）。
    // 修复（2026-08-05，设备实证）：回退完后 delete_mode_ 残留 true，跨输入序列
    // 未重置 → 第二次回退第一步误走阶段 B（删数字）而非阶段 A（undo 段）。
    void ResetDeletePhase();
    // undo 段后设置删除区最小位置（最早关联段的起始完整位置）；
    // kTailConsume/kSeparator 不改变删除区。
    void SetDeleteMinPosForOp(const T9SegmentOp& op);
    // 段 idx 的 digits 在 digitSeq 中的起始位置（前面段 digits 长度之和）
    int SegmentDigitStart(int idx) const;
    // digitSeq 位置 → 完整输入位置（加其前的分隔符数）
    int FullPos(int digit_pos) const;
    // 分隔符插入位置 → 完整输入位置
    int FullPosSeparator(int sep_pos) const;
    // 全部数字（段 digits + tail）的 digitSeq 总长
    int TotalDigitLength() const;
    // 简拼模式判定（2026-08-06，用户裁定"当前问题均为简拼/混合输入"）：
    //   有分词键（separator_positions 非空）→ 简拼（借助分词键构造）
    //   或 全部段都是字母段（digit_length <= 1）→ 简拼（借助左选字母构造）
    //   否则（存在完整拼音段，如 54482 系列）→ 全拼（段撤销优先）
    bool IsAbbrevMode() const;
    // 简拼模式回退（用户裁定"先删剩余数字"，设计文档 §5.4 5143 场景）：
    //   1. 栈顶 RC 优先撤销（含延后判定，与全拼一致）
    //   2. 删位置最后的未分配数字（tail / unassigned 段 digits）
    //   3. 撤销栈顶 selected 段（undo LC）
    //   4. 删最后分词键（段全部撤销后，按完整位置与剩余数字比较）
    bool AbbrevBackspace();
    // 位置最后的未分配数字（unassigned 段 digits 末位 或 tail 末位）的完整输入位置（-1 = 无）
    int LastUnassignedDigitFullPos() const;
    // 最后分词键的完整输入位置（-1 = 无）
    int LastSeparatorFullPos() const;

    std::vector<T9Segment> segments_;
    std::string tail_digits_;
    int total_digits_entered_ = 0;
    std::vector<T9SegmentOp> ops_;
    // 分词键插入位置（digitSeq 中，升序）
    std::vector<int> separator_positions_;
    // 阶段 B 标志：undo 段后为 true，强制删除阶段
    bool delete_mode_ = false;
    // 当前删除区最小完整位置（刚 undo 段的起始位置；-1 = 无限制）
    int delete_min_pos_ = -1;
    // 防连击标志（命令模式 UpdateTopRightCommitRemaining(-1) 的段模型版）：
    // 上次 Backspace 撤销了 commit 类操作（kRC/kTailConsume）→ 若栈顶仍是 commit 类，
    // 优先删未分配数字（防止连续撤销两个 commit）。撤销 LC 时清除，输入操作（ResetDeletePhase）清除。
    bool last_backspace_undid_commit_ = false;
    // 撤销的 commit 操作计数（kRC/kTailConsume 各计 1），ConsumeUndoneCommitCount 清零
    int undone_commit_count_ = 0;
    // 右选序列的调频捕获（text+code，按选择顺序）。生命周期见上方公开 API：
    // Clear() 一并清空（EnterIdle 会话边界），Memorize/Forget 由处理器经访问器驱动。
    std::vector<std::pair<std::string, T9SyllableCode>> commit_captures_;
};

}  // namespace rime

#endif  // T9_SEGMENT_MODEL_H_
