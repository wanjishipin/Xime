// =====================================================================
// WebDAV 云备份插件（QuickJS 脚本）
//
// 备份包的生成与恢复由宿主（BackupManager）完成，本插件只承载 WebDAV
// 传输协议：PUT / GET / PROPFIND / DELETE / MKCOL，认证用 Basic Auth
// （host.crypto.base64），配置经 host.config 存取（密码为宿主加密存储）。
//
// 备份条目 id = 服务器上的绝对路径（已解码），pull/delete 时原样回传。
//
// JS 约定（对齐 QuickJS 沙箱）：
//   - 二进制一律 Uint8Array；host.crypto.base64 / host.http.request 的 body
//     只接受字节，字符串先经 TextEncoder 转 UTF-8（JS 字符串直接传桥会变空）。
//   - JSON 用原生 JSON（宿主不再提供 host.json）。
//   - URL 编解码与 PROPFIND XML 解析拆在 libs/（构建时内联为单文件）。
// =====================================================================

import { encodePath } from './libs/url-codec';
import { parseBackupList } from './libs/webdav-xml';

// ------------------------------------------------------------------
// 配置
// ------------------------------------------------------------------

interface BackupConfig {
  url: string;
  username: string;
  password: string;
  remotePath: string;
}

// resolveBase 结果；error 非空时其余字段不可用
interface ResolvedBase {
  origin: string;
  base: string;
  basePath: string;
  davRoot: string;
  headers: Record<string, string>;
  cfg: BackupConfig;
  error: string | null;
}

// backup.push 结果（id 为上传成功后的远端条目 id，宿主 BackupResult 可读）
interface PushBackupResult {
  ok: boolean;
  id?: string;
  message?: string;
}

function getConfig(): BackupConfig {
  return {
    url: host.config.get('url') || '',
    username: host.config.get('username') || '',
    password: host.config.get('password') || '',
    remotePath: host.config.get('remote_path') || '/xime_backup',
  };
}

// 归一化：url 去尾斜杠，remotePath 去首尾斜杠。
// 返回：
//   base     完整 URL（origin + url 路径前缀 + remotePath），PROPFIND/PUT 用
//   basePath 服务器绝对路径（如坚果云为 /dav/xime_backup），条目 id / 列表过滤用
//   davRoot  origin + url 路径前缀（如 https://dav.jianguoyun.com/dav），MKCOL 起点用
//   origin   scheme + host（pull/delete 用：条目 id 已是含路径前缀的绝对路径）
//   headers  认证头
function resolveBase(): ResolvedBase {
  const cfg = getConfig();
  const result: ResolvedBase = {
    origin: '', base: '', basePath: '', davRoot: '', headers: {}, cfg, error: null,
  };
  if (cfg.url === '') { result.error = '请先填写服务器地址'; return result; }
  if (cfg.username === '') { result.error = '请先填写账号'; return result; }
  let url = cfg.url;
  if (!/^https?:\/\//.test(url)) url = 'https://' + url;
  url = url.replace(/\/+$/, '');
  const m = url.match(/^https?:\/\/[^/]+/);
  const origin = m ? m[0] : url;
  const urlPath = url.substring(origin.length); // DAV 根的路径前缀（坚果云为 /dav）
  const remote = cfg.remotePath.replace(/^\/+/, '').replace(/\/+$/, '');
  result.origin = origin;
  result.base = origin + urlPath + '/' + remote;
  result.basePath = urlPath + '/' + remote;
  result.davRoot = origin + urlPath;
  if (cfg.password !== '') {
    const b64 = host.crypto.base64(new TextEncoder().encode(cfg.username + ':' + cfg.password));
    if (!b64) { result.error = 'Basic 认证编码失败'; return result; }
    result.headers['Authorization'] = 'Basic ' + b64;
  }
  return result;
}

// ------------------------------------------------------------------
// WebDAV 操作
// ------------------------------------------------------------------

// 逐级 MKCOL 创建远端目录（起点为 DAV 根，即 url 的路径前缀；已存在 405 视为成功）
async function ensureCollection(cfg: BackupConfig, headers: Record<string, string>): Promise<boolean> {
  const { davRoot } = resolveBase();
  if (!davRoot) return false;
  const remote = cfg.remotePath.replace(/^\/+/, '').replace(/\/+$/, '');
  let cur = davRoot;
  for (const seg of remote.split('/').filter((s) => s !== '')) {
    cur = cur + '/' + encodePath(seg);
    try {
      const res = await host.http.request('MKCOL', cur, headers, null);
      if (res.status !== 201 && res.status !== 405 && res.status !== 200 && res.status !== 301) {
        return false;
      }
    } catch (e) {
      host.logError('MKCOL 失败: ' + cur + ' ' + ((e as Error).message || ''));
      return false;
    }
  }
  return true;
}

// 从失败响应提取可读原因（去 XML 标签、截断），拼进错误消息给设置页展示
function statusDetail(res: XimeHttpResponse): string {
  let body = res.text || '';
  body = body.replace(/<[^>]*>/g, ' ').replace(/\s+/g, ' ').trim();
  if (body === '') return '';
  return ': ' + body.substring(0, 160);
}

// 仅供宿主 JVM 回归测试使用（JsEncodePathTest）；非插件契约字段，经 spread 并入，
// 不参与 definePlugin 的规格约束收窄
const testHooks = { _encodePath: encodePath };

const plugin = definePlugin({
  // ------------------------------------------------------------------
  // backup 扩展点（宿主调用路径 backup.*）
  // ------------------------------------------------------------------

  backup: {
    // 连接测试；返回错误消息，null 表示成功
    async test(): Promise<string | null> {
      const { base, headers, cfg, error } = resolveBase();
      if (error) return error;
      headers['Depth'] = '0';
      try {
        const res = await host.http.request('PROPFIND', encodePath(base), headers, null);
        if (res.status === 207 || res.status === 200) return null;
        if (res.status === 401) return '认证失败（401），请检查账号与密码';
        if (res.status === 404) {
          // 目录不存在不算失败（首次备份会自动创建）
          if (await ensureCollection(cfg, headers)) return null;
          return '远端目录不存在且自动创建失败';
        }
        return '服务器返回 HTTP ' + res.status + statusDetail(res);
      } catch (e) {
        return (e as Error).message || '请求失败';
      }
    },

    // 上传备份包
    async push(args: XimeBackupPushArgs): Promise<PushBackupResult> {
      const name = args.name || '';
      const archive = args.archive;
      if (name === '') return { ok: false, message: '备份包名为空' };
      if (!archive || archive.length === 0) {
        return { ok: false, message: '备份包为空' };
      }

      const { base, basePath, headers, cfg, error } = resolveBase();
      if (error) return { ok: false, message: error };

      headers['Content-Type'] = 'application/octet-stream';
      headers['Overwrite'] = 'T';
      const url = encodePath(base) + '/' + encodePath(name);

      try {
        let res = await host.http.request('PUT', url, headers, archive);
        if (res.status === 409 || res.status === 404) {
          // 父目录不存在：逐级创建后重试一次
          // （标准 DAV 报 409 Conflict；Alist/Nextcloud 等报 404）
          if (!await ensureCollection(cfg, headers)) {
            return { ok: false, message: '创建远端目录失败' };
          }
          res = await host.http.request('PUT', url, headers, archive);
        }
        if (res.status === 200 || res.status === 201 || res.status === 204) {
          return { ok: true, id: basePath + '/' + name };
        }
        if (res.status === 401) return { ok: false, message: '认证失败（401），请检查账号与密码' };
        return { ok: false, message: '上传失败（HTTP ' + res.status + '）' + statusDetail(res) };
      } catch (e) {
        return { ok: false, message: (e as Error).message || '上传请求失败' };
      }
    },

    // 下载备份包（id 为 list 返回的远端条目 id）
    async pull(id: string): Promise<Uint8Array | null> {
      if (!id || id === '') return null;
      const { origin, headers, error } = resolveBase();
      if (error) return null;
      try {
        const res = await host.http.request('GET', origin + encodePath(id), headers, null);
        if (res.status !== 200) return null;
        return res.body;
      } catch (e) {
        host.logError('pull 失败: ' + ((e as Error).message || ''));
        return null;
      }
    },

    // 列出远端备份
    async list(): Promise<XimeBackupItem[] | null> {
      const { base, basePath, headers, error } = resolveBase();
      if (error) return null;
      headers['Depth'] = '1';
      try {
        const res = await host.http.request('PROPFIND', encodePath(base), headers, null);
        if (res.status !== 207 && res.status !== 200) return null;
        return parseBackupList(res.text || '', basePath);
      } catch (e) {
        host.logError('list 失败: ' + ((e as Error).message || ''));
        return null;
      }
    },

    // 删除远端备份
    async remove(id: string): Promise<boolean> {
      if (!id || id === '') return false;
      const { origin, headers, error } = resolveBase();
      if (error) return false;
      try {
        const res = await host.http.request('DELETE', origin + encodePath(id), headers, null);
        // 404：远端已不存在，视为删除成功
        return res.status === 204 || res.status === 200 || res.status === 404;
      } catch (e) {
        host.logError('remove 失败: ' + ((e as Error).message || ''));
        return false;
      }
    },
  },

  // ------------------------------------------------------------------
  // 配置表单（UiNode 契约）
  // ------------------------------------------------------------------

  settings: {
    schema(): XimeUiNode[] {
      return [
        {
          type: 'text', key: 'url', label: '服务器地址', required: true,
          placeholder: 'https://dav.jianguoyun.com/dav/',
          helpText: 'WebDAV 根地址；坚果云为 https://dav.jianguoyun.com/dav/',
        },
        { type: 'text', key: 'username', label: '账号', required: true },
        {
          type: 'secret', key: 'password', label: '密码 / 应用密码', required: true,
          helpText: '坚果云请到网页端「安全选项」生成应用密码',
        },
        {
          type: 'text', key: 'remote_path', label: '备份目录',
          defaultValue: '/xime_backup',
          helpText: '远端目录，不存在时自动创建',
        },
        { type: 'button', key: 'testConnection', label: '测试连接' },
      ];
    },
  },

  ...testHooks,
});

export default plugin;
