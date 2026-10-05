// WebDAV 剪贴板同步的 URL 组装（纯函数，rolldown 构建时内联进单文件 main.js）
//
// 远端剪贴板文件布局：
//   {davUrl}/{remotePath}/clipboard/current.json            ← Profile JSON（文本/图片元数据）
//   {davUrl}/{remotePath}/clipboard/blobs/<data_name>       ← 图片原图附件（Phase 3 / D12）
//   davUrl     = 服务器根（如 https://dav.jianguoyun.com/dav/）
//   remotePath = 远程目录（如 xime，留空为根目录）
//
// 与 ximed 的布局保持一致（`clipboard/current.json`），附件只是并列多一个 blobs 目录；
// blob 名由宿主内容寻址生成（`<sha256>.<ext>`），同内容重复 PUT 幂等。

/** 剪贴板文件名（相对 remotePath） */
export const CLIPBOARD_FILE = 'clipboard/current.json';

/** 附件目录名（相对 clipboard 目录） */
export const BLOB_DIR = 'blobs';

/** 附件名白名单：内容寻址名（hex + 扩展名）只含 [A-Za-z0-9._-]，顺带挡掉路径穿越 */
const BLOB_NAME_PATTERN = /^[A-Za-z0-9][A-Za-z0-9._-]*$/;

/** 去掉末尾斜杠（url 归一化） */
export function stripTrailingSlashes(value: string): string {
  return value.replace(/\/+$/, '');
}

/** 去掉首尾斜杠（remotePath 归一化） */
export function stripSlashes(value: string): string {
  return value.replace(/^\/+/, '').replace(/\/+$/, '');
}

// 远端剪贴板文件 URL：{davUrl}/{remotePath}/clipboard/current.json
// davUrl 为空返回 null（未配置服务器地址）
export function buildFileUrl(davUrl: string, remotePath: string): string | null {
  let base = stripTrailingSlashes(davUrl);
  if (base === '') return null;
  const path = stripSlashes(remotePath);
  if (path !== '') base = base + '/' + path;
  return base + '/' + CLIPBOARD_FILE;
}

// 远端剪贴板目录 URL（{davUrl}/{remotePath}/clipboard，用于连接测试的 PROPFIND 探测）
export function buildDirUrl(fileUrl: string | null): string | null {
  if (fileUrl === null) return null;
  return fileUrl.replace(/\/[^/]+$/, '');
}

// 远端附件 URL：{davUrl}/{remotePath}/clipboard/blobs/{dataName}
// dataName 由宿主生成（`<sha256>.<ext>`），但它是远端/宿主传入的字符串——
// 一律先过白名单再拼路径（拒绝空名、路径分隔符与 `..`），绝不直接拼接。
export function buildBlobUrl(davUrl: string, remotePath: string, dataName: string | null): string | null {
  if (dataName === null || dataName === undefined) return null;
  const name = dataName.trim();
  if (!BLOB_NAME_PATTERN.test(name)) return null;
  const clipboardFile = buildFileUrl(davUrl, remotePath);
  if (clipboardFile === null) return null;
  // 复用 current.json 的目录，避免"目录怎么拼"的规则写两份
  const clipboardDir = clipboardFile.substring(0, clipboardFile.lastIndexOf('/'));
  return clipboardDir + '/' + BLOB_DIR + '/' + name;
}

// 从文件 URL 里解析出相对 davUrl 的目录层级（不含文件名），供逐级 MKCOL 创建。
// 从用户配置的 davUrl（通常已存在）之后开始创建，避免 MKCOL 服务器根/WebDAV 根被拒。
// 纯字符串前缀匹配，避免正则中 . - + 等特殊字符或前缀误匹配。
export function relativeDirParts(baseUrl: string, fileUrl: string): string[] {
  const basePath = stripTrailingSlashes(baseUrl.replace(/^https?:\/\/[^/]+/, ''));
  let dirPart = fileUrl.replace(/^https?:\/\/[^/]+/, '').replace(/\/[^/]+$/, '');
  if (basePath !== '' && dirPart.substring(0, basePath.length) === basePath) {
    dirPart = dirPart.substring(basePath.length);
  }
  return dirPart.replace(/^\/+/, '').split('/').filter((part) => part !== '');
}
