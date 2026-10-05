// ximed 剪贴板同步插件（QuickJS 脚本）
//
// 对接 ximed 服务器的 HTTP 接口，实现文本双向同步。
//
// 职责划分：
//   插件  = 协议逻辑（HTTP 端点拼装、Basic Auth、ETag 条件拉取、Profile JSON 组装/解析）
//   宿主  = 同步引擎（轮询/去重/回声抑制）+ 通用原语：
//     host.http        HTTP 白名单（request，async await，域名经用户授权）
//     host.config      配置存储（含 ETag 缓存）
//     host.crypto      base64（Basic Auth 头）
//
// ximed HTTP 接口（参考 ximed crates/client/src/transport.rs HttpTransport）：
//   端点   {server_url}/api/clipboard
//   push   PUT {endpoint}，Authorization: Basic base64(user:pass)，body = Profile JSON
//   pull   GET {endpoint}，Authorization: Basic；ETag 缓存到 host.config，
//          拉取时发 If-None-Match，304 → 无变更（返回 null）
//   Profile JSON（snake_case）：
//     { "type":"text", "hash":"...", "text":"...", "has_data":false,
//       "data_name":null, "size":12, "source":null }

const KEY_SERVER_URL = 'serverUrl';
const KEY_USERNAME = 'username';
const KEY_PASSWORD = 'password';
const KEY_LAST_ETAG = 'lastEtag';

const ENDPOINT_SUFFIX = '/api/clipboard';

// ================= 工具函数 =================

function basicAuthHeader(username: string, password: string): string {
  return 'Basic ' + host.crypto.base64(new TextEncoder().encode(username + ':' + password));
}

function endpointUrl(): string | null {
  let base = host.config.get(KEY_SERVER_URL) ?? '';
  base = base.replace(/\/+$/, '');
  if (base === '') return null;
  return base + ENDPOINT_SUFFIX;
}

function buildHeaders(): Record<string, string> {
  const headers: Record<string, string> = {};
  const username = host.config.get(KEY_USERNAME) ?? '';
  const password = host.config.get(KEY_PASSWORD) ?? '';
  if (username !== '') {
    headers['Authorization'] = basicAuthHeader(username, password);
  }
  headers['Accept'] = 'application/json';
  return headers;
}

// 记录响应头里的 ETag（大小写两种头名，供下次条件拉取）
function cacheEtag(resp: XimeHttpResponse): void {
  const headers = resp.headers;
  if (headers === null || headers === undefined) return;
  const etag = headers['ETag'] ?? headers['etag'];
  if (etag !== null && etag !== undefined) host.config.set(KEY_LAST_ETAG, etag);
}

const plugin = definePlugin({
  // ================= 生命周期 =================

  onLoad(): void {
  },

  onUnload(): void {
  },

  // ================= 配置 schema（与 manifest 一致） =================

  settings: {
    schema(): XimeUiNode[] {
      return [
        {
          key: KEY_SERVER_URL,
          label: '服务器地址',
          type: 'text',
          placeholder: 'https://host:port',
          helpText: 'ximed 服务器地址（形如 https://192.168.1.50:8080）',
        },
        {
          key: KEY_USERNAME,
          label: '用户名',
          type: 'text',
          required: false,
        },
        {
          key: KEY_PASSWORD,
          label: '密码',
          type: 'secret',
          required: false,
        },
        {
          // 拉取间隔由宿主引擎消费（键盘弹出时节流），插件自身不读取
          key: 'pull_interval_seconds',
          label: '拉取最小间隔（秒）',
          type: 'number',
          defaultValue: '30',
          placeholder: '30',
          helpText: '键盘每次弹出时拉取远端的最小间隔，范围 1~600，默认 30。' +
            '自建服务器无严格限流时可调小（如 1 秒，仅作防抖）',
          required: false,
        },
        {
          key: 'testConnection',
          label: '测试连接',
          type: 'button',
          required: false,
        },
      ];
    },
  },

  // ================= 同步接口（宿主调用路径 clipboardSync.*） =================

  clipboardSync: {
    // 推送本地 profile 到远端（宿主剪贴板变化时调用）
    async push(profile: XimeClipboardProfile): Promise<boolean> {
      const url = endpointUrl();
      if (url === null) return false;
      const headers = buildHeaders();
      headers['Content-Type'] = 'application/json';
      // wire 协议为 snake_case（ximed Profile 同构），SDK profile 为 camelCase：显式映射
      const body = JSON.stringify({
        type: profile.type,
        hash: profile.hash,
        text: profile.text,
        has_data: profile.hasData,
        data_name: profile.dataName,
        size: profile.size,
        source: profile.source,
      });
      if (body === null || body === undefined) return false;
      try {
        const resp = await host.http.request('PUT', url, headers, new TextEncoder().encode(body));
        if (resp.status >= 200 && resp.status < 300) {
          // 记录远端 etag，供下次条件拉取
          cacheEtag(resp);
          return true;
        }
        return false;
      } catch (e) {
        host.logError('push failed: ' + ((e as Error).message || ''));
        return false;
      }
    },

    // 拉取远端 profile（宿主轮询调用）；无变更返回 null
    async pull(): Promise<XimeClipboardProfile | null> {
      const url = endpointUrl();
      if (url === null) return null;
      const headers = buildHeaders();
      const etag = host.config.get(KEY_LAST_ETAG);
      if (etag !== null && etag !== '') {
        headers['If-None-Match'] = etag;
      }
      let resp: XimeHttpResponse;
      try {
        resp = await host.http.request('GET', url, headers, null);
      } catch (e) {
        host.logError('pull failed: ' + ((e as Error).message || ''));
        return null;
      }
      if (resp.status === 304) {
        // Not Modified，无变更
        return null;
      }
      if (resp.status >= 200 && resp.status < 300) {
        cacheEtag(resp);
        if (resp.text === null || resp.text === undefined) return null;
        let decoded: unknown;
        try {
          // 宿主 sandbox 无 host.json，用原生 JSON；非法输入 JSON.parse 抛异常
          decoded = JSON.parse(resp.text);
        } catch (e) {
          return null;
        }
        if (decoded === null || decoded === undefined) return null;
        // wire（snake_case）→ SDK profile（camelCase）
        const w = decoded as Record<string, unknown>;
        return {
          type: w.type === undefined || w.type === null ? 'text' : String(w.type),
          hash: w.hash === undefined || w.hash === null ? '' : String(w.hash),
          text: w.text === undefined || w.text === null ? '' : String(w.text),
          hasData: w.has_data === true,
          dataName: w.data_name === undefined || w.data_name === null ? null : String(w.data_name),
          size: typeof w.size === 'number' ? w.size : 0,
          source: w.source === undefined || w.source === null ? null : String(w.source),
        };
      }
      return null;
    },

    // 校验配置可用性（连接测试）；返回错误消息，null 表示成功
    async test(): Promise<string | null> {
      const url = endpointUrl();
      if (url === null) return '未配置服务器地址';
      try {
        const resp = await host.http.request('GET', url, buildHeaders(), null);
        if (resp.status === 401 || resp.status === 403) {
          return '认证失败（HTTP ' + resp.status + '）';
        }
        if (resp.status >= 200 && resp.status < 400) {
          return null;
        }
        return '连接失败（HTTP ' + resp.status + '）';
      } catch (e) {
        return (e as Error).message || '连接失败';
      }
    },
  },
});

export default plugin;
