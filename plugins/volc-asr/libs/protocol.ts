// 火山引擎（火山方舟）bigmodel_async 二进制协议工具
//
// 协议（参考官方 sauc_websocket_demo.py）：
//   帧 = 4 字节头 + 4 字节有符号序号 seq + 4 字节大端 payload 长度 + payload
//     头[0] = (协议版本 0x1 << 4) | (头长度单位 0x1)
//     头[1] = (消息类型 << 4) | flags
//     头[2] = (序列化 << 4) | 压缩
//     头[3] = 0
//   消息类型：0x1 full client req / 0x2 audio-only / 0x9 server resp / 0xF error
//   压缩：0x0 none / 0x1 gzip；序列化：0x0 raw / 0x1 json
//   消息类型专属 flags：
//     NO_SEQUENCE=0x0 / POS_SEQUENCE=0x1（带正 seq）/ NEG_SEQUENCE=0x2 / NEG_WITH_SEQUENCE=0x3
//   客户端最后一包：flags=0x3 且 seq 取负
//   服务端响应 flags：0x1 带 seq、0x2 末包、0x4 带 event（在 payload 前）
//
// 注意：从 WS 二进制回调取子段时先复制成独立 Uint8Array 再交宿主 gunzip——
// subarray 视图经桥接后 Kotlin 侧无法解析成字节，gunzip 会 reject。
// host.zlib.gunzip 为 async 服务，解析函数相应地 await（帧格式/解析逻辑不变）。

export const MSG_FULL_CLIENT_REQ = 0x1;
export const MSG_AUDIO_ONLY = 0x2;
export const MSG_SERVER_RESP = 0x9;
export const MSG_SERVER_ERROR = 0xf;

export const FLAG_POS_SEQUENCE = 0x1;
export const FLAG_NEG_WITH_SEQUENCE = 0x3;

/** 服务端帧解析结果（字段缺省表示帧内未携带）。 */
export interface ParsedFrame {
  msgType: number;
  seq?: number;
  isLast?: boolean;
  event?: number;
  text?: string;
  code?: number;
  message?: string;
}

export function utf8Encode(s: string): Uint8Array {
  return new TextEncoder().encode(s);
}

export function utf8Decode(bytes: Uint8Array): string {
  return new TextDecoder().decode(bytes);
}

/** 帧头 + seq(4) + payload_size(4) + payload */
export function buildFrame(
  msgType: number,
  seqValue: number,
  ser: number,
  comp: number,
  payload: Uint8Array,
): Uint8Array {
  const flags = seqValue < 0 ? FLAG_NEG_WITH_SEQUENCE : FLAG_POS_SEQUENCE;
  const out = new Uint8Array(12 + payload.length);
  out[0] = 0x11;
  out[1] = (msgType * 16 + flags) & 0xff;
  out[2] = (ser * 16 + comp) & 0xff;
  out[3] = 0;
  out.set(host.bin.int32be(seqValue), 4);
  out.set(host.bin.uint32be(payload.length), 8);
  out.set(payload, 12);
  return out;
}

export function readInt32(arr: Uint8Array, offset: number): number {
  return (arr[offset] << 24) | (arr[offset + 1] << 16) | (arr[offset + 2] << 8) | arr[offset + 3];
}

export function readUint32(arr: Uint8Array, offset: number): number {
  return ((arr[offset] << 24) | (arr[offset + 1] << 16) | (arr[offset + 2] << 8) | arr[offset + 3]) >>> 0;
}

export async function parseServerFrame(frame: Uint8Array): Promise<ParsedFrame | null> {
  if (frame.length < 8) return null;
  const b0 = frame[0];
  const b1 = frame[1];
  const b2 = frame[2];
  const headerSize = (b0 % 16) * 4;
  const msgType = Math.floor(b1 / 16) % 16;
  const flags = b1 % 16;
  const ser = Math.floor(b2 / 16) % 16;
  const comp = b2 % 16;
  let offset = headerSize;

  const parsed: ParsedFrame = { msgType };
  if (flags % 2 === 1) { // flags & 0x01：带 sequence
    parsed.seq = readInt32(frame, offset);
    offset = offset + 4;
  }
  if (Math.floor(flags / 2) % 2 === 1) { // flags & 0x02：末包
    parsed.isLast = true;
  }
  if (Math.floor(flags / 4) % 2 === 1) { // flags & 0x04：带 event
    parsed.event = readInt32(frame, offset);
    offset = offset + 4;
  }

  if (msgType === MSG_SERVER_RESP) {
    if (offset + 4 > frame.length) return null;
    const size = readUint32(frame, offset);
    offset = offset + 4;
    // 先复制成独立 Uint8Array（subarray 视图传桥会变 null，gunzip 失败）
    let payload: Uint8Array = new Uint8Array(frame.subarray(offset, offset + size));
    if (comp === 1) {
      try {
        payload = await host.zlib.gunzip(payload);
      } catch (e) {
        return parsed;
      }
    }
    if (ser === 1) {
      let obj: Record<string, any> | null = null;
      try {
        obj = JSON.parse(utf8Decode(payload));
      } catch (e) {
        obj = null;
      }
      if (obj !== null && obj !== undefined && obj.result !== undefined) {
        parsed.text = obj.result.text || '';
      }
    }
  } else if (msgType === MSG_SERVER_ERROR) {
    if (offset + 8 > frame.length) return parsed;
    parsed.code = readInt32(frame, offset);
    const size = readUint32(frame, offset + 4);
    const start = offset + 8;
    parsed.message = utf8Decode(new Uint8Array(frame.subarray(start, start + size)));
  }
  return parsed;
}
