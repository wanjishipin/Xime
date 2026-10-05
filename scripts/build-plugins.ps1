# Build all TS plugin xipk packages (Rust CLI: tools/xime-plugin).
#
# Usage:
#   .\scripts\build-plugins.ps1
#   .\scripts\build-plugins.ps1 --with-assets
#
# Plugin sources are TypeScript (plugins/<name>/main.ts + manifest.json + resources/);
# the xipm CLI compiles them to an IIFE single-file main.js and packs the xipk.

$ErrorActionPreference = "Stop"

$PROJECT_DIR = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path

# 内置插件（debug 构建从 assets 自动安装的集合）
$BUNDLED_PLUGINS = @("ai-reply", "ai-translate", "ai-write", "webdav-clipboard-sync")

$WITH_ASSETS = $false
$CLI_ARGS = @()
foreach ($arg in $args) {
    if ($arg -eq "--with-assets") { $WITH_ASSETS = $true } else { $CLI_ARGS += $arg }
}

Write-Host "=== Building TS plugin xipk packages (xipm CLI) ==="

Set-Location (Join-Path $PROJECT_DIR "tools\xime-plugin")
cargo run --quiet -- pack --all `
  --plugins-dir (Join-Path $PROJECT_DIR "plugins") `
  --out (Join-Path $PROJECT_DIR "build\plugin-js") `
  --release-dir (Join-Path $PROJECT_DIR "build\plugin-release") `
  @CLI_ARGS

if ($WITH_ASSETS) {
    $ASSETS_DIR = Join-Path $PROJECT_DIR "app\src\main\assets\plugins"
    Write-Host ""
    Write-Host "=== 同步内置插件到 assets ==="
    Get-ChildItem -Path $ASSETS_DIR -Filter "*.xipk" -File -ErrorAction SilentlyContinue |
        Remove-Item -Force
    foreach ($name in $BUNDLED_PLUGINS) {
        $xipk = Get-ChildItem -Path (Join-Path $PROJECT_DIR "build\plugin-release") -Filter "$name-*.xipk" -File -ErrorAction SilentlyContinue |
            Select-Object -First 1
        if (-not $xipk) { throw "未找到 $name 的 xipk 产物" }
        Copy-Item $xipk.FullName $ASSETS_DIR
        Write-Host "  ↳ $($xipk.Name)"
    }
}

Write-Host ""
Write-Host "=== Done ==="
Get-ChildItem -Path (Join-Path $PROJECT_DIR "build\plugin-release") -Filter "*.xipk" -File |
    Sort-Object Name |
    ForEach-Object { "{0,10:N1} K  {1}" -f ($_.Length / 1KB), $_.Name }
