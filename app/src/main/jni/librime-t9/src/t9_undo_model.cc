#include "t9_undo_model.h"

#include <map>
#include <sstream>

#include "t9_log.h"

namespace rime {

// ── 段创建与消费 ──

void T9UndoModel::DigitPressed(char d) {
    // 数字输入只增长 tail（不作为独立回退 op）——
    // 回退删数字由 DeleteLastActiveElement 按"位置从后往前"执行
    // （设计文档 §5：数字维度 = 删未删元素）。
    tail_digits_.push_back(d);
    total_digits_entered_++;
    ResetDeletePhase();  // 新输入打断删除阶段
}

void T9UndoModel::LeftChoice(const SyllableOption& option) {
    // 从 tail 消费 option.digit_length 位作为段 digits
    int n = option.digit_length;
    std::string digits;
    if (static_cast<int>(tail_digits_.size()) >= n) {
        digits = tail_digits_.substr(0, static_cast<size_t>(n));
        tail_digits_.erase(0, static_cast<size_t>(n));
    } else {
        digits = tail_digits_;
        tail_digits_.clear();
    }
    T9Segment seg;
    seg.option = option;
    seg.digits = digits;
    seg.phase = T9Segment::kSelected;
    seg.has_lc = true;
    segments_.push_back(std::move(seg));
    ops_.push_back(T9SegmentOp::LC(static_cast<int>(segments_.size()) - 1));
    ResetDeletePhase();  // 新输入打断删除阶段
}

void T9UndoModel::RightCommit(int segment_index) {
    RightCommitMulti({segment_index});
}

void T9UndoModel::RightCommitMulti(const std::vector<int>& indices) {
    for (int idx : indices) {
        if (idx < 0 || idx >= static_cast<int>(segments_.size())) {
            continue;
        }
        segments_[idx].phase = T9Segment::kCommitted;
        segments_[idx].has_rc = true;
    }
    T9SegmentOp op = T9SegmentOp::RC(indices);
    op.rc_digit_seq_len = static_cast<int>(ToBuffer().digit_sequence.size());
    ops_.push_back(op);
    ResetDeletePhase();  // 新输入打断删除阶段
}

void T9UndoModel::ConsumeTail(int n, bool linked_rc) {
    if (n <= 0 || n > static_cast<int>(tail_digits_.size())) {
        return;
    }
    T9SegmentOp op = T9SegmentOp::TailConsume(n, tail_digits_);
    op.linked_rc = linked_rc;  // 与 kRC 同一次右选 → undo 联动整体撤销
    tail_digits_.erase(0, static_cast<size_t>(n));
    op.rc_digit_seq_len = static_cast<int>(ToBuffer().digit_sequence.size());
    ops_.push_back(op);
    ResetDeletePhase();  // 新输入打断删除阶段
}

void T9UndoModel::SeparatorPressed(int pos) {
    // 分词键：不消费数字，记录插入位置（digitSeq 中），入栈 kSeparator。
    // 回退时分词键作为"位置元素"参与阶段 B 删除（位置从后往前，见 DeleteLastActiveElement）。
    separator_positions_.push_back(pos);
    ops_.push_back(T9SegmentOp::Separator(pos));
    ResetDeletePhase();  // 新输入打断删除阶段
}

// ── 集成辅助 ──

void T9UndoModel::SyncRightCommit(const T9Buffer& prev_buf, const T9Buffer& new_buf) {
    // 命令模式 RightCommit 完成后的段模型同步（差异推导）：
    // ① 被 commit 段 = prev_buf.selections 中在 new_buf.selections 里消失的段
    //    （多词 commit 如"价格"一次消费 j、g 两段，差集同时覆盖）。
    // ② tail 消费 = prev_buf.unassigned() 前缀被消费的长度（如"咕"消费 '48'）。
    // 前提：DigitPressed/LeftChoice 已双写，段与命令模式 selections 顺序一致。
    // ⚠️ 已知风险（2026-08-05 设备实证，待修复）：命令模式 letterBuffer 策略
    //    重建 new_buf（selections 可能整体清空），差异推导会把多个段合并 commit，
    //    导致 undo 第一步撤销整组而非最上层单个 commit。
    T9LOG("[UndoModel] SyncRightCommit ENTER: prev.digitSeq='%s' sel=%zu consumed=%d unassigned='%s'",
          prev_buf.digit_sequence.c_str(), prev_buf.selections.size(),
          prev_buf.consumed_count, prev_buf.unassigned().c_str());
    T9LOG("[UndoModel] SyncRightCommit:        new.digitSeq='%s' sel=%zu consumed=%d unassigned='%s'",
          new_buf.digit_sequence.c_str(), new_buf.selections.size(),
          new_buf.consumed_count, new_buf.unassigned().c_str());
    // 2026-08-06 修复（设备实证，场景30/两个单字/价格+公）：removed 查找必须"一一对应"——
    // 每个 new selection 只能匹配一个 prev selection。旧实现"存在即匹配"：相同拼音段
    // （左选 j、g、g 中的两个 g）时，prev 的两个 'g' 都匹配到 new 的同一个 'g' → 中间单字
    // 的 commit 被误判"仍存在"而漏记（感/广 commitIndices=[]），或两字词组只 commit 首段
    // （价格只 commit j，g 段丢失）→ 回退段错位、次数减少。
    std::vector<bool> matched(new_buf.selections.size(), false);
    std::vector<int> indices;
    // 相同拼音段区分：记录每个 (pinyin,len) 已 removed 的序号 → FindSegmentIndex(sel, skip)
    // （场景32 九宫格 j g g：两个 g 分别对应段1、段2，旧实现都映射段1 → commitIndices=[0,1,1]）。
    std::map<std::pair<std::string, int>, int> removed_seq;
    for (size_t i = 0; i < prev_buf.selections.size(); ++i) {
        const auto& sel = prev_buf.selections[i];
        bool present = false;
        for (size_t j = 0; j < new_buf.selections.size(); ++j) {
            if (!matched[j] && new_buf.selections[j].pinyin == sel.pinyin &&
                new_buf.selections[j].digit_length == sel.digit_length) {
                matched[j] = true;
                present = true;
                break;
            }
        }
        if (!present) {
            auto key = std::make_pair(sel.pinyin, sel.digit_length);
            int skip = removed_seq[key]++;
            int idx = FindSegmentIndex(sel, skip);
            T9LOG("[UndoModel] SyncRightCommit: removed sel[%zu]='%s'(%d) skip=%d -> segIdx=%d",
                  i, sel.pinyin.c_str(), sel.digit_length, skip, idx);
            if (idx >= 0) indices.push_back(idx);
        }
    }
    const std::string& old_un = prev_buf.unassigned();
    const std::string& new_un = new_buf.unassigned();
    int tail_consumed = static_cast<int>(old_un.size()) - static_cast<int>(new_un.size());
    if (tail_consumed < 0) tail_consumed = 0;
    // 命令模式释放数字（new_un 比 old_un 长）：本次右选只消费了提交段的部分数字。
    // 场景33（5143 k、he、口号 kou hao）：口号只消费 '5'+'4'（k、h），把 he 段的 '3'（e）
    // 释放回未分配区（new.unassigned='3'）。若把 he[43] 整体 commit，'3' 被困在 he 段 →
    // 后续左选 d 拿不到数字（d 段 digits=''）→ 回退时 SELECTION(d) 无候选数字 → 左侧候选区
    // 白屏 + ⌫1 错删分词键（设备实证 2026-08-06）。
    // 修复：从最后一个提交段逆序截短 digits，释放的数字放回 tail 开头（保持完整输入位置）
    // → he[43] → he[4]，'3' 回 tail → 左选 d 从 tail 正常消费 '3' → d[3]。
    // 与 tail_consumed 互斥（一个增一个减，不会同时为正）。
    int released_total = static_cast<int>(new_un.size()) - static_cast<int>(old_un.size());
    int released = std::max(released_total, 0);
    if (!indices.empty()) RightCommitMulti(indices);
    if (released > 0 && !indices.empty()) {
        for (size_t i = indices.size(); i-- > 0 && released > 0;) {
            int idx = indices[i];
            int cut = std::min(released, static_cast<int>(segments_[idx].digits.size()));
            if (cut <= 0) continue;
            released -= cut;
            std::string rel = segments_[idx].digits.substr(
                segments_[idx].digits.size() - static_cast<size_t>(cut));
            segments_[idx].digits.resize(
                segments_[idx].digits.size() - static_cast<size_t>(cut));
            tail_digits_ = rel + tail_digits_;
            T9LOG("[UndoModel] SyncRightCommit: release %d digit(s) '%s' from segIdx=%d -> tail='%s'",
                  cut, rel.c_str(), idx, tail_digits_.c_str());
        }
        // 记录到 RC op：undo 口号 时回收释放的数字（he[4] → he[43] 恢复完整拼音段）。
        // 释放区是消费区的尾部后缀 → 只涉及最后一个提交段（indices.back()）。
        ops_.back().released_seg = indices.back();
        ops_.back().released_count = released_total;
    }
    std::ostringstream oss;
    for (size_t i = 0; i < indices.size(); ++i) {
        if (i) oss << ',';
        oss << indices[i];
    }
    T9LOG("[UndoModel] SyncRightCommit EXIT: commitIndices=[%s] tailConsumed=%d released=%d segCount=%zu tail='%s'",
          oss.str().c_str(), tail_consumed, std::max(released_total, 0),
          segments_.size(), tail_digits_.c_str());
    if (tail_consumed > 0) ConsumeTail(tail_consumed, !indices.empty());
}

void T9UndoModel::ReplaceLastSelection(const SyllableOption& option) {
    // SELECTION 态筛选层替换：仅替换最后一个 selected 段的 option（同位数），
    // 段 digits 不变（与命令模式 ReplaceLastSelection 同位数替换语义一致）。
    for (size_t i = segments_.size(); i-- > 0;) {
        auto& seg = segments_[i];
        if (seg.phase == T9Segment::kSelected) {
            seg.option = option;
            return;
        }
    }
}

void T9UndoModel::Clear() {
    segments_.clear();
    tail_digits_.clear();
    total_digits_entered_ = 0;
    ops_.clear();
    separator_positions_.clear();
    delete_mode_ = false;
    delete_min_pos_ = -1;
    last_backspace_undid_commit_ = false;
    // 右选序列的调频捕获随段模型一并清空（EnterIdle 会话边界）。
    commit_captures_.clear();
}

void T9UndoModel::PushCommitCapture(const std::string& text, const T9SyllableCode& code) {
    commit_captures_.emplace_back(text, code);
}

std::optional<std::pair<std::string, T9SyllableCode>> T9UndoModel::PopLastCommitCapture() {
    if (commit_captures_.empty())
        return std::nullopt;
    auto capture = std::move(commit_captures_.back());
    commit_captures_.pop_back();
    return capture;
}

bool T9UndoModel::HasPendingCommit() const {
    for (const auto& op : ops_) {
        if (op.kind == T9SegmentOp::kRC ||
            op.kind == T9SegmentOp::kTailConsume) {
            return true;
        }
    }
    return false;
}

// ── 回退（设计文档 §5：两阶段状态机）──
//
// 阶段 A（段撤销，优先）：按操作 LIFO undo 最后段操作（LC/RC/TC），含 partial commit 延后
// 阶段 B（元素删除）：undo 段后强制删除"完整输入位置 ≥ 该段起始"的未删元素
//   （unassigned 数字 + 分词键，位置从后往前），删空后回阶段 A。
// 分词键（kSeparator）不通过阶段 A undo，仅作为位置元素在阶段 B 删除。
bool T9UndoModel::Backspace() {
    // 一次右选（SyncRightCommit）产生 kRC + linked kTailConsume → 整体撤销
    // （场景16/17/18：undo"价格/结婚后"一次 backspace 完成 j 回 selected + tail 恢复）。
    if (TryUndoLinkedCommit()) return true;
    // 简拼/混合输入（分词键或全字母段）：用户裁定"先删剩余数字"（2026-08-06），
    // 回退按"位置删除优先 + RC 优先 + 段撤销 + 分词键最后"（设计文档 §5.4 5143 场景）。
    // 全拼序列（存在完整拼音段，如 54482 系列）保持"段撤销优先"两阶段状态机。
    if (IsAbbrevMode()) {
        return AbbrevBackspace();
    }
    // 阶段 B：元素删除（undo 段后进入）
    if (delete_mode_) {
        if (DeleteLastActiveElement()) {
            T9LOG("[UndoModel] Backspace B: delete element, tail='%s' seps=%zu min=%d",
                  tail_digits_.c_str(), separator_positions_.size(), delete_min_pos_);
            return true;
        }
        delete_mode_ = false;
    }
    // 无段操作 或 栈顶是分词键 → 位置删除（阶段 B 语义，分词键只在 B 删除）
    if (ops_.empty() || ops_.back().kind == T9SegmentOp::kSeparator) {
        if (DeleteLastActiveElement()) {
            T9LOG("[UndoModel] Backspace B(no-op): delete element, tail='%s' seps=%zu",
                  tail_digits_.c_str(), separator_positions_.size());
            return true;
        }
        return false;
    }
    // 阶段 A：段撤销（操作 LIFO，含 partial commit 延后）
    T9SegmentOp op = ops_.back();
    if (op.kind == T9SegmentOp::kRC && NeedsDefer(op)) {
        T9LOG("[UndoModel] Backspace A: RC defer, undo below op");
        for (size_t i = ops_.size() - 1; i-- > 0;) {
            const T9SegmentOp& cand = ops_[i];
            if (!(cand.kind == T9SegmentOp::kRC && NeedsDefer(cand))) {
                T9SegmentOp target = cand;
                ops_.erase(ops_.begin() + static_cast<ptrdiff_t>(i));
                delete_mode_ = true;
                SetDeleteMinPosForOp(target);
                // 撤销的是 LC：防连击 flag 保持（已撤销过 commit 的状态不变）
                T9LOG("[UndoModel] Backspace A: undo below kind=%d segIdx=%d min=%d",
                      static_cast<int>(target.kind), target.segment_index, delete_min_pos_);
                return UndoOp(target);
            }
        }
        return false;  // 无可撤销（理论上不会）
    }
    ops_.pop_back();
    if (op.kind == T9SegmentOp::kRC ||
        op.kind == T9SegmentOp::kTailConsume) {
        last_backspace_undid_commit_ = true;  // 撤销 commit 类 → 防连击 + 后续 RC 可延后
    }
    delete_mode_ = true;
    SetDeleteMinPosForOp(op);
    T9LOG("[UndoModel] Backspace A: undo top kind=%d segIdx=%d min=%d",
          static_cast<int>(op.kind), op.segment_index, delete_min_pos_);
    return UndoOp(op);
}

// ── 简拼模式回退（2026-08-06，用户裁定"先删剩余数字"）──
//
// 问题场景（文档 L234-415）均为"借助分词键或左选字母的简拼/混合输入"：
//   - 场景1（543 k,h）：预期 删 3 → undo h → 删 4 → undo k → 删 5
//   - 场景2（5'43 k）：预期 删 3 → 删 4 → undo k → 删 1(分词键) → 删 5
//   - 5143 右选（拷/跨国）：预期 undo RC 先（有分词键 → 不延后）
//   - 九/股（j/g 简拼，无分词键）：undo 股 → 删 2,8,4（防连击）→ undo LC(g) → 删 4 → undo 九 → 删 5
// 而完整拼音序列（54482 系列）保持"段撤销优先"（两阶段状态机）。
//
// 优先级（从高到低）：
//   1. 防连击：上次撤销过 commit 类（RC/TC）且栈顶仍是 commit 类 → 先删未分配数字
//   2. 栈顶 RC/TC → 撤销（延后仅限"无分词键"序列，见 NeedsDefer）
//   3. 位置最后的未分配数字（完整位置 >= 分词键位置）→ 删除
//   4. 位置最后是分词键且存在 selected 段 → 撤销段（拼音优先于分隔符）
//   5. 最后分词键 → 删除（无 selected 段时）
//   6. 防御：撤销栈顶 LC
bool T9UndoModel::AbbrevBackspace() {
    bool stack_has_commit = !ops_.empty() &&
        (ops_.back().kind == T9SegmentOp::kRC ||
         ops_.back().kind == T9SegmentOp::kTailConsume);
    // 1. 防连击：刚撤销过 commit 且栈顶仍是 commit → 先删未分配数字
    if (stack_has_commit && last_backspace_undid_commit_) {
        int digit_fp = LastUnassignedDigitFullPos();
        int sep_fp = LastSeparatorFullPos();
        if (digit_fp >= 0 && digit_fp >= sep_fp) {
            T9LOG("[UndoModel] AbbrevBackspace: anti-repeat, delete digit, tail='%s'",
                  tail_digits_.c_str());
            return DeleteLastActiveDigit();
        }
        // 无未分配数字可删 → 落回正常处理（撤销/延后栈顶 commit）
    }
    // 2. 栈顶 RC/TC 优先撤销
    if (stack_has_commit) {
        T9SegmentOp op = ops_.back();
        if (op.kind == T9SegmentOp::kRC && NeedsDefer(op)) {
            for (size_t i = ops_.size() - 1; i-- > 0;) {
                const T9SegmentOp& cand = ops_[i];
                if (!(cand.kind == T9SegmentOp::kRC && NeedsDefer(cand))) {
                    T9SegmentOp target = cand;
                    ops_.erase(ops_.begin() + static_cast<ptrdiff_t>(i));
                    // 撤销的是 LC：防连击 flag 保持（九/股 bs5 undo LC(g) 后 bs6 仍删数字）
                    T9LOG("[UndoModel] AbbrevBackspace: RC defer, undo below kind=%d segIdx=%d",
                          static_cast<int>(target.kind), target.segment_index);
                    return UndoOp(target);
                }
            }
            return false;
        }
        ops_.pop_back();
        last_backspace_undid_commit_ = true;  // 撤销 commit 类 → 防连击
        T9LOG("[UndoModel] AbbrevBackspace: undo commit kind=%d", static_cast<int>(op.kind));
        return UndoOp(op);
    }
    // 3-5. 位置优先决策
    int digit_fp = LastUnassignedDigitFullPos();
    int sep_fp = LastSeparatorFullPos();
    if (digit_fp >= 0 && digit_fp >= sep_fp) {
        // 位置最后的未分配数字（tail / unassigned 段 digits）
        T9LOG("[UndoModel] AbbrevBackspace: delete digit, tail='%s'", tail_digits_.c_str());
        return DeleteLastActiveDigit();
    }
    if (sep_fp >= 0) {
        // 位置最后是分词键：存在 selected 段（可撤销）→ 先撤销段（拼音优先于分隔符）
        for (size_t i = segments_.size(); i-- > 0;) {
            auto& seg = segments_[i];
            if (seg.phase == T9Segment::kSelected && !seg.digits.empty()) {
                for (auto it = ops_.rbegin(); it != ops_.rend(); ++it) {
                    if (it->kind == T9SegmentOp::kLC &&
                        it->segment_index == static_cast<int>(i)) {
                        T9SegmentOp op = *it;
                        ops_.erase((it + 1).base());
                        // 撤销的是 LC：防连击 flag 保持
                        T9LOG("[UndoModel] AbbrevBackspace: undo LC segIdx=%zu", i);
                        return UndoOp(op);
                    }
                }
            }
        }
        T9LOG("[UndoModel] AbbrevBackspace: delete separator");
        return DeleteLastSeparator();
    }
    // 6. 防御：撤销栈顶 selected 段
    if (!ops_.empty() && ops_.back().kind == T9SegmentOp::kLC) {
        T9SegmentOp op = ops_.back();
        ops_.pop_back();
        // 撤销的是 LC：防连击 flag 保持
        T9LOG("[UndoModel] AbbrevBackspace: undo LC segIdx=%d", op.segment_index);
        return UndoOp(op);
    }
    return false;
}

bool T9UndoModel::IsAbbrevMode() const {
    // 有分词键（借助分词键构造的简拼/混合输入）
    if (!separator_positions_.empty()) return true;
    // 全部段都是字母段（digit_length <= 1，借助左选字母构造的纯简拼）
    for (const auto& seg : segments_) {
        if (seg.option.digit_length >= 2) return false;
    }
    return true;
}

bool T9UndoModel::TryUndoLinkedCommit() {
    // 一次右选（SyncRightCommit）同时 commit 段（kRC）+ 消费 tail（linked kTailConsume）
    // 时，undo 应整体撤销（场景16/17/18：undo"价格/结婚后"一次 backspace 完成）。
    // 顺序：先 undo TC（tail 恢复 prev_tail 前缀）→ 再 undo RC（段回 selected；
    // 此时 tail 非空 → merge_first 不触发，拼音保留，符合用户预期"j g hua"）。
    // 防连击：刚撤销过 commit 类（last_backspace_undid_commit_）且还有未分配数字 → 先删数字
    // （场景18：undo 沽 后 ⌫2 应防连击删 2，而非联动 undo 价格；删完数字后才联动）。
    if (ops_.size() < 2) return false;
    const T9SegmentOp& top = ops_.back();
    if (top.kind != T9SegmentOp::kTailConsume || !top.linked_rc) return false;
    const T9SegmentOp& below = ops_[ops_.size() - 2];
    if (below.kind != T9SegmentOp::kRC) return false;
    if (last_backspace_undid_commit_ && LastUnassignedDigitFullPos() >= 0) {
        return false;  // 防连击：先删未分配数字
    }
    T9SegmentOp tc = top;
    T9SegmentOp rc = below;
    ops_.pop_back();
    ops_.pop_back();
    last_backspace_undid_commit_ = true;  // 撤销 commit 类 → 防连击
    T9LOG("[UndoModel] TryUndoLinkedCommit: undo RC+TC (linked right-commit)");
    UndoOp(tc);  // tail 恢复（先，保证 undo RC 时 tail 非空 → 非 merge_first）
    UndoOp(rc);
    return true;
}

bool T9UndoModel::IsFirstNonEmptySegment(int idx) const {
    // 段 idx 之前的所有段 digits 均为空（残留空段/已删空段）→ idx 是"逻辑首段"。
    // merge_first/NeedsDefer 用它替代硬编码 idx==0，避免残留空段导致段索引偏移
    // （场景16 j=seg0 vs 场景17 残留空段后 j=seg1，commit_indices 的 idx 不稳定）。
    for (int i = 0; i < idx; ++i) {
        if (!segments_[i].digits.empty()) return false;
    }
    return true;
}

int T9UndoModel::LastUnassignedDigitFullPos() const {
    // 位置最后的未分配数字（unassigned 段 digits 末位 或 tail 末位）的完整输入位置。
    for (size_t i = segments_.size(); i-- > 0;) {
        auto& seg = segments_[i];
        if (seg.phase == T9Segment::kUnassigned && !seg.digits.empty()) {
            int dpos = SegmentDigitStart(static_cast<int>(i)) +
                       static_cast<int>(seg.digits.size()) - 1;
            return FullPos(dpos);
        }
    }
    if (!tail_digits_.empty()) {
        return FullPos(TotalDigitLength() - 1);
    }
    return -1;
}

int T9UndoModel::LastSeparatorFullPos() const {
    // 最后分词键的完整输入位置（-1 = 无）。
    if (separator_positions_.empty()) return -1;
    return FullPosSeparator(separator_positions_.back());
}

bool T9UndoModel::UndoOp(const T9SegmentOp& op) {
    switch (op.kind) {
        case T9SegmentOp::kLC: {
            auto& seg = segments_[op.segment_index];
            seg.phase = T9Segment::kUnassigned;
            seg.has_lc = false;
            return true;
        }
        case T9SegmentOp::kRC: {
            // 回收右选释放的数字（部分消费，场景33）：
            // SyncRightCommit 处理"口号 kou hao"部分消费时把 he[43] 截为 he[4]、'3' 释放到 tail；
            // 左选 d 从 tail 借用 '3' → d[3]。undo 口号 时必须把 '3' 收回 he（he[4] → he[43]），
            // 使 he 恢复完整拼音段——随后 undo he → 删 3 → 删 4，'3' 作为 he 的 'e' 处理
            // （用户裁定 543 = k + he，'3' 属于 he，不是 d 的独立数字；2026-08-06）。
            // 释放的数字按原顺序位于 tail 开头（未借用）或 released_seg 之后第一个非空段
            // digits 头部（被左选借用，如 d[3]），逐个取回追加到 released_seg。
            if (op.released_count > 0 && op.released_seg >= 0 &&
                op.released_seg < static_cast<int>(segments_.size())) {
                for (int ri = 0; ri < op.released_count; ++ri) {
                    char d = 0;
                    if (!tail_digits_.empty()) {
                        d = tail_digits_.front();
                        tail_digits_.erase(0, 1);
                    } else {
                        for (size_t s = static_cast<size_t>(op.released_seg) + 1;
                             s < segments_.size(); ++s) {
                            if (!segments_[s].digits.empty()) {
                                d = segments_[s].digits.front();
                                segments_[s].digits.erase(0, 1);
                                break;
                            }
                        }
                    }
                    if (d == 0) break;  // 防御：无可回收（理论上不会）
                    segments_[op.released_seg].digits.push_back(d);
                }
            }
            // 修复（2026-08-11，场景34 设备实证）：回收不完整（释放的数字已被删除，
            // 如场景34 的 '26' 在 undo RC 前被 ⌫2/⌫3 删掉）时，被截短段的 digits 与
            // option.digit_length 失配（tiao[4] 只剩 '84'）。此时段无法恢复完整拼音段，
            // 若回 selected 会残留错误 option（预编辑"洮tiao/tian"而非"洮ti"）。
            // 修复：该段回 unassigned（合并撤销 LC，与 merge_first 同语义），
            // digits 保留待删 → 派生 unassigned 供 RIME 正确显示剩余数字。
            bool release_incomplete = op.released_count > 0 && op.released_seg >= 0 &&
                op.released_seg < static_cast<int>(segments_.size()) &&
                static_cast<int>(segments_[op.released_seg].digits.size()) !=
                segments_[op.released_seg].option.digit_length;
            // 2026-08-05 修复：仅单段 commit（size==1）的最早段（idx==0）合并撤销 LC
            // （场景13 undo 里：li 段回 unassigned，数字回退）。
            // 多段 commit（RC({0,1}) 如"价格"）是整体撤销：所有段回 selected（拼音），
            // 其 LC 稍后单独撤销。
            // 2026-08-06 修复：merge_first 条件 = 单段 commit + 被 commit 段是"完整拼音段"
            // （digit_length >= 2，如 li/ji）+ 逻辑首段（前面无非空段）+ 其后无活跃段 + tail 空。
            //   - 场景13 undo 里（li 2 位完整拼音）：合并撤销 LC，li 回 unassigned（9 次）。
            //   - 九/股 undo 九、场景30 undo 九（j 1 位字母段）：**不合并**，j 回 selected（拼音
            //     保留），用户随后单独 undo LC(j)（删 5）——对标主流输入法（九/股 9 次、场景30 11 次，
            //     2026-08-06 用户裁定，之前九/股 8 次为错误）。
            //   - 场景16/17/18 undo 价格/结婚后（tail 有数字）：非合并，段回 selected。
            bool merge_first =
                op.commit_indices.size() == 1 &&
                tail_digits_.empty() &&
                IsFirstNonEmptySegment(op.commit_indices[0]) &&
                segments_[op.commit_indices[0]].option.digit_length >= 2;
            if (merge_first) {
                for (size_t i = 0; i < segments_.size(); ++i) {
                    if (static_cast<int>(i) != op.commit_indices[0] &&
                        segments_[i].IsActive()) {
                        merge_first = false;
                        break;
                    }
                }
            }
            for (size_t i = 0; i < op.commit_indices.size(); ++i) {
                auto& seg = segments_[op.commit_indices[i]];
                // 场景34：release_incomplete（回收失败，digits 与 option 失配）时
                // 该段回 unassigned（合并撤销 LC），与 merge_first 同语义——
                // 否则残留 option='tiao'(4) 与 digits='84' 失配，预编辑错误显示
                // "洮tiao/tian"而非"洮ti"（设备实证 2026-08-11）。
                bool merge_lc = merge_first ||
                    (release_incomplete && static_cast<int>(op.commit_indices[i]) ==
                        op.released_seg);
                if (merge_lc && seg.has_lc) {
                    // 被替换段（selections[0] = 最早段）：合并撤销 LC，段回 unassigned。
                    // 同时移除 ops_ 中该段的 LC op（对应命令模式 merge_lc 的 Pop）。
                    for (size_t j = ops_.size(); j-- > 0;) {
                        if (ops_[j].kind == T9SegmentOp::kLC &&
                            ops_[j].segment_index == op.commit_indices[i]) {
                            ops_.erase(ops_.begin() + static_cast<ptrdiff_t>(j));
                            break;
                        }
                    }
                    seg.phase = T9Segment::kUnassigned;
                    seg.has_lc = false;
                } else if (seg.has_lc) {
                    // 其他段：回 selected（其 LC 稍后单独撤销）
                    seg.phase = T9Segment::kSelected;
                } else if (seg.phase == T9Segment::kCommitted) {
                    // 无 LC 记录（防御）：回 selected
                    seg.phase = T9Segment::kSelected;
                }
                seg.has_rc = false;
            }
            undone_commit_count_++;  // 撤销一个 commit 操作（供 Kotlin 同步）
            return true;
        }
        case T9SegmentOp::kTailConsume: {
            // 恢复被消费的 tail 数字到开头。
            // 修复（2026-08-09）：undo TC 前若存在 undo LC 残留的 unassigned 段，
            // 其数字一并并入 tail；段数字是 prev_tail 剩余部分的后缀（原位置在被
            // 恢复数字之后）→ 顺序 = consumed + 段数字，否则段数字 + consumed，
            // 避免派生 unassigned 顺序错乱（'34' 而非 '43'，预编辑 "k di" 异常）。
            std::string consumed =
                op.prev_tail.substr(0, static_cast<size_t>(op.tail_consumed));
            std::string remaining =
                op.prev_tail.substr(static_cast<size_t>(op.tail_consumed));
            std::string seg_digits;
            for (auto& seg : segments_) {
                if (seg.phase == T9Segment::kUnassigned && !seg.digits.empty()) {
                    seg_digits += seg.digits;
                    seg.digits.clear();
                }
            }
            std::string restored;
            if (remaining.size() >= seg_digits.size() &&
                remaining.compare(remaining.size() - seg_digits.size(),
                                  seg_digits.size(), seg_digits) == 0) {
                restored = consumed + seg_digits;
            } else {
                restored = seg_digits + consumed;
            }
            tail_digits_ = restored + tail_digits_;
            undone_commit_count_++;  // 撤销一个 commit 操作（供 Kotlin 同步）
            return true;
        }
        case T9SegmentOp::kSeparator: {
            // 移除分隔符位置（分词键字符从完整输入序列消失）。
            // 注：阶段 B 删除分词键时已移除，此处防御（阶段 A 不应 undo kSeparator）。
            for (size_t i = separator_positions_.size(); i-- > 0;) {
                if (separator_positions_[i] == op.pos) {
                    separator_positions_.erase(
                        separator_positions_.begin() + static_cast<ptrdiff_t>(i));
                    break;
                }
            }
            return true;
        }
    }
    return false;
}

bool T9UndoModel::DeleteLastActiveDigit() {
    // 数字维度（设计文档 §4）：删"最后 unassigned 段"digits 最后一位；无则删 tail。
    // 仅删 unassigned 段（P2 前提 = unassigned 非空）——selected/committed 段
    // 必须先 undo LC/RC 回 unassigned 才能删数字。
    for (size_t i = segments_.size(); i-- > 0;) {
        auto& seg = segments_[i];
        if (!seg.digits.empty() && seg.phase == T9Segment::kUnassigned) {
            seg.digits.pop_back();
            return true;
        }
    }
    if (!tail_digits_.empty()) {
        tail_digits_.pop_back();
        return true;
    }
    return false;
}

bool T9UndoModel::NeedsDefer(const T9SegmentOp& op) const {
    // partial-commit RC 延后规则（对应命令模式 P34 跳过逻辑）：
    // 栈顶 RC 延后当且仅当存在"最后一个 selected 段"（未被本 RC commit）——此时先撤销
    // 该段的 LC，避免 undo 本 RC 后多个段同时 selected（拼音/数字重叠显示）。
    //   场景13：RC(里) commit 段0，最后 selected 段=b（段2，∉{0}）→ 延后 undo b。
    //   场景30：RC(感) commit 段1，最后 selected 段=g（段2，∉{1}）→ 延后 undo g(段2)，
    //           再 undo 感（此时段2 已删空，undo 感 后只剩一个 g）。
    //   九/股：RC(九) commit 段0，最后 selected 段=g（段1，∉{0}）→ 延后 undo g。
    //   undo 感/九 时其下段已删空（无 selected 非空段）→ 不延后，直接撤销 RC。
    // 2026-08-05 修复：多段 commit（RC({0,1}) 如"价格"）整体撤销，不延后。
    // 2026-08-06 修复（场景29，设备实证）：本回退序列**尚未撤销过任何 commit**
    // （last_backspace_undid_commit_=false）时，栈顶 RC 是"最后产生的右选"，直接撤销
    // （操作 LIFO）。九/股、场景13/30 延后的 RC 都发生在已撤销过更晚右选之后。
    // 2026-08-06 修复 #2（场景30）：延后目标从"紧邻 below LC"改为"最后 selected 段"——
    // 场景30 的段2 g 的 LC 位于中间 RC（九）之下，紧邻 below 判定找不到。
    // 2026-08-06 修复 #3（场景31）：**去掉"多段 commit 不延后"限制**——"价格"（{0,1}）两字词组
    // 也延后：undo 共同 后段2、3 selected，undo 价格 前先撤销段3 t、段2 g（最后 selected 段
    // ∉ {0,1}）。仅当无"未被本 RC commit 的 selected 段"（如所有段已删空或全被本 RC commit）才
    // 整体撤销 RC（场景13 价格 {0,1}：段0、1 committed 且无其他 selected → 不延后）。
    if (ops_.size() < 2) return false;
    if (!last_backspace_undid_commit_) return false;  // 尚未撤销过 commit → 不延后
    if (!separator_positions_.empty()) return false;  // 分词键序列（5143）不延后
    for (size_t i = segments_.size(); i-- > 0;) {
        const auto& seg = segments_[i];
        if (seg.phase == T9Segment::kSelected && !seg.digits.empty()) {
            for (int idx : op.commit_indices) {
                if (idx == static_cast<int>(i)) return false;  // 是本 RC commit 的段 → 不延后
            }
            return true;  // 存在其他 selected 段 → 延后
        }
    }
    return false;  // 无 selected 非空段 → 不延后
}

int T9UndoModel::FindSegmentIndex(const SyllableOption& option, int skip) const {
    // 被 commit 段 = 活跃段（phase==kSelected）中第 skip+1 个匹配段。
    // 相同拼音段（两个 g）时按 skip 区分：第 0 个 g → 段1、第 1 个 g → 段2。
    // 与命令模式 prev_buf.selections 的"活跃段顺序"一致。
    int count = 0;
    for (size_t i = 0; i < segments_.size(); ++i) {
        if (segments_[i].phase == T9Segment::kSelected &&
            segments_[i].option.pinyin == option.pinyin &&
            segments_[i].option.digit_length == option.digit_length) {
            if (count == skip) return static_cast<int>(i);
            ++count;
        }
    }
    return -1;
}

// ── 阶段 B：位置删除（设计文档 §5.2 / §7 / §8）──

bool T9UndoModel::DeleteLastActiveElement() {
    // 删除"完整输入位置最后的未删元素"（unassigned 数字 或 分词键），
    // 且位置 ≥ delete_min_pos_（当前删除区起始，undo 段后设置；-1 = 无限制）。
    // 位置 = 完整输入位置（digitSeq 位置 + 其前的分隔符数），保证分词键
    // （如 5143 的 '1'，位置 1）在数字 '5'（位置 0）之前删除。
    int best_full = delete_min_pos_;
    int best_type = -1;   // 0=段 digits, 1=分词键, 2=tail
    int best_seg = -1;
    size_t best_sep = 0;

    // 候选 1：最后 unassigned 段 digits 最后一位
    for (size_t i = segments_.size(); i-- > 0;) {
        auto& seg = segments_[i];
        if (seg.phase == T9Segment::kUnassigned && !seg.digits.empty()) {
            int start = SegmentDigitStart(static_cast<int>(i));
            int dpos = start + static_cast<int>(seg.digits.size()) - 1;
            int fp = FullPos(dpos);
            if (fp >= best_full) {
                best_full = fp;
                best_type = 0;
                best_seg = static_cast<int>(i);
            }
            break;  // 反向第一个 unassigned 段即位置最后的 unassigned 段
        }
    }
    // 候选 2：tail 最后一位（tail 恒在全部段 digits 之后）
    if (!tail_digits_.empty()) {
        int dpos = TotalDigitLength() - 1;
        int fp = FullPos(dpos);
        if (fp >= best_full) {
            best_full = fp;
            best_type = 2;
        }
    }
    // 候选 3：最后分词键（完整输入位置）
    if (!separator_positions_.empty()) {
        int sp = separator_positions_.back();
        int fp = FullPosSeparator(sp);
        if (fp >= best_full) {
            best_full = fp;
            best_type = 1;
            best_sep = separator_positions_.size() - 1;
        }
    }

    if (best_type == 0) {
        segments_[best_seg].digits.pop_back();
        return true;
    }
    if (best_type == 1) {
        return DeleteLastSeparator();
    }
    if (best_type == 2) {
        tail_digits_.pop_back();
        return true;
    }
    return false;
}

bool T9UndoModel::DeleteLastSeparator() {
    // 删位置最后的未删分词键：移除 separator_positions 末尾 + 栈中对应 kSeparator op。
    // 供阶段 B（DeleteLastActiveElement 候选 3）与简拼模式（AbbrevBackspace 步骤 4）复用。
    if (separator_positions_.empty()) return false;
    int sp = separator_positions_.back();
    separator_positions_.pop_back();
    for (auto it = ops_.rbegin(); it != ops_.rend(); ++it) {
        if (it->kind == T9SegmentOp::kSeparator && it->pos == sp) {
            ops_.erase((it + 1).base());
            break;
        }
    }
    return true;
}

void T9UndoModel::ResetDeletePhase() {
    // 新输入操作打断删除阶段：回退的内部瞬时状态（阶段 B 标志 + 删除区起始）不跨输入序列。
    delete_mode_ = false;
    delete_min_pos_ = -1;
    last_backspace_undid_commit_ = false;  // 防连击状态也不跨输入序列
}

void T9UndoModel::SetDeleteMinPosForOp(const T9SegmentOp& op) {
    // undo 段后，删除区最小位置 = 最早关联段的起始完整位置。
    // kTailConsume/kSeparator 不涉及段，不改变删除区（保持 -1 或旧值）。
    int min_idx = -1;
    if (op.kind == T9SegmentOp::kLC) {
        min_idx = op.segment_index;
    } else if (op.kind == T9SegmentOp::kRC) {
        for (int idx : op.commit_indices) {
            if (min_idx < 0 || idx < min_idx) min_idx = idx;
        }
    }
    if (min_idx >= 0) {
        delete_min_pos_ = FullPos(SegmentDigitStart(min_idx));
    }
}

int T9UndoModel::SegmentDigitStart(int idx) const {
    int pos = 0;
    for (int i = 0; i < idx; ++i) {
        pos += static_cast<int>(segments_[i].digits.size());
    }
    return pos;
}

int T9UndoModel::TotalDigitLength() const {
    int pos = 0;
    for (const auto& seg : segments_) {
        pos += static_cast<int>(seg.digits.size());
    }
    pos += static_cast<int>(tail_digits_.size());
    return pos;
}

int T9UndoModel::FullPos(int digit_pos) const {
    // digitSeq 位置 → 完整输入位置：加其前（≤ digit_pos）的分隔符数。
    // 分隔符插入位置语义：分隔符在 digitSeq 位置 sp 之前插入，数字 sp 的完整位置 +1。
    int n = 0;
    for (int sp : separator_positions_) {
        if (sp <= digit_pos) ++n;
    }
    return digit_pos + n;
}

int T9UndoModel::FullPosSeparator(int sep_pos) const {
    int n = 0;
    for (int sp : separator_positions_) {
        if (sp < sep_pos) ++n;
    }
    return sep_pos + n;
}

// ── 查询 ──

bool T9UndoModel::HasSelectableDigits() const {
    for (const auto& seg : segments_) {
        if (seg.phase == T9Segment::kUnassigned && !seg.digits.empty()) {
            return true;
        }
    }
    return !tail_digits_.empty();
}

bool T9UndoModel::HasSelectedSegment() const {
    for (const auto& seg : segments_) {
        if (seg.phase == T9Segment::kSelected) {
            return true;
        }
    }
    return false;
}

T9Buffer T9UndoModel::ToBuffer() const {
    // 派生 T9Buffer（设计文档 §8）：
    // 消费段 = 前缀连续（回退逆序保证：未消费段恒在已消费段之后）。
    std::string digit_seq;
    for (const auto& seg : segments_) {
        digit_seq += seg.digits;
    }
    digit_seq += tail_digits_;

    int consumed = 0;
    std::vector<SyllableOption> selections;
    for (const auto& seg : segments_) {
        // 跳过非活跃空段（已回退删除，digits=''）：残留段不触发 break、不贡献 consumed。
        // 修复（2026-08-05，设备实证）：回退完后空段留在 segments_ 数组中，再次输入
        // 左选追加到残留段之后——若不跳过，残留空段（unassigned）提前 break，
        // 导致 consumed=0、selections 丢失、预编辑变全数字（"第二次输入异常"）。
        if (seg.digits.empty()) continue;
        if (seg.phase == T9Segment::kUnassigned) {
            break;  // 首个"有数字"的未消费段
        }
        consumed += static_cast<int>(seg.digits.length());
        if (seg.phase == T9Segment::kSelected) {
            selections.push_back(seg.option);
        }
    }
    T9Buffer buf(digit_seq, selections, consumed, total_digits_entered_);
    // 派生分词键位置：越界位置钳制到末尾（数字删除后，分词键显示在剩余序列末尾，
    // 如 5143 删 3、4 后 "5' "；原始完整位置用于 DeleteLastActiveElement 比较）。
    buf.separator_positions = separator_positions_;
    int len = static_cast<int>(digit_seq.size());
    for (auto& sp : buf.separator_positions) {
        if (sp > len) sp = len;
    }
    return buf;
}

}  // namespace rime
