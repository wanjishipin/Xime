# 清除 Kime 主应用的所有插件数据
#
# 使用方式：
#   powershell -File scripts/clear-plugins.ps1
#
# 需要 adb 在 PATH 中，且设备已连接

$PackageName = "com.kingzcheung.kime"
$PluginsDir = "/data/data/$PackageName/files/plugins"

Write-Host "=== 清除 Kime 插件数据 ===" -ForegroundColor Cyan

# 检查 adb 是否可用
try {
    $devices = adb devices 2>&1
    if ($devices -notmatch "device") {
        Write-Host "错误: 未检测到已连接的设备" -ForegroundColor Red
        exit 1
    }
} catch {
    Write-Host "错误: adb 未找到，请确保 adb 在 PATH 中" -ForegroundColor Red
    exit 1
}

# 清除插件文件目录
Write-Host "清除插件文件目录..."
adb shell "rm -rf $PluginsDir" 2>$null

# 清除插件配置 SharedPreferences
Write-Host "清除插件配置..."
adb shell "rm -rf /data/data/$PackageName/shared_prefs/plugin_*.xml" 2>$null
adb shell "rm -rf /data/data/$PackageName/shared_prefs/plugins.xml" 2>$null

# 列出并删除所有插件相关的 SharedPreferences
Write-Host "清除所有插件相关 SharedPreferences..."
$files = adb shell "ls /data/data/$PackageName/shared_prefs/" 2>$null
foreach ($file in $files) {
    $trimmedFile = $file.Trim()
    if ($trimmedFile.StartsWith("plugin_") -or $trimmedFile.StartsWith("plugins")) {
        adb shell "rm -f /data/data/$PackageName/shared_prefs/$trimmedFile" 2>$null
        Write-Host "  已删除: $trimmedFile"
    }
}

Write-Host "=== 完成 ===" -ForegroundColor Green
Write-Host "请重启 Kime 应用以重新加载插件" -ForegroundColor Yellow