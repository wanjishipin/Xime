#ifndef T9_PANEL_STATE_H_
#define T9_PANEL_STATE_H_

#include <optional>
#include <string>
#include <vector>

namespace rime {

class T9Buffer;
class T9StateMachine;

// P4/P5（2026-07-19）：左侧面板状态的结构化数据。
//
// 替代原 GetLeftPanelState() 的字符串协议
// "STATE;PINYIN;DIGIT_LEN;SEL_DIGITS;PANEL_DIGITS;LEFT_LOCKED"，
// 消除 JNI 边界的字符串序列化/解析开销与字段顺序耦合。
// JNI 层直接据此构造 Java 对象，Kotlin 端无需 split 解析。
struct LeftPanelStateData {
    enum class State { kIdle = 0, kInput = 1, kSelection = 2 };
    State state = State::kIdle;
    std::string selected_pinyin;
    int selected_digit_length = 0;
    std::string selection_candidate_digits;
    std::string panel_digits;
    bool left_locked = false;
};

// P2（2026-07-19）：面板状态查询的只读上下文。
//
// T9Processor 的面板查询方法需要读取多个状态成员。
// 通过此结构体传入所需状态的 const 引用，
// 使查询逻辑与 T9Processor 的状态管理解耦。
struct T9PanelStateContext {
    const T9Buffer& input_buffer;
    const T9StateMachine& state_machine;
    bool left_column_locked = false;
    const std::optional<std::string>& separator_consumed_digits;

    T9PanelStateContext(const T9Buffer& buf, const T9StateMachine& sm,
                        bool locked, const std::optional<std::string>& sep_digits)
        : input_buffer(buf), state_machine(sm), left_column_locked(locked),
          separator_consumed_digits(sep_digits) {}
};

// P2（2026-07-19）：从 T9Processor 提取的面板状态查询函数。
//
// 原 T9Processor 中的 GetLeftPanelState/GetRemainingDigits/
// GetFirstSyllableOptions/GetAndConsumeUndoneRightCommitCount
// 均为只读查询（除 ConsumeUndoneRightCommitCount 有原子消费语义），
// 与 T9Processor 的核心按键处理逻辑无耦合。
// 提取后 T9Processor 仅保留 public 方法签名，内部委托此命名空间。
namespace t9_panel_state {

// 左侧候选区模式（2026-08-07，英文九键适配）：
//   kPinyin：拼音音节消歧（t9_pinyin 等 script_translator 方案）
//   kNone  ：无左栏候选（melt_eng_t9 等英文 table_translator 方案，或显式配置关闭）
enum class LeftPanelMode {
    kPinyin = 0,
    kNone = 1,
};

// 解析左侧候选区模式（纯函数，无 RIME 依赖）：
//   explicit_mode："pinyin" | "none" | "auto"（空串按 auto）
//   auto 判定：engine/translators 含 script_translator → kPinyin（拼音方案），
//              否则 → kNone（英文/词级预测方案，如 melt_eng_t9）。
//   显式模式优先于 auto 判定（为第三方方案作者留覆盖口子）。
LeftPanelMode ResolveLeftPanelMode(bool has_script_translator,
                                   const std::string& explicit_mode);

// 获取左侧面板状态（结构化）
void GetLeftPanelState(const T9PanelStateContext& ctx, LeftPanelStateData& out);

// 获取左侧面板状态（字符串格式，调试/向后兼容用）
std::string GetLeftPanelStateString(const T9PanelStateContext& ctx);

// 获取 partial commit 后剩余的 RIME 输入串
std::string GetRemainingDigits(const T9Buffer& input_buffer);

// 获取首音节候选列表，序列化为 "pinyin|digitLength" 格式
void GetFirstSyllableOptions(const std::string& digits, int max_results,
                              std::vector<std::string>& out);

// 获取并消费 RightCommit 撤销计数（原子消费语义）
int ConsumeUndoneRightCommitCount(int& count);

}  // namespace t9_panel_state

}  // namespace rime

#endif  // T9_PANEL_STATE_H_
