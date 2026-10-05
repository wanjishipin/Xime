# 插件「声明式配置」方案（含在线 ASR 插件化）

> 目标：插件（如 funasr ASR、其他在线 ASR 平台）只声明字段/表单与识别能力，由主 App 负责 UI 渲染、配置存储与麦克风采集。
> 实现"一个插件 = 一个平台"，同时让主 App 完整掌控 Compose 的 R8 压缩与混淆，避免插件包体积膨胀。
> 本方案的 ASR 契约参考了开源输入法 `asr/` 模块的多供应商抽象（`StreamingAsrEngine.Listener` / `PcmBatchRecognizer` / `GenericPushFileAsrAdapter` / `CancelableAsrEngine` / 能力声明 / 会话工厂）。

## 一、核心设计

```
插件(纯逻辑)                       宿主(UI + 存储 + 采集)
─────────────                      ─────────────
AsrPlugin
 ├─ getSettingsSchema()  ────────►  通用表单渲染器(宿主 Compose)
 ├─ configStore.get("apiKey") ◄───  PluginConfigStore(宿主按 pluginId 存)
 ├─ createBackend() ─────────────►  SpeechRecognitionManager(宿主采集 16k/mono/PCM16LE 推流)
 │    └─ AsrPluginBackend            processAudioChunk() → 引擎
 └─ isConfigured() / 能力声明        状态与结果经 AsrPluginListener 回传
```

原则：
- **插件 = 数据 + 逻辑 + 网络 + 识别契约**，不写任何 Compose / UI。
- **UI / 配置存储 / 麦克风采集 = 宿主**，Compose 在宿主内照常被 R8 压缩混淆。
- 插件通过 `compileOnly(project(":plugin-core"))` 编译，UI 库不进入插件 dex。
- 宿主统一采集音频并推流给插件（Push PCM 模式），插件不自己采麦克风（避免重复实现预启动/语音门限/录音线程生命周期）。

## 二、plugin-core 新增「声明式配置」接口（纯数据，无 UI）

```kotlin
interface IPluginConfigurable {
    fun getSettingsSchema(): List<PluginSettingField> = emptyList()

    /** 动态选项：SELECT / MULTI_SELECT 的 options 为空时，宿主异步拉取（如模型列表）。返回 null 表示无。 */
    fun getOptions(key: String): List<String>? = null
}

enum class PluginFieldType { TEXT, SECRET, SELECT, MULTI_SELECT, SWITCH, NUMBER }

data class PluginSettingField(
    val key: String,                 // 如 "apiKey"
    val label: String,               // "API Key"
    val type: PluginFieldType,
    val placeholder: String? = null,
    val defaultValue: String? = null,   // 表单初始值
    val options: List<String> = emptyList(),   // SELECT / MULTI_SELECT 静态选项
    val helpText: String? = null,
    val section: String? = null       // 分组名（如 "基本" / "高级"），宿主按组分段渲染
)
```

- `IPluginConfigurable` 是独立接口，任何类型插件（emoji / prediction / asr）都可实现；`AsrPlugin` 继承它。
- 插件零 Compose 依赖。
- 表单能力对齐多平台需求（参考各平台配置）：单 token → `SECRET`；区域/模型/语言单选 → `SELECT`；多语言 hints → `MULTI_SELECT`（存逗号分隔）；自定义 endpoint → `TEXT`；静音灵敏度/线程数 → `NUMBER`；VAD/DDC 等开关 → `SWITCH`；模型列表这类运行时接口数据 → `getOptions(key)` 动态拉取。

## 三、宿主提供「按插件隔离」的配置存储

- `plugin-core` 加 `PluginConfigStore`（`get/set/remove/keys`），挂在 `PluginContext.configStore`。
- 宿主实现 `PluginConfigStoreImpl(hostApp, pluginId)`，落到 `plugin_cfg_$pluginId` 独立 prefs 文件。
- 插件 A 只拿得到 A 的 store，互不串。

```kotlin
interface PluginConfigStore {
    fun get(key: String): String?
    fun set(key: String, value: String)
    fun remove(key: String)
    fun keys(): Set<String>
}
```

- `PluginContext` 增加 `configStore` 字段（默认 Noop，兼容现有单测）。宿主在 `PluginLifecycleManager.instantiatePlugin`（约 L205-209）构造 `PluginContext` 时注入 `PluginConfigStoreImpl(application, plugin.id)`。
- 插件在 `onLoad(context: PluginContext)` 中缓存 `context.configStore`，供 `isConfigured()` / `createBackend()` 使用。

## 四、plugin-core 新增「ASR 插件契约」（纯 Kotlin，无 UI）

```kotlin
enum class AsrPluginState { IDLE, LISTENING, PROCESSING, ERROR }

enum class AsrInputMode { STREAMING, BATCH }
// STREAMING: 实时转发 PCM（如 funasr-realtime WebSocket）
// BATCH:     内部累积 PCM，stop() 时一次性提交（如 Whisper 文件接口）

data class AsrAudioFormat(
    val sampleRate: Int = 16000,     // v1 宿主仅支持 16k/mono/pcm16le
    val channels: Int = 1,
    val encoding: String = "pcm16le"
)

data class AsrPluginCapabilities(
    val inputMode: AsrInputMode = AsrInputMode.STREAMING,
    val supportsPartialResults: Boolean = true,   // BATCH 引擎可为 false
    val maxRecordDurationMillis: Int = 10 * 60 * 1000,  // 宿主据此限时停止
    val requiresNetwork: Boolean = true
)

interface AsrPluginListener {
    fun onFinal(text: String)
    fun onPartial(text: String) {}
    fun onError(message: String) {}               // 错误文案由插件自带资源格式化
    fun onStateChanged(state: AsrPluginState) {}
    fun onStopped() {}                            // 录音阶段结束（松手后仍在最终识别）
    fun onAmplitude(amplitude: Float) {}
}

interface AsrPluginBackend {
    val isRunning: Boolean
    fun setListener(listener: AsrPluginListener)
    fun initialize(): Boolean
    fun start(): Boolean                          // 开始一个会话
    fun processAudioChunk(pcm: ByteArray)         // 宿主实时推 16k/mono/pcm16le
    fun stop()                                    // STREAMING: 发 finish-task；BATCH: 提交累积音频
    fun cancel()                                  // 丢弃当前会话，不提交（页面销毁/用户取消）
    fun release()
    fun getState(): AsrPluginState
}

interface AsrPlugin : IPluginEntryClass, IPluginConfigurable {
    val providerId: String                        // 与 pluginId 一致，用于持久化选择
    fun getDisplayName(): String                  // 如 "阿里百炼 FunAsr"
    fun getCapabilities(): AsrPluginCapabilities
    fun getAudioFormat(): AsrAudioFormat = AsrAudioFormat()
    fun isConfigured(): Boolean                   // 内部读 onLoad 缓存的 configStore，如 apiKey 非空
    fun createBackend(context: Context): AsrPluginBackend   // 每次会话工厂
}
```

设计要点（与参考实现逐条对照）：

- **宿主采集、插件消费（Push PCM）**：宿主 `SpeechRecognitionManager.RecordingThread` 统一产出 16k/mono/PCM16LE chunk，无论 `inputMode` 是什么都只调 `processAudioChunk()` 推流。插件内部决定"实时转发 WS"（STREAMING）还是"累积后 stop() 提交"（BATCH，对应参考实现 `PcmBatchRecognizer` + `GenericPushFileAsrAdapter`）。上传编码（WAV/m4a/opus）是插件内部职责，宿主不关心。
- **会话工厂 `createBackend()`**：引擎持有每次会话状态（WS/缓冲），配置变更下次创建即生效；对应参考实现 `AsrDirectMicrophoneEngineFactory`。宿主可按现有习惯复用后端实例（initialize 一次、start/stop 多次）。
- **`cancel()` 丢弃语义**：对应参考实现 `CancelableAsrEngine`，BATCH 引擎清空缓冲不提交。
- **`onStopped()`**：对应参考实现 `StreamingAsrEngine.Listener.onStopped()`，WS 引擎"松手后仍在等 task-finished"时 UI 需要复位麦克风。
- **`maxRecordDurationMillis`**：对应各 File 引擎的上限（如 DashScope 3 分钟），防 BATCH 模式内存膨胀（3 分钟 PCM ≈ 5.7MB，可接受）。
- **错误文案插件自带**：参考实现用宿主 `R.string.error_xxx` 统一映射；插件是独立 APK 有自己资源，`onError(message)` 直接传已格式化文案，无需共享错误分类协议。

## 五、宿主渲染「通用配置表单」

新建 `PluginConfigFormScreen`（复用 `FunAsrSettingsScreen` / `SettingsComponents` 的 Material 3 组件），输入：
- `plugin`（取 `getSettingsSchema()`）
- `configStore`（当前值 + 写回）

按 `PluginFieldType` 渲染：
- `TEXT` → `OutlinedTextField`
- `SECRET` → 密码输入框（`PasswordVisualTransformation`）
- `SELECT` → 下拉
- `SWITCH` → `Switch`
- `NUMBER` → 数字键盘

保存按钮统一写回 `configStore`。UI 全在宿主，宿主 R8 完全掌控 Compose。

## 六、接通入口（改宿主，不动表情插件路径）

1. `PluginsSettingsScreen` 的 `hasSettings` 判断（约 L232-237）和 `PluginDetailScreen` 的 `when`（约 L66-69），从"只认 EmojiPlugin"改成：

   ```kotlin
   val hasSettings =
       (pluginInstance as? IPluginConfigurable)?.getSettingsSchema()?.isNotEmpty() == true ||
       (pluginInstance as? EmojiPlugin)?.hasSettings() == true
   ```

2. 点"设置"：有 schema → 跳 `PluginConfigFormScreen(pluginId)`（宿主渲染通用表单）；否则沿用 `EmojiPlugin.openSettings()`。
3. `ExtensionManager` 加 `getEnabledAsrPlugins()`（仿 `getEnabledEmojiPlugins`，约 L184），供 `SpeechRecognitionManager.createBackend()` 插件优先、内置兜底。
4. 设置新增偏好 `stt_online_plugin_id`，持久化用户选择的在线 ASR 插件。
5. `SpeechRecognitionManager.createBackend()`（约 L326-333）：

   ```kotlin
   private fun createBackend(): AsrBackend? {
       return when {
           SettingsPreferences.isSttUseLocal(context) -> InferenceAsrBackend(context)
           else -> {
               val selectedId = SettingsPreferences.getSttOnlinePluginId(context)
               val (_, plugin) = ExtensionManager.getEnabledAsrPlugins(context)
                   .firstOrNull { it.first == selectedId }
                   ?: ExtensionManager.getEnabledAsrPlugins(context).firstOrNull()
                   ?: return FunAsrAsrBackend(context)   // 内置兜底
               PluginAsrBackendAdapter(plugin.createBackend(context))
           }
       }
   }
   ```

6. 宿主侧 `PluginAsrBackendAdapter` 实现现有 `AsrBackend`，把 `AsrPluginState ↔ RecognitionState` 映射，`SpeechRecognitionManager` 其余逻辑零改动（最小修改原则）。
7. `SpeechToTextSettingsScreen`（约 L90-102）的在线 provider 列表动态化：内置 funasr 项 + 各启用 ASR 插件（`getDisplayName()` / `isConfigured()` / capabilities→features），点击分别进 `FunAsrSettingsContent` 或 `PluginConfigFormScreen`。

## 七、funasr 插件（`plugins/funasr-asr/`）只需

- `class FunAsrAsrPlugin : AsrPlugin, ...`
- `getSettingsSchema()` 返回一个 `SECRET` 字段 `apiKey`
- `isConfigured()` 读 `configStore["apiKey"]` 非空
- `createBackend()` 返回 `FunAsrAsrBackend`（内部用 `FunAsrWebSocketManager`），实现 `AsrPluginBackend`：
  - `inputMode = STREAMING`、`supportsPartialResults = true`
  - `start()` → `wsManager.connect()`；`processAudioChunk()` → `wsManager.sendAudioChunk()`；`stop()` → `sendFinishTask()`；`cancel()` → `wsManager.cancel()`；`release()` → `disconnect()`
- 保留 `FunAsrWebSocketManager`（`build.gradle.kts` 用 `compileOnly` 跟随宿主的 stdlib 与 okhttp，运行时解析到宿主那份，插件不自带）
- 不写任何 UI
- **交付 = 独立 APK 按需安装**：插件不打进主 App（不在 `copyPluginsToAssets` 的打包列表里）。用户单独安装 funasr-asr APK 后，宿主 `PluginManager.scanAndInstallSystemPlugins()`（`XimeApplication.onCreate`）扫描 `com.kingzcheung.xime.plugin.EXTENSION` 意图的已安装包并加载；卸载插件 APK 即停用。

### 插件分类（`plugin.type` 受控词汇）

manifest 的 `plugin.type` 决定插件在「插件管理」里归入哪个分类，由 `PluginCategory`（plugin-core `model` 包）解析，分类驱动「在哪消费、怎么激活、管理如何展示」：

| `plugin.type` | PluginCategory | 激活模式 | 消费入口 |
|---|---|---|---|
| `emoji` | EMOJI | 多选 · 启用即生效 | 表情键盘（`emojiCategoriesFlow`） |
| `speech` | ASR | 单选 · 在对应功能设置中选择 | 设置 → 语音转文本 |
| `prediction` | PREDICTION | 多选 | 智能预测（规划中） |
| 其他/缺失 | UNKNOWN | 无 | 插件管理「其他」分组 |

- 新增分类 = 给 `PluginCategory` 加一个枚举值 + 对应消费页；管理 UI、configStore、来源徽标全部通用。
- `ExtensionManager.getPluginsByCategory(category)` / `getEnabledPluginsByCategory(context, category)` 按分类取插件元数据，替代散落的硬编码过滤。

### 插件宿主版本范围（可选）

插件可在 manifest 声明其支持的**主应用（宿主）版本范围**，宿主在安装与加载时校验，避免宿主更新后插件行为异常：

```xml
<meta-data
    android:name="plugin.minHostVersion"
    android:value="2.6.0" />
<meta-data
    android:name="plugin.maxHostVersion"
    android:value="3.0.0" />
```

- 字段均为可选；缺省表示"不限版本"（现有插件不声明即默认兼容，不破坏旧插件）。
- 取值为主应用 `versionName`（如 `2.6.0` / `2.6.0-beta3`），比较时忽略预发布/构建后缀（`-beta3`），按数字段逐位比较（`VersionUtil`）。
- 宿主行为：
  - **安装时**：当前 app 版本不在范围内 → 安装失败并提示"当前主应用版本 vX 不在插件支持范围内（最低 vA - vB）"。
  - **加载时**：批量加载与单插件启动都校验，不兼容的插件跳过加载 / 拒绝启动。
  - **插件管理页**：不兼容的插件显示 ⚠ 标记、"与主应用版本不兼容"及支持范围，并禁用启用开关 / "去选择"按钮。
- 常见用法：`minHostVersion` 指向插件依赖的宿主 API 起始版本，`maxHostVersion` 用于提前拦截已知不兼容的大版本升级。


## 八、体积 / 混淆结论

| 项 | 结论 |
|---|---|
| 插件包 | 只有逻辑，**不含 Compose / 不含 stdlib / 不含 okhttp**（均跟随宿主），几十 KB |
| 宿主包 | UI/存储全在宿主，Compose 照常被宿主 R8 压缩混淆，**不损失** |
| keep 规则 | 宿主对 `AsrPlugin / AsrPluginBackend / PluginConfigStore / IPluginConfigurable` 均为静态引用（ExtensionManager / PluginAsrBackendAdapter / SettingsScreen），keep 规则量极小 |

相关背景：
- 宿主 `app/build.gradle.kts`：`isMinifyEnabled = true`、`isShrinkResources = true`（约 L76-77）、`kotlin-stdlib 2.4.10`（L174）、`okhttp 5.4.0`（L205）。
- 插件 `plugins/funasr-asr/gradle.properties`：`kotlin.stdlib.default.dependency=false`（关闭 KGP 自动带 stdlib），build.gradle.kts 里 stdlib / okhttp 用 `compileOnly`。
- 插件运行时父加载器 = `application.classLoader`（宿主），UI 库复用宿主那份。

## 九、网络能力说明（原理层）

- 插件代码在宿主进程内运行，网络权限由宿主决定。
- 宿主 `app/src/main/AndroidManifest.xml` 已声明 `android.permission.INTERNET`（约 L6），插件在 IME 进程内可直接发起 https / wss / ws。
- `INTERNET` 是 normal 权限，安装即默认授予。
- 明文 http（非 https）：Android 9+ 默认禁止 cleartext，需宿主 manifest 开 `usesCleartextTraffic` 或网络安全配置；插件自身 manifest 无法改变宿主进程策略。
- **类加载是 parent-first**：`PluginClassLoader`（`DexClassLoader`）先查宿主 classloader 再查插件 dex。因此宿主已有的类（如 stdlib 2.4.10、okhttp 5.4.0）运行时**总是解析到宿主那份**，插件用 `compileOnly` 跟随宿主即可；宿主没有的类（如第三方 ASR SDK）才用 `implementation` 自带兜底。

## 十、落地顺序（一次一个功能点，端到端验证后再继续）

1. `plugin-core`：`PluginConfigStore` + `PluginSettingField` / `IPluginConfigurable` + `PluginContext.configStore` + `AsrPlugin` 契约层（`AsrPlugin / AsrPluginBackend / AsrPluginListener / AsrPluginCapabilities / AsrAudioFormat / AsrPluginState`）+ 单测。
2. 宿主：`PluginConfigStoreImpl` + `PluginConfigFormScreen` + 设置入口通用化。
3. 宿主：`ExtensionManager.getEnabledAsrPlugins()` + `createBackend()` 插件优先、内置兜底 + `PluginAsrBackendAdapter`。
4. 宿主：`SpeechToTextSettingsScreen` 动态 provider + `stt_online_plugin_id` 选择持久化。
5. `plugins/funasr-asr/`：纯逻辑插件 + 单测（参考 `EmojiPluginTest`）。
6. 端到端验证 + 回归（确认未装插件时行为与现状一致，最小修改原则）。

## 十一、注意事项

- **同进程共享 UID**：所有插件跑在宿主进程（宿主 UID），不要给插件暴露宿主全局 prefs，只暴露它自己那个 `configStore`；存储层面用 `plugin_cfg_$pluginId` 独立文件隔离。
- **token 明文**：SharedPreferences 明文是常规做法（宿主私有目录）；如需更强可用 AndroidX `EncryptedSharedPreferences`（需给宿主加 `security-crypto` 依赖）。
- **BATCH 引擎内存**：插件内部累积 PCM 直到 stop()，`maxRecordDurationMillis` 由宿主据此限时；插件可自行压缩编码（m4a/opus）后再上传。
- **卸载清理**：`onUnload()` 清内存 token；宿主卸载插件时可选清理其 prefs 文件。
- **宿主 R8 keep**：保住 `plugin-core` 的插件接口与反射入口类。
