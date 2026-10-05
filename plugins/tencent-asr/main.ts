// 腾讯云实时语音识别 V2（WebSocket）TypeScript 脚本插件
//
// 职责划分：
//   JS    = 全部功能逻辑（签名鉴权、连接时机、握手状态机、结果解析、结束通知）
//   宿主  = 仅提供通用原语：
//     host.ws         WebSocket 白名单（connect/sendText/sendBinary/close，事件走回调槽）
//     host.asr.emit*  结果回传桥
//     host.crypto     hmacSha1 / base64 / epochSeconds（腾讯签名三件套）
//     host.config / host.uuid；JSON 用 JS 原生 JSON
//
// 协议（参考官方文档《实时语音识别 V2（WebSocket）》）：
//   URL    = wss://asr.cloud.tencent.com/asr/v2/<appid>?<参数>
//   签名   = 除 signature 外所有参数按 key 字典序拼接
//            "asr.cloud.tencent.com/asr/v2/<appid>?k1=v1&k2=v2&..."（值为原文，不做 URL 编码）
//            取 HMAC-SHA1(SecretKey, 签名原文) 再 Base64，URL 编码后追加为 signature
//   握手   = 连接建立后服务端先回 {"code":0} 文本帧（code 非 0 表示鉴权等失败并断开）
//   音频   = 二进制帧直接发 PCM（16k/mono/16bit），按宿主录音节奏即 1:1 实时率
//   结束   = 发送文本帧 {"type":"end"}，服务端回 final=1 后断开
//   结果   = 文本帧 JSON：sentences（sentence_type 0=不确定 1=确定，speaker_id 说话人）、final
//
// 沙箱约束：无 URL/Intl；JSON 用原生 JSON.parse（非法输入抛异常，须 try/catch）。
// TS 范式：host.ws 为 async 服务（await；失败 throw XimeError，try/catch 后 emitError 上报）。

import { utf8Encode, percentEncode, buildQuery } from './libs/codec';

const WS_HOST_PATH = 'asr.cloud.tencent.com/asr/v2/';

const KEY_APP_ID = 'appId';
const KEY_SECRET_ID = 'secretId';
const KEY_SECRET_KEY = 'secretKey';
const KEY_ENGINE = 'engineModelType';
const KEY_HOTWORD_LIST = 'hotwordList';
const DEFAULT_ENGINE = '16k_zh_en_2.0';

let voiceId = '';
let audioReady = false;
let prebuffer: Uint8Array[] = [];
// 握手（{"code":0}）到达前收到 stop：置位后由 onWsMessage 在补发缓存之后发结束通知
let stopPending = false;

function isConfigured(): boolean {
  for (const key of [KEY_APP_ID, KEY_SECRET_ID, KEY_SECRET_KEY]) {
    const value = host.config.get(key);
    if (value === null || value === undefined || value === '') return false;
  }
  return true;
}

function getSettingsSchema(): XimeUiNode[] {
  return [
    {
      key: KEY_APP_ID,
      label: 'AppID',
      type: 'text',
      placeholder: '腾讯云账号 AppID（纯数字）',
      helpText: '腾讯云控制台 → 语音识别 → API 密钥管理',
    },
    {
      key: KEY_SECRET_ID,
      label: 'SecretId',
      type: 'secret',
      placeholder: '输入 SecretId',
    },
    {
      key: KEY_SECRET_KEY,
      label: 'SecretKey',
      type: 'secret',
      placeholder: '输入 SecretKey',
    },
    {
      key: KEY_ENGINE,
      label: '引擎模型',
      type: 'text',
      defaultValue: DEFAULT_ENGINE,
      placeholder: DEFAULT_ENGINE,
      helpText: '16k_zh_en_2.0 中英粤大模型；16k_zh_en_speaker_2.0 支持说话人分离',
    },
    {
      key: KEY_HOTWORD_LIST,
      label: '临时热词表',
      type: 'text',
      required: false,
      placeholder: '热词|权重,多个用英文逗号分隔',
      helpText: '可选。如：腾讯云|10,语音识别|5；单个热词最多 10 个汉字，权重 1-11 或 100',
    },
  ];
}

async function configure(): Promise<boolean> {
  return true;
}

// 随机正整数（最长 10 位）：取 uuid 前 8 个十六进制位取模，沙箱内无 os.math 随机源
function randNonce(): number {
  const hex = host.uuid().replace(/-/g, '');
  const n = parseInt(hex.substring(0, 8), 16) || 0;
  return Math.floor(n % 1000000000) + 1;
}

// ================= 启动 =================

async function start(): Promise<boolean> {
  if (!isConfigured()) {
    host.asr.emitError('未配置 AppID / SecretId / SecretKey，请在插件设置中填写');
    return false;
  }
  const appId = host.config.get(KEY_APP_ID) ?? '';
  const secretId = host.config.get(KEY_SECRET_ID) ?? '';
  const secretKey = host.config.get(KEY_SECRET_KEY) ?? '';
  voiceId = host.uuid();
  audioReady = false;
  prebuffer = [];
  stopPending = false;

  const ts = host.crypto.epochSeconds();
  const params: Record<string, string | number> = {
    secretid: secretId,
    timestamp: ts,
    expired: ts + 86400,          // 有效期 1 天（须 >timestamp 且 <90 天）
    nonce: randNonce(),
    engine_model_type: host.config.get(KEY_ENGINE) || DEFAULT_ENGINE,
    voice_id: voiceId,
    voice_format: 1,              // pcm（宿主录音 16k/mono/16bit）
    needvad: 1,                   // 长音频 60s 强制切分，开启 VAD 提升分句效果
  };
  const hotword = host.config.get(KEY_HOTWORD_LIST) || '';
  if (hotword !== '') params.hotword_list = hotword;

  // 签名原文用原始值；URL 中值做百分号编码（服务端按解码后参数重建原文校验）
  const signStr = WS_HOST_PATH + appId + '?' + buildQuery(params, false);
  const sig = host.crypto.base64(host.crypto.hmacSha1(utf8Encode(secretKey), utf8Encode(signStr)));
  if (sig === null || sig === undefined || sig === '') {
    host.asr.emitError('签名计算失败（host.crypto 不可用）');
    return false;
  }
  const url = 'wss://' + WS_HOST_PATH + appId + '?'
    + buildQuery(params, true) + '&signature=' + percentEncode(sig);

  try {
    await host.ws.connect(url, {});
  } catch (e) {
    host.asr.emitError((e as Error).message);
    return false;
  }
  return true;
}

// ================= WebSocket 事件（状态机） =================

// 腾讯不收 full request 帧：连接建立后等握手文本帧，onWsOpen 无需发送任何数据
function onWsOpen(): void {
}

async function onWsMessage(text: string): Promise<void> {
  let obj: Record<string, any> | null = null;
  try {
    obj = JSON.parse(text);
  } catch (e) {
    host.asr.emitError('无法解析服务端消息: ' + text);
    return;
  }
  if (obj === null || obj === undefined) {
    host.asr.emitError('无法解析服务端消息: ' + text);
    return;
  }
  const code = obj.code || 0;
  if (code !== 0) {
    host.asr.emitError('ASR 错误 ' + String(code) + ': ' + (obj.message || ''));
    await host.ws.close();
    return;
  }
  // 握手成功（{"code":0}）后才允许发音频；补发握手期间缓冲的开头音频
  if (!audioReady) {
    audioReady = true;
    for (const pcm of prebuffer) {
      try {
        await host.ws.sendBinary(pcm);
      } catch (e) {
        host.asr.emitError((e as Error).message);
      }
    }
    prebuffer = [];
    if (stopPending) {
      // 握手中途已收到 stop：先补发开头音频再发结束通知，否则 end 会排在音频前面
      stopPending = false;
      await sendEnd();
    }
  }

  const sentences = obj.sentences;
  if (sentences !== null && sentences !== undefined) {
    if (sentences.sentence !== undefined) {
      handleSentence(sentences);
    } else if (Array.isArray(sentences)) {
      for (const sentence of sentences) {
        handleSentence(sentence);
      }
    }
  }
  if (obj.final === 1) {
    await host.ws.close();
  }
}

// sentence_type：0=不确定（partial 上屏预览），1=确定（final 提交）
function handleSentence(sentence: Record<string, any>): void {
  const text = sentence.sentence || '';
  if (text === '') return;
  if ((sentence.sentence_type || 0) === 1) {
    host.asr.emitFinal(text);
  } else {
    host.asr.emitPartial(text);
  }
}

function onWsBinary(_frame: Uint8Array): void {
  // 本接口结果均为文本帧，二进制帧忽略
}

function onWsError(msg: string): void {
  host.asr.emitError(msg);
}

function onWsClose(): void {
  voiceId = '';
  audioReady = false;
  prebuffer = [];
  stopPending = false;
}

// ================= 音频数据（主 App 每帧提交，JS 决策） =================

async function processAudioChunk(pcm: Uint8Array): Promise<void> {
  if (audioReady) {
    try {
      await host.ws.sendBinary(pcm);
    } catch (e) {
      host.asr.emitError((e as Error).message);
    }
  } else {
    prebuffer.push(pcm);
    if (prebuffer.length > 300) prebuffer.shift();
  }
}

/** 结束通知：服务端回 final=1 后断开。 */
async function sendEnd(): Promise<void> {
  try {
    await host.ws.sendText('{"type":"end"}');
  } catch (e) {
    host.asr.emitError((e as Error).message);
  }
}

async function stop(): Promise<void> {
  if (voiceId === '') return;
  if (!audioReady && host.ws.getState() !== 3) {
    // 握手尚未完成：此刻发 end 会排在缓冲音频之前（服务端已结束会话，音频会被丢弃），
    // 记为待发送，由 onWsMessage 补发缓存后再发；连接已关闭时照常发送以暴露错误
    stopPending = true;
    return;
  }
  await sendEnd();
}

async function cancel(): Promise<void> {
  await host.ws.close();
  voiceId = '';
  audioReady = false;
  prebuffer = [];
  stopPending = false;
}

const plugin = definePlugin({
  speech: {
    isConfigured,
    configure,
    feed: processAudioChunk,
    start,
    stop,
    cancel,
  },

  settings: {
    schema: getSettingsSchema,
  },

  ws: {
    onOpen: onWsOpen,
    onMessage: onWsMessage,
    onBinary: onWsBinary,
    onError: onWsError,
    onClose: onWsClose,
  },
});

export default plugin;
