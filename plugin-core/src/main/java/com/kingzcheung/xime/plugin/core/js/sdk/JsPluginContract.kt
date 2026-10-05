package com.kingzcheung.xime.plugin.core.js.sdk

/**
 * JS 插件契约（v2）：入口脚本导出全局对象 plugin 的约定。
 *
 * ## 入口（TS 模块范式）
 * 插件源码用 `definePlugin` 定义（宿主注入的全局工厂，类型见 xime-plugin.d.ts），
 * xipm 编译为 IIFE 单文件 main.js，产物 `var plugin = (...)()` 即 globalThis.plugin。
 *
 * ```ts
 * const plugin = definePlugin({
 *   events: { onTextCommitted(e) { ... } },
 *   panel: { state(input) { ... } },
 * });
 * export default plugin;
 * ```
 *
 * ## 结构（能力三轨模型）
 * - **生命周期**：`onLoad` / `onUnload`（顶层）
 * - **横切**：`events`（下行事件槽）/ `settings`（配置表单）/ `transform`（候选变换）
 * - **扩展点**：`panel` / `emoji` / `speech` / `clipboardSync` / `backup`
 *   （manifest `extensions.<name>` 声明，宿主按点路由；激活面见 SDK 文档）
 * - **网络槽**：`ws` / `sse`（host.ws.connect / host.http.stream 建立后投递）
 *
 * ## 宿主调用路径（扩展点方法）
 * - settings.schema() / settings.options(key)
 * - transform.candidates(req)
 * - panel.state({inputText}) / panel.onInput({key,value}，key 为空串 = 主输入框，其余为 ui 节点 key) /
 *   panel.onAction({actionId}) / panel.onItemClick({itemId})
 * - emoji.listCategories() / emoji.query(q) / emoji.icon()
 * - speech.configure(options) / speech.feed(chunk) / speech.start() /
 *   speech.stop() / speech.cancel()
 * - clipboardSync.push(profile) / clipboardSync.pull() / clipboardSync.test()
 * - backup.push(args) / backup.pull(id) / backup.list() / backup.remove(id) / backup.test()
 * - events.onXxx(payload)（事件类型 input_changed → 槽名 onInputChanged，见 [eventSlotName]）
 * - ws.onOpen / ws.onMessage / ws.onBinary / ws.onError / ws.onClose
 * - sse.onData / sse.onDone / sse.onError
 *
 * ## 惯例
 * - 方法 camelCase，字段**一律 camelCase**（事件 payload / profile 等跨桥对象）
 * - 输入结构化：宿主以对象入参（如 `panel.state({inputText})`），不用位置参数
 * - **TS 范式（async/await）**：host 服务调用返回 Promise（`await host.http.request(...)`），
 *   失败 throw `XimeError`（结构化 code + message）；纯计算 API 同步返回
 * - 能力探测：`host.has(name)` / `host.capabilities`；未声明子能力不存在
 *   （`typeof host.ws === 'undefined'`），推荐 has 显式探测
 * - 配置糖：`host.config.getJson(key)`（键不存在/内容非法返回 null）
 * - 二进制一律 `Uint8Array`；时间用 `Date` / `Date.now()`
 * - 呈现模型：插件不自绘 UI（data-only / declarative / headless 三档，见 SDK 文档）
 * - 错误形态：插件抛出的异常被宿主捕获并记日志（含 main.js:行号）；返回形态不符
 *   协议时宿主丢弃并记协议日志，不拖垮宿主
 */
object JsPluginContract {

    /**
     * SDK 版本（宿主注入的 host.sdkVersion）。
     *
     * v3 = TS 范式：异步服务（await + Promise）、结构化错误（throw XimeError）、
     * 扩展点 async 支持（panel/speech/clipboardSync/backup/onLoad）。
     * 破坏性变更，插件须随之提升大版本（manifest.version）。
     */
    const val SDK_VERSION = "3.0.0"

    // ---- 宿主注入的全局对象 ----
    const val GLOBAL_HOST = "host"

    /** 插件导出对象挂在 globalThis 下的名字（入口脚本必须定义它）。 */
    const val GLOBAL_PLUGIN = "plugin"

    /** 插件定义工厂（宿主注入的类型恒等函数）。 */
    const val GLOBAL_DEFINE_PLUGIN = "definePlugin"

    /** 结构化错误类（宿主注入；async 服务失败 throw 的形态）。 */
    const val GLOBAL_XIME_ERROR = "XimeError"

    // ---- XimeError.code（结构化错误码） ----
    const val ERR_NETWORK = "E_NETWORK"
    const val ERR_TIMEOUT = "E_TIMEOUT"
    const val ERR_DENIED = "E_DENIED"
    const val ERR_INVALID = "E_INVALID"
    const val ERR_IO = "E_IO"
    const val ERR_INTERNAL = "E_INTERNAL"
    const val ERR_UNKNOWN = "E_UNKNOWN"

    // ---- async 服务桥（原生判别式桥命名：`__<方法名>`；bootstrap wrapper 覆盖同名 API） ----
    /** 原生桥名前缀：host.http.__request / host.ws.__connect / host.zlib.__gzip 等。 */
    const val BRIDGE_PREFIX = "__"

    // ---- 宿主调用插件 async 方法的暂存槽（「发起 + 二次读取」模式） ----
    const val ASYNC_STASH_STATUS = "__ximeAsyncStatus"
    const val ASYNC_STASH_RESULT = "__ximeAsyncResult"
    const val ASYNC_STASH_ERROR = "__ximeAsyncError"

    // ---- 生命周期（顶层） ----
    const val FN_ON_LOAD = "onLoad"
    const val FN_ON_UNLOAD = "onUnload"

    // ---- 事件槽：events.<slot> ----
    /** 事件槽路径前缀（槽名 = "on" + 事件类型 PascalCase，见 [eventSlotName]）。 */
    const val PATH_EVENTS_PREFIX = "events."

    /** 事件类型（snake_case）→ 事件槽名（camelCase）：input_changed → onInputChanged。 */
    fun eventSlotName(type: String): String =
        "on" + type.split('_').joinToString("") { part ->
            part.replaceFirstChar { c -> c.uppercaseChar() }
        }

    // ---- 配置表单（settings） ----
    const val PATH_SETTINGS_SCHEMA = "settings.schema"
    const val PATH_SETTINGS_OPTIONS = "settings.options"

    // ---- 候选词变换（transform，hotPath） ----
    const val PATH_TRANSFORM_CANDIDATES = "transform.candidates"

    // ---- panel ----
    const val PATH_PANEL_STATE = "panel.state"
    const val PATH_PANEL_ON_INPUT = "panel.onInput"
    const val PATH_PANEL_ON_ACTION = "panel.onAction"
    const val PATH_PANEL_ON_ITEM_CLICK = "panel.onItemClick"

    // ---- emoji ----
    const val PATH_EMOJI_CATEGORIES = "emoji.listCategories"
    const val PATH_EMOJI_QUERY = "emoji.query"
    const val PATH_EMOJI_ICON = "emoji.icon"

    // ---- speech ----
    const val PATH_SPEECH_CONFIGURE = "speech.configure"
    const val PATH_SPEECH_FEED = "speech.feed"
    const val PATH_SPEECH_START = "speech.start"
    const val PATH_SPEECH_STOP = "speech.stop"
    const val PATH_SPEECH_CANCEL = "speech.cancel"

    // ---- clipboardSync ----
    const val PATH_CLIPBOARD_PUSH = "clipboardSync.push"
    const val PATH_CLIPBOARD_PULL = "clipboardSync.pull"
    const val PATH_CLIPBOARD_TEST = "clipboardSync.test"

    // ---- backup ----
    const val PATH_BACKUP_PUSH = "backup.push"
    const val PATH_BACKUP_PULL = "backup.pull"
    const val PATH_BACKUP_LIST = "backup.list"
    const val PATH_BACKUP_REMOVE = "backup.remove"
    const val PATH_BACKUP_TEST = "backup.test"

    // ---- 网络回调槽（ws / sse） ----
    const val SLOT_WS_OPEN = "ws.onOpen"
    const val SLOT_WS_MESSAGE = "ws.onMessage"
    const val SLOT_WS_BINARY = "ws.onBinary"
    const val SLOT_WS_ERROR = "ws.onError"
    const val SLOT_WS_CLOSE = "ws.onClose"
    const val SLOT_SSE_DATA = "sse.onData"
    const val SLOT_SSE_DONE = "sse.onDone"
    const val SLOT_SSE_ERROR = "sse.onError"

    // ---- emoji item 字段 ----
    const val FIELD_ID = "id"
    const val FIELD_TEXT = "text"
    const val FIELD_INSERT_TEXT = "insertText"
    const val FIELD_IMAGE_URL = "imageUrl"
}
