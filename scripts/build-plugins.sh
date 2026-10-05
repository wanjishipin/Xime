#!/bin/bash
# 构建所有 TS 插件 xipk 包（Rust CLI: tools/xime-plugin）
#
# 使用方式：
#   bash scripts/build-plugins.sh                    # 编译 + 打包到 build/plugin-release/
#   bash scripts/build-plugins.sh --with-assets      # 同时同步内置插件到 app assets
#
# 插件源码为 TypeScript（plugins/<name>/main.ts + manifest.json + resources/），
# 由 xipm CLI 编译为 IIFE 单文件 main.js 后打包为 xipk。

set -e

PROJECT_DIR="$(cd "$(dirname "$0")/.." && pwd)"

# 内置插件（debug 构建从 assets 自动安装的集合）
BUNDLED_PLUGINS="ai-reply ai-translate ai-write webdav-clipboard-sync"

WITH_ASSETS=0
CLI_ARGS=()
for arg in "$@"; do
  if [ "$arg" = "--with-assets" ]; then
    WITH_ASSETS=1
  else
    CLI_ARGS+=("$arg")
  fi
done

echo "=== 构建 TS 插件 xipk 包（xipm CLI）==="

cd "$PROJECT_DIR/tools/xime-plugin"
cargo run --quiet -- pack --all \
  --plugins-dir "$PROJECT_DIR/plugins" \
  --out "$PROJECT_DIR/build/plugin-js" \
  --release-dir "$PROJECT_DIR/build/plugin-release" \
  "${CLI_ARGS[@]+"${CLI_ARGS[@]}"}"

if [ "$WITH_ASSETS" = "1" ]; then
  ASSETS_DIR="$PROJECT_DIR/app/src/main/assets/plugins"
  echo ""
  echo "=== 同步内置插件到 assets ==="
  rm -f "$ASSETS_DIR"/*.xipk
  for name in $BUNDLED_PLUGINS; do
    if ls "$PROJECT_DIR/build/plugin-release/$name"-*.xipk >/dev/null 2>&1; then
      cp "$PROJECT_DIR/build/plugin-release/$name"-*.xipk "$ASSETS_DIR/"
      echo "  ↳ $name"
    else
      echo "  ! 未找到 $name 的 xipk 产物"
      exit 1
    fi
  done
fi

echo ""
echo "=== 完成 ==="
ls -lh "$PROJECT_DIR/build/plugin-release"/*.xipk
