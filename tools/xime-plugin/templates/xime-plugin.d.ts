/**
 * Xime 输入法插件 SDK 类型定义（xime-plugin.d.ts）—— v2 插件形态
 *
 * 插件源码为 TypeScript（标准模块），由 `xipm build` 编译为 IIFE 单文件 main.js，
 * 在宿主 QuickJS 沙箱中执行。本文件描述：
 *  - 插件工厂 `definePlugin` 与插件规格 `XimePluginSpec`（入口 `export default`）
 *  - 下行事件槽（events）、配置表单（settings）、候选变换（transform）与各扩展点
 *  - 宿主注入的全局 `host` API（白名单，按 manifest 能力门禁）
 *  - 宿主补齐的环境 API（console / TextEncoder / TextDecoder / atob / btoa）
 *
 * ## 插件入口（TS 模块范式）
 * ```ts
 * const plugin = definePlugin({
 *   events: {
 *     onTextCommitted(e) { ... },       // e: XimeTextCommittedEvent（自动推断）
 *   },
 *   panel: {
 *     state(input) { return { items: [], ui: [...] }; },
 *     onAction({ actionId }) { ... },
 *   },
 * });
 *
 * export default plugin;
 * ```
 * 构建产物为 `var plugin = (function () { ... })();`——QuickJS 脚本模式下
 * 即 `globalThis.plugin`（宿主契约：入口脚本定义全局对象 plugin）。
 *
 * ## 能力三轨模型
 * 1. **Events（下行）**：宿主 → 插件的通知。manifest 声明后，宿主投递到 events 方法槽。
 * 2. **Services（上行）**：插件 → 宿主的服务调用（host.* 白名单，manifest 门禁）。
 * 3. **Extensions（本体）**：插件实现的扩展点（emoji/panel/speech/...），宿主按点路由调用。
 *
 * ## 扩展点与激活面（插件不自绘 UI，呈现控制权分三档）
 * | 扩展点          | 呈现档       | 宿主集成点                        |
 * |----------------|-------------|----------------------------------|
 * | `emoji`        | data-only   | 候选栏 emoji 页签（宿主渲染网格/搜索）|
 * | `panel`        | declarative | 工具栏按钮 → InfoPanel（ui 节点树）  |
 * | `speech`       | headless    | 麦克风键（宿主管理录音状态机）        |
 * | `clipboardSync`| headless    | 后台（剪贴板事件 + 设置页手动触发）    |
 * | `backup`       | headless    | 设置页触发                         |
 * | `transform`    | headless    | 按键路径（15ms 硬超时）             |
 * | `events`       | ——          | 宿主事件投递                       |
 *
 * ## TS 范式（async/await + 结构化错误）
 * host 的**网络 / IO 服务**为 async 调用（返回 Promise）：`await host.http.request(...)`；
 * 失败 reject `XimeError`（`code` + `message`），用 try/catch 处理：
 * ```ts
 * try {
 *   const resp = await host.http.request('GET', url);
 * } catch (e) {
 *   host.logError((e as XimeError).code + ': ' + (e as Error).message);
 * }
 * ```
 * 纯计算 API（config/uuid/bin/hex/base64/crypto 哈希/utcTime）保持同步返回。
 *
 * ## 无需 import
 * `host` 与 `definePlugin` 是宿主注入的**运行时全局**（QuickJS 中即 globalThis 属性），
 * 本文件所有声明均为全局类型（ambient declarations，由 tsconfig `include` 引入）——
 * 插件源码直接使用 `definePlugin`、`host.xxx`、`XimeXxx` 类型，无需 import。
 * 与 Node 的 `process`、浏览器的 `window` 同款约定：运行时全局 → 类型全局。
 *
 * ## 语言基线
 * ES2020（QuickJS 原生支持），内建对象（BigInt/Set/Map/Proxy/Promise/TypedArray/
 * Date/JSON/RegExp）全部可用；不支持 Intl（ECMA402）、setTimeout、URL、fetch 等
 * 浏览器/Node 环境 API——网络与 IO 一律走 host 白名单。
 *
 * ## 能力探测
 * host.has(name) / host.capabilities 可在运行时探测 manifest 声明后实际注入的能力；
 * 未声明的子能力不存在（如未声明网络时 host.ws === undefined），推荐 has 显式探测。
 */

// ============================================================
// 结构化错误（async 服务失败的 reject 形态）
// ============================================================

/**
 * 插件侧结构化错误（async 服务失败的 reject 形态）。
 *
 * - `code`：错误码（E_NETWORK / E_TIMEOUT / E_DENIED / E_INVALID / E_IO / E_INTERNAL / E_UNKNOWN）
 * - `message`：人类可读原因（可直接展示/记录）
 */
declare class XimeError extends Error {
  readonly code: string;
  constructor(code: string, message: string);
}

// ============================================================
// 插件工厂与规格
// ============================================================

/**
 * 插件定义工厂（类型恒等函数，运行时原样返回入参）。
 * 按 manifest 声明实现对应扩展点；未实现的扩展点宿主不调用。
 */
declare function definePlugin<T extends XimePluginSpec>(spec: T): T;

/** 插件规格：生命周期 + 横切能力 + 扩展点（按 manifest 声明实现，至少一个扩展点）。 */
interface XimePluginSpec {
  /** 插件加载完成（host API 已注入）；宿主等待 Promise settle 后才视为加载成功。 */
  onLoad?(): void | Promise<void>;
  /** 插件卸载；用于释放资源（宿主也会关闭未关闭的连接）。 */
  onUnload?(): void | Promise<void>;

  // ---- 横切能力（任何插件可按 manifest 声明使用） ----
  /** 下行事件槽（manifest `events` 声明后投递）。 */
  events?: XimeEventSlots;
  /** 配置表单（插件中心的设置页数据源）。 */
  settings?: XimeSettingsExtension;
  /** 候选词变换（manifest `extensions.transform` 声明；按键路径 15ms 硬超时）。 */
  transform?: XimeTransformExtension;

  // ---- 扩展点（manifest `extensions.<name>` 声明；宿主按点路由） ----
  /** 工具面板（declarative 呈现：ui 节点树 + items 候选）。 */
  panel?: XimePanelExtension;
  /** emoji 表情（data-only 呈现：宿主渲染页签/网格/搜索）。 */
  emoji?: XimeEmojiExtension;
  /** 语音识别（headless：宿主管理录音与状态机，插件做识别后端）。 */
  speech?: XimeSpeechExtension;
  /** 剪贴板同步（headless：后台 + 设置页手动触发）。 */
  clipboardSync?: XimeClipboardSyncExtension;
  /** 备份（headless：备份设置页触发）。 */
  backup?: XimeBackupExtension;

  // ---- 网络连接回调槽（随 host.ws.connect / host.http.stream 建立后由宿主投递） ----
  /** WebSocket 事件槽（宿主连接单例）。 */
  ws?: XimeWsSlots;
  /** SSE 流事件槽（按 sessionId 区分会话）。 */
  sse?: XimeSseSlots;
}

// ============================================================
// 下行事件（events，payload 字段一律 camelCase）
// ============================================================

/** input_changed：当前编码快照（高频事件，仅内存态处理）。 */
interface XimeInputChangedEvent {
  inputText: string;
}

/**
 * text_committed：一次上屏提交。
 * - sessionXxx 为宿主进程生命周期累计（conflated 快照，差值增量）
 * - isPaste：粘贴性质上屏（剪贴板点选/编辑面板提交），不计打字量
 */
interface XimeTextCommittedEvent {
  committedText: string;
  sessionTotalChars: number;
  sessionTotalCommits: number;
  isPaste: boolean;
}

/** quick_send_changed：快捷发送条目变更（count 为变更后条目数）。 */
interface XimeQuickSendChangedEvent {
  count: number;
}

/**
 * 事件槽：按 manifest `events` 声明投递；payload 恒为非空对象（宿主保证），
 * 方法参数类型自动推断，无需手动收窄。
 */
interface XimeEventSlots {
  onInputChanged?(e: XimeInputChangedEvent): void;
  onTextCommitted?(e: XimeTextCommittedEvent): void;
  onQuickSendChanged?(e: XimeQuickSendChangedEvent): void;
}

// ============================================================
// 配置表单（settings）
// ============================================================

interface XimeSettingsExtension {
  /** 表单结构（宿主渲染设置页；字段必须带 key 绑定 host.config）。 */
  schema(): XimeUiNode[];
  /** select / multi_select 的动态选项（按字段 key 查询）。 */
  options?(key: string): string[];
}

// ============================================================
// 候选词变换（transform，hot path）
// ============================================================

/** 候选词变换请求（必须纯内存同步计算，15ms 硬超时）。 */
interface XimeTransformRequest {
  inputText: string;
  preedit: string;
  asciiMode: boolean;
  candidates: Array<{ text: string; comment?: string }>;
}

/** 候选词变换结果：按 index 修改或新增文本候选。 */
interface XimeTransformResponse {
  candidates: Array<{ engineIndex?: number; text?: string; comment?: string }>;
}

interface XimeTransformExtension {
  /** 返回 null 表示不干预（回退原始候选）。 */
  candidates(req: XimeTransformRequest): XimeTransformResponse | null;
}

// ============================================================
// 扩展点：panel（工具面板）
// ============================================================

/** 面板状态请求的上下文（宿主收集）。 */
interface XimePanelInput {
  /** 宿主收集的当前输入上下文（如键盘正在输入的文本）。 */
  inputText: string;
}

/** 面板交互输入事件（direct 面板：key 为空串 = 主输入框，其余为 ui 控件行节点 key；passive 面板：ui 树内 input/textarea 节点 key）。 */
interface XimePanelInputEvent {
  /** 节点 key（主输入框为空串 ""；ui 树中声明的 key） */
  key: string;
  value: string;
}

/** 面板操作事件（button/action 节点点击；`confirm` 声明时宿主确认后回调）。 */
interface XimePanelActionEvent {
  actionId: string;
}

/** 面板候选点击事件（items 点选，上屏由宿主完成）。 */
interface XimePanelItemClickEvent {
  itemId: string;
}

interface XimePanelExtension {
  /**
   * 返回面板状态（ui 节点树 + items 候选）；宿主渲染，插件不自绘。
   * async：宿主等待 Promise settle（面板有 loading 态），可 `await` 拉取数据。
   */
  state(input: XimePanelInput): XimePanelState | Promise<XimePanelState>;
  /** ui 树内输入组件的内容变化（声明式组件 → 结构化回流；高频，同步入口）。 */
  onInput?(input: XimePanelInputEvent): void;
  /**
   * 按钮/操作点击（危险操作可在节点声明 `confirm`，宿主确认后回调）。
   * async：宿主等待完成后刷新面板（长任务如 AI 生成可在回调内 await）。
   */
  onAction?(input: XimePanelActionEvent): void | Promise<void>;
  /** 候选条目点击（点选上屏由宿主完成；同步入口，内部可 fire-and-forget 异步）。 */
  onItemClick?(input: XimePanelItemClickEvent): void;
}

/** 工具面板状态。 */
interface XimePanelState {
  /**
   * 面板输入框内容回显。缺省（不返回该字段）= 沿用宿主传入的上下文；
   * 返回空串 = 明确要求空输入框（如翻译插件拒绝剪贴板预填，无需零宽字符 hack）。
   */
  inputText?: string;
  /** 候选条目（形状不符的元素由宿主丢弃并记协议日志，不拖垮宿主） */
  items: XimePanelItem[];
  /**
   * 声明式控件/展示节点树。display: passive 在 InfoPanel 渲染展示型白名单；
   * display: direct 在输入框下方渲染控件行，v1 白名单：
   * text（带 key=可输入框，value 为初始内容，用户编辑后以用户输入为准）/
   * select（options 静态声明，value 为当前值；options 支持 "label|value" 约定，
   * 显示 label、回传 value；按钮动作重拉后以插件回传 value 为准）/
   * button（key 即 actionId，点击后宿主重拉 state 刷新 ui）/
   * section / divider；其余类型 direct 面板忽略。
   * 语言选择等偏好建议用 host.config 持久化，下次打开面板自动恢复。
   */
  ui?: XimeUiNode[];
  loading?: boolean;
}

/** 工具面板候选条目（宿主渲染并点选上屏；协议要求非空 id/text，重复 id 被丢弃）。 */
interface XimePanelItem {
  id: string;
  text: string;
}

// ============================================================
// 扩展点：emoji
// ============================================================

interface XimeEmojiQuery {
  /** 分类（空串 = 全部分类） */
  category?: string;
  keyword?: string;
  topK?: number;
}

interface XimeEmojiItem {
  id: string;
  text: string;
  insertText?: string;
  /** resources/ 下图片资源绝对路径（宿主渲染） */
  imageUrl?: string;
}

/** 插件图标：text 与 assetName 至少一个（text 优先）。 */
interface XimeIcon {
  text?: string;
  assetName?: string;
}

interface XimeEmojiExtension {
  /** 分类列表（宿主渲染页签）。 */
  listCategories(): string[];
  /** 查询表情（宿主渲染网格；搜索词经 keyword 回流）。 */
  query(q: XimeEmojiQuery): XimeEmojiItem[];
  /** 插件图标（页签/列表展示）。 */
  icon?(): XimeIcon;
}

// ============================================================
// 扩展点：speech（语音识别）
// ============================================================

interface XimeSpeechExtension {
  /** 配置是否就绪（可选；宿主默认按 settings schema required 字段判定；同步读）。 */
  isConfigured?(): boolean;
  /** 初始化识别后端（宿主在开始前调用一次）；resolve false 中止启动。 */
  configure(): boolean | Promise<boolean>;
  /** 投递音频帧（PCM 16k/mono/16bit，宿主按帧长切片；await 天然提供顺序与背压）。 */
  feed(chunk: Uint8Array): void | Promise<void>;
  /**
   * 开始识别；resolve false 表示启动失败
   * （结果经 host.asr.emitPartial / emitFinal / emitError / emitState 上报）。
   */
  start(): boolean | Promise<boolean>;
  /** 正常结束（等待最终结果）。 */
  stop(): void | Promise<void>;
  /** 取消（丢弃未出结果）。 */
  cancel(): void | Promise<void>;
}

// ============================================================
// 扩展点：clipboardSync（剪贴板同步）
// ============================================================

/**
 * 剪贴板同步 profile（push/pull 的交换对象，字段 camelCase）。
 *
 * 文本条目：`type="text"`、`hasData=false`、`text` 为内容本身、`hash=sha256(utf8(text))`。
 *
 * **图片条目**：`type="image"`、`text=""`（**不能**据此判空，须同时看 `hasData`）、
 * `hasData=true`、`data` 为原图字节、`hash=sha256(原图字节)`、`dataName="<hash>.<ext>"`
 * （内容寻址 ⇒ 同一张图在任何设备 hash 相同，重复 PUT 幂等）。
 * 远端 blob 位置由插件自行约定（webdav 插件为 `clipboard/blobs/<dataName>`）。
 */
interface XimeClipboardProfile {
  type: string;
  hash: string;
  text: string;
  hasData: boolean;
  dataName: string | null;
  /** 附件字节（图片原图，宿主不压缩）；无附件或未下载成功为 null/undefined。 */
  data?: Uint8Array | null;
  size: number;
  source: string | null;
}

interface XimeClipboardSyncExtension {
  /** 推送本地剪贴板（resolve 是否成功；可 await 网络 IO；有附件时先把 `data` 写远端再更新元数据）。 */
  push(profile: XimeClipboardProfile): boolean | Promise<boolean>;
  /** 拉取远端（resolve null = 无变更/失败；可 await 网络 IO）。 */
  pull(): XimeClipboardProfile | null | Promise<XimeClipboardProfile | null>;
  /** 连接测试（resolve null = 成功；字符串 = 错误消息；可 await 网络 IO）。 */
  test(): string | boolean | null | Promise<string | boolean | null>;
}

// ============================================================
// 扩展点：backup（备份）
// ============================================================

/** 备份上传参数（archive 为宿主生成的备份包字节）。 */
interface XimeBackupPushArgs {
  name: string;
  archive: Uint8Array;
}

/** 远端备份条目。 */
interface XimeBackupItem {
  id: string;
  name: string;
  createdAt?: number;
  size?: number;
}

/** 备份上传结果（bool 简写或对象形态；id 为远端新条目 id）。 */
interface XimeBackupPushResult {
  ok?: boolean;
  id?: string;
  message?: string;
}

interface XimeBackupExtension {
  /** 上传备份包（resolve true / {ok, id?, message?} 表示结果；可 await 网络 IO）。 */
  push(args: XimeBackupPushArgs): boolean | XimeBackupPushResult | null | Promise<boolean | XimeBackupPushResult | null>;
  /** 下载备份包（resolve zip 字节；null = 失败；可 await 网络 IO）。 */
  pull(id: string): Uint8Array | null | Promise<Uint8Array | null>;
  /** 列出远端备份（resolve null = 失败；可 await 网络 IO）。 */
  list(): XimeBackupItem[] | null | Promise<XimeBackupItem[] | null>;
  /** 删除远端备份。 */
  remove(id: string): boolean | Promise<boolean>;
  /** 连接测试（resolve null = 成功；字符串 = 错误消息）。 */
  test(): string | boolean | null | Promise<string | boolean | null>;
}

// ============================================================
// 网络连接回调槽（R1 保留槽形态；后续版本将改为连接句柄）
// ============================================================

/** WebSocket 事件槽（host.ws.connect 后由宿主投递）。 */
interface XimeWsSlots {
  onOpen?(): void;
  onMessage?(text: string): void;
  onBinary?(data: Uint8Array): void;
  onError?(message: string): void;
  onClose?(): void;
}

/** SSE 流事件槽（host.http.stream 建立后由宿主投递；sessionId 区分会话）。 */
interface XimeSseSlots {
  onData?(sessionId: number, text: string): void;
  onDone?(sessionId: number, fullText: string): void;
  onError?(sessionId: number, message: string): void;
}

// ============================================================
// 声明式 UI（settings 表单 + panel ui 树共用）
// ============================================================

/**
 * 声明式节点（宿主渲染白名单，与 parseUiNodes 实现对齐）。
 * settings 表单：input/textarea/secret/select/multi_select/switch/number（key 绑定 config）
 * panel ui 树：section/text/metric/divider/button（展示与操作）
 * 未知 type 由宿主降级为 text；已知字段均有强类型，额外字段经 index signature 透传。
 */
interface XimeUiNode {
  type:
    | 'section'
    | 'text'
    | 'metric'
    | 'divider'
    | 'button'
    | 'action'
    | 'input'
    | 'textarea'
    | 'secret'
    | 'select'
    | 'multi_select'
    | 'switch'
    | 'number';
  label?: string;
  value?: string;
  /** settings：绑定 config key；panel：节点标识（onInput/onAction 回调携带） */
  key?: string;
  /** select / multi_select 静态选项（宿主按 string[] 解析；动态选项用 settings.options(key)） */
  options?: string[];
  /** 表单默认值（首次未写入 config 时展示） */
  defaultValue?: string;
  placeholder?: string;
  /** 字段下方帮助文本 */
  helpText?: string;
  /** metric 等展示节点的单位 */
  unit?: string;
  /** 文本样式提示（如 'caption'） */
  style?: string;
  /** 分组标题（宿主可选渲染） */
  section?: string;
  /** 表单必填标记（speech isConfigured 默认按 required 字段判定） */
  required?: boolean;
  /** 危险操作确认（button/action 节点）：宿主弹确认框，确认后才回调 onAction */
  confirm?: { title: string; message: string };
  [key: string]: unknown;
}

// ============================================================
// host API（上行服务，白名单 + manifest 门禁）
// ============================================================

/** 插件配置（宿主按插件 id 独立存储，string → string）。 */
interface XimeConfig {
  get(key: string): string | null;
  /** 读取并 JSON.parse 配置值；键不存在或内容非法 JSON 均返回 null（不抛异常） */
  getJson<T = unknown>(key: string): T | null;
  set(key: string, value: string): void;
  remove(key: string): void;
  keys(): string[];
}

/** 插件包 resources/ 下的资源（宿主渲染图片；插件只拿路径字符串）。 */
interface XimeResource {
  /** 资源绝对路径；不存在返回 null（同步：纯路径拼接） */
  path(name: string): string | null;
  /** 列出 resources/<dir> 下的文件名（不含子目录；目录不存在/非法返回空数组） */
  list(dir: string): Promise<string[]>;
}

/** 大端序整数打包（ASR 协议等二进制帧用）。 */
interface XimeBin {
  int32be(value: number): Uint8Array;
  uint32be(value: number): Uint8Array;
}

/**
 * gzip 压缩（如火山引擎 ASR 的 gzip 请求体）。
 * async 服务：失败 reject `XimeError`（E_IO 压缩/解压失败 / E_INVALID 输入为空）。
 */
interface XimeZlib {
  gzip(data: Uint8Array): Promise<Uint8Array>;
  gunzip(data: Uint8Array): Promise<Uint8Array>;
}

/** HTTP 响应（body 为原始字节，text 为 UTF-8 文本）。 */
interface XimeHttpResponse {
  status: number;
  headers: Record<string, string>;
  body: Uint8Array;
  text: string;
}

/**
 * HTTP 能力（需 manifest 声明网络域名）。
 *
 * request 为 async 服务调用（TS 范式）：成功 resolve 响应对象；
 * 失败 reject `XimeError`（E_NETWORK 请求失败 / E_TIMEOUT 超时 /
 * E_DENIED 域名未授权 / E_INVALID 参数非法）。
 * 流式（SSE）事件经 plugin.sse 回调槽。
 */
interface XimeHttp {
  request(
    method: string,
    url: string,
    headers: Record<string, string>,
    body?: Uint8Array | null,
    timeoutMillis?: number
  ): Promise<XimeHttpResponse>;
  /** 打开 SSE 流，resolve sessionId（回调携带该 id）；失败 reject `XimeError` */
  stream(
    url: string,
    headers: Record<string, string>,
    timeoutMillis?: number,
    method?: string,
    body?: Uint8Array | null
  ): Promise<number>;
  closeStream(sessionId: number): Promise<void>;
}

/**
 * WebSocket 能力（二进制帧经 plugin.ws.onBinary 回调）。
 *
 * async 服务（失败 reject `XimeError`，E_NETWORK）：
 * - `connect` resolve true=已发起（连接结果经 plugin.ws.onOpen/onError 投递）
 * - `sendText` / `sendBinary` resolve true=已入队（插件的 await 天然提供背压与顺序）
 * - `close` 幂等；`getState` 为同步读（无 IO）
 */
interface XimeWs {
  connect(url: string, headers?: Record<string, string>): Promise<boolean>;
  sendText(text: string): Promise<boolean>;
  sendBinary(data: Uint8Array): Promise<boolean>;
  close(): Promise<void>;
  /** 0=CONNECTING 1=OPEN 2=CLOSING 3=CLOSED */
  getState(): number;
}

/** 摘要与编码能力。 */
interface XimeCrypto {
  sha256(data: Uint8Array): Uint8Array;
  hmacSha256(key: Uint8Array, data: Uint8Array): Uint8Array;
  hmacSha1(key: Uint8Array, data: Uint8Array): Uint8Array;
  hex(data: Uint8Array): string;
  base64(data: Uint8Array): string;
  /** 支持格式：YYYYMMDD / YYYYMMDDTHHMMSSZ（UTC） */
  utcTime(format: string): string;
  epochSeconds(): number;
}

/** ASR 结果上报（speech 扩展点）。 */
interface XimeAsr {
  emitFinal(text: string): void;
  emitPartial(text: string): void;
  emitError(message: string): void;
  /** 1=LISTENING 2=PROCESSING 3=ERROR 0=IDLE */
  emitState(state: 0 | 1 | 2 | 3): void;
}

interface XimeQuickSendItem {
  /** 数据库 id（宿主 Long，JS 侧为 number） */
  id: number;
  text: string;
  code: string | null;
  timestamp: number;
  isPinned: boolean;
}

/** 快捷发送只读（manifest capabilities.quick_send_read 声明后注入）。 */
interface XimeQuickSend {
  list(): XimeQuickSendItem[];
}

/** 剪贴板只读（manifest capabilities.clipboard_read 声明后注入）。 */
interface XimeClipboard {
  get(): string | null;
}

/** 可按 manifest 声明注入的 host 子能力（host.has / host.capabilities 用）。 */
type XimeCapability =
  | 'config'
  | 'resource'
  | 'uuid'
  | 'bin'
  | 'zlib'
  | 'crypto'
  | 'http'
  | 'ws'
  | 'asr'
  | 'quickSend'
  | 'clipboard';

/**
 * 宿主注入的全局白名单 API（唯一能触及宿主的入口）。
 * 各子能力按 manifest 能力声明注入；未声明的子能力不存在（undefined），
 * 推荐先用 has(name) / capabilities 探测，再决定功能分支（优雅降级）。
 */
interface XimeHost {
  /** SDK 版本（如 "2.0.0"） */
  readonly sdkVersion: string;
  /** 探测能力是否已注入（按 manifest 声明）；等价于 capabilities.includes(name) */
  has(capability: XimeCapability): boolean;
  /** 宿主已注入的能力子表列表（只读） */
  readonly capabilities: readonly XimeCapability[];
  /** 输出日志（logcat，tag 含插件 id） */
  log(message: string): void;
  /** 输出错误日志（Log.e 级别，关键失败用） */
  logError(message: string): void;
  readonly config: XimeConfig;
  readonly resource: XimeResource;
  readonly bin: XimeBin;
  readonly zlib: XimeZlib;
  /** 需网络能力（AI/ASR/WebDAV 等） */
  readonly crypto: XimeCrypto;
  /** 需 manifest 声明网络域名 */
  readonly http: XimeHttp;
  /** WS 协议插件（ASR） */
  readonly ws: XimeWs;
  /** speech 扩展点 */
  readonly asr: XimeAsr;
  /** manifest capabilities.quick_send_read */
  readonly quickSend: XimeQuickSend;
  /** manifest capabilities.clipboard_read */
  readonly clipboard: XimeClipboard;
  /** 生成唯一 id（ASR task_id 等） */
  uuid(): string;
}

declare const host: XimeHost;

// ============================================================
// 宿主补齐的环境 API（QuickJS 精简构建不含 Web API，由宿主注入）
// ============================================================

/** 日志转发到宿主 logcat（host.log / host.logError）。 */
declare const console: {
  log(...args: unknown[]): void;
  info(...args: unknown[]): void;
  warn(...args: unknown[]): void;
  error(...args: unknown[]): void;
  debug(...args: unknown[]): void;
};

declare class TextEncoder {
  readonly encoding: string;
  encode(input?: string): Uint8Array;
}

declare class TextDecoder {
  constructor(label?: string);
  readonly encoding: string;
  decode(input?: Uint8Array | ArrayBuffer): string;
}

declare function atob(data: string): string;
declare function btoa(data: string): string;

// ============================================================
// 测试环境（仅 `xipm test` 注入；真机运行不存在这些全局）
// ============================================================

/** 注册一个测试用例（fn 可为 async）。 */
declare function test(name: string, fn: () => void | Promise<void>): void;

declare const assert: {
  ok(value: unknown, message?: string): void;
  equal(actual: unknown, expected: unknown, message?: string): void;
  deepEqual(actual: unknown, expected: unknown, message?: string): void;
  throws(fn: () => void, message?: string): void;
  /** 断言 Promise 被 reject（不校验错误内容）。 */
  rejects(promise: Promise<unknown>, message?: string): Promise<void>;
};

interface XimeTestMockRequest {
  method: string;
  url: string;
  headers: Record<string, string>;
  body: Uint8Array | null;
  text: string | null;
  timeoutMillis?: number;
}

interface XimeTestMockResponse {
  status?: number;
  headers?: Record<string, string>;
  text?: string;
  body?: Uint8Array;
}

/** 测试环境的 mock host 控制入口：stub 注册、事件推动、调用记录断言。 */
declare const __ximeMock: {
  /** 预置 host.config（string → string）。 */
  setConfig(key: string, value: string): void;
  /** 固定 host.crypto.epochSeconds/utcTime 的时钟（秒）；0 = 真实时钟。 */
  setClock(epochSeconds: number): void;
  resetClock(): void;
  /**
   * 注册 HTTP stub。pattern 为精确 URL 或 (url) => boolean；
   * resp 为静态响应或 async (req, url) => 响应。未 stub 的请求 reject
   * XimeError E_NETWORK（绝不真实联网）。
   */
  addHttpResponse(
    method: string,
    pattern: string | ((url: string) => boolean),
    resp:
      | XimeTestMockResponse
      | ((
          req: XimeTestMockRequest,
          url: string,
        ) => XimeTestMockResponse | Promise<XimeTestMockResponse>),
  ): void;
  /** 已发起的 HTTP 请求记录（断言用）。 */
  httpRequests: XimeTestMockRequest[];
  /** 注册 SSE 流 stub：stream() 后按序投递 onData（data 帧）→ onDone。 */
  addSse(url: string, events: Array<{ data: string; event?: string }>): void;
  /** 向已建立的 SSE 会话手动推送一帧（plugin.sse.onData）。 */
  pushSse(sessionId: number, data: string): void;
  /** 注册 WS stub；connect 成功后可用 wsOpen/wsMessage/... 推动事件槽。 */
  addWs(url: string): void;
  wsOpen(): void;
  wsMessage(text: string): void;
  wsBinary(data: Uint8Array): void;
  wsError(message: string): void;
  wsClose(): void;
  /** host.ws.sendText/sendBinary 已入队的记录（断言用）。 */
  readonly sentWs: Array<
    { type: 'text'; text: string } | { type: 'binary'; data: Uint8Array }
  >;
  /** host.asr.emit* 事件记录（断言用）。 */
  asrEvents: Array<{
    type: string;
    text?: string;
    message?: string;
    state?: number;
  }>;
  setQuickSend(items: XimeQuickSendItem[]): void;
  setClipboard(text: string): void;
};
