// WebDAV PROPFIND（207 Multi-Status）响应解析（纯函数，rolldown 内联）
import { hrefToPath } from './url-codec';

/** 远端备份条目（listBackups 返回项，字段与宿主 RemoteBackupEntry 对齐）。 */
export interface BackupItem {
  id: string;
  name: string;
  createdAt: number;
  size: number;
}

const MONTHS: Record<string, number> = {
  Jan: 1, Feb: 2, Mar: 3, Apr: 4, May: 5, Jun: 6,
  Jul: 7, Aug: 8, Sep: 9, Oct: 10, Nov: 11, Dec: 12,
};

// Howard Hinnant days_from_civil（公历日期 → 自 1970-01-01 的天数）
export function epochFromParts(y: number, m: number, d: number,
  hh?: number, mm?: number, ss?: number): number {
  if (!y || !m || !d) return 0;
  let yy = y;
  if (m <= 2) yy = yy - 1;
  const era = Math.floor(yy / 400);
  const yoe = yy - era * 400;
  const mp = m + (m > 2 ? -3 : 9);
  const doy = Math.floor((153 * mp + 2) / 5) + d - 1;
  const doe = yoe * 365 + Math.floor(yoe / 4) - Math.floor(yoe / 100) + doy;
  const days = era * 146097 + doe - 719468;
  return days * 86400 + (hh || 0) * 3600 + (mm || 0) * 60 + (ss || 0);
}

// 优先 creationdate（ISO 8601），其次 getlastmodified（RFC 1123）
export function parseTime(block: string): number {
  const m = block.match(/<[dD]:?creationdate>\s*(\d{4})-(\d{2})-(\d{2})T(\d+):(\d+):(\d+)/);
  if (m) {
    return epochFromParts(
      parseInt(m[1], 10), parseInt(m[2], 10), parseInt(m[3], 10),
      parseInt(m[4], 10), parseInt(m[5], 10), parseInt(m[6], 10)
    );
  }
  const g = block.match(
    /<[dD]:?getlastmodified>\s*\w+,\s*(\d+)\s+(\w+)\s+(\d+)\s+(\d+):(\d+):(\d+)/);
  if (g && MONTHS[g[2]]) {
    return epochFromParts(
      parseInt(g[3], 10), MONTHS[g[2]], parseInt(g[1], 10),
      parseInt(g[4], 10), parseInt(g[5], 10), parseInt(g[6], 10)
    );
  }
  return 0;
}

// 大小写不敏感地取子标签文本（命名空间前缀 d:/D:/无 均可；开标签允许携带属性）
export function tagValue(block: string, tag: string): string | null {
  const re = new RegExp('<[dD]:?' + tag + '[^>]*>\\s*([\\s\\S]*?)\\s*</[dD]:?' + tag + '>');
  const m = block.match(re);
  return m ? m[1] : null;
}

// 对齐 Lua `tonumber(x) or -1`：非法/缺失内容统一为 -1
function parseLength(raw: string | null): number {
  if (raw === null || raw.trim() === '') return -1;
  const n = Number(raw.trim());
  return Number.isFinite(n) ? n : -1;
}

/**
 * 解析 PROPFIND Depth:1 响应，返回 basePath 下的文件条目（不含目录），
 * 按 createdAt 降序（最新在前）。无法解析的响应返回空数组。
 */
export function parseBackupList(xml: string, basePath: string): BackupItem[] {
  const basePrefix = basePath + '/';
  const items: BackupItem[] = [];
  const blockRe = /<[dD]:?response[^>]*>([\s\S]*?)<\/[dD]:?response>/g;
  let bm: RegExpExecArray | null;
  while ((bm = blockRe.exec(xml)) !== null) {
    const block = bm[1];
    const p = hrefToPath(tagValue(block, 'href'));
    if (p === null) continue;
    const isDir = p.slice(-1) === '/' || /<d?:?collection/.test(block.toLowerCase());
    if (isDir) continue;
    if (p.substring(0, basePrefix.length) !== basePrefix) continue;
    const name = p.substring(basePrefix.length);
    if (name === '' || name.indexOf('/') !== -1) continue;
    items.push({
      id: p,
      name,
      createdAt: parseTime(block),
      size: parseLength(tagValue(block, 'getcontentlength')),
    });
  }
  items.sort((a, b) => b.createdAt - a.createdAt);
  return items;
}
