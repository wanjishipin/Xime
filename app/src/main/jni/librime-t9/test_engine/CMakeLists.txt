cmake_minimum_required(VERSION 3.12)
project(t9_engine_test CXX)

set(CMAKE_CXX_STANDARD 17)
find_package(Threads REQUIRED)

# 由 run_engine_tests.sh 传入：
#   T9_SRC       librime-t9 顶层源码目录（t9_processor.h 等）
#   LIBRIME_SRC  librime 源码目录（rime_api.h）
#   LIBRIME_BUILD librime 宿主构建目录（lib/librime.so）
#   GTEST_DIR    googletest 源码目录
set(T9_SRC "${CMAKE_CURRENT_SOURCE_DIR}/.." CACHE PATH "")
set(LIBRIME_SRC "${CMAKE_CURRENT_SOURCE_DIR}/../../librime" CACHE PATH "")
set(LIBRIME_BUILD "${CMAKE_CURRENT_SOURCE_DIR}/build_engine/librime" CACHE PATH "")
set(GTEST_DIR "${LIBRIME_SRC}/deps/googletest" CACHE PATH "")

add_executable(t9_engine_test t9_engine_test.cc)

# 候选排名诊断工具（stdin 每行 "输入串\t目标词"，输出 rank；配合
# T9_DUMP_CAP/T9_DEBUG_PREEDIT 环境变量），引擎级问题排查用。
add_executable(rank_probe rank_probe.cc)

target_include_directories(t9_engine_test PRIVATE
    "${LIBRIME_SRC}/src"
    "${LIBRIME_BUILD}/src"
    "${T9_SRC}/src"
    "${GTEST_DIR}/googletest/include"
)

# 与 librime 自家 rime_test 同款：shared 库符号导入
target_compile_definitions(t9_engine_test PRIVATE RIME_IMPORTS)
target_compile_definitions(rank_probe PRIVATE RIME_IMPORTS)

find_library(LIBRIME_LIB rime PATHS "${LIBRIME_BUILD}/lib" NO_DEFAULT_PATH REQUIRED)

target_link_libraries(t9_engine_test PRIVATE
    "${LIBRIME_LIB}"
    gtest
    Threads::Threads
)

target_link_libraries(rank_probe PRIVATE
    "${LIBRIME_LIB}"
    Threads::Threads
)

# googletest 顶层目录（内部 googletest/ 子目录的 CMakeLists 依赖顶层定义的变量）
set(gtest_force_shared_crt ON CACHE BOOL "" FORCE)
add_subdirectory("${GTEST_DIR}" "${CMAKE_BINARY_DIR}/googletest-build")
