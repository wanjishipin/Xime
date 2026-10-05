// 腾讯云 ASR 签名与 URL 编码工具（utf8 字节级，避免 JS 字符串码元与字节混淆）
//
// 沙箱无 URL/Intl，百分号编码必须按 UTF-8 字节循环实现（与 Lua string.byte 语义对齐）。

/** 字符串 → UTF-8 字节（宿主注入 TextEncoder，Kotlin 侧完整实现） */
export function utf8Encode(s: string): Uint8Array {
  return new TextEncoder().encode(s);
}

// RFC 3986 unreserved 之外的字节按 UTF-8 百分号编码（byte 循环，避开 gsub 对
// 非 ASCII 字节流的匹配单位错位问题，与 webdav-backup 插件同因）
export function percentEncode(s: string): string {
  const bytes = utf8Encode(s);
  let out = '';
  for (let i = 0; i < bytes.length; i++) {
    const b = bytes[i];
    if ((b >= 48 && b <= 57) || (b >= 65 && b <= 90) || (b >= 97 && b <= 122)
        || b === 45 || b === 46 || b === 95 || b === 126) {
      out += String.fromCharCode(b);
    } else {
      out += '%' + (b < 16 ? '0' : '') + b.toString(16).toUpperCase();
    }
  }
  return out;
}

/** params 按 key 字典序拼成 "k=v&k=v"；encodeValues=true 时值做百分号编码（签名原文用 false） */
export function buildQuery(params: Record<string, string | number>, encodeValues: boolean): string {
  const keys = Object.keys(params).sort();
  const parts: string[] = [];
  for (const key of keys) {
    let value = String(params[key]);
    if (encodeValues) value = percentEncode(value);
    parts.push(key + '=' + value);
  }
  return parts.join('&');
}
