// T9 插件单元测试入口
//
// T9PinyinMap / T9Buffer 等纯算法测试无需初始化 RIME 引擎，
// 直接使用 gtest_main 即可。
// 若后续测试需要 RIME 引擎（如 T9Processor 集成测试），
// 可参考 librime/test/rime_test_main.cc 添加 GlobalEnvironment。
#include <gtest/gtest.h>

int main(int argc, char** argv) {
    testing::InitGoogleTest(&argc, argv);
    return RUN_ALL_TESTS();
}
