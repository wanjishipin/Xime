# 插件能力注册表（Capability Registry）设计

> 日期：2026-09-01
> 背景：为「候选词变换」（插件可修改候选栏内容）设计插件 API 时，审查发现插件系统能力协议层存在结构性混乱。本文档定义收敛方案，并给出首个接入输入主流程的能力 `candidate_transform` 的完整规范与分阶段迁移计划。
> 关系：本文档是 `plugin-system-audit.md`（2026-08-28 架构盘点）的延续——审计文档第 8 节「设计原则」的修订与第 6 条结论「能力声明消费端需要统一框架」的落地设计。

## 1. 现状诊断：能力协议的乱象

骨架层（生命周期、崩溃隔离、签名、网络三重门、配置隔离）是健康的。乱的是**新增一个能力的成本与身份表达**：

### 1.1 新增一个能力要动 6 处

1. `PluginCategory` 加枚举（分类驱动消费入口 + 激活模式）
2. `PluginCapabilities` 加字段和参数类（注释自称"宿主消费能力的唯一来源"）
3. Kotlin 接口（EmojiPlugin / ToolPlugin / AsrPlugin / ClipboardSyncPlugin 各一套）
4. `LuaPluginContract` 注册 Lua 契约函数名
5. 新写一个 Lua Adapter（已有 5 个：Lua / LuaEmoji / LuaTool / LuaAsr / LuaClipboardSync）
6. 宿主消费点接线（ExtensionManager per-category 查询 + 各消费页面）

### 1.2 `type` 与 `capabilities` 双轨重叠

`PluginCategory`（manifest `type` 字段）与 `PluginCapabilities`（manifest `capabilities` 节点）表达的是**同一个插件身份的两半**，且语义重叠：

| PluginCategory | PluginCapabilities 字段 | 重叠内容 |
|---|---|---|
| EMOJI | `emoji` | 同一能力 |
| ASR | `speech` | 同一能力 |
| TOOL | `tool` | 同一能力 |
| CLIPBOARD_SYNC | `clipboardSync` | 同一能力 |
| PREDICTION | （无） | 预留未实现 |

实例证据（`plugins/typing-stats/manifest.yaml`）：同一文件里 `type: tool` 与 `capabilities.tool.display: passive` 写的是同一个身份的两半。

### 1.3 交互模型的执行底座其实只有两种

盘点全部现有能力（见 audit 文档 §2），底层执行模型只有两种：

- **同步调用**（宿主等待插件返回值）：getEmojis / getPanelState / pull() / getSettingsSchema
- **异步投递**（宿主不等）：input_changed / text_committed 事件、clipboard push、ASR PCM 推流（+ 异步回调回传结果）

混乱的表象（每能力一套说辞）掩盖了统一的底座，这为收敛提供了条件。

## 2. 设计原则

1. **单一事实来源**：manifest `capabilities` 是插件身份与能力的唯一来源；`type` 退役（过渡期降级为纯 UI 分组展示）。
2. **注册表驱动**：能力 = 注册表一行；新能力不再新造机制，六处接线从注册表派生。
3. **执行模型两分**：SYNC（宿主等结果，必须有硬超时预算）/ FIRE_AND_FORGET（宿主不等）。任何能力必须显式声明归属。
4. **hotPath 显式化**：是否接入输入主流程（按键路径）是能力的显式属性，主流程豁免条款只对 `hotPath=true` 的能力生效。
5. **双形态收敛在解析层**：APK 插件（XML meta-data）与 Lua 插件（manifest.yaml）解析产物统一进 `PluginInfo.capabilities`，上层不感知载体差异。
6. **数据出入门沿用审计原则 1**：任何用户数据离开 IME 进插件，必须 capability 声明 + 启动前校验 + 设置页可见开关（模板 = clipboardSync）。

## 3. 能力注册表

### 3.1 模型定义（plugin-core）

```kotlin
/** 交互语义（给人看的分类，对齐 audit 文档三类 + 面板） */
enum class PluginInteraction { PULL, EVENT, STREAM, PANEL }

/** 执行模型（机器判定的底座） */
enum class PluginExecution { SYNC, FIRE_AND_FORGET }

enum class PluginCapabilityDef(
    val manifestKey: String,        // capabilities 下的 key
    val label: String,              // 管理页展示名
    val interaction: PluginInteraction,
    val execution: PluginExecution,
    val luaFunctions: List<String>, // Lua 契约函数（声明了能力才允许宿主分发调用）
    val hotPath: Boolean,           // 是否接入输入主流程（按键路径）
    val timeoutMs: Long?,           // SYNC 必填硬超时；FIRE_AND_FORGET 为 null
) { ... }
```

### 3.2 现有能力映射（存量收编，行为不变）

| manifestKey | interaction | execution | luaFunctions | hotPath | timeout | 备注 |
|---|---|---|---|---|---|---|
| `emoji` | PULL | SYNC | getCategories / getEmojis | false | 沿用现状（180s） | 搜索通路待接线（audit §7.4） |
| `speech` | STREAM | FIRE_AND_FORGET（回调） | onLoad 等 + host.asr.emit* | false | 回调 5s | 宿主推 PCM，插件异步回推结果 |
| `tool` | PANEL | SYNC（getPanelState）+ FIRE_AND_FORGET（onPanel*） | getPanelState / onPanelInput / onPanelAction / onPanelItemClick | false | 沿用现状（180s） | 200ms 轮询待改 `panelStateChanged`（audit §7.2） |
| `clipboard_sync` | EVENT + PULL | FIRE_AND_FORGET（push）+ SYNC（pull） | push / pull | false | 沿用现状 | 出入门管控模板 |
| `events` | EVENT | FIRE_AND_FORGET | onPluginEvent | false | conflated 投递 | 事件订阅（input_changed / text_committed） |
| `prediction` | PULL | SYNC | （预留） | false | 待定 | PREDICTION 消费页未实现（audit §7.3） |
| `candidate_transform` | PULL | SYNC | transformCandidates | **true** | **15ms** | 首个 hotPath 能力，规范见 §5 |
| `quick_send_read` | PULL | SYNC | host.quickSend.list() | false | 沿用现状（180s） | 快捷发送只读（ClipboardManager 内存缓存，零 IO）；配 `quick_send_changed` 事件做缓存刷新 |
| `clipboard_read` | PULL | SYNC | host.clipboard.get() | false | 沿用现状（180s） | 剪贴板当前文本只读；外发仍受网络三重门约束 |

### 3.3 manifest 规范（统一后）

```yaml
id: com.example.plugin
name: ...
version: ...
entry: main.lua

capabilities:            # 插件身份与能力的唯一来源
  emoji:                 # 静态参数节点（现有，不变）
    supportsSearch: true
  events:                # 事件订阅（现有，不变）
    - input_changed
  candidate_transform: true   # 布尔型能力（新增）

type: tool               # 过渡期保留：仅作插件管理页分组展示；阶段 3 退役
```

规则：
- `capabilities` 未声明的能力，宿主**不建立通道、不分发调用**（未声明零开销，沿用事件系统模式）。
- 布尔型能力（无参数）用 `true`；带静态参数的能力用节点（现有 emoji/speech/tool/clipboard_sync 模式不变）。
- 未知能力字段：解析期忽略（沿用现有 sanitize），注册表校验时对"已注册但未声明"的能力静默跳过。

## 4. 交互模型与安全策略矩阵

| interaction | execution | 数据流出管控 | 线程与超时 | 敏感输入豁免 |
|---|---|---|---|---|
| PULL | SYNC | 请求体含用户数据（编码/候选/上下文）→ 必须声明 | 调用方线程 + 硬超时（hotPath=15ms，非 hotPath 沿用 180s）+ hotPath 熔断 | hotPath 必须 |
| EVENT | FIRE_AND_FORGET | payload 含用户数据 → 必须声明 events | conflated channel + 插件执行器 | 敏感输入不投递（已实现） |
| STREAM | FIRE_AND_FORGET（回调回推） | 音频等 → 必须声明 | 专用执行器 + 回调 5s | 录音由用户主动触发 |
| PANEL | 混合 | 上下文文本 → 待补声明（audit §3 管控缺口） | 200ms 轮询（待改事件通知） | 待补 |

### 4.1 审计原则 4「主流程零等待」的豁免条款（修订）

原文：插件调用一律异步投递，主流程只 trySend。

修订为：**主线程 / 主调度器永不阻塞插件调用；但 `hotPath=true` 的 PULL 能力允许在 key-processing 线程同步等待插件结果**，条件（全部满足才豁免）：

1. 硬超时 ≤ 15ms，超时回退原始数据（不重试、不阻塞后续）；
2. 连续超时熔断（连续 3 次超时 → 本会话禁用该插件此能力）；
3. 敏感输入框（复用 `PluginEventDispatcher.isSensitiveEditor` 判定）短路，不产生请求；
4. 不持有 `rimeLock` 等引擎锁等待（变换发生在引擎结果返回之后，锁已释放）。

## 5. 首个 hotPath 能力：candidate_transform

### 5.1 需求语义

用户场景（快捷发送）：rime 返回候选后、候选栏渲染前，插件根据当前编码串查询快捷内容，**替换部分候选或追加新候选**；用户点击插件候选时**上屏插件文本**（所见即所得）。

### 5.2 manifest 声明

```yaml
capabilities:
  candidate_transform: true
```

### 5.3 Lua 契约（LuaPluginContract 新增 FN_TRANSFORM_CANDIDATES）

```lua
function plugin.transformCandidates(request)
  -- request = {
  --   input_text = "nh",        -- 原始键入串（无分隔符）
  --   preedit     = "ni'hao",   -- 引擎回显（带分隔符）
  --   candidates  = { {text="你好", comment=""}, ... },  -- ascii 过滤后的当前引擎候选
  --   ascii_mode  = false,
  -- }
  -- 返回 nil = 不干预（渲染原始候选）
  return {
    candidates = {
      { engine_index = 0 },                      -- 引用引擎候选：点击走引擎选词；comment 可选覆盖显示
      { text = "快捷短语A", comment = "快捷" },   -- 插件候选：点击直接上屏 text
    }
  }
end
```

校验规则（宿主侧，违规即丢弃）：
- `engine_index` 越界或重复 → 丢弃该项；
- `text` 为空 → 丢弃该项；
- 响应候选总数上限 20；
- 响应格式错误 / 超时 / Lua 报错 → 整体回退原始候选。

### 5.4 宿主实现要点

**数据模型**：

```kotlin
data class CandidateAction(
    val engineIndex: Int,      // >= 0 引用引擎候选；-1 为插件候选
    val commitText: String,    // 插件候选的上屏文本（engineIndex < 0 时有效）
)
// CandidateState 新增：candidateActions: List<CandidateAction> = emptyList()
// 默认空 = 纯引擎语义（displayIndex == engineIndex），所有现有路径零影响
```

**挂钩点**（全部在 key-processing 线程，主线程零等待）：

1. `ImeKeyRouter` 主字母路径（:559 附近）拿到 `RimeProcessResult` 并完成 ascii 过滤后调用变换；
2. delete 路径（:804 附近，退格后候选仍需匹配快捷短语）；
3. 内联刷新路径（:649 附近，getInput + getCandidatesWithComments 分支）；
4. 翻页路径（`pageUp`/`pageDown` → `service.updateUI()`）：实现时确认 `updateUI()` 取数点所在线程——若在 key-processing 线程则翻页后重新变换（推荐，保证插件候选跨页存在）；否则翻页后回退纯引擎候选（actions 清空）。

**变换协调器**（新建 `CandidateTransformCoordinator`，service 组件，模式仿 PluginEventDispatcher）：
- 前置条件（全部满足才调用）：存在声明 `candidate_transform` 的已启用插件 + 非敏感输入 + 中文模式 + 编码非空；
- 每能力一插件：v1 仅取第一个匹配插件，多插件链式变换列为后续增强；
- 连续 3 次超时 → 本会话熔断（FileLogger 记录）。

**选词分流**（`ImeKeyRouter.selectCandidateAsync` :872 入口）：

```kotlin
val action = service.candidateState.value.candidateActions.getOrNull(index)
if (action != null && action.engineIndex < 0) {
    // 插件候选：直接上屏 + clearComposition + 清空候选状态（复用 enter 分支 :294-306 的直接提交模式）
    return
}
// 引擎候选：现有逻辑；engineIndex 已知时跳过 resolveRimeCandidateIndex（仅作兜底）
```

T9 分支不受影响（见 5.5 边界）。

**数字选词拦截**（关键，否则引擎绕过插件候选）：
中文组合态 + `candidateActions` 非空时，数字键 `'1'..'9'` 不再进入 `processKeyAndGetResult`（rime 会自行选词返回 committedText，绕过映射），改为映射到 `selectCandidateAsync(n-1)`。物理键盘 KEYCODE_1-9 已收口在 `keyRouter.selectCandidate`（XimeInputMethodService.kt:1479-1488），天然走分流，无需改动。

### 5.5 失败降级

| 场景 | 行为 |
|---|---|
| 插件超时（15ms）/ Lua 报错 / 返回 nil / 格式错误 | 原始候选原样渲染，actions 为空 |
| 敏感输入框 | 不产生请求，直接跳过 |
| 无插件声明该能力 | 零开销（不建通道、不调用） |
| 连续 3 次超时 | 本会话熔断该插件，恢复原始候选直至插件重载 |

### 5.6 边界与后续

- **v1 不覆盖 T9**：T9 路径（applyComposition 在主线程）恒不启用变换，candidateActions 恒空，T9 逻辑零影响；T9 接入列为后续独立功能点。
- **ascii 模式跳过**：英文模式不调用变换。
- **插件候选是整段直接上屏**：不走引擎的段落续接语义（如 `nihaoshijie` 选前半）；文档向插件作者明示。
- **快捷发送数据源**：v1 示例插件使用插件自有数据（Lua 内存表）；宿主快捷发送数据的只读桥接 API（如 `xime.getQuickSends()`）列为后续增强。
- **多插件链式变换**、**候选列表插件可全量重排**（当前 engine_index 引用 + 插件文本两分，不支持中间任意插入保持引擎语义）列为后续增强。

## 6. 迁移计划（分阶段 → 已合并一次性实现）

> 2026-09-01 更新：应项目决策，阶段 1（candidate_transform 全链路）已一次性实现完成；
> 阶段 2 的注册表核心（能力声明 + Lua 契约 + 宿主协调器）随阶段 1 一并落地。
> 阶段 3（type 退役）与阶段 4（Adapter 收敛）为纯重构，与功能无关，仍按独立 PR 排期。

| 阶段 | 内容 | 状态 |
|---|---|---|
| **0（本文档）** | 规范基线：注册表模型 + 能力映射表 + 安全策略矩阵 + 豁免条款 | ✅ 完成 |
| **1** | 实现 `candidate_transform`：PluginCapabilities 加布尔字段 + LuaPluginContract 注册函数 + LuaScriptRuntime 桥接（15ms 超时/解析/校验）+ 宿主挂钩（§5.4）+ 单元测试 | ✅ 完成（2026-09-01） |
| **2** | 注册表代码化：`PluginCapabilityDef` 枚举落进 plugin-core（纯数据），InstallerManager 校验统一走注册表；ExtensionManager 新增 `getEnabledWithCapability(key)`，消费点渐进切换 | 部分落地（能力声明/契约/协调器已按规范实现；`PluginCapabilityDef` 枚举待抽为纯数据） |
| **3** | `type` 退役：PluginCategory 降级为"由 capabilities 派生的展示分组"；manifest schema 文档更新；PREDICTION 消费页落地时同步 | 未开始 |
| **4（低优）** | Lua Adapter 收敛：5 个 Adapter → 1 个通用 binder + 每能力转换器；manifest 解析层统一 | 未开始 |

### 阶段 1 落地清单（2026-09-01）

**plugin-core**：
- `model/PluginCapabilities.kt`：`candidateTransform: Boolean`（`@SerialName("candidate_transform")`）
- `runtime/installer/InstallerManager.kt`：`CapabilitiesConfig.candidateTransform`（kaml 解析）+ `toModel` 透传
- `runtime/installer/XmlManager.kt`：capabilities JSON 编解码补 `candidateTransform`
- `lua/CandidateTransform.kt`：Request / Candidate / Item / Outcome（sealed）数据模型
- `lua/sdk/LuaPluginContract.kt`：`FN_TRANSFORM_CANDIDATES = "transformCandidates"` + 契约文档
- `lua/LuaScriptRuntime.kt`：`transformCandidates(request)` 同步桥接（15ms 超时不中毒、解析、非法项丢弃、20 上限截断）
- 测试：`LuaCandidateTransformTest`（11 用例）

**app**：
- `service/CandidateAction.kt`：上屏动作模型（引擎引用 / 插件候选）
- `service/CandidateState.kt`：`candidateActions` 字段（默认空 = 纯引擎语义）
- `service/CandidateTransformCoordinator.kt`：前置条件短路（敏感输入/T9/ascii/空编码/无插件/熔断）+ `buildDisplay` 纯函数映射（越界/重复/空文本校验）
- `service/PluginEventDispatcher.kt`：暴露 `isCurrentEditorSensitive`
- `service/ImeSessionController.kt`：`updateUIWithResult` / `applyComposition` 加 `pluginActions` 参数
- `service/XimeInputMethodService.kt`：实例化 Coordinator；`updateUI()` 挂钩（含主线程 Looper 防御）
- `service/ImeKeyRouter.kt`：`sendTransformedResult` 统一挂钩（字母/退格/词分隔/内联刷新路径）、`selectCandidateAsync` 按 actions 分流 + `commitPluginCandidate`（插件候选直接上屏）、数字选词拦截、全部候选状态清空点补 `candidateActions = emptyList()`
- 测试：`CandidateTransformCoordinatorTest`（8 用例，buildDisplay 纯函数）

**示例插件**：`plugins/quick-phrase/`（常用语，manifest 声明 `candidate_transform: true`；前缀匹配快捷短语追加候选；`scripts/build-plugins.sh` 自动扫描打包，无需注册，不进 assets 预装）

## 7. 不做的事（明确排除）

- 不推翻现有插件骨架（生命周期/安全/网络门/配置存储），只收敛能力协议层。
- 不合并 APK/Lua 双形态（各有存在理由：ASR 需要 SDK，Lua 面向轻量插件），收敛点仅在 manifest 解析产物。
- 不在实现 candidate_transform 的 PR 里顺手做阶段 2-4 的重构（遵守"每次只做一个功能点"）。
