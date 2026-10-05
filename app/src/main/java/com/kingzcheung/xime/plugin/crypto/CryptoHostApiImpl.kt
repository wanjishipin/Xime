package com.kingzcheung.xime.plugin.crypto

import com.kingzcheung.xime.plugin.core.js.crypto.CryptoHostApi
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * 宿主加密/编码原语实现（S3 SigV4 签名等）。
 *
 * - sha256 / hmacSha256：标准 JCE
 * - utcTime：SigV4 时间戳（YYYYMMDDTHHMMSSZ）与日期戳（YYYYMMDD）
 */
class CryptoHostApiImpl : CryptoHostApi {

    override fun sha256(data: ByteArray): ByteArray {
        return MessageDigest.getInstance("SHA-256").digest(data)
    }

    override fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(data)
    }

    override fun hmacSha1(key: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA1")
        mac.init(SecretKeySpec(key, "HmacSHA1"))
        return mac.doFinal(data)
    }

    override fun epochSeconds(): Long = System.currentTimeMillis() / 1000

    override fun hex(data: ByteArray): String {
        return data.joinToString("") { "%02x".format(it) }
    }

    override fun base64(data: ByteArray): String {
        return android.util.Base64.encodeToString(data, android.util.Base64.NO_WRAP)
    }

    override fun utcTime(format: String): String {
        val result = when (format) {
            "YYYYMMDDTHHMMSSZ" -> SimpleDateFormat("yyyyMMdd'T'HHmmss'Z'", Locale.US)
            "YYYYMMDD" -> SimpleDateFormat("yyyyMMdd", Locale.US)
            else -> SimpleDateFormat(format, Locale.US)
        }
        result.timeZone = TimeZone.getTimeZone("UTC")
        return result.format(Date())
    }
}
