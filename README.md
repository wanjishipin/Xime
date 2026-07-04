<p align="center">
  <img src="docs/logo.jpg" alt="Xime Logo" width="600">
</p>

<h1 align="center">Xime（曦码） - 五笔/拼音输入法</h1>

[Xime 输入法 (Windows 版)](https://github.com/ximeiorg/winxime) | [Xime 输入法 (Linux 版)](https://github.com/ximeiorg/xime-wayland) | [联想词预测模型](https://github.com/ximeiorg/predictive-text)  | [手写输入法模型](https://github.com/ximeiorg/ochwpro) 

一款基于 <a href="https://rime.im/">Rime</a> 引擎构建的 Android 五笔/拼音输入法，专注于简洁高效的中文输入体验。

如果你觉得 UI 或者功能不符合你的要求，你可以直接 fork 一份自行修改。

---

> 本输入法支持五笔/拼音输入，只是本人以五笔为主，拼音为辅，因此资源会倾向于五笔为主。


<table align="center">
  <tr>
    <td><img src="docs/Screenshot/full_keyboard_light.jpg" width="180"><br><p align="center">全键盘（亮色）</p></td>
    <td><img src="docs/Screenshot/full_keyboard_dark.jpg" width="180"><br><p align="center">全键盘（暗色）</p></td>
    <td><img src="docs/Screenshot/全键盘_下滑_light.jpg" width="180"><br><p align="center">字根下滑</p></td>
    <td><img src="docs/Screenshot/shotcut_light.jpg" width="180"><br><p align="center">快捷操作</p></td>
  </tr>
  <tr>
    <td><img src="docs/Screenshot/floating.jpg" width="180"><br><p align="center">悬浮键盘</p></td>
    <td><img src="docs/Screenshot/t9_pinyin.jpg" width="180"><br><p align="center">T9 九宫格拼音</p></td>
    <td><img src="docs/Screenshot/number.jpg" width="180"><br><p align="center">数字键盘</p></td>
    <td><img src="docs/Screenshot/symbol.jpg" width="180"><br><p align="center">符号键盘</p></td>
  </tr>
  <tr>
    <td><img src="docs/Screenshot/hw.png" width="180"><br><p align="center">手写输入</p></td>
    <td><img src="docs/Screenshot/hw2.png" width="180"><br><p align="center">手写找字（候选）</p></td>
    <td><img src="docs/Screenshot/voice.jpg" width="180"><br><p align="center">语音输入</p></td>
    <td><img src="docs/Screenshot/emoji.jpg" width="180"><br><p align="center">Emoji 键盘</p></td>
  </tr>
  <tr>
    <td><img src="docs/Screenshot/theme_light.jpg" width="180"><br><p align="center">主题设置（亮色）</p></td>
    <td><img src="docs/Screenshot/theme_dark.jpg" width="180"><br><p align="center">主题设置（暗色）</p></td>
    <td><img src="docs/Screenshot/plugin_light.jpg" width="180"><br><p align="center">插件管理</p></td>
    <td><img src="docs/Screenshot/方案市场.jpg" width="180"><br><p align="center">方案市场</p></td>
  </tr>
  <tr>
  </tr>
</table>

## 功能特点

- **多种输入方案** - 内置五笔86/98、拼音、混输方案，支持自定义（双拼、笔画等），可通过方案市场下载或无线导入
- **Rime 引擎** - 使用成熟稳定的 Rime 输入法引擎，精准可靠的中文输入体验
- **丰富键盘布局** - QWERTY 全键盘、T9 九宫格拼音、九宫格笔画、手写、数字（含计算器）
- **悬浮键盘** - 悬浮卡片样式，支持拖拽移动、半透明圆角设计
- **语音转文本** - 支持阿里百炼 FunAsr（在线）和 sherpa-onnx（本地离线）双引擎
- **AI 智能增强** - 基于 Transformer 的联想词预测和标点预测，输入更高效
- **简洁界面** - Material Design 3 风格，支持浅色/深色主题及多种配色方案
- **键盘调节** - 支持键盘高度调整和位置移动
- **工具栏定制** - 可自定义工具栏按钮布局和功能
- **按键反馈** - 可调节音效和振动强度
- **滑动手势** - 光标移动、删除、符号输入等滑动手势操作
- **剪贴板管理** - 剪贴板历史记录，支持快捷发送和置顶
- **候选词编码提示** - 候选词显示五笔编码，辅助学习
- **字根显示** - 下滑按钮显示五笔字根，方便健忘用户
- **实体键盘支持** - 连接物理/蓝牙键盘时显示浮动候选栏
- **WebDAV 同步** - 通过 WebDAV 备份和恢复方案与配置
- **表情插件** - 支持扩展表情插件（颜文字、表情包等）

## 系统要求

- Android 9.0 (API 28) 及以上

## 安装

### 主程序下载
选择对应架构的APK：
- **arm64-v8a**: 适用于大多数现代手机（**绝大部分人的手机都是这个**⬅⬅⬅）
- **armeabi-v7a**: 适用于旧款32位手机
- **x86_64**: 适用于模拟器
- **universal**: 包含所有架构，体积较大

### 插件下载（可选）
插件为独立 APK，安装后可在主应用中启用：
- **meme-bunny**: 恶搞兔表情包插件（提供8个表情）
- **kaomoji**: 颜文字插件（提供精选颜文字）

### 从 Release 下载

1. 在 [Releases](https://github.com/ximeiorg/Xime/releases) 页面下载最新版本的 APK
2. 安装应用
3. 在系统设置中启用 Xime 输入法
4. 将 Xime 设为当前输入法

### 国内下载
由于 apk 包是通过 github actions 自动构建的，国内的仓库没有免费的功能使用，因此如果你觉得github release 不稳定，请自行构建安装，或者通过[https://github.akams.cn](https://github.akams.cn) 来下载。

### 手动构建安装

1. 克隆项目并构建 APK
2. 安装应用
3. 在系统设置中启用 Xime 输入法
4. 将 Xime 设为当前输入法

## 使用文档

详细使用说明请查看 [使用文档](https://ime.ximei.me)。

## 构建

```bash
# 克隆项目（包含子模块）
git clone --recursive https://github.com/ximeiorg/Xime.git

# 或者在已克隆的项目中初始化子模块
git submodule update --init --recursive

# 构建 Release APK
./gradlew assembleRelease
```

### 本地语音识别构建

项目支持本地离线语音识别（基于 sherpa-onnx）。首次构建时会自动下载并编译 JNI 库。

如果自动构建失败，可手动执行：

```bash
# 手动构建 sherpa-onnx JNI 库
./build-sherpa-onnx.sh
```

构建完成后，会在 `app/src/main/jniLibs/` 生成 `libsherpa-onnx-jni.so`。

本地 ASR 模型可在应用内设置页面下载。

### AI 模型下载

#### 智能联想词模型

- **项目地址**: https://github.com/ximeiorg/predictive-text
- **模型下载**: https://www.modelscope.cn/models/bikeand/predictive-text-small
- **模型文件**: `model_int8_dynamic.onnx` (约 17MB)
- **词表文件**: `vocab.json`
- **存放位置**: `filesDir/` 目录（即应用私有目录根目录）
- **功能**: 基于 Transformer 的中文联想词预测，提供智能候选词推荐

#### 标点预测模型

- **项目地址**: https://github.com/ximeiorg/srf-punctuation
- **在线演示**: https://srf-punctuation.ximei.me/
- **模型下载**: https://www.modelscope.cn/models/bikeand/srf-punctuation
- **模型文件**: `punctuation_int8.onnx` (约 2.2MB)
- **词表文件**: `vocab.json`
- **存放位置**: `filesDir/punctuation_models/` 目录
- **功能**: 基于 Transformer 的中文标点预测，语音识别后自动添加标点

**注意**: 所有模型均可直接在应用内"设置 > 智能联想/语音识别"页面下载，无需手动放置。


## 技术栈

- Kotlin
- Jetpack Compose
- Material Design 3
- Rime (librime)
- JNI (Native C++)

## 贡献

欢迎贡献！在提交 PR 之前，请先阅读 [CONTRIBUTING.md](CONTRIBUTING.md) 了解贡献流程。

核心规则：
- **先提 Issue** — 所有改动必须先创建 Issue 讨论
- **最小修改** — PR 只包含所需的最小改动
- **GPG 签名** — 所有 commit 必须 GPG 签名

## 致谢

- [Rime](https://rime.im/) - 中州韵输入法引擎
- [Trime](https://github.com/osfans/trime) - 同文输入法，部分实现参考
- [Linux Do](https://linux.do) - 中文开发社区

## Star History

<a href="https://www.star-history.com/?repos=ximeiorg/Xime&type=date&legend=top-left">
 <picture>
   <source media="(prefers-color-scheme: dark)" srcset="https://api.star-history.com/chart?repos=ximeiorg/Xime&type=date&theme=dark&legend=top-left" />
   <source media="(prefers-color-scheme: light)" srcset="https://api.star-history.com/chart?repos=ximeiorg/Xime&type=date&legend=top-left" />
   <img alt="Star History Chart" src="https://api.star-history.com/chart?repos=ximeiorg/Xime&type=date&legend=top-left" />
 </picture>
</a>

## 许可证

GPLv3 License
