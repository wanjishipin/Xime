// S3 兼容对象存储（Cloudflare R2 / AWS S3 / MinIO 等）剪贴板同步插件
//
// 职责划分：
//   插件 = 协议逻辑（SigV4 签名、对象 PUT/GET/HEAD/DELETE、条件写乐观锁、wire JSON、附件 blob）
//   宿主 = 同步引擎（轮询/去重/回声抑制）+ 原语：
//     host.http    请求原语（PUT/GET/HEAD/DELETE，async await，域名经用户授权）
//     host.config  配置存储（含 ETag 缓存）
//     host.crypto  sha256 / hmacSha256 / hex / utcTime（SigV4 需要的全部原语）
//
// 远端布局（单个元数据对象 + 内容寻址附件；与 webdav 插件的约定一致，固定路径式访问）：
//   目录    {endpoint}/{bucket}/{dir}/                  用户只配目录，默认 xime
//   元数据  {endpoint}/{bucket}/{dir}/clipboard.json     文件名固定
//   附件    {endpoint}/{bucket}/{dir}/blobs/{data_name}
//           blob 名即 sha256 ⇒ 重复上传幂等，对端可按 hash 去重（名字来自远端时按白名单校验）
//
// 交互模型：
//   push  有附件时**先 PUT blob、再 PUT 元数据**（顺序反了会留下"元数据指向缺失 blob"的悬空引用）
//         元数据 PUT 带条件头做乐观锁：有缓存 ETag → If-Match；无 → If-None-Match: *
//         412/409（条件不满足）= 远端被其它设备改过 → HEAD 取新 ETag 后重试一次
//   pull  GET 元数据带 If-None-Match（304 = 无变更）；has_data 时再 GET blob（取 resp.body 字节）
//         blob 取不到（push 中间态/网络失败）→ 清掉 ETag 并返回 null，下一轮重来
//   test  HEAD bucket（签名/凭据/桶存在）→ PUT+DELETE 探针对象（真正验证写权限）
//         → HEAD 元数据对象（报告远端是否已有内容）
//
// 认证：AWS Signature Version 4（Authorization 头，SignedHeaders = host;x-amz-content-sha256;x-amz-date）。
//       R2 的签名 region 固定用 auto；其它服务按实际 region 填。
//       签名里的 host 必须与 OkHttp 实际发出的 Host 头逐字节一致 ⇒ 域名一律小写、省略 scheme 默认端口
//       （443/80）；路径里的 "."/".." 段会被 HTTP 层归一化 ⇒ 对象键与附件名直接拒绝这种段。
// 注意：S3 单对象 PUT 上限 5GiB，宿主图片上限默认 5MB，不可能触及。
//       手机系统时间与服务器偏差超过 15 分钟会得到 RequestTimeTooSkewed。

import { EMPTY_PAYLOAD_SHA256, encodeS3Path, sha256Hex, signRequest } from './libs/sigv4';

const KEY_ENDPOINT = 'endpoint';
const KEY_REGION = 'region';
const KEY_BUCKET = 'bucket';
const KEY_DIR = 'dir';
const KEY_ACCESS_KEY_ID = 'accessKeyId';
const KEY_SECRET_ACCESS_KEY = 'secretAccessKey';
const KEY_LAST_ETAG = 'lastEtag';

const DEFAULT_REGION = 'auto'; // R2：空值 / us-east-1 / auto 等价；AWS 需填实际区域
const DEFAULT_DIR = 'xime';              // 所有对象都收拢在该目录下（留空即用它），不散落到桶根
const METADATA_FILE = 'clipboard.json';  // 元数据文件名固定：不暴露给用户，避免写出五花八门的键
const BLOBS_DIR = 'blobs';               // 附件固定放 <目录>/blobs/
const SERVICE = 's3';
const PROBE_OBJECT_NAME = '.xime-probe';

// 限流退避（沙箱无定时器，用调用计数近似时间窗口）：
// pull 由宿主节流后约 30s 一次，跳 12 次 ≈ 6 分钟；push 由剪贴板变化驱动，跳 3 次。
const PULL_BACKOFF_SKIPS = 12;
const PUSH_BACKOFF_SKIPS = 3;
let pullSkipCount = 0;
let pushSkipCount = 0;

// ================= 配置读取 =================

// 凭据可能被粘贴带入换行/首尾空白：不清掉会让签名与 AK 都对不上（SigV4 对字节敏感）
function cleanCredential(value: string | null): string {
  return value === null ? '' : value.replace(/[\r\n]/g, '').trim();
}

function region(): string {
  const value = (host.config.get(KEY_REGION) || '').trim();
  return value === '' ? DEFAULT_REGION : value;
}

function bucket(): string {
  return (host.config.get(KEY_BUCKET) || '').trim();
}

// 目录前缀：只让用户配目录，元数据文件名固定。留空按默认目录（绝不散落到桶根）。
function dirPrefix(): string {
  const raw = (host.config.get(KEY_DIR) || '').trim();
  const cleaned = raw.replace(/^\/+/, '').replace(/\/+$/, '').replace(/\/{2,}/g, '/');
  return cleaned === '' ? DEFAULT_DIR : cleaned;
}

// 元数据对象键固定为 <目录>/clipboard.json
function metadataKey(): string {
  return dirPrefix() + '/' + METADATA_FILE;
}

// 附件与元数据同目录：<目录>/blobs/<data_name>
function keyDir(): string {
  return dirPrefix() + '/';
}

interface S3Endpoint {
  scheme: string;
  host: string;
  /** 端点自带路径前缀（如反向代理挂在 /s3 下），已去掉尾部斜杠 */
  basePath: string;
}

// OkHttp 实际发出的 Host 头 = 域名小写 + 省略 scheme 的默认端口（BridgeInterceptor.toHostHeader），
// 签名里的 host 必须与之逐字节一致，否则每个请求都会 SignatureDoesNotMatch。
// 只支持路径式访问 host/{bucket}/{key}：虚拟主机式 {bucket}.{host} 是配置里解析不出来的域名，
// 会被宿主 NetworkPolicy（精确匹配，自动授权只覆盖配置中出现过的域名）直接拦下，故不提供该开关。
function normalizeHost(rawHost: string, scheme: string): string {
  let out = rawHost.toLowerCase();
  const defaultPort = scheme === 'https' ? ':443' : ':80';
  if (out.length > defaultPort.length
    && out.substring(out.length - defaultPort.length) === defaultPort) {
    out = out.substring(0, out.length - defaultPort.length);
  }
  return out;
}

// 解析端点：只接受 http(s)://host[:port][/path]，bucket 必须单独配置（不允许塞进端点里）
function parseEndpoint(): S3Endpoint | null {
  const raw = (host.config.get(KEY_ENDPOINT) || '').trim();
  if (raw === '') return null;
  const matched = /^(https?):\/\/([^/?#]+)(\/[^?#]*)?$/i.exec(raw);
  if (matched === null) return null;
  const scheme = matched[1].toLowerCase();
  const tail = matched[3] === undefined ? '' : matched[3].replace(/\/+$/, '');
  return { scheme: scheme, host: normalizeHost(matched[2], scheme), basePath: tail };
}

// 路径里的 "."/".." 段会被 HTTP 层按 RFC 3986 归一化后再发出，而签名用的是原文 ⇒ 必然 403。
// 对象键来自用户配置，data_name 来自远端元数据（不可信输入），一律拒绝。
function hasDotSegment(path: string): boolean {
  const parts = path.split('/');
  for (let i = 0; i < parts.length; i++) {
    if (parts[i] === '.' || parts[i] === '..') return true;
  }
  return false;
}

// 附件名由本插件按 "<sha256>.<扩展名>" 生成，白名单同时挡住无法编码的孤立代理项
// （encodeURIComponent 对孤立代理项抛 URIError，会让异常逃出 pull）
function isSafeDataName(name: string): boolean {
  return /^[A-Za-z0-9._-]+$/.test(name) && !hasDotSegment(name);
}

// 域名含非 ASCII（中文域名等）会被 OkHttp 转成 punycode 再发出，QuickJS 里没有 punycode 实现，
// 只能要求用户填 xn-- 形式，否则签名必然对不上
function hasNonAsciiHost(): boolean {
  const endpoint = parseEndpoint();
  return endpoint !== null && /[^\x00-\x7F]/.test(endpoint.host);
}

function missingConfig(): string | null {
  if ((host.config.get(KEY_ENDPOINT) || '').trim() === '') return '未配置 S3 端点（endpoint）';
  if (parseEndpoint() === null) {
    return 'S3 端点格式不对：应以 http(s):// 开头，例如 https://<账户ID>.r2.cloudflarestorage.com';
  }
  if (hasNonAsciiHost()) {
    return 'S3 端点域名含非 ASCII 字符：请填 punycode（xn-- 开头）形式，否则域名会被转码而签名对不上';
  }
  if (bucket() === '') return '未配置 bucket';
  if (hasDotSegment(dirPrefix())) {
    return '目录不能含 "." 或 ".." 路径段（HTTP 层会归一化路径，导致签名不匹配）';
  }
  if (cleanCredential(host.config.get(KEY_ACCESS_KEY_ID)) === '') return '未配置 Access Key ID';
  if (cleanCredential(host.config.get(KEY_SECRET_ACCESS_KEY)) === '') return '未配置 Secret Access Key';
  return null;
}

// ================= 请求构造（SigV4） =================

interface ObjectTarget {
  url: string;
  canonicalUri: string;
  hostHeader: string;
}

function targetFor(objectPath: string, isBucketRequest: boolean): ObjectTarget | null {
  const endpoint = parseEndpoint();
  const name = bucket();
  if (endpoint === null || name === '') return null;
  const rawPath = isBucketRequest
    ? endpoint.basePath + '/' + name
    : endpoint.basePath + '/' + name + '/' + objectPath;
  const canonicalUri = encodeS3Path(rawPath === '' ? '/' : rawPath);
  return {
    url: endpoint.scheme + '://' + endpoint.host + canonicalUri,
    canonicalUri: canonicalUri,
    hostHeader: endpoint.host,
  };
}

interface SignedRequest {
  url: string;
  headers: Record<string, string>;
}

// 组装一次已签名请求：extraHeaders 不参与签名（S3 允许存在未签名头），
// Authorization / x-amz-* 由这里统一生成。
function signedRequest(
  method: string,
  objectPath: string,
  extraHeaders: Record<string, string>,
  body: Uint8Array | null,
  isBucketRequest: boolean
): SignedRequest | null {
  const target = targetFor(objectPath, isBucketRequest);
  if (target === null) return null;
  const payloadHash = body === null || body.length === 0 ? EMPTY_PAYLOAD_SHA256 : sha256Hex(body);
  const amzDate = host.crypto.utcTime('YYYYMMDDTHHMMSSZ');
  // 只取一次时钟：两次独立取时钟若正好跨 UTC 午夜，scope 日期会与 x-amz-date 日期不一致
  const dateStamp = amzDate.substring(0, 8);
  const signed = signRequest({
    method: method,
    canonicalUri: target.canonicalUri,
    canonicalQuery: '',
    headers: {
      host: target.hostHeader,
      'x-amz-content-sha256': payloadHash,
      'x-amz-date': amzDate,
    },
    signedHeaders: ['host', 'x-amz-content-sha256', 'x-amz-date'],
    payloadHash: payloadHash,
    dateStamp: dateStamp,
    amzDate: amzDate,
    region: region(),
    service: SERVICE,
    accessKeyId: cleanCredential(host.config.get(KEY_ACCESS_KEY_ID)),
    secretAccessKey: cleanCredential(host.config.get(KEY_SECRET_ACCESS_KEY)),
  });
  const headers: Record<string, string> = {};
  const extraKeys = Object.keys(extraHeaders);
  for (let i = 0; i < extraKeys.length; i++) headers[extraKeys[i]] = extraHeaders[extraKeys[i]];
  headers['x-amz-content-sha256'] = payloadHash;
  headers['x-amz-date'] = amzDate;
  headers['Authorization'] = signed.authorization;
  return { url: target.url, headers: headers };
}

// ================= 响应解读 =================

function headerValue(resp: XimeHttpResponse, name: string): string {
  const wanted = name.toLowerCase();
  const keys = Object.keys(resp.headers);
  for (let i = 0; i < keys.length; i++) {
    if (keys[i].toLowerCase() === wanted) return resp.headers[keys[i]];
  }
  return '';
}

const S3_ERROR_HINTS: Record<string, string> = {
  SignatureDoesNotMatch:
    '签名不匹配：检查 Secret Access Key 是否完整（有无多余空格/换行），以及 region 是否填对（R2 固定 auto）',
  InvalidAccessKeyId: 'Access Key ID 不存在：检查是否复制完整',
  AccessDenied: '权限不足：该 Access Key / API Token 需要此 bucket 的对象读写权限',
  NoSuchBucket: 'bucket 不存在：检查 bucket 名（R2 的 bucket 名区分大小写）',
  NoSuchKey: '对象不存在',
  RequestTimeTooSkewed: '手机系统时间与服务器偏差超过 15 分钟：请校准系统时间后重试',
  AuthorizationHeaderMalformed: '签名头不合法：通常是 region 填错（R2 用 auto）',
  SignatureDoesNotMatchException: '签名不匹配：检查凭据与 region',
  SlowDown: '服务器限流（SlowDown）：请稍后重试',
  PreconditionFailed: '条件写失败：远端已被其它设备更新',
};

function xmlField(text: string, tag: string): string {
  const matched = new RegExp('<' + tag + '>([\\s\\S]*?)</' + tag + '>').exec(text);
  return matched === null ? '' : matched[1].trim();
}

// S3 的错误响应是 XML（<Error><Code>..</Code><Message>..</Message></Error>）
function describeS3Error(resp: XimeHttpResponse): string {
  const code = xmlField(resp.text, 'Code');
  const message = xmlField(resp.text, 'Message');
  if (code === '' && message === '') return '';
  const hint = S3_ERROR_HINTS[code];
  let out = '（';
  if (code !== '') out += code;
  if (message !== '') out += (code === '' ? '' : ': ') + message;
  out += hint === undefined ? '）' : ' → ' + hint + '）';
  return out;
}

function isSuccess(resp: XimeHttpResponse): boolean {
  return resp.status >= 200 && resp.status < 300;
}

// 限流：S3 用 503 SlowDown，部分边缘/CDN 用 429
function isThrottled(resp: XimeHttpResponse): boolean {
  return resp.status === 429 || resp.status === 503;
}

function isConditionFailed(resp: XimeHttpResponse): boolean {
  return resp.status === 412 || resp.status === 409 || resp.status === 428;
}

function errorText(e: unknown): string {
  return (e as Error).message || '未知错误';
}

// ================= ETag（乐观锁 + 条件拉取） =================

function cacheEtag(resp: XimeHttpResponse): void {
  const etag = headerValue(resp, 'ETag');
  if (etag !== '') host.config.set(KEY_LAST_ETAG, etag);
}

// HEAD 元数据对象取最新 ETag：'' = 远端没有该对象（可用 If-None-Match: * 新建）；
// null = 查询失败（不改动本地缓存）
async function refreshEtag(): Promise<string | null> {
  const req = signedRequest('HEAD', metadataKey(), {}, null, false);
  if (req === null) return null;
  try {
    const resp = await host.http.request('HEAD', req.url, req.headers, null);
    if (resp.status === 404) return '';
    if (isSuccess(resp)) {
      const etag = headerValue(resp, 'ETag');
      return etag === '' ? null : etag;
    }
    host.logError('刷新 ETag 失败: HEAD ' + req.url + ' -> HTTP ' + resp.status + describeS3Error(resp));
    return null;
  } catch (e) {
    host.logError('刷新 ETag 请求失败: ' + errorText(e));
    return null;
  }
}

// ================= 附件（图片原图，内容寻址） =================

const EXT_CONTENT_TYPE: Record<string, string> = {
  png: 'image/png',
  jpg: 'image/jpeg',
  jpeg: 'image/jpeg',
  gif: 'image/gif',
  webp: 'image/webp',
  bmp: 'image/bmp',
  heic: 'image/heic',
  heif: 'image/heic',
  avif: 'image/avif',
};

function contentTypeOf(dataName: string): string {
  const dot = dataName.lastIndexOf('.');
  if (dot < 0) return 'application/octet-stream';
  const ext = dataName.substring(dot + 1).toLowerCase();
  return EXT_CONTENT_TYPE[ext] === undefined ? 'application/octet-stream' : EXT_CONTENT_TYPE[ext];
}

function blobPath(dataName: string): string {
  return keyDir() + BLOBS_DIR + '/' + dataName;
}

// 上传附件：blob 名即内容 hash ⇒ 重复 PUT 幂等，不做 HEAD 预检（省一次请求）。
// 返回 true 才允许写元数据：绝不留下"元数据指向不存在 blob"的悬空引用。
async function putBlob(profile: XimeClipboardProfile): Promise<boolean> {
  const data = profile.data;
  if (data === null || data === undefined || data.length === 0) {
    host.logError('push failed: has_data 为真但没有附件字节');
    return false;
  }
  const dataName = profile.dataName;
  if (dataName === null || dataName === undefined || dataName === '') {
    host.logError('push failed: has_data 为真但缺少附件名');
    return false;
  }
  if (!isSafeDataName(dataName)) {
    host.logError('push failed: 附件名非法（只允许 [A-Za-z0-9._-] 且不含 "."/".." 段）：' + dataName);
    return false;
  }
  const req = signedRequest('PUT', blobPath(dataName), { 'Content-Type': contentTypeOf(dataName) }, data, false);
  if (req === null) {
    host.logError('push failed: 附件地址非法或未配置端点/bucket');
    return false;
  }
  try {
    const resp = await host.http.request('PUT', req.url, req.headers, data);
    if (isSuccess(resp)) {
      host.log('push ok: PUT blob ' + req.url + ' -> ' + resp.status + ' (' + data.length + ' bytes)');
      return true;
    }
    if (isThrottled(resp)) {
      pushSkipCount = PUSH_BACKOFF_SKIPS;
      host.logError('push failed: 附件上传限流 HTTP ' + resp.status
        + '，跳过接下来 ' + PUSH_BACKOFF_SKIPS + ' 次推送');
      return false;
    }
    host.logError('push failed: PUT blob ' + req.url + ' -> HTTP ' + resp.status + describeS3Error(resp));
    return false;
  } catch (e) {
    host.logError('push failed: 附件上传请求失败 ' + errorText(e));
    return false;
  }
}

// 下载附件。**必须用 resp.body**：resp.text 会按 UTF-8 解码，图片字节会被破坏且不可逆。
async function fetchBlob(dataName: string): Promise<Uint8Array | null> {
  const req = signedRequest('GET', blobPath(dataName), {}, null, false);
  if (req === null) {
    host.logError('pull failed: 附件地址非法或未配置端点/bucket');
    return null;
  }
  try {
    const resp = await host.http.request('GET', req.url, req.headers, null);
    if (isThrottled(resp)) {
      pullSkipCount = PULL_BACKOFF_SKIPS;
      host.logError('pull failed: 附件下载限流 HTTP ' + resp.status
        + '，跳过接下来 ' + PULL_BACKOFF_SKIPS + ' 次拉取');
      return null;
    }
    if (!isSuccess(resp)) {
      host.logError('pull failed: GET blob ' + req.url + ' -> HTTP ' + resp.status + describeS3Error(resp));
      return null;
    }
    const body = resp.body;
    if (body === null || body === undefined || body.length === 0) {
      host.logError('pull failed: 远端附件为空');
      return null;
    }
    return body;
  } catch (e) {
    host.logError('pull failed: 附件下载请求失败 ' + errorText(e));
    return null;
  }
}

// ================= 插件定义 =================

const plugin = definePlugin({
  onLoad(): void {
  },

  onUnload(): void {
  },

  settings: {
    schema(): XimeUiNode[] {
      return [
        {
          key: KEY_ENDPOINT,
          label: 'S3 端点',
          type: 'text',
          placeholder: 'https://<账户ID>.r2.cloudflarestorage.com',
          required: true,
          helpText: 'S3 兼容服务端点，**不要带 bucket**：R2 形如 https://<账户ID>.r2.cloudflarestorage.com；'
            + 'MinIO 形如 http://192.168.1.50:9000',
        },
        {
          key: KEY_REGION,
          label: '区域（region）',
          type: 'text',
          defaultValue: DEFAULT_REGION,
          placeholder: DEFAULT_REGION,
          required: false,
          helpText: 'R2 固定 auto（留空也按 auto）；AWS S3 填实际区域（如 us-east-1）；MinIO 可填 us-east-1',
        },
        {
          key: KEY_BUCKET,
          label: 'Bucket',
          type: 'text',
          required: true,
          helpText: '存放剪贴板对象的存储桶名',
        },
        {
          key: KEY_DIR,
          label: '目录（前缀）',
          type: 'text',
          defaultValue: DEFAULT_DIR,
          placeholder: DEFAULT_DIR,
          required: false,
          helpText: '**所有对象都收拢在这个目录下**（留空即 ' + DEFAULT_DIR + '）：元数据固定为 '
            + '<目录>/clipboard.json，图片固定为 <目录>/blobs/<sha256>.<扩展名>，不会散落到桶根目录',
        },
        {
          key: KEY_ACCESS_KEY_ID,
          label: 'Access Key ID',
          type: 'text',
          required: true,
          helpText: 'R2：在「R2 → Manage R2 API Tokens」创建 Object Read & Write 权限的令牌后获得',
        },
        {
          key: KEY_SECRET_ACCESS_KEY,
          label: 'Secret Access Key',
          type: 'secret',
          required: true,
          helpText: '只需创建令牌时显示一次；本机加密存储，不会写入日志',
        },
        {
          key: 'pull_interval_seconds',
          label: '拉取最小间隔（秒）',
          type: 'number',
          defaultValue: '30',
          placeholder: '30',
          required: false,
          helpText: '键盘每次弹出时拉取远端的最小间隔（1~600，默认 30）；S3 没有 WebDAV 那种请求额度，一般不用改',
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

  clipboardSync: {
    // 推送本地剪贴板（宿主剪贴板变化时调用）
    async push(profile: XimeClipboardProfile): Promise<boolean> {
      if (pushSkipCount > 0) {
        pushSkipCount = pushSkipCount - 1;
        host.log('push: 限流退避中，跳过（剩余 ' + pushSkipCount + ' 次）');
        return false;
      }
      const missing = missingConfig();
      if (missing !== null) {
        host.logError('push failed: ' + missing);
        return false;
      }
      // 附件先行：blob 没写成功就绝不写元数据（否则对端会拉到"有元数据、无图"的空条目）
      if (profile.hasData) {
        const uploaded = await putBlob(profile);
        if (!uploaded) {
          host.logError('push failed: 附件未上传成功，跳过元数据写入');
          return false;
        }
      }

      let payload: Uint8Array;
      try {
        // wire 为 snake_case（与 webdav 插件 / ximed Profile 同构），SDK profile 为 camelCase
        payload = new TextEncoder().encode(JSON.stringify({
          type: profile.type,
          hash: profile.hash,
          text: profile.text,
          has_data: profile.hasData,
          data_name: profile.dataName,
          size: profile.size,
          source: profile.source,
        }));
      } catch (e) {
        host.logError('push failed: 元数据序列化失败 ' + errorText(e));
        return false;
      }

      const key = metadataKey();
      const cachedEtag = host.config.get(KEY_LAST_ETAG);
      // 有缓存 ETag 就要求"远端还是我看到的那版"；没有则要求"远端还不存在"（首次创建）
      let conditional: Record<string, string> = cachedEtag !== null && cachedEtag !== ''
        ? { 'If-Match': cachedEtag }
        : { 'If-None-Match': '*' };

      let req = signedRequest('PUT', key, conditional, payload, false);
      if (req === null) {
        host.logError('push failed: 对象地址非法或未配置端点/bucket');
        return false;
      }
      let resp: XimeHttpResponse;
      try {
        resp = await host.http.request('PUT', req.url, req.headers, payload);
      } catch (e) {
        host.logError('push failed: 请求失败 ' + errorText(e));
        return false;
      }

      // 412/409/428 = 条件不满足；404 = 缓存 ETag 指的对象已被删除（自愈路径相同：刷新 ETag 后重试一次）
      if (isConditionFailed(resp) || resp.status === 404) {
        const fresh = await refreshEtag();
        if (fresh === null) {
          host.logError('push failed: 条件写失败（HTTP ' + resp.status + '）且无法刷新远端 ETag，'
            + '本次跳过，下次剪贴板变化时重试');
          return false;
        }
        conditional = fresh === '' ? { 'If-None-Match': '*' } : { 'If-Match': fresh };
        req = signedRequest('PUT', key, conditional, payload, false);
        if (req === null) return false;
        host.log('push: ' + (resp.status === 404 ? '远端对象已不存在' : '条件写冲突')
          + '（HTTP ' + resp.status + '），已刷新 ETag 后重试一次');
        try {
          resp = await host.http.request('PUT', req.url, req.headers, payload);
        } catch (e) {
          host.logError('push failed: 重试请求失败 ' + errorText(e));
          return false;
        }
      }

      if (isSuccess(resp)) {
        cacheEtag(resp);
        host.log('push ok: PUT ' + req.url + ' -> ' + resp.status + ' (' + payload.length + ' bytes)');
        return true;
      }
      if (isThrottled(resp)) {
        pushSkipCount = PUSH_BACKOFF_SKIPS;
        host.logError('push failed: HTTP ' + resp.status + ' 服务器限流，跳过接下来 '
          + PUSH_BACKOFF_SKIPS + ' 次推送');
        return false;
      }
      host.logError('push failed: PUT ' + req.url + ' -> HTTP ' + resp.status + describeS3Error(resp));
      return false;
    },

    // 拉取远端剪贴板（宿主轮询调用）；无变更/无对象返回 null
    async pull(): Promise<XimeClipboardProfile | null> {
      if (pullSkipCount > 0) {
        pullSkipCount = pullSkipCount - 1;
        host.log('pull: 限流退避中，跳过（剩余 ' + pullSkipCount + ' 次）');
        return null;
      }
      const missing = missingConfig();
      if (missing !== null) {
        host.log('pull: ' + missing);
        return null;
      }
      const etag = host.config.get(KEY_LAST_ETAG);
      const conditional: Record<string, string> = etag !== null && etag !== ''
        ? { 'If-None-Match': etag }
        : {};
      const req = signedRequest('GET', metadataKey(), conditional, null, false);
      if (req === null) {
        host.log('pull: 对象地址非法或未配置端点/bucket');
        return null;
      }
      let resp: XimeHttpResponse;
      try {
        resp = await host.http.request('GET', req.url, req.headers, null);
      } catch (e) {
        host.logError('pull failed: 请求失败 ' + errorText(e));
        return null;
      }
      if (resp.status === 304) return null; // 条件请求命中：无变更
      if (resp.status === 404) {
        // 远端对象不存在（尚未创建，或被控制台/生命周期规则删了）：清掉过期 ETag，
        // 否则下次 push 会拿着旧 ETag 去 If-Match 一个不存在的对象
        if (host.config.get(KEY_LAST_ETAG) !== null) host.config.remove(KEY_LAST_ETAG);
        return null;
      }
      if (isThrottled(resp)) {
        pullSkipCount = PULL_BACKOFF_SKIPS;
        host.logError('pull failed: HTTP ' + resp.status + ' 服务器限流，跳过接下来 '
          + PULL_BACKOFF_SKIPS + ' 次拉取');
        return null;
      }
      if (!isSuccess(resp)) {
        host.logError('pull failed: GET ' + req.url + ' -> HTTP ' + resp.status + describeS3Error(resp));
        return null;
      }
      cacheEtag(resp);

      // 解析段整体兜底：任何内部异常都不允许把这次响应的 ETag 留在缓存里——否则之后每轮
      // 都命中 304，这条记录会被永久跳过，且不留任何"待重试"线索
      try {
        const text = resp.text;
        if (text === null || text === undefined || text === '') {
          host.log('pull: 远端对象为空');
          return null;
        }
        let decoded: unknown = null;
        try {
          decoded = JSON.parse(text);
        } catch (e) {
          decoded = null;
        }
        if (decoded !== null && typeof decoded === 'object' && !Array.isArray(decoded)) {
          const wire = decoded as Record<string, unknown>;
          const hasData = wire.has_data === true;
          const hasTextKey = wire.text !== null && wire.text !== undefined;
          // 图片记录**没有 text 键**（只有 has_data）：必须一起判，否则会掉进纯文本兼容分支，
          // 把整段 JSON 当剪贴板文本写回本地。
          if (hasData || hasTextKey) {
            const profile: XimeClipboardProfile = {
              type: wire.type === undefined || wire.type === null ? 'text' : String(wire.type),
              hash: wire.hash === undefined || wire.hash === null ? '' : String(wire.hash),
              text: wire.text === undefined || wire.text === null ? '' : String(wire.text),
              hasData: hasData,
              dataName: wire.data_name === undefined || wire.data_name === null
                ? null
                : String(wire.data_name),
              size: typeof wire.size === 'number' ? wire.size : 0,
              source: wire.source === undefined || wire.source === null ? null : String(wire.source),
            };
            if (hasData) {
              if (profile.dataName === null || profile.dataName === '') {
                // 远端元数据畸形（声明有附件却没有附件名）：必须清掉 ETag，否则之后的 304
                // 会让这条坏记录被**永久跳过**（与附件下载失败同一处理）
                host.config.remove(KEY_LAST_ETAG);
                host.logError('pull failed: 远端声明 has_data 但没有 data_name，已清掉 ETag 待下轮重试');
                return null;
              }
              if (!isSafeDataName(profile.dataName)) {
                // data_name 是不可信输入：白名单同时挡住 "."/".." 段（会被 HTTP 层归一化路径）
                // 与孤立代理项（encodeURIComponent 会抛 URIError 逃出本函数）
                host.config.remove(KEY_LAST_ETAG);
                host.logError('pull failed: 远端附件名非法（只允许 [A-Za-z0-9._-] 且不含 "."/".." 段）：'
                  + profile.dataName + '，已清掉 ETag');
                return null;
              }
              const blob = await fetchBlob(profile.dataName);
              if (blob === null) {
                // 附件还没传上来（push 先 blob 后元数据的中间态）或下载失败：清掉 ETag，
                // 下一轮重新拉元数据再试；否则 304 会让这次失败变成永久跳过
                host.config.remove(KEY_LAST_ETAG);
                return null;
              }
              profile.data = blob;
              host.log('pull ok: 附件 ' + profile.dataName + ' (' + blob.length + ' bytes)');
            }
            return profile;
          }
        }
        // 兼容纯文本对象（用户手动放了一个纯文本文件，或早期版本）：hash 留空让宿主计算
        host.log('pull: 远端非 JSON，按纯文本兼容处理');
        const plain: XimeClipboardProfile = {
          type: 'text',
          hash: '',
          text: text,
          hasData: false,
          dataName: null,
          size: new TextEncoder().encode(text).length,
          source: null,
        };
        return plain;
      } catch (e) {
        host.config.remove(KEY_LAST_ETAG);
        host.logError('pull failed: 解析远端元数据异常 ' + errorText(e) + '，已清掉 ETag 待下轮重试');
        return null;
      }
    },

    // 连接测试；返回错误消息，null 表示成功
    async test(): Promise<string | null> {
      const missing = missingConfig();
      if (missing !== null) return missing;

      // 1) HEAD bucket：验证端点/签名/凭据，以及 bucket 是否存在
      const bucketReq = signedRequest('HEAD', '', {}, null, true);
      if (bucketReq === null) return '端点或 bucket 配置不完整';
      let resp: XimeHttpResponse;
      try {
        resp = await host.http.request('HEAD', bucketReq.url, bucketReq.headers, null);
      } catch (e) {
        return '连接失败：' + errorText(e);
      }
      if (resp.status === 401 || resp.status === 403) {
        // HEAD 响应没有 body（HTTP 层对 HEAD 不解析 body），拿不到 <Code>，按状态码给排查方向
        return '鉴权失败（HTTP ' + resp.status + '）：检查 Access Key ID / Secret Access Key 是否完整'
          + '（有无多余空格/换行），以及 region（R2 固定 auto）' + describeS3Error(resp);
      }
      if (resp.status === 404) {
        return 'bucket 不存在或无权访问（HTTP 404）：检查 bucket 名拼写与令牌权限'
          + '（R2 的 bucket 名区分大小写）' + describeS3Error(resp);
      }
      if (resp.status === 301 || resp.status === 307 || resp.status === 308) {
        return '端点发生重定向（HTTP ' + resp.status + '）：请确认端点与 region 填对（R2 用 auto）';
      }
      if (!isSuccess(resp)) {
        return '连接失败（HTTP ' + resp.status + '）' + describeS3Error(resp);
      }

      // 2) 写权限探针：只有真的 PUT 成功才说明能同步（读权限通不代表能写）
      const probeBody = new TextEncoder().encode('xime-s3-probe');
      const probe = signedRequest('PUT', keyDir() + PROBE_OBJECT_NAME,
        { 'Content-Type': 'text/plain' }, probeBody, false);
      if (probe === null) return '探针对象地址非法';
      let probeResp: XimeHttpResponse;
      try {
        probeResp = await host.http.request('PUT', probe.url, probe.headers, probeBody);
      } catch (e) {
        return '写入测试失败：' + errorText(e);
      }
      if (!isSuccess(probeResp)) {
        return '无权写入 bucket（HTTP ' + probeResp.status + '）' + describeS3Error(probeResp);
      }
      // 探针用完即删（失败也不影响结论，只记日志）
      const cleanup = signedRequest('DELETE', keyDir() + PROBE_OBJECT_NAME, {}, null, false);
      if (cleanup !== null) {
        try {
          const cleanupResp = await host.http.request('DELETE', cleanup.url, cleanup.headers, null);
          if (!isSuccess(cleanupResp) && cleanupResp.status !== 404) {
            host.logError('测试探针删除失败（HTTP ' + cleanupResp.status + '）：可手动删除 '
              + KEY_ENDPOINT + ' 下 ' + keyDir() + PROBE_OBJECT_NAME);
          }
        } catch (e) {
          host.logError('测试探针删除请求失败：' + errorText(e));
        }
      }

      // 3) 报告远端是否已有剪贴板对象（信息性，失败不影响结论）
      const headReq = signedRequest('HEAD', metadataKey(), {}, null, false);
      if (headReq !== null) {
        try {
          const headResp = await host.http.request('HEAD', headReq.url, headReq.headers, null);
          if (isSuccess(headResp)) {
            host.log('测试连接：远端已有 ' + metadataKey() + '（ETag '
              + (headerValue(headResp, 'ETag') || '未知') + '），首次拉取会同步其内容');
          } else if (headResp.status === 404) {
            host.log('测试连接：远端尚无 ' + metadataKey() + '，首次推送时创建');
          }
        } catch (e) {
          host.log('测试连接：探测远端对象失败 ' + errorText(e));
        }
      }
      host.log('测试连接成功：bucket=' + bucket() + ' region=' + region()
        + ' 目录=' + dirPrefix() + ' 元数据=' + metadataKey()
        + ' host=' + (parseEndpoint() === null ? '?' : (parseEndpoint() as S3Endpoint).host));
      return null;
    },
  },
});

export default plugin;