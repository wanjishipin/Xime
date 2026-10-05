#include "t9_panel_state.h"

#include "t9_buffer.h"
#include "t9_log.h"
#include "t9_pinyin_map.h"
#include "t9_state_machine.h"

namespace rime {
namespace t9_panel_state {

LeftPanelMode ResolveLeftPanelMode(bool has_script_translator,
                                   const std::string& explicit_mode) {
    if (explicit_mode == "pinyin") return LeftPanelMode::kPinyin;
    if (explicit_mode == "none") return LeftPanelMode::kNone;
    // auto（默认）：按 translators 类型判定。拼音方案（script_translator）
    // 才有音节消歧概念；英文/词级预测方案（table_translator，如 melt_eng_t9）
    // 左栏拼音候选是错误信息且点击会污染输入，直接禁用。
    return has_script_translator ? LeftPanelMode::kPinyin : LeftPanelMode::kNone;
}

void GetLeftPanelState(const T9PanelStateContext& ctx, LeftPanelStateData& out) {
    out.left_locked = ctx.left_column_locked;

    switch (ctx.state_machine.state()) {
        case T9StateMachine::State::kIdle:
            out.state = LeftPanelStateData::State::kIdle;
            break;
        case T9StateMachine::State::kInput:
            out.state = LeftPanelStateData::State::kInput;
            break;
        case T9StateMachine::State::kSelection:
            out.state = LeftPanelStateData::State::kSelection;
            break;
    }

    const auto& opt = ctx.state_machine.selected_option();
    out.selection_candidate_digits =
        ctx.state_machine.selection_candidate_digits().value_or("");

    // 当 unassigned 非空时，左侧面板显示当前输入段候选，不报告 selectedOption
    // （下沉 Kotlin 的 selectedOption=null 逻辑）
    std::string unassigned = ctx.input_buffer.unassigned();
    if (unassigned.empty()) {
        out.selected_pinyin = opt ? opt->pinyin : "";
        out.selected_digit_length = opt ? opt->digit_length : 0;
    } else {
        out.selected_pinyin.clear();
        out.selected_digit_length = 0;
    }

    // panelDigits：left_column_locked_=true 时优先用 unassigned()，
    // 与 main 分支 `if (leftColumnLocked && !force) return` 语义对齐：
    // 分词键确认后，左侧候选区锁定，数字键不刷新左侧面板。
    // 分隔符场景：unassigned 非空（如 "482"），应显示未分配数字段的候选；
    // RightCommit/Apostrophe 场景：unassigned 为空，回退到 separator_consumed_digits。
    if (!ctx.state_machine.is_idle()) {
        if (ctx.left_column_locked && ctx.separator_consumed_digits.has_value()) {
            // left_column_locked_=true：左侧锁定。
            // 无 selections 时（分词键刚确认、尚未左选），使用 separator_consumed_digits
            // 作为锁定段数字。有 selections 时（已左选区隔段的首个音节），使用 unassigned()
            // 显示剩余未消费数字段的候选。
            // 修复：此前无条件取 unassigned()，在 consumed_count=0 时返回完整 digit_sequence，
            //       导致分词键场景下左侧候选区错误显示全量数字段的候选词。
            if (ctx.input_buffer.selections.empty()) {
                out.panel_digits = ctx.separator_consumed_digits.value();
            } else {
                out.panel_digits = ctx.input_buffer.unassigned();
            }
            if (out.panel_digits.empty()) {
                out.panel_digits = ctx.state_machine.selection_candidate_digits().value_or("");
            }
            if (out.panel_digits.empty()) {
                out.panel_digits = ctx.separator_consumed_digits.value();
            }
        } else {
            out.panel_digits = ctx.input_buffer.unassigned();
            if (out.panel_digits.empty()) {
                out.panel_digits = ctx.state_machine.selection_candidate_digits().value_or("");
            }
            if (out.panel_digits.empty() && ctx.separator_consumed_digits.has_value()) {
                out.panel_digits = ctx.separator_consumed_digits.value();
            }
        }
    } else {
        out.panel_digits.clear();
    }

    T9LOG(">> GetLeftPanelState(struct): state=%d, pinyin='%s', digitLen=%d, selDigits='%s', panelDigits='%s', leftLocked=%d",
          static_cast<int>(out.state), out.selected_pinyin.c_str(),
          out.selected_digit_length, out.selection_candidate_digits.c_str(),
          out.panel_digits.c_str(), out.left_locked ? 1 : 0);
    T9LOG(">>   buf.digitSeq='%s', buf.consumedCount=%d, buf.unassigned='%s', leftColumnLocked=%d, sepConsumed='%s'",
          ctx.input_buffer.digit_sequence.c_str(), ctx.input_buffer.consumed_count,
          unassigned.c_str(), ctx.left_column_locked ? 1 : 0,
          ctx.separator_consumed_digits.has_value() ? ctx.separator_consumed_digits->c_str() : "(null)");
}

std::string GetLeftPanelStateString(const T9PanelStateContext& ctx) {
    LeftPanelStateData data;
    GetLeftPanelState(ctx, data);

    std::string state_str;
    switch (data.state) {
        case LeftPanelStateData::State::kIdle:      state_str = "IDLE"; break;
        case LeftPanelStateData::State::kInput:     state_str = "INPUT"; break;
        case LeftPanelStateData::State::kSelection: state_str = "SELECTION"; break;
    }
    // 返回格式：STATE;PINYIN;DIGIT_LEN;SEL_DIGITS;PANEL_DIGITS;LEFT_LOCKED
    return state_str + ";" + data.selected_pinyin + ";" +
           std::to_string(data.selected_digit_length) + ";" +
           data.selection_candidate_digits + ";" + data.panel_digits + ";" +
           (data.left_locked ? "1" : "0");
}

std::string GetRemainingDigits(const T9Buffer& input_buffer) {
    // 返回 ToRimeInputString() 而非 unassigned()：
    // partial commit 后，forceSendToRime 需要把剩余 buffer 完整发给 RIME，
    // 包括剩余 selections（如 "g'7"），否则只发 unassigned="7" 会丢失 selections，
    // 导致 RIME 候选词不匹配、预编辑文本错误。
    return input_buffer.ToRimeInputString();
}

void GetFirstSyllableOptions(const std::string& digits, int max_results,
                              std::vector<std::string>& out) {
    // P3 方案 A：替代 Kotlin T9PinyinMap.firstSyllableOptions，消除双端维护
    // 委托给 C++ T9PinyinMap 单例，序列化为 "pinyin|digitLength" 格式供 JNI 传输
    auto options = T9PinyinMap::Instance().FirstSyllableOptions(digits, max_results);
    out.clear();
    out.reserve(options.size());
    for (const auto& opt : options) {
        out.push_back(opt.pinyin + "|" + std::to_string(opt.digit_length));
    }
}

int ConsumeUndoneRightCommitCount(int& count) {
    int result = count;
    count = 0;
    return result;
}

}  // namespace t9_panel_state
}  // namespace rime
