package com.kingzcheung.xime.plugin.core.api

import com.kingzcheung.xime.plugin.core.config.IPluginConfigurable

/**
 * 远端备份条目（listBackups 返回的单条）。
 *
 * @param id        远端标识（WebDAV 路径 / S3 key 等），恢复与删除时原样回传给插件
 * @param name      展示名（如 "Xime配置-2026-09-06.zip"）
 * @param createdAt 创建时间（毫秒时间戳；远端不提供时为 0）
 * @param size      字节数（远端不提供时为 -1）
 */
data class RemoteBackupEntry(
    val id: String,
    val name: String,
    val createdAt: Long = 0,
    val size: Long = -1
)

/**
 * pushBackup 的执行结果。
 *
 * @param ok      是否成功
 * @param id      成功时远端条目 id（插件未返回时为 null，宿主随后 listBackups 获取）
 * @param message 失败原因（成功时为 null）
 */
data class BackupResult(
    val ok: Boolean,
    val id: String? = null,
    val message: String? = null
)

/**
 * 备份插件能力接口（宿主侧，由 JS 适配器实现，协议逻辑在 JS）。
 *
 * 分工与 [ClipboardSyncPlugin] 一致：备份包的**生成与恢复**（zip 打包、路径校验、
 * 落盘）全部由宿主 BackupManager 承载，插件只负责**传输协议**
 * （WebDAV / S3 / 自建 HTTP），用 `host.http` + `host.crypto` + `host.config` 实现。
 * 服务器地址、账号等配置由插件 getSettingsSchema 表单承载（host.config 存取）。
 *
 * 归档数据为 zip 字节流，经 JS 侧二进制安全的 Uint8Array 传递，
 * 插件可直接作为 host.http.request 的 body 上传。
 */
interface BackupPlugin : IPluginEntryClass, IPluginConfigurable {

    /**
     * 上传备份包到远端。
     *
     * @param name    建议的远端文件名（如 "Xime配置-2026-09-06.zip"），插件可自行附加目录前缀
     * @param archive zip 字节流
     */
    suspend fun pushBackup(name: String, archive: ByteArray): BackupResult

    /**
     * 下载指定备份包。
     *
     * @param id listBackups 返回的远端标识
     * @return zip 字节流；失败返回 null
     */
    suspend fun pullBackup(id: String): ByteArray?

    /**
     * 列出远端备份条目。
     *
     * @return 按创建时间倒序；失败返回 null（与"远端为空"的空列表区分）
     */
    suspend fun listBackups(): List<RemoteBackupEntry>?

    /** 删除远端备份。 */
    suspend fun deleteBackup(id: String): Boolean

    /** 校验配置可用性（连接测试），返回错误消息（null 表示成功）。 */
    suspend fun testConnection(): String?
}
