#!/usr/bin/env bash
# ─────────────────────────────────────────────────────────────
# 九键引擎级功能测试：构建/运行 t9_engine_test
#
# 与 run_t9_tests.sh（纯算法层）互补：本脚本在宿主 librime（merged
# t9 插件）上跑真实引擎，验证「词库→键码→候选召回/preedit」全链路。
#
# 用法:
#   ./run_engine_tests.sh                    # 构建并运行全部用例
#   ./run_engine_tests.sh -f "*Recall*"      # 运行指定测试（gtest_filter）
#   ./run_engine_tests.sh --regen            # 重新生成用例后运行
#   ./run_engine_tests.sh --no-build         # 跳过构建直接运行
# ─────────────────────────────────────────────────────────────
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
ASSETS_DIR="$(cd "${SCRIPT_DIR}/../../../assets/rime" && pwd)"
BUILD_DIR="${SCRIPT_DIR}/build_engine"
LIBRIME_DIR="$(cd "${SCRIPT_DIR}/../../librime" && pwd)"
JOBS=$(nproc)

# 确保 librime-t9 以符号链接形式接入 librime 插件目录（与 app 构建一致，
# 物理单份）。本脚本直接 configure librime，绕过 Rime.cmake，故在此兜底。
T9_LINK="${LIBRIME_DIR}/plugins/librime-t9"
if [ ! -L "${T9_LINK}" ]; then
    rm -rf "${T9_LINK}"
    ln -s "../../librime-t9" "${T9_LINK}"
fi

FILTER=""
DO_BUILD=true
REGEN=false

RED='\033[0;31m'; GREEN='\033[0;32m'; YELLOW='\033[0;33m'; NC='\033[0m'

while [[ $# -gt 0 ]]; do
    case "$1" in
        -f|--filter) FILTER="$2"; shift 2 ;;
        --no-build|--skip-build) DO_BUILD=false; shift ;;
        --regen) REGEN=true; shift ;;
        -h|--help) sed -n '2,13p' "$0"; exit 0 ;;
        *) echo -e "${RED}未知选项: $1${NC}"; exit 1 ;;
    esac
done

if $REGEN || [ ! -f "${SCRIPT_DIR}/cases/t9_cases.yaml" ]; then
    echo -e "${YELLOW}[用例] 生成 t9_cases.yaml ...${NC}"
    python3 "${SCRIPT_DIR}/gen_cases.py" --out "${SCRIPT_DIR}/cases/t9_cases.yaml"
fi

if $DO_BUILD; then
    # 1) librime 宿主（merged t9 插件）
    if [ ! -f "${BUILD_DIR}/librime/CMakeCache.txt" ]; then
        echo -e "${YELLOW}[构建] 配置 librime 宿主 ...${NC}"
        cmake -S "${LIBRIME_DIR}" -B "${BUILD_DIR}/librime" \
            -DBUILD_TEST=OFF -DBUILD_SAMPLE=OFF -DBUILD_DATA=OFF \
            -DCMAKE_BUILD_TYPE=Debug -Wno-dev 2>&1 | tail -2
    fi
    echo -e "${YELLOW}[构建] 编译 librime ...${NC}"
    cmake --build "${BUILD_DIR}/librime" --target rime -j"${JOBS}" 2>&1 | tail -2

    # 2) 测试数据目录：方案 + 词典 + 裁剪后的 default.yaml（schema_list 仅 t9_pinyin）
    echo -e "${YELLOW}[数据] 准备 ${BUILD_DIR}/data ...${NC}"
    rm -rf "${BUILD_DIR}/data"
    mkdir -p "${BUILD_DIR}/data"
    cp "${ASSETS_DIR}/t9_pinyin.schema.yaml" \
       "${ASSETS_DIR}/pinyin_simp.dict.yaml" \
       "${ASSETS_DIR}/pinyin_simp_ext.dict.yaml" \
       "${BUILD_DIR}/data/"
    python3 - "$ASSETS_DIR/default.yaml" "${BUILD_DIR}/data/default.yaml" <<'EOF'
import re, sys
src, dst = sys.argv[1], sys.argv[2]
s = open(src, encoding="utf-8").read()
s = re.sub(r"schema_list:\n(?:\s+- schema: \S+\n)+",
           "schema_list:\n  - schema: t9_pinyin\n", s, count=1)
open(dst, "w", encoding="utf-8").write(s)
EOF
    cp "${SCRIPT_DIR}/cases/t9_cases.yaml" "${BUILD_DIR}/t9_cases.yaml"

    # 3) 测试本体
    if [ ! -f "${BUILD_DIR}/test/CMakeCache.txt" ]; then
        echo -e "${YELLOW}[构建] 配置 t9_engine_test ...${NC}"
        cmake -S "${SCRIPT_DIR}" -B "${BUILD_DIR}/test" \
            -DT9_SRC="$(cd "${SCRIPT_DIR}/.." && pwd)" \
            -DLIBRIME_SRC="${LIBRIME_DIR}" \
            -DLIBRIME_BUILD="${BUILD_DIR}/librime" \
            -DGTEST_DIR="${LIBRIME_DIR}/deps/googletest" \
            -Wno-dev 2>&1 | tail -2
    fi
    echo -e "${YELLOW}[构建] 编译 t9_engine_test ...${NC}"
    cmake --build "${BUILD_DIR}/test" --target t9_engine_test -j"${JOBS}" 2>&1 | tail -2
fi

# 4) 运行（词典首次部署约数秒，全部用例共享一个 session）
cd "${BUILD_DIR}/test"
echo -e "${YELLOW}[测试] 运行 t9_engine_test ...${NC}"
set +e
if [ -n "$FILTER" ]; then
    T9_ENGINE_DATA_DIR="${BUILD_DIR}/data" \
        ./t9_engine_test --gtest_filter="$FILTER" 2>&1
else
    T9_ENGINE_DATA_DIR="${BUILD_DIR}/data" ./t9_engine_test 2>&1
fi
EXIT_CODE=$?
set -e

echo ""
if [ $EXIT_CODE -eq 0 ]; then
    echo -e "${GREEN}  ✓ 引擎级测试通过${NC}"
else
    echo -e "${RED}  ✗ 引擎级测试失败 (exit code: ${EXIT_CODE})${NC}"
fi
exit $EXIT_CODE
