// URL 编解码（纯函数，rolldown 构建时内联进单文件 main.js）
//
// 沙箱无 Node/browser 环境 helper，仅用宿主补齐的 TextEncoder/TextDecoder
// 完成 UTF-8 ↔ 字节转换；不使用 encodeURIComponent/decodeURIComponent：
// 其保留字符集合与合法转义判定和 WebDAV 插件的历史行为不一致
// （encodeURIComponent 不编码 ! ' ( ) *，decodeURIComponent 对非法 %XX 抛异常）。

/** JS 字符串 → UTF-8 字节（TextEncoder 由宿主注入）。 */
export function utf8Bytes(value: string): Uint8Array {
  return new TextEncoder().encode(value);
}

function isHexDigit(ch: string): boolean {
  return (ch >= '0' && ch <= '9') || (ch >= 'a' && ch <= 'f') || (ch >= 'A' && ch <= 'F');
}

// 路径编码：逐字节循环（保留字母数字与 -._~:/，其余转 %XX）。
// 注意不能按字符编码：中文等多字节字符必须逐 UTF-8 字节转义
// （实测 配置 → E9%85%8D%E7%BD%AE 中首字节漏编码会导致真机 PUT 中文文件名 400），
// 必须逐字节处理。
export function encodePath(path: string): string {
  const bytes = utf8Bytes(path);
  let out = '';
  for (let i = 0; i < bytes.length; i++) {
    const b = bytes[i];
    if ((b >= 48 && b <= 57) || (b >= 65 && b <= 90) || (b >= 97 && b <= 122)
      || b === 45 || b === 46 || b === 95 || b === 126 || b === 47 || b === 58) {
      out += String.fromCharCode(b);
    } else {
      out += '%' + (b < 16 ? '0' : '') + b.toString(16).toUpperCase();
    }
  }
  return out;
}

// %XX 解码：只解码合法十六进制对，其余字符原样按 UTF-8 字节拼回
// （对齐 Lua 的 gsub("%%(%x%x)") 语义，非法转义不抛异常）。
export function decodeSegment(segment: string): string {
  const bytes: number[] = [];
  for (let i = 0; i < segment.length; i++) {
    const c = segment.charCodeAt(i);
    if (c === 0x25 && i + 2 < segment.length
      && isHexDigit(segment.charAt(i + 1)) && isHexDigit(segment.charAt(i + 2))) {
      bytes.push(parseInt(segment.slice(i + 1, i + 3), 16));
      i += 2;
    } else {
      const encoded = utf8Bytes(segment.charAt(i));
      for (let j = 0; j < encoded.length; j++) bytes.push(encoded[j]);
    }
  }
  return new TextDecoder().decode(new Uint8Array(bytes));
}

// href / id → 服务器绝对路径（解码后的）
// 如 https://dav.jianguoyun.com/dav/xime_backup/Xime%E9%85%8D%E7%BD%AE.zip
//    → /dav/xime_backup/Xime配置.zip
export function hrefToPath(href: string | null | undefined): string | null {
  if (!href) return null;
  const m = href.match(/^https?:\/\/[^/]+(\/.*)$/);
  let p = m ? m[1] : href;
  if (p.charAt(0) !== '/') p = '/' + p;
  const decoded: string[] = [];
  for (const seg of p.split('/')) {
    if (seg === '') continue;
    decoded.push(decodeSegment(seg));
  }
  return '/' + decoded.join('/');
}
