// AWS Signature Version 4（Authorization 头签名，payload 单块直传）
//
// 纯函数 + 宿主 crypto 原语，不读配置、不发请求：便于用 AWS 官方测试向量做固定断言。
// 参考：AWS「Signature Calculations for the Authorization Header:
//      Transferring Payload in a Single Chunk (AWS Signature Version 4)」
//
// 只支持无查询串的请求（本插件的对象 PUT/GET/HEAD/DELETE 都不带查询）；
// 若要接 ListObjectsV2 之类，需另加 canonicalQuery 的排序编码。

/** 空 body 的 sha256（GET/HEAD/DELETE 用；SigV4 文档里的常量） */
export const EMPTY_PAYLOAD_SHA256 =
  'e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855';

export interface SigV4Request {
  method: string;
  /** canonical URI：以 / 开头、已按 SigV4 规则编码（见 [encodeS3Path]） */
  canonicalUri: string;
  /** canonical query string：已按名排序并编码；无查询传 ''/省略 */
  canonicalQuery?: string;
  /** 参与签名的头（键必须小写；值会去首尾空白并把连续空白折叠为单空格） */
  headers: Record<string, string>;
  /** 参与签名的头名（小写）；顺序无关（内部排序后写入 SignedHeaders） */
  signedHeaders: string[];
  /** payload 的 sha256 十六进制；空 body 用 [EMPTY_PAYLOAD_SHA256] */
  payloadHash: string;
  /** YYYYMMDD */
  dateStamp: string;
  /** YYYYMMDDTHHMMSSZ */
  amzDate: string;
  region: string;
  service: string;
  accessKeyId: string;
  secretAccessKey: string;
}

export interface SigV4Result {
  authorization: string;
  canonicalRequest: string;
  stringToSign: string;
  scope: string;
}

function utf8(text: string): Uint8Array {
  return new TextEncoder().encode(text);
}

/** sha256 → 小写十六进制（宿主 hex 的大小写不作假设，统一折叠） */
export function sha256Hex(data: Uint8Array): string {
  return host.crypto.hex(host.crypto.sha256(data)).toLowerCase();
}

function hmacSha256(key: Uint8Array, data: string): Uint8Array {
  return host.crypto.hmacSha256(key, utf8(data));
}

/** SigV4 派生密钥链：kDate → kRegion → kService → kSigning */
export function signingKey(
  secretAccessKey: string,
  dateStamp: string,
  region: string,
  service: string
): Uint8Array {
  const kDate = hmacSha256(utf8('AWS4' + secretAccessKey), dateStamp);
  const kRegion = hmacSha256(kDate, region);
  const kService = hmacSha256(kRegion, service);
  return hmacSha256(kService, 'aws4_request');
}

/**
 * SigV4 的 URI 编码：unreserved（A-Za-z0-9-_.~）与路径分隔符 '/' 之外全部百分号编码。
 * encodeURIComponent 会漏掉 `! ' ( ) *`（这几个在 SigV4 里必须编码），故额外转义。
 */
export function encodeS3Path(path: string): string {
  const segments = path.split('/');
  const encoded = segments.map(function (segment) {
    return encodeURIComponent(segment).replace(/[!'()*]/g, function (ch) {
      return '%' + ch.charCodeAt(0).toString(16).toUpperCase();
    });
  });
  return encoded.join('/');
}

/** 生成 Authorization 头（同时回传 canonical request / string to sign，便于排错与断言） */
export function signRequest(req: SigV4Request): SigV4Result {
  const signedHeaders = req.signedHeaders
    .map(function (name) { return name.toLowerCase(); })
    .sort();
  // canonical headers：按头名升序，每行 `name:value\n`（值需去首尾空白、折叠内部连续空白）
  const canonicalHeaders = signedHeaders
    .map(function (name) {
      const raw = req.headers[name];
      const value = (raw === undefined || raw === null ? '' : String(raw))
        .trim()
        .replace(/\s+/g, ' ');
      return name + ':' + value + '\n';
    })
    .join('');
  const payloadHash = req.payloadHash.toLowerCase();
  const canonicalRequest = [
    req.method.toUpperCase(),
    req.canonicalUri,
    req.canonicalQuery === undefined ? '' : req.canonicalQuery,
    canonicalHeaders, // 自身以 \n 结尾 ⇒ join 再补一个，正好是规范要求的空行
    signedHeaders.join(';'),
    payloadHash,
  ].join('\n');

  const scope = req.dateStamp + '/' + req.region + '/' + req.service + '/aws4_request';
  const stringToSign = [
    'AWS4-HMAC-SHA256',
    req.amzDate,
    scope,
    sha256Hex(utf8(canonicalRequest)),
  ].join('\n');

  const signature = host.crypto
    .hex(hmacSha256(signingKey(req.secretAccessKey, req.dateStamp, req.region, req.service), stringToSign))
    .toLowerCase();

  const authorization = 'AWS4-HMAC-SHA256 Credential=' + req.accessKeyId + '/' + scope
    + ', SignedHeaders=' + signedHeaders.join(';')
    + ', Signature=' + signature;

  return {
    authorization: authorization,
    canonicalRequest: canonicalRequest,
    stringToSign: stringToSign,
    scope: scope,
  };
}