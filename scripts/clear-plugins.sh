#!/bin/bash
# 清除 Kime 主应用的所有插件数据
# 
# 使用方式：
#   bash scripts/clear-plugins.sh
#
# 需要 adb 在 PATH 中，且设备已连接

PACKAGE_NAME="com.kingzcheung.kime"
PLUGINS_DIR="/data/data/$PACKAGE_NAME/files/plugins"

echo "=== 清除 Kime 插件数据 ==="

# 检查设备连接
if ! adb devices | grep -q "device$"; then
    echo "错误: 未检测到已连接的设备"
    exit 1
fi

# 清除插件目录
echo "清除插件文件目录..."
adb shell "rm -rf $PLUGINS_DIR" 2>/dev/null

# 清除插件配置 SharedPreferences
echo "清除插件配置..."
adb shell "rm -rf /data/data/$PACKAGE_NAME/shared_prefs/plugin_*.xml" 2>/dev/null
adb shell "rm -rf /data/data/$PACKAGE_NAME/shared_prefs/plugins.xml" 2>/dev/null

# 清除插件相关的 SharedPreferences (以 plugin_ 开头的)
echo "清除所有插件相关 SharedPreferences..."
adb shell "ls /data/data/$PACKAGE_NAME/shared_prefs/" 2>/dev/null | while read file; do
    if [[ "$file" == plugin_* ]] || [[ "$file" == plugins* ]]; then
        adb shell "rm -f /data/data/$PACKAGE_NAME/shared_prefs/$file" 2>/dev/null
        echo "  已删除: $file"
    fi
done

echo "=== 完成 ==="
echo "请重启 Kime 应用以重新加载插件"