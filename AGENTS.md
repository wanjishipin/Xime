# Xime 输入法

## 项目简介
这是一个基于 rime 框架实现的安卓手机输入法，采用 kotlin + jetpack compose 构建。

## 快速开始
- 构建： `./gradlew assembleDebug --quiet`
- 测试： `./gradlew test`

## 插件开发
- 清除插件数据： `./gradlew clearPlugins`
- 完全卸载主应用： `./gradlew uninstallApp`
- [插件开发指南](docs/plugins/PLUGIN_DEVELOPMENT_GUIDE.md) - 开发插件时必读

## 硬性规则（必须遵守，CI 会验证）

## 工作规则
- 每次只做一个功能点
- 当前功能点端到端验证通过后，才能开始下一个
- 不要在实现功能 A 时"顺便"重构功能 B
- 当觉得有必要时，就添加单元测试


## 每次会话开始时（上班打卡）
1. 读 PROGRESS.md 了解当前状态
2. 读 DECISIONS.md 了解重要决策
3. 从 PROGRESS.md 的"下一步"部分继续工作

## 每次会话结束前（下班打卡）
1. 更新 PROGRESS.md
2. 跑 `./gradlew assembleDebug --quiet` 确认一致状态
3. 提交所有已完成的工作