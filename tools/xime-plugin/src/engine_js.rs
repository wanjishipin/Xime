// 测试引擎 JS 资产：bootstrap（polyfill + mock host）+ 输出辅助。
//
// 对齐宿主 JsScriptRuntime 的引导语义（v3 契约）：
// - console/TextEncoder/TextDecoder/atob/btoa polyfill（宿主行为一致）
// - host.* 按 d.ts（async 服务 reject XimeError{code,message}；纯计算同步）
// - 确定性优先：网络一律走 __ximeMock 注册表，绝不真实联网
// - 测试 API：全局 test(name, fn) / assert.* / __ximeMock（配置与断言入口）

// ─────────────────────────────────────────────────────────────────────────────
// 输出辅助（Rust 侧调用）
// ─────────────────────────────────────────────────────────────────────────────

use std::io::IsTerminal;

/// 插件 console/log 输出透出（stdout）。
pub fn emit_log(plugin_id: &str, level: &str, message: &str) {
    match level {
        "error" => eprintln!("  {} [{}] {}", red("error"), plugin_id, message),
        "warn" => println!("  {} [{}] {}", yellow("warn"), plugin_id, message),
        _ => println!("  [{}] {}", plugin_id, message),
    }
}

pub fn green(s: &str) -> String {
    if std::io::stdout().is_terminal() {
        format!("\x1b[32m{s}\x1b[0m")
    } else {
        s.to_string()
    }
}

pub fn red(s: &str) -> String {
    if std::io::stderr().is_terminal() {
        format!("\x1b[31m{s}\x1b[0m")
    } else {
        s.to_string()
    }
}

pub fn yellow(s: &str) -> String {
    if std::io::stdout().is_terminal() {
        format!("\x1b[33m{s}\x1b[0m")
    } else {
        s.to_string()
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Bootstrap + mock host
// ─────────────────────────────────────────────────────────────────────────────

pub const BOOTSTRAP_JS: &str = r#"
// ============================================================
// Xime Plugin 测试环境引导（与宿主 JsScriptRuntime 对齐）
// ============================================================

// ---- 原生能力（Rust 侧注入，未注入则明确报错而非静默） ----
var __ximeNative = globalThis.__ximeNative || {};

function __requireNative(name) {
  if (typeof __ximeNative[name] !== 'function') {
    throw new Error('[test-env] 原生能力未注入: ' + name);
  }
  return __ximeNative[name];
}

// ---- 错误模型（v3：throw XimeError{code,message}） ----
if (typeof globalThis.XimeError === 'undefined') {
  globalThis.XimeError = class XimeError extends Error {
    constructor(code, message) {
      super(message);
      this.code = code;
      this.name = 'XimeError';
    }
  };
}

// ---- console polyfill（宿主：error→logError，其余→log） ----
if (typeof globalThis.console === 'undefined') {
  globalThis.console = {
    log:   function () { commonLog('log', arguments); },
    info:  function () { commonLog('log', arguments); },
    debug: function () { commonLog('log', arguments); },
    warn:  function () { commonLog('warn', arguments); },
    error: function () { commonLog('error', arguments); },
  };
}
function commonLog(level, args) {
  var msgs = [];
  for (var i = 0; i < args.length; i++) {
    var a = args[i];
    msgs.push(typeof a === 'string' ? a : safeStringify(a));
  }
  globalThis.__ximeNative.log(level, msgs.join(' '));
}
function safeStringify(v) {
  try { return JSON.stringify(v); } catch (e) { return String(v); }
}

// ---- TextEncoder / TextDecoder / atob / btoa（Rust 原生 UTF-8/Base64） ----
if (typeof globalThis.TextEncoder === 'undefined') {
  globalThis.TextEncoder = function () {};
  globalThis.TextEncoder.prototype.encode = function (s) {
    return new Uint8Array(__requireNative('textEncode')(String(s)));
  };
}
if (typeof globalThis.TextDecoder === 'undefined') {
  globalThis.TextDecoder = function () {};
  globalThis.TextDecoder.prototype.decode = function (buf) {
    return __requireNative('textDecode')(buf);
  };
}
if (typeof globalThis.atob === 'undefined') {
  globalThis.atob = function (s) {
    return __requireNative('atob')(String(s));
  };
}
if (typeof globalThis.btoa === 'undefined') {
  globalThis.btoa = function (s) {
    return __requireNative('btoa')(String(s));
  };
}

// ---- definePlugin（与宿主同：恒等函数；产物 banner 已自带，幂等） ----
if (typeof globalThis.definePlugin === 'undefined') {
  globalThis.definePlugin = function (spec) { return spec; };
}

// ============================================================
// Mock 状态
// ============================================================
var __mock = {
  config: {},                         // host.config 的 string→string 存储
  clock: 0,                           // 0 = 真实时钟；>0 = 固定 epoch 秒
  httpRoutes: [],                     // {method, pattern, handler}
  httpRequests: [],                   // 已发起的请求记录（供断言）
  sseRoutes: [],                      // {url, sessionId, events, done}
  sseSessions: {},                    // sessionId -> {events, done, idx}
  nextSseId: 1,
  wsSessions: {},                     // url -> {sent: [], state}
  activeWsUrl: null,
  asrEvents: [],                      // {type, text|message|state}
  quickSendItems: [],
  clipboard: null,
};

// 测试可写入口：globalThis.__ximeMock
globalThis.__ximeMock = {
  // ---- 配置/时钟 ----
  setConfig: function (key, value) { __mock.config[String(key)] = String(value); },
  setClock: function (epochSeconds) { __mock.clock = Math.floor(epochSeconds); },
  resetClock: function () { __mock.clock = 0; },

  // ---- HTTP stub ----
  //  pattern: 字符串（精确 URL）或函数 (url) => boolean
  //  resp: {status, headers?, text?} 静态响应；或 async (req, url) => resp
  addHttpResponse: function (method, pattern, resp) {
    __mock.httpRoutes.push({ method: String(method).toUpperCase(), pattern: pattern, resp: resp });
  },
  httpRequests: __mock.httpRequests,

  // ---- SSE stub ----
  //  events: [{data, event?}] 按序投递后 onDone；也可 {chunk: async (req,url)=>string[]}
  addSse: function (url, events) {
    __mock.sseRoutes.push({ url: url, events: events });
  },
  pushSse: function (sessionId, data) {
    var sess = __mock.sseSessions[sessionId];
    if (!sess) throw new Error('[test-env] 未知 SSE 会话: ' + sessionId);
    deliverSse(sess, { data: data });
  },

  // ---- WebSocket stub ----
  addWs: function (url) {
    __mock.wsSessions[url] = { sent: [], state: 0 };
  },
  wsOpen: function () { mockWsOpen(); },
  wsMessage: function (text) { mockWsMessage(text); },
  wsBinary: function (data) { mockWsBinary(data); },
  wsError: function (msg) { mockWsError(msg); },
  wsClose: function () { mockWsClose(); },

  // ---- 断言时的读侧 ----
  get sentWs() { return __mock.wsSessions[__mock.activeWsUrl] ? __mock.wsSessions[__mock.activeWsUrl].sent : []; },
  asrEvents: __mock.asrEvents,
  setQuickSend: function (items) { __mock.quickSendItems = items; },
  setClipboard: function (text) { __mock.clipboard = text; },
};

// ---- host 树（按 d.ts v3 契约） ----
globalThis.host = {
  sdkVersion: '3.0.0',
  has: function (cap) {
    return Object.prototype.hasOwnProperty.call(globalThis.host, cap);
  },
  capabilities: ['config', 'resource', 'bin', 'zlib', 'crypto', 'http', 'ws', 'asr', 'quickSend', 'clipboard', 'uuid'],
  log: function (m) { globalThis.__ximeNative.log('log', String(m)); },
  logError: function (m) { globalThis.__ximeNative.log('error', String(m)); },

  config: {
    get: function (key) {
      var v = __mock.config[String(key)];
      return v === undefined ? null : v;
    },
    getJson: function (key) {
      try {
        var v = __mock.config[String(key)];
        return v === undefined ? null : JSON.parse(v);
      } catch (e) { return null; }
    },
    set: function (key, value) { __mock.config[String(key)] = String(value); },
    remove: function (key) { delete __mock.config[String(key)]; },
    keys: function () { return Object.keys(__mock.config); },
  },

  resource: {
    path: function (name) { return __requireNative('resourcePath')(String(name)); },
    list: async function (dir) {
      var names = __requireNative('resourceList')(String(dir));
      return names;
    },
  },

  bin: {
    int32be: function (value) {
      var b = new Uint8Array(4);
      new DataView(b.buffer).setInt32(0, value, false);
      return b;
    },
    uint32be: function (value) {
      var b = new Uint8Array(4);
      new DataView(b.buffer).setUint32(0, value, false);
      return b;
    },
  },

  zlib: {
    gzip: async function (data) {
      return new Uint8Array(__requireNative('gzip')(data));
    },
    gunzip: async function (data) {
      return new Uint8Array(__requireNative('gunzip')(data));
    },
  },

  crypto: {
    sha256: function (data) { return new Uint8Array(__requireNative('sha256')(data)); },
    hmacSha256: function (key, data) {
      return new Uint8Array(__requireNative('hmacSha256')(key, data));
    },
    hmacSha1: function (key, data) {
      return new Uint8Array(__requireNative('hmacSha1')(key, data));
    },
    hex: function (data) { return __requireNative('hex')(data); },
    base64: function (data) { return __requireNative('base64')(data); },
    utcTime: function (format) {
      var epoch = __mock.clock > 0 ? __mock.clock : Math.floor(Date.now() / 1000);
      var d = new Date(epoch * 1000);
      var p = function (n) { return (n < 10 ? '0' : '') + n; };
      var s = '' + d.getUTCFullYear() + p(d.getUTCMonth() + 1) + p(d.getUTCDate());
      if (format === 'YYYYMMDDTHHMMSSZ') {
        s += 'T' + p(d.getUTCHours()) + p(d.getUTCMinutes()) + p(d.getUTCSeconds()) + 'Z';
      }
      return s;
    },
    epochSeconds: function () {
      return __mock.clock > 0 ? __mock.clock : Math.floor(Date.now() / 1000);
    },
  },

  http: {
    request: async function (method, url, headers, body, timeoutMillis) {
      var req = {
        method: String(method).toUpperCase(),
        url: String(url),
        headers: headers || {},
        body: body || null,
        timeoutMillis: typeof timeoutMillis === 'number' ? timeoutMillis : undefined,
        text: body instanceof Uint8Array
          ? globalThis.__ximeNative.textDecode(body) : null,
      };
      __mock.httpRequests.push(req);
      var matched = __mock.httpRoutes.find(function (r) {
        return r.method === req.method && matchPattern(r.pattern, req.url);
      });
      if (!matched) {
        throw new globalThis.XimeError('E_NETWORK',
          '[test-env] 未注册该请求的 stub（__ximeMock.addHttpResponse: ' + req.method + ' ' + req.url + '）');
      }
      var resp = typeof matched.resp === 'function'
        ? await matched.resp(req, req.url) : matched.resp;
      var status = resp.status !== undefined ? resp.status : 200;
      var headers = resp.headers || {};
      var bodyBytes = new Uint8Array(0);
      if (typeof resp.text === 'string') {
        bodyBytes = new Uint8Array(globalThis.__ximeNative.textEncode(resp.text));
      } else if (resp.body instanceof Uint8Array) {
        bodyBytes = resp.body;
      } else if (resp.body !== undefined) {
        throw new globalThis.XimeError('E_INVALID', '[test-env] stub resp.body 需为 Uint8Array，或使用 text');
      }
      return {
        status: status,
        headers: headers,
        body: bodyBytes,
        text: globalThis.__ximeNative.textDecode(bodyBytes),
      };
    },
    stream: async function (url, headers, timeoutMillis, method, body) {
      var route = __mock.sseRoutes.find(function (r) { return matchPattern(r.url, url); });
      if (!route) {
        throw new globalThis.XimeError('E_NETWORK',
          '[test-env] 未注册该 SSE 流的 stub（__ximeMock.addSse: ' + url + '）');
      }
      var sessionId = __mock.nextSseId++;
      var sess = { events: route.events.slice(), idx: 0 };
      __mock.sseSessions[sessionId] = sess;
      deliverSseLoop(sessionId, sess);
      return sessionId;
    },
    closeStream: async function (sessionId) {
      if (__mock.sseSessions[sessionId]) {
        delete __mock.sseSessions[sessionId];
      }
    },
  },

  ws: {
    connect: async function (url, headers) {
      var sess = __mock.wsSessions[url];
      if (!sess) {
        throw new globalThis.XimeError('E_NETWORK',
          '[test-env] 未注册该 WS 的 stub（__ximeMock.addWs: ' + url + '）');
      }
      __mock.activeWsUrl = url;
      sess.state = 0;
      return true;
    },
    sendText: async function (text) {
      var url = __mock.activeWsUrl;
      var sess = url ? __mock.wsSessions[url] : null;
      if (!sess) {
        throw new globalThis.XimeError('E_NETWORK', '[test-env] 无活跃 WS 会话');
      }
      sess.sent.push({ type: 'text', text: String(text) });
      return true;
    },
    sendBinary: async function (data) {
      var url = __mock.activeWsUrl;
      var sess = url ? __mock.wsSessions[url] : null;
      if (!sess) {
        throw new globalThis.XimeError('E_NETWORK', '[test-env] 无活跃 WS 会话');
      }
      sess.sent.push({ type: 'binary', data: data });
      return true;
    },
    close: async function () {
      var url = __mock.activeWsUrl;
      if (url && __mock.wsSessions[url]) __mock.wsSessions[url].state = 3;
      __mock.activeWsUrl = null;
    },
    getState: function () {
      var url = __mock.activeWsUrl;
      return url && __mock.wsSessions[url] ? __mock.wsSessions[url].state : 3;
    },
  },

  asr: {
    emitFinal: function (text) { __mock.asrEvents.push({ type: 'final', text: String(text) }); },
    emitPartial: function (text) { __mock.asrEvents.push({ type: 'partial', text: String(text) }); },
    emitError: function (message) { __mock.asrEvents.push({ type: 'error', message: String(message) }); },
    emitState: function (state) { __mock.asrEvents.push({ type: 'state', state: state }); },
  },

  uuid: function () {
    return '00000000-0000-4000-8000-000000000000';
  },

  quickSend: {
    list: function () { return __mock.quickSendItems.slice(); },
  },

  clipboard: {
    get: function () { return __mock.clipboard; },
  },
};

// ============================================================
// 事件投递辅助（SSE / WS 槽，与宿主回调槽语义一致）
// ============================================================
function matchPattern(pattern, url) {
  if (typeof pattern === 'function') return pattern(url);
  if (pattern instanceof RegExp) return pattern.test(url);
  return String(pattern) === String(url);
}

function callSlot(slot, args) {
  var plugin = globalThis.plugin;
  if (!plugin) throw new Error('[test-env] globalThis.plugin 未定义');
  var fn = plugin[slot];
  if (typeof fn !== 'function') return;
  return fn.apply(plugin, args);
}

async function deliverSseLoop(sessionId, sess) {
  while (sess.idx < sess.events.length) {
    var ev = sess.events[sess.idx++];
    await Promise.resolve();
    callSlot('sse', [{ data: ev.data, event: ev.event }].length === 2
      ? [] : [{ data: ev.data }]);
    // 投递到 plugin.sse.onData(sessionId, text)
    var p = globalThis.plugin;
    if (p && p.sse && typeof p.sse.onData === 'function') {
      p.sse.onData(sessionId, ev.data);
    }
  }
  if (typeof globalThis.plugin !== 'undefined' && globalThis.plugin.sse
      && typeof globalThis.plugin.sse.onDone === 'function') {
    globalThis.plugin.sse.onDone(sessionId, '');
  }
}

function deliverSse(sess, ev) {
  if (!sess) return;
  sess.events.splice(sess.idx, 0, ev);
  sess.idx++;
  var p = globalThis.plugin;
  if (p && p.sse && typeof p.sse.onData === 'function') {
    p.sse.onData(__mock.nextSseId - 1, ev.data);
  }
}

function mockWsOpen() {
  var p = globalThis.plugin;
  if (p && p.ws && typeof p.ws.onOpen === 'function') p.ws.onOpen();
}
function mockWsMessage(text) {
  var p = globalThis.plugin;
  if (p && p.ws && typeof p.ws.onMessage === 'function') p.ws.onMessage(String(text));
}
function mockWsBinary(data) {
  var p = globalThis.plugin;
  if (p && p.ws && typeof p.ws.onBinary === 'function') p.ws.onBinary(data);
}
function mockWsError(msg) {
  var p = globalThis.plugin;
  if (p && p.ws && typeof p.ws.onError === 'function') p.ws.onError(String(msg));
}
function mockWsClose() {
  var p = globalThis.plugin;
  if (p && p.ws && typeof p.ws.onClose === 'function') p.ws.onClose();
}

// ============================================================
// 测试 API：test / assert / __ximeRunTests
// ============================================================
var __ximeTests = [];

globalThis.test = function (name, fn) {
  if (typeof name !== 'string') throw new Error('[test-env] test 名称必须为字符串');
  __ximeTests.push({ name: name, fn: fn });
};

globalThis.assert = {
  ok: function (value, message) {
    if (!value) throw new Error(message || 'assert.ok 失败');
  },
  equal: function (actual, expected, message) {
    if (actual !== expected) {
      throw new Error((message ? message + '：' : '') + '期望 ' + JSON.stringify(expected)
        + '，实际 ' + JSON.stringify(actual));
    }
  },
  deepEqual: function (actual, expected, message) {
    var a = JSON.stringify(actual);
    var e = JSON.stringify(expected);
    if (a !== e) {
      throw new Error((message ? message + '：' : '') + '期望 ' + e + '，实际 ' + a);
    }
  },
  throws: function (fn, message) {
    var threw = false;
    try { fn(); } catch (e) { threw = true; }
    if (!threw) throw new Error(message || '期望抛异常，但未抛出');
  },
  rejects: async function (promise, message) {
    var rejected = false;
    try { await promise; } catch (e) { rejected = true; }
    if (!rejected) throw new Error(message || '期望 reject，但未 reject');
  },
};

globalThis.__ximeRunTests = async function () {
  var results = [];
  for (var i = 0; i < __ximeTests.length; i++) {
    var t = __ximeTests[i];
    try {
      await t.fn();
      results.push({ name: t.name, passed: true, message: '', stack: null });
    } catch (e) {
      results.push({
        name: t.name,
        passed: false,
        message: e && e.message ? String(e.message) : String(e),
        stack: e instanceof Error && e.stack ? String(e.stack) : null,
      });
    }
  }
  return results;
};
"#;