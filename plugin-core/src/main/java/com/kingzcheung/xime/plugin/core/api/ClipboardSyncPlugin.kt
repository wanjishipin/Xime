package com.kingzcheung.xime.plugin.core.api

import com.kingzcheung.xime.plugin.core.config.IPluginConfigurable

/**
 * 剪贴板条目（同步用）。与 ximed `Profile` JSON 同构（snake_case 序列化）：
 *
 * ```json
 * {
 *   "type": "text",
 *   "hash": "9f86d081884c7d65...",
 *   "text": "完整文本内容",
 *   "has_data": false,
 *   "data_name": null,
 *   "size": 12,
 *   "source": "device-a"
 * }
 * ```
 *
 * **图片条目（Phase 3，决策 D12）**：`type = "image"`、`text = ""`、`has_data = true`、
 * `data` = 原图字节（宿主不压缩）、`hash` = `sha256(原图字节)`，
 * `data_name` = `"<hash>.<ext>"`（内容寻址，重复 PUT 幂等）。远端 blob 路径
 * `clipboard/blobs/<data_name>` 由插件拼装；协议不新增字段。
 *
 * ⚠️ `text` 为空**不代表**没有内容：判空必须用 `text.isEmpty() && !hasData`。
 * 早期实现拿"text 非空"当有内容的判据，会把纯图片 profile 误判成"无变更"而永久丢弃。
 *
 * @param type     类型（`"text"` | `"image"`）
 * @param hash     小写 hex SHA256：文本 = `SHA256(utf8(text))`；图片 = `SHA256(原图字节)`
 * @param text     文本内容（图片条目为空串）
 * @param hasData  是否携带附件数据（图片 = true）
 * @param dataName 附件文件名（内容寻址 `<hash>.<ext>`；无附件为 null）
 * @param data     附件字节（图片原图；无附件或未下载成功为 null）
 * @param size     内容字节大小
 * @param source   来源设备标识
 */
data class ClipboardProfile(
    val type: String = "text",
    val hash: String,
    val text: String,
    val hasData: Boolean = false,
    val dataName: String? = null,
    val data: ByteArray? = null,
    val size: Long = 0,
    val source: String? = null
) {
    // `data` 是 ByteArray：data class 生成的 equals/hashCode 只比较数组引用，
    // 必须按内容重写，否则"字节相同的两个 profile"会被判为不等（引擎去重与单测断言都依赖值语义）。
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ClipboardProfile) return false
        return type == other.type &&
            hash == other.hash &&
            text == other.text &&
            hasData == other.hasData &&
            dataName == other.dataName &&
            size == other.size &&
            source == other.source &&
            data.contentEquals(other.data)
    }

    override fun hashCode(): Int {
        var result = type.hashCode()
        result = 31 * result + hash.hashCode()
        result = 31 * result + text.hashCode()
        result = 31 * result + hasData.hashCode()
        result = 31 * result + (dataName?.hashCode() ?: 0)
        result = 31 * result + (data?.contentHashCode() ?: 0)
        result = 31 * result + size.hashCode()
        result = 31 * result + (source?.hashCode() ?: 0)
        return result
    }

    override fun toString(): String =
        "ClipboardProfile(type=$type, hash=$hash, text=$text, hasData=$hasData, " +
            "dataName=$dataName, dataBytes=${data?.size ?: 0}, size=$size, source=$source)"

    companion object {
        /** 由文本构造 text 类型 profile，自动计算 hash 与 size。 */
        fun fromText(text: String, source: String? = null): ClipboardProfile {
            return ClipboardProfile(
                type = "text",
                hash = sha256Hex(text.toByteArray(Charsets.UTF_8)),
                text = text,
                hasData = false,
                dataName = null,
                size = text.toByteArray(Charsets.UTF_8).size.toLong(),
                source = source
            )
        }

        /**
         * 由图片**原图字节**构造 image 类型 profile（决策 D12：内容寻址 + 原图直传）。
         *
         * `hash` 与 `dataName` 都由内容决定 ⇒ 同一张图在任何设备上都生成同一个 profile，
         * 重复推送幂等、也不会出现"同图不同名"导致的来回乒乓。
         *
         * @param extension 不带点的扩展名（如 `png`），取自本地内容寻址文件名
         */
        fun fromImage(data: ByteArray, extension: String, source: String? = null): ClipboardProfile {
            val hash = sha256Hex(data)
            return ClipboardProfile(
                type = "image",
                hash = hash,
                text = "",
                hasData = true,
                dataName = "$hash.$extension",
                data = data,
                size = data.size.toLong(),
                source = source
            )
        }

        fun sha256Hex(data: ByteArray): String {
            val digest = java.security.MessageDigest.getInstance("SHA-256")
            return digest.digest(data).joinToString("") { "%02x".format(it) }
        }
    }
}

/**
 * 剪贴板同步插件能力接口（宿主侧，由 JS 适配器实现，协议逻辑在 JS）。
 *
 * 同步引擎（宿主 ClipboardSyncBridge）只依赖此接口做 push / pull / 连接测试，
 * 具体传输协议（WebDAV / S3 / ximed HTTP）由插件 Lua 用 `host.http` + `host.crypto`
 * 实现。
 */
interface ClipboardSyncPlugin : IPluginEntryClass, IPluginConfigurable {

    /**
     * 推送本地 profile 到远端（宿主在剪贴板变化时调用）。
     *
     * @param profile 本地剪贴板 profile（含 hash，引擎已做去重）
     * @return 成功 true；失败 false（引擎记录错误并退避重试）
     */
    suspend fun push(profile: ClipboardProfile): Boolean

    /**
     * 拉取远端 profile（宿主轮询调用）。
     *
     * ETag / If-None-Match 条件请求由插件 Lua 内部用 `host.config` 自行缓存管理：
     * 远端返回 304 或无变更时返回 null（宿主据此跳过写回）。
     *
     * @return 远端 profile；无变更/失败返回 null
     */
    suspend fun pull(): ClipboardProfile?

    /** 校验配置可用性（连接测试），返回错误消息（null 表示成功）。 */
    suspend fun testConnection(): String?
}
