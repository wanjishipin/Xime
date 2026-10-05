package com.kingzcheung.xime.plugin.core.js.crypto

/**
 * 宿主提供的加密/编码白名单原语（S3 SigV4 / 腾讯云 HMAC-SHA1 签名等协议插件使用）。
 *
 * Lua 侧注入为 `host.crypto`：
 *   host.crypto.sha256(data)        -- 二进制 → SHA256 摘要字节（Lua 字符串）
 *   host.crypto.hmacSha256(key, data)
 *   host.crypto.hmacSha1(key, data)
 *   host.crypto.hex(data)           -- 字节 → 小写 hex 字符串
 *   host.crypto.base64(data)        -- 字节 → Base64 字符串
 *   host.crypto.utcTime(format)     -- 当前 UTC 时间，如 "YYYYMMDDTHHMMSSZ" / "YYYYMMDD"
 *   host.crypto.epochSeconds()      -- 当前 UNIX 时间戳（秒）
 *
 * 字节在 Lua 中以字符串表示（与 host.bin / host.ws.sendBinary 的约定一致）。
 */
interface CryptoHostApi {

    /** SHA-256 摘要（Lua 字符串字节 → Lua 字符串字节）。 */
    fun sha256(data: ByteArray): ByteArray

    /** HMAC-SHA256（key 与 data 均为字节）。 */
    fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray

    /** HMAC-SHA1（key 与 data 均为字节；腾讯云实时 ASR 签名使用）。 */
    fun hmacSha1(key: ByteArray, data: ByteArray): ByteArray

    /** 当前 UNIX 时间戳（秒）。沙箱剥离了 os 库，插件经此获取签名用时间戳。 */
    fun epochSeconds(): Long

    /** 字节 → 小写十六进制字符串。 */
    fun hex(data: ByteArray): String

    /** 字节 → Base64 字符串。 */
    fun base64(data: ByteArray): String

    /**
     * 当前 UTC 时间格式化。支持占位符：
     *   "YYYYMMDDTHHMMSSZ" → "20230811T120000Z"（SigV4 时间戳）
     *   "YYYYMMDD"         → "20230811"（SigV4 日期戳）
     */
    fun utcTime(format: String): String
}
