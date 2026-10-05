#!/usr/bin/env bash
# ─────────────────────────────────────────────────────────────
# T9 插件单元测试构建与运行脚本
# 使用 CMake 独立构建 librime-t9 纯算法层测试（不依赖 RIME 引擎）
# 构建产物在 build_test/ 目录，已加入 .gitignore 不会被 git 跟踪
#
# 性能测试已移除（2026-08-01）：纯算法层 perf 测试阈值宽松 30-40x，
# 实际捕捉不到任何回归。改用实体机 UI 点击测试（docs/调优/scripts/run_ui_tap_test.py）
# 测量全链路用户感知性能。
#
# 用法:
#   ./run_t9_tests.sh                    # 运行全部测试（默认）
#   ./run_t9_tests.sh -f "*MyTest*"      # 运行指定测试用例
#   ./run_t9_tests.sh --rebuild          # 强制重新构建
#   ./run_t9_tests.sh --no-build         # 跳过构建，直接运行
#   ./run_t9_tests.sh -v                 # 详细输出
#   ./run_t9_tests.sh --help             # 显示帮助
# ─────────────────────────────────────────────────────────────
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
BUILD_DIR="${SCRIPT_DIR}/build_test"
GTEST_DIR="$(cd "${SCRIPT_DIR}/../librime/deps/googletest" && pwd)"
JOBS=$(nproc)

# 默认值
FILTER=""
DO_BUILD=true
REBUILD=false
VERBOSE=false

# 颜色输出
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

# ── 解析参数 ──
while [[ $# -gt 0 ]]; do
    case "$1" in
        -f|--filter)
            FILTER="$2"
            shift 2
            ;;
        --no-build|--skip-build)
            DO_BUILD=false
            shift
            ;;
        --rebuild|--force-rebuild)
            REBUILD=true
            shift
            ;;
        -v|--verbose)
            VERBOSE=true
            shift
            ;;
        -h|--help)
            echo "用法: $0 [选项]"
            echo ""
            echo "选项:"
            echo "  -f, --filter PATTERN   运行匹配的测试（gtest_filter 格式）"
            echo "  --no-build             跳过构建，直接运行现有测试"
            echo "  --rebuild              强制重新构建（删除 build_test 目录）"
            echo "  -v, --verbose          详细输出（显示 cmake 构建细节）"
            echo "  -h, --help             显示此帮助"
            echo ""
            echo "示例:"
            echo "  $0 -f \"*LetterBuffer*\""
            echo "  $0 -f \"*T9Buffer*\" --no-build"
            echo "  $0 --rebuild"
            echo ""
            echo "性能测试: 使用实体机 UI 点击测试"
            echo "  python3 docs/调优/scripts/run_ui_tap_test.py --calibrate"
            echo "  python3 docs/调优/scripts/run_ui_tap_test.py --repeats 20"
            exit 0
            ;;
        *)
            echo -e "${RED}未知选项: $1${NC}"
            echo "用法: $0 [选项]   (使用 --help 查看帮助)"
            exit 1
            ;;
    esac
done

# ── 打印标题 ──
echo -e "${YELLOW}══════════════════════════════════════════════${NC}"
echo -e "${YELLOW}  T9 单元测试                                 ${NC}"
echo -e "${YELLOW}══════════════════════════════════════════════${NC}"
echo "  源目录: ${SCRIPT_DIR}"
echo "  构建目录: ${BUILD_DIR}"
echo "  过滤: ${FILTER:-无}"
echo "  构建: ${DO_BUILD}${REBUILD:+ (rebuild)}"
echo "  并行任务: ${JOBS}"
echo ""

# ── 构建阶段 ──
if $DO_BUILD; then
    if $REBUILD; then
        echo -e "${YELLOW}[构建] 强制重新构建（删除 build_test）...${NC}"
        rm -rf "${BUILD_DIR}"
    fi

    if [ ! -f "${BUILD_DIR}/CMakeCache.txt" ]; then
        echo -e "${YELLOW}[构建] CMake 配置...${NC}"
        mkdir -p "${BUILD_DIR}"
        cd "${BUILD_DIR}"
        cmake .. \
          -DT9_BUILD_TESTS=ON \
          -DT9_GTEST_DIR="${GTEST_DIR}" \
          -Wno-dev 2>&1 | tail -3
        echo -e "${GREEN}  ✓ CMake 配置完成${NC}"
    else
        echo -e "${BLUE}[构建] 使用现有 CMake 缓存${NC}"
        cd "${BUILD_DIR}"
    fi

    echo -e "${YELLOW}[构建] 构建 t9_test...${NC}"
    if $VERBOSE; then
        cmake --build . --target t9_test -j"${JOBS}" 2>&1
    else
        cmake --build . --target t9_test -j"${JOBS}" 2>&1 | tail -5
    fi
    echo -e "${GREEN}  ✓ 构建完成${NC}"
    echo ""
fi

cd "${BUILD_DIR}"

# ── 运行单元测试 ──
if [ ! -f ./t9_test ]; then
    echo -e "${RED}  ✗ t9_test 不存在，请先构建（去掉 --no-build）${NC}"
    exit 1
fi
echo -e "${YELLOW}[测试] 运行单元测试...${NC}"
if [ -n "$FILTER" ]; then
    echo -e "${BLUE}  过滤: ${FILTER}${NC}"
    set +e
    ./t9_test --gtest_filter="$FILTER" 2>&1
    EXIT_CODE=$?
    set -e
else
    set +e
    ./t9_test 2>&1
    EXIT_CODE=$?
    set -e
fi
echo ""
if [ $EXIT_CODE -eq 0 ]; then
    echo -e "${GREEN}  ✓ 单元测试通过${NC}"
else
    echo -e "${RED}  ✗ 单元测试失败 (exit code: ${EXIT_CODE})${NC}"
fi
echo ""

# ── 汇总 ──
echo -e "${GREEN}══════════════════════════════════════════════${NC}"
if [ $EXIT_CODE -eq 0 ]; then
    echo -e "${GREEN}  全部完成                                   ${NC}"
else
    echo -e "${RED}  有测试失败 (exit code: ${EXIT_CODE})           ${NC}"
fi
echo -e "${GREEN}══════════════════════════════════════════════${NC}"
echo ""
echo "构建产物: ${BUILD_DIR}/"
echo "快速运行单个测试: $0 -f \"*TestName*\""
echo ""

exit $EXIT_CODE