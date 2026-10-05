#ifndef T9_RIGHT_COMMIT_HANDLER_H_
#define T9_RIGHT_COMMIT_HANDLER_H_

#include <memory>
#include <string>
#include <vector>
#include <optional>
#include <functional>

#include "t9_buffer.h"
#include "t9_state_machine.h"
#include "t9_pinyin_map.h"
#include "t9_string_utils.h"
#include "t9_syllable_alignment.h"

namespace rime {

// 顶层 Strategy 前向声明（已提取为独立文件）
class ApostropheStrategy;
class DigitSegmentStrategy;
class LetterBufferStrategy;
// 段模型前向声明（渐进集成：Context 用指针，避免头文件依赖膨胀）
class T9UndoModel;

// 右侧候选词选词处理器（设计文档 §6.2-§6.6）。
//
// ─── v2.0 架构（评估报告 P9 重构） ───
//
// 采用策略模式（Strategy Pattern）替代 v1.0 的单一巨类：
//
//   T9RightCommitHandler（协调器）
//     ├── HandleRightCommit() 三层分流入口
//     └── 持有三个 Strategy 实例
//           ├── ApostropheStrategy    —— apostrophe 模式（selections + unassigned）
//           ├── DigitSegmentStrategy  —— digitSegment 模式（仅 unassigned）
//           └── LetterBufferStrategy  —— letterBuffer 模式（仅 selections）
//
// 每个 Strategy 内部按子路径进一步拆分（P10）：
//   - ApostropheStrategy: 5 个子路径谓词 + 处理器
//   - LetterBufferStrategy: SELECTION 态子模式（含 4 个子方法）
//
// 状态转换通过 Context::TransitionTo* 集中管理（P11），
// 消除 v1.0 中 15+ 处散落的 sync_state + update_candidates + set_rime_input 调用。
//
// 三层消费算法（设计稿 §6.2）：
//   层级1：apostrophe 模式（selections 非空 && unassigned 非空）
//   层级2：digitSegment 模式（selections 为空 && unassigned 非空）
//   层级3：letterBuffer 模式（selections 非空 && unassigned 为空）
class T9RightCommitHandler {
public:
    // 右侧选词三层路由类型（S3：替代 is_letter_buffer_sel 透传）。
    // 路由判定集中在 ClassifyRoute，HandleRightCommit 与 CRCC 共享同一份语义。
    enum class RouteType {
        kDigitSegment,            // 仅 unassigned 非空 → DigitSegmentStrategy
        kApostrophe,              // selections + unassigned，候选词从 unassigned 消费
        kLetterBuffer_Selection,  // selections + unassigned，候选词在 selections 段消费
        kLetterBuffer_Input,      // 仅 selections 非空 → LetterBufferStrategy（INPUT 态）
    };

    // 控制器可变状态上下文。
    // 仅暴露右侧选词处理过程所需的读写字段与回调。
    struct Context {
        T9Buffer input_buffer;
        T9StateMachine state_machine;
        // 段模型指针：HandleRightCommit 在策略执行后调用
        // undo_model->SyncRightCommit(prev_buf, input_buffer) 同步段状态（回退唯一真相源）。
        T9UndoModel* undo_model = nullptr;
        bool left_column_locked = false;
        std::optional<std::string> separator_consumed_digits;
        std::optional<std::string> last_choice_consumed_digits;
        char manual_delimiter = '\'';   // 分隔符字符，从 speller.delimiter 配置读取

        // 回调（由 T9Processor 注入）
        std::function<void()> sync_state = []{};
        std::function<void(bool)> update_candidates = [](bool){};
        std::function<void(const std::optional<std::string>&)> set_rime_input =
            [](const std::optional<std::string>&){};

        // ── 高层状态转换（P11：集中散落的状态变更） ──
        // 封装 sync_state + update_candidates + set_rime_input 组合，
        // 让 Strategy 代码聚焦于业务分支判断。

        // 清空 buffer + 进入 Idle 态 + 同步
        void TransitionToIdle();

        // 根据 buffer 是否为空自动选择 Idle/Input 态 + 同步
        // @param keep_history true: 保留 selection_history（用于 RestorePrevState 场景）
        //                     false: 清空 selection_history（用于全新输入态）
        void TransitionToInput(bool keep_history = false);

        // 恢复到 SELECTION 态（用于 RestorePrevState 场景）
        // @param option 选中的拼音选项（nullopt 则回退到 Input 态）
        // @param digits 选中项对应的数字段
        // @param confirmed_pinyin 选中项之前的已确认拼音前缀
        void TransitionToSelection(
            const std::optional<SyllableOption>& option,
            const std::optional<std::string>& digits,
            const std::string& confirmed_pinyin = "");

        // 同步 buffer 到 RIME 输入（用于 INPUT 态）
        void SyncBufferToRime();
    };

    T9RightCommitHandler();
    ~T9RightCommitHandler();

    // 禁止拷贝（持有 Strategy 实例）
    T9RightCommitHandler(const T9RightCommitHandler&) = delete;
    T9RightCommitHandler& operator=(const T9RightCommitHandler&) = delete;

    // 右侧候选选词，返回 true = 完整消费（full commit）。
    //
    // @param ctx 控制器可变状态上下文
    // @param candidate_pinyin RIME 候选词注释（spelling_hints）
    // @param candidate_text_length 候选词文字长度（汉字数）
    // @param rime_consumed_digits 方案 A：RIME 候选 end 换算的应消费数字位数，
    //                             -1 表示无法确定（fallback 到现有消费算法）
    // @param is_t9_user_word 候选是否为 T9 用户词：是则 unassigned 全量消费
    bool HandleRightCommit(Context& ctx,
                           const std::optional<std::string>& candidate_pinyin,
                           int candidate_text_length = 0,
                           int rime_consumed_digits = -1,
                           bool is_t9_user_word = false);

    // ── 策略共享公共服务 ──
    // 三个 Strategy 类通过 Context 与协调器交互所需的下层辅助。
    void ClearAndEnterIdle(Context& ctx);

    // 恢复到 SELECTION/INPUT 态（用于 RestorePrevState 场景）
    bool RestorePrevState(Context& ctx,
                          const std::optional<SyllableOption>& prev_selected_option,
                          const std::optional<std::string>& prev_selection_candidate_digits,
                          const std::string& prev_confirmed_pinyin = "");

    // 移除已消费的 selections（按拼音前缀匹配裁剪 selection 列表）
    T9Buffer RemoveConsumedSelections(Context& ctx,
                                      const T9Buffer& buf,
                                      const std::string& consumed_pinyin);

    // ── 策略共享静态工具 ──

    // 谓词：是否为 letter-buffer 模式下的 SELECTION 态。
    // 条件：prev_opt 存在 && selected_pinyin 以 prev_opt.pinyin 结尾且更长。
    // 此时候选词音节覆盖部分 selections + unassigned，应走 LetterBufferStrategy。
    static bool IsLetterBufferSelection(
        const std::string& selected_pinyin,
        const std::optional<SyllableOption>& prev_opt);

    // S3：路由分类谓词。集中 HandleRightCommit 与 CRCC 的路由判定语义。
    // 依据 T9Buffer 的 selections / unassigned 占用情况 + prev_opt（上一轮选中项）
    // 判定走哪条 Strategy 路径，替代原先散落的 is_letter_buffer_sel 透传。
    static RouteType ClassifyRoute(const T9Buffer& buf,
                                   const std::optional<SyllableOption>& prev_opt);

    // 辅助：从前轮选中拼音（末尾项）截取"非选中前缀"。
    // 即 DropLast(selected_pinyin, prev_opt.pinyin.size())。
    // 用于多处选区消费逻辑中计算未被候选词覆盖的 selections 拼音段。
    static std::string NonSelectedPinyin(
        const std::string& selected_pinyin,
        const SyllableOption& prev_opt) {
        return DropLast(selected_pinyin,
                        static_cast<int>(prev_opt.pinyin.size()));
    }

private:
    // ── 三层策略（P9：策略模式） ──
    //
    // 每个 Strategy 实现 Handle 方法，接收 Context 与共享参数。
    // Strategy 之间通过 Context 协作，互不直接依赖。
    // Strategy 类已提取为独立文件（t9_apostrophe_strategy.h 等）

    std::unique_ptr<ApostropheStrategy> apostrophe_strategy_;
    std::unique_ptr<DigitSegmentStrategy> digit_segment_strategy_;
    std::unique_ptr<LetterBufferStrategy> letter_buffer_strategy_;

    // 消费计算（保留在协调器，因为三层共享）
    // 返回 (consumed, remaining)
    // @param route 路由类型（由 ClassifyRoute 预计算，CRCC 内部据此分支）
    // @param alignment S4-3：由 HandleRightCommit 一次构造的候选词对齐上下文，
    //                  CRCC 与 ApostropheStrategy 共享，消除重复 ParseSyllables 调用
    // @param rime_consumed_digits 方案 A：RIME 候选 end 换算的应消费数字位数，
    //                             -1 表示无法确定（fallback 到现有消费算法）
    // @param is_t9_user_word 候选是否为 T9 用户词：是则 unassigned 全量消费
    std::pair<std::string, std::string> ComputeRightCommitConsumption(
        const T9Buffer& buf,
        const SyllableAlignment& alignment,
        const std::optional<std::string>& candidate_pinyin,
        RouteType route,
        int rime_consumed_digits = -1,
        bool is_t9_user_word = false);
};

}  // namespace rime

#endif  // T9_RIGHT_COMMIT_HANDLER_H_
