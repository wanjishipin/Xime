// T9 日志与性能埋点统一接口
//
// 设计目标：
//   1. 编译期开关：Release 构建零开销（无 __android_log_print 调用）
//   2. 模块化 TAG：各模块保留独立 TAG（T9Processor / T9RightCommit / T9Buffer / T9Filter）
//   3. 扩展性：预留耗时埋点接口（T9_SCOPED_TIMER），独立开关不影响日志
//
// 使用方式（推荐统一命名）：
//   #include "t9_log.h"
//   T9_LOG_DEBUG("T9Processor", "ProcessKeyEvent: ch=%d", ch);
//   T9_LOG_DEBUG("T9RightCommit", ">> HandleRightCommit: ...");
//   T9_LOG_DEBUG("T9Buffer", ">> ToPreeditString: ...");
//   T9_LOG_DEBUG("T9Filter", ">> preedit='%s'", preedit);
//
// 兼容旧版便捷宏（保留向后兼容）：
//   T9LOG(...)   → T9_LOG_DEBUG("T9Processor", ...)
//   RCLOG(...)   → T9_LOG_DEBUG("T9RightCommit", ...)
//   BUFLOG(...)  → T9_LOG_DEBUG("T9Buffer", ...)
//   T9FLOG(...)  → T9_LOG_DEBUG("T9Filter", ...)
//
// 耗时埋点（需启用 T9_ENABLE_TIMING）：
//   {
//       T9_SCOPED_TIMER_TAG("T9RightCommit", "HandleApostropheRightCommit");
//       // ... 待测代码 ...
//   }
//
// 编译选项（CMakeLists.txt）：
//   option(T9_ENABLE_VERBOSE_LOG "Enable verbose T9 debug logging" OFF)
//   option(T9_ENABLE_TIMING      "Enable T9 timing instrumentation" OFF)
//   默认两者均 OFF；通过环境变量按需开启：
//     T9_ENABLE_VERBOSE_LOG=ON T9_ENABLE_TIMING=ON ./gradlew assembleDebug

#ifndef T9_LOG_H_
#define T9_LOG_H_

#include <cstdio>

#ifdef __ANDROID__
    #include <android/log.h>
#endif

#include <chrono>
#include <utility>

namespace rime {
namespace t9_log_detail {

// ── 耗时埋点 RAII 守卫 ──
// 仅在 T9_ENABLE_TIMING 启用时输出，否则退化为空操作。
struct ScopedTimer {
    using Clock = std::chrono::steady_clock;

    const char* tag;
    const char* name;
    Clock::time_point start;

    ScopedTimer(const char* t, const char* n)
        : tag(t), name(n), start(Clock::now()) {}

    ~ScopedTimer() {
#ifdef T9_ENABLE_TIMING
        auto elapsed = Clock::now() - start;
        auto us = std::chrono::duration_cast<
            std::chrono::microseconds>(elapsed).count();
#ifdef __ANDROID__
        __android_log_print(ANDROID_LOG_DEBUG, tag,
                            "[TIMING] %s: %lld us", name,
                            static_cast<long long>(us));
#else
        std::fprintf(stderr, "[TIMING] [%s] %s: %lld us\n",
                     tag, name, static_cast<long long>(us));
#endif
#else
        (void)tag;
        (void)name;
#endif
    }
};

}  // namespace t9_log_detail
}  // namespace rime

// ════════════════════════════════════════
// 日志宏：编译期开关 T9_ENABLE_VERBOSE_LOG
// ════════════════════════════════════════

#ifdef __ANDROID__
    #ifdef T9_ENABLE_VERBOSE_LOG
        #define T9_DICT_LOG(...) \
            __android_log_print(ANDROID_LOG_INFO, "RimePerf", "[T9Dict] " __VA_ARGS__)
    #else
        #define T9_DICT_LOG(...) do { } while (0)
    #endif
#else
    #define T9_DICT_LOG(...) do { } while (0)
#endif

#ifdef T9_ENABLE_VERBOSE_LOG
    // 通用日志宏：指定 TAG（推荐统一入口）
    #ifdef __ANDROID__
        #define T9_LOG_DEBUG(tag, ...) \
            __android_log_print(ANDROID_LOG_DEBUG, tag, __VA_ARGS__)
    #else
        #define T9_LOG_DEBUG(tag, ...) \
            std::fprintf(stderr, "[%s] ", tag); \
            std::fprintf(stderr, __VA_ARGS__); \
            std::fputc('\n', stderr)
    #endif

    // 各模块便捷宏（保留向后兼容）
    #define T9LOG(...)  T9_LOG_DEBUG("T9Processor",    __VA_ARGS__)
    #define RCLOG(...)  T9_LOG_DEBUG("T9RightCommit",  __VA_ARGS__)
    #define BUFLOG(...) T9_LOG_DEBUG("T9Buffer",       __VA_ARGS__)
    #define T9FLOG(...) T9_LOG_DEBUG("T9Filter",       __VA_ARGS__)

    // 统一 RimePerf 标签日志（用于性能测试，与 engine.cc/menu.cc 等标签一致）
    #define T9_LOG_PERF(...) T9_LOG_DEBUG("RimePerf", __VA_ARGS__)
    #define T9_PERFLOG(...) T9_LOG_PERF(__VA_ARGS__)
#else
    // Release 构建：日志完全消除（零开销）
    #define T9_LOG_DEBUG(tag, ...) ((void)0)
    #define T9_LOG_TAG(tag, ...)   ((void)0)
    #define T9LOG(...)  ((void)0)
    #define RCLOG(...)  ((void)0)
    #define BUFLOG(...) ((void)0)
    #define T9FLOG(...) ((void)0)
    #define T9_LOG_PERF(...) ((void)0)
    #define T9_PERFLOG(...) ((void)0)
#endif

// ════════════════════════════════════════
// 耗时埋点宏：编译期开关 T9_ENABLE_TIMING
// ════════════════════════════════════════
//
// 使用 RAII 守卫在作用域结束时输出耗时。
// 独立于 T9_ENABLE_VERBOSE_LOG，可单独启用。
//
// 示例：
//   {
//       T9_SCOPED_TIMER_TAG("T9RightCommit", "HandleApostropheRightCommit");
//       // ... 待测代码 ...
//   }  // 作用域结束自动输出 [TIMING] HandleApostropheRightCommit: 123 us

#ifdef T9_ENABLE_TIMING
    #define T9_SCOPED_TIMER_TAG(tag, name) \
        ::rime::t9_log_detail::ScopedTimer \
            _t9_scoped_timer_##__LINE__(tag, name)

    #define T9_PERF_SCOPED_TIMER(name) \
        ::rime::t9_log_detail::ScopedTimer \
            _t9_perf_timer_##__LINE__("RimePerf", name)
#else
    #define T9_SCOPED_TIMER_TAG(tag, name) ((void)0)
    #define T9_PERF_SCOPED_TIMER(name)    ((void)0)
#endif

#endif  // T9_LOG_H_
