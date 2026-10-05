# 插件系统架构盘点（2026-08-28）

> 目的：在引入"输入事件订阅"之前，整体审视插件系统，统一交互模型与数据流出管控。
> 结论先行：事件系统不是新造通道，而是**通用化已有的 ASR 推送模式**，并顺手解决 Tool 轮询、emoji 搜索未接线、PREDICTION 未实现三个遗留问题。

## 1. 模块与生命周期

- `plugin-core` 独立 Gradle 模块，Lua 插件框架（luaj）。
- 组合根：`PluginManager`（单例门面，工厂注入）→ `PluginFrameworkContext` → `PluginLifecycleManager`（加载/卸载执行者）。
- 安装：`.xipk`（zip）→ `InstallerManager`（≤10MB、防 zip bomb、防路径穿越、manifest.yaml → `PluginInfo`）→ `XmlManager` 注册表。
- 启动：版本兼容检查 → 建 `LuaScriptRuntime`（此刻注入全部 HostApi）→ 按 `PluginCategory` 选适配器 → `onLoad` → Lua `onLoad()`。
- 生命周期钩子：Lua 侧 `onLoad`/`onUnload`；宿主侧 `pluginInstancesFlow` 驱动 UI（如 `closeToolPanelIfPluginGone`）。

## 2. 交互模型分类（现状全景）

| 插件类别 | 模型 | 说明 |
|---|---|---|
| EMOJI | **数据提供**（宿主拉取，无回流） | 预载时拉 `getCategories`/`getEmojis`；点击行为全部宿主处理，无事件给插件；**搜索通路 `getEmojis(searchText)` 已铺好但无 UI 调用方**（ExtensionManager.kt:274-289） |
| TOOL | 请求-响应 + **轮询** | `onPanelInput` → `onPanelAction("generate")` → 宿主每 200ms 轮询 `getPanelState` 直到 loading=false（XimeInputMethodService.kt:906-921） |
| ASR | **双向流（推送先例）** | 宿主主动推 PCM `processAudioChunk`，插件经 `host.asr.emit*` 回推识别结果 |
| CLIPBOARD_SYNC | 推 + 拉 | `push(profile)`（剪贴板变化推送）、`pull()`（手动拉取，无后台轮询） |
| PREDICTION | **未实现** | 枚举已预留（PluginCategory.kt:16），宿主无任何加载/调用代码；`getPredictionFromPlugin` 名不副实，实际走本地 ONNX 模型 |

交互模型归纳为三类：
1. **拉取 API**：插件向宿主要数据（未来形态，当前少）
2. **事件订阅**：宿主向插件推事件（当前仅 ASR 音频推送这一个特例）
3. **UI 回调**：宿主 UI 动作转插件调用（onPanelItemClick/onPanelAction/onAction）

## 3. 数据流出点全景（用户数据 → 插件，安全审计面）

| # | 数据 | 通道 | 管控现状 |
|---|---|---|---|
| 1 | 选中文本 | `collectToolPanelContext`（XimeInputMethodService.kt:1001-1020）→ `getPanelState(contextText)` | 无 capability 声明要求 |
| 2 | 面板输入全文 | `triggerToolPanelGenerate` → `onPanelInput`/`getPanelState` | 无声明要求；插件可外发 |
| 3 | 面板点击 itemId | `commitToolPanelItem` → `onPanelItemClick` | 低敏感 |
| 4 | **剪贴板全文** | `ClipboardSyncBridge.pushLocal` → `push(profile)` | ✅ 有：`capabilities.clipboardSync.protocols` 硬校验，未声明拒绝启动（XimeInputMethodService.kt:667-674） |
| 5 | **麦克风音频** | `processAudioChunk`（16k/mono/pcm16le） | 无专项声明（靠使用时用户主动触发） |
| 6 | 插件配置（密钥/URL） | `host.config.get` | 落盘 AES-GCM（SecureValueCipher） |
| 7 | 中文上屏文本 | `commitText` → 本地 ONNX/用户词典 | ✅ 不进任何插件 |
| — | Emoji 用户行为 | 无传递点 | 预载仅发 `EmojiQuery(category, keyword=null)` |

外发总闸：网络三重门（manifest declaredHosts → 用户授权 → NetworkPolicy 白名单强校验，Http/Ws/Sse 三处执行）。

**结论：出入门管控不统一。** 仅剪贴板同步有"能力声明 → 启动前硬校验"完整链路；选中文本、面板输入、音频均无声明要求。

## 4. 数据流入点全景（插件 → 宿主/UI）

| # | 数据 | 链路 | UI 落点 |
|---|---|---|---|
| 1 | emoji 项 | `getEmojis` → `parseResultItems` 强校验 → `List<PluginResultItem>` → `_emojiCategoriesFlow` | EmojiKeyboardLayout（内置+插件双 tab） |
| 2 | 工具面板状态 | `getPanelState` → `ToolPanelState{inputText,items,loading}` → `toolPanelItems` | ToolPanel / AiResultPanel |
| 3 | ASR 结果 | `host.asr.emitFinal/Partial/Error` → `asrResultCallback` | 上屏 |
| 4 | 远端剪贴板 | `pull()` → `ClipboardProfile` → hash 比对 → 写回系统剪贴板 | 剪贴板面板 |
| 5 | 设置 schema | `getSettingsSchema` → 动态表单 | 插件设置页 |
| 6 | 图标 | `getIcon` → `PluginIcon` | 工具栏/插件中心 |

统一契约：`PluginResultItem`（id/text/insertText/imageUrl）。

## 5. 能力声明体系现状

- 声明层统一：manifest `capabilities` → kaml 解析 → sanitize（未知字段忽略、非法枚举按未声明）→ JSON 存 XmlManager → `PluginCapabilities`（注释明确"宿主消费能力的唯一来源"）。
- **消费层无框架，各能力各写各的**：
  - clipboardSync.protocols：启动前硬校验（唯一完整链路）
  - tool.display：解析期枚举归一，UI 按值决策（DIRECT 直接上屏 / SELECT 开结果页 / 未声明按数量兜底）
  - speech.*/emoji.*：透传或 sanitize，无运行时校验
- 缺失：**events 声明**（事件订阅能力）与通用"capability → 消费点校验"框架。

## 6. 线程与并发基础设施（事件系统的安全底座，已就绪）

- 每插件专用单线程执行器（`xime-lua-$pluginId`），死循环不耗宿主线程池。
- 超时 + 中毒：业务 180s（超时中毒弃线程）、网络回调 5s（不中毒）。
- `luaLock` 串行锁：SSE/WS 回调（OkHttp 线程）与业务调用并发进入同一 Lua state 已被防住。
- 事件系统可直接复用：conflated channel → 插件执行器 → luaLock，与现有模式同构。

## 7. 关键发现（盘点结论）

1. **ASR 推送是事件系统的先例**：`processAudioChunk` 已是"宿主主动推数据给插件"，事件系统 = 该模式通用化 + conflated 丢旧 + capability 声明。
2. **Tool 面板 200ms 轮询是第一个改造对象**：轮询改事件通知（`panelStateChanged`）可省空转、降低延迟。
3. **PREDICTION 类别已预留未实现**：`getPredictionFromPlugin` 名不副实。输入事件订阅落地后，PREDICTION 插件自然有实现位置。
4. **emoji 搜索通路已铺好但未接线**：`ExtensionManager.getEmojis(searchText)` 无调用方。输入事件 + 搜索通路 = "打字实时出表情"场景（如输"生日"出蛋糕 emoji），正是"表情没有事件"的补全点。
5. **PluginCrashHandler 名存实亡**：Lua 沙箱错误不传播宿主，`findCulpritPluginId` 恒 null——事件系统失败隔离需依赖中毒机制，不依赖它。
6. **能力声明消费端需要统一框架**：以 clipboardSync 的"启动前硬校验"为模板，推广到 events/音频/上下文。

## 8. 设计原则（草案）

1. **数据出入门统一**：任何用户数据离开 IME 进插件，必须 manifest capability 声明 + 启动前校验 + 设置页可见开关（模板 = clipboardSync）。
2. **交互模型三类**：拉取 API / 事件订阅 / UI 回调。新功能归入现有类，不新造第四类。
3. **敏感输入豁免**：密码框（inputType=password / NO_PERSONALIZED_LEARNING）不产生 inputChanged 事件、不供上下文。
4. **主流程零等待**：插件调用一律异步投递（conflated channel + 插件执行器），主流程只 trySend。
5. **数据快照不可变**：跨线程传递一律 data class copy，插件线程不摸 Compose state。
6. **宿主只给原语不给协议**（现有原则，保持）。

## 9. 事件系统融入方案（下一步讨论输入）

- 能力声明：`capabilities.events: ["inputChanged", ...]`，进 `PluginCapabilities`，启动校验 + 按声明建通道（未声明零开销）。
- 注入模式：照抄 `sseHostApiFactory` → `PluginManager.eventHubFactory(pluginId)`。
- 首个事件：`inputChanged`（composing 文本快照：inputText / isComposing / 是否敏感输入）。
- 受益方：PREDICTION（补齐预留类别）、emoji 搜索接线、TOOL 轮询改造（后续独立 PR）。
