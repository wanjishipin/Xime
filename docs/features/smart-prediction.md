# 智能联想

Xime 内置智能联想词功能，可在用户输入后自动预测下一个可能的词语，提升输入效率。

## 功能特点

- **双引擎支持** - 中文联想基于 ONNX 模型，英文联想基于 Trie 树
- **用户学习** - 记录用户输入习惯，个性化联想结果
- **离线运行** - 模型下载后无需联网即可使用

## 工作原理

### 中文联想

中文联想使用 ONNX Runtime 运行轻量级预测模型：

1. 用户输入文字后，ONNX 模型预测可能的下一个词语
2. NgramFusionEngine 融合用户历史输入数据优化排序
3. 在候选栏显示联想词

### 英文联想

英文联想使用 Trie 树实现：

1. 根据当前输入前缀查找匹配单词
2. 按词频排序返回候选词

### 模型来源

联想预测模型项目地址：
[https://github.com/ximeiorg/predictive-text](https://github.com/ximeiorg/predictive-text)

## 启用方法

1. 进入输入法设置页面
2. 找到「智能联想」选项
3. 开启开关即可启用

## 模型下载

智能联想需要下载预测模型才能使用：

- 模型位置：`应用私有目录/files/`
- 模型文件：`model_int8_dynamic.onnx`、`vocab.json`
- 模型来源：可配置模型仓库地址

### 配置模型仓库

在设置页面可自定义模型下载地址，默认使用 ModelScope 仓库。

## 技术细节

### 相关文件

| 文件 | 说明 |
|------|------|
| `AssociationService.kt` | 联想服务入口，统一调度中英文联想 |
| `AssociationManager.kt` | 中文联想管理器，协调模型与融合引擎 |
| `OnnxAssociationEngine.kt` | ONNX 模型推理引擎（中文联想） |
| `TrieAssociationEngine.kt` | Trie 树联想引擎（英文联想） |
| `NgramFusionEngine.kt` | 用户历史数据融合引擎 |
| `PredictionManager.kt` | 预测生命周期管理 |

## 性能优化

- 模型采用 INT8 量化，减小体积和推理延迟
- 异步初始化，不阻塞主线程
- 懒加载机制，首次使用时才初始化
- 候选词缓存，减少重复计算