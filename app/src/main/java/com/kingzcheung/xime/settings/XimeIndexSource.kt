package com.kingzcheung.xime.settings

import android.content.Context
import android.util.Log
import com.kingzcheung.xime.settings.KeysConfigHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

/** 下载校验状态：null=未提供sha256, true=校验通过, false=校验不通过。 */
typealias Sha256Status = Boolean?

/** 安装结果（带失败原因 + 未解决依赖 + sha256 校验状态）。 */
data class InstallResult(
    val success: Boolean,
    val unresolvedDeps: List<String> = emptyList(),
    val failureReason: String? = null,
    /** null=未提供sha256, true=校验通过, false=校验不通过 */
    val sha256Status: Sha256Status = null,
)

/** 方案列表拉取结果（含命中的来源主机名，供 UI 显示「从哪个端点拉的」）。 */
data class SchemesFetch(
    val schemes: List<MarketSchemeItem>,
    val source: String,
)

/**
 * 方案市场数据源：从镜像基址的 rimes/index.yaml（扁平索引，schemas 内联所有 MarketScheme）
 * 获取方案列表，按版本 sha256 下载；安装后用 [RimeDependencyResolver] 补齐编译依赖。
 * 网络/Android 依赖集中在此层；解析/版本/兼容性逻辑在 [XimeIndexParser] 纯函数里。
 *
 * 端点列表通过 [xime.yaml] 的 `xime_index.base_urls` 配置，用户可自定义镜像列表。
 */
object XimeIndexSource {
    private const val TAG = "XimeIndexSource"
    private val defaultBaseUrls = listOf("https://index.ximei.me/")

    private var baseUrls: List<String> = defaultBaseUrls
    private var mirrors: List<String> = buildMirrors(defaultBaseUrls)

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    private val DownloadItem.fileName: String
        get() = url.substringAfterLast('/').takeIf { it.isNotBlank() }
            ?: "file.${url.substringAfterLast('.').takeIf { it.length in 1..6 } ?: "bin"}"

    private val DownloadItem.sizeBytes: Long
        get() = size?.removeSuffix(" MB")?.trim()?.toDoubleOrNull()
            ?.let { (it * 1024.0 * 1024.0).toLong() } ?: 0L

    private fun buildMirrors(userUrls: List<String>): List<String> = userUrls

    private fun ensureConfigured(context: Context) {
        val cfg = KeysConfigHelper.loadXimeIndexConfig(context)
        if (cfg.baseUrls != baseUrls) {
            baseUrls = cfg.baseUrls
            mirrors = buildMirrors(baseUrls)
            Log.d(TAG, "XimeIndex configured: baseUrls=$baseUrls")
        }
    }

    /** 镜像 base → 展示用主机名（如 index.ximei.me / fastly.jsdelivr.net）。 */
    private fun hostOf(base: String): String =
        base.substringAfter("://").substringBefore("/")

    /** 跟随索引跳转：根 → 子 → 逐方案（并行、部分失败容忍）。逐个镜像尝试直到获取到方案。 */
    suspend fun fetchSchemes(context: Context, appVersion: String): Result<SchemesFetch> =
        withContext(Dispatchers.IO) {
            ensureConfigured(context)
            try {
                // 遍历所有镜像，第一个成功获取到方案的返回
                for (base in mirrors) {
                    val result = tryFetchFromBase(base, appVersion)
                    if (result != null) return@withContext Result.success(result)
                }
                // 全部镜像都失败
                val lastUrl = mirrors.lastOrNull() ?: "未知"
                Result.failure(IOException("无法连接到方案市场（已尝试 ${mirrors.size} 个镜像）"))
            } catch (e: Exception) {
                Log.e(TAG, "fetchSchemes failed", e)
                Result.failure(e)
            }
        }

    /**
     * 尝试从一个镜像基址获取方案列表。
     * 新索引格式：直接抓取 rimes/index.yaml，其中 schemas 已内联所有 MarketScheme。
     */
    private fun tryFetchFromBase(base: String, appVersion: String): SchemesFetch? {
        val repoPath = "rimes/index.yaml"
        val host = hostOf(base)
        try {
            val text = fetchTextSingle(base, repoPath) ?: return null
            val direct = XimeIndexParser.parseDirectIndex(text)
            val schemes = direct.schemas.distinctBy { it.id }
                .map { XimeIndexParser.toItem(it, appVersion) }
            Log.i(TAG, "tryFetchFromBase $host: 获取到 ${schemes.size} 个方案（扁平索引）")

            if (schemes.isEmpty()) {
                Log.w(TAG, "tryFetchFromBase $host: 0 个方案，尝试下一个镜像")
                return null
            }
            return SchemesFetch(schemes, host)
        } catch (e: Exception) {
            Log.w(TAG, "tryFetchFromBase $host failed: ${e.message}")
            return null
        }
    }

    /** 从镜像基址获取文件内容，失败返回 null。 */
    private fun fetchTextSingle(base: String, repoPath: String): String? {
        return try {
            client.newCall(Request.Builder().url(base + repoPath).build()).execute().use { resp ->
                if (resp.isSuccessful) {
                    resp.body?.string()?.takeIf { it.isNotBlank() }
                } else null
            }
        } catch (e: Exception) {
            Log.w(TAG, "fetchTextSingle $base$repoPath failed: ${e.message}")
            null
        }
    }

    /**
     * 下载一个方案到 market 目录（仅下载，不解压）。
     * 遍历所有 downloadUrl（如主包 + 语言模型等），逐一下载到 market/{schemeId}/。
     */
    suspend fun downloadScheme(
        context: Context,
        scheme: MarketScheme,
        version: String? = null,
        onDownloadProgress: (Long, Long) -> Unit = { _, _ -> },
    ): InstallResult = withContext(Dispatchers.IO) {
        val v = if (version != null) {
            scheme.versions.firstOrNull { it.version == version }
        } else {
            scheme.resolvedVersion()
        } ?: return@withContext InstallResult(false, failureReason = "无可用版本")
        if (v.downloadUrls.isEmpty() || v.downloadUrls.all { it.url.isBlank() }) {
            return@withContext InstallResult(false, failureReason = "缺少下载地址")
        }

        val items = v.downloadUrls.filter { it.url.isNotBlank() }
        val totalBytesAll = items.sumOf { it.sizeBytes }
        var accumulatedBytes = 0L
        var anyVerified = false

        for (dl in items) {
            val result = SchemaManager.downloadToMarket(
                context, dl.url, scheme.id, dl.fileName, dl.sha256?.takeIf { it.isNotBlank() },
                onProgress = { read, _ ->
                    val overall = accumulatedBytes + read
                    if (totalBytesAll > 0) onDownloadProgress(overall, totalBytesAll)
                },
            )
            accumulatedBytes += dl.sizeBytes
            if (!result.success) {
                val schemeDir = SchemaManager.getMarketDir(context, scheme.id)
                if (schemeDir.exists()) schemeDir.deleteRecursively()
                val reason = if (result.sha256Verified == false)
                    "文件校验失败（sha256 不匹配），文件可能不完整" else "下载失败"
                return@withContext InstallResult(
                    false, failureReason = reason, sha256Status = result.sha256Verified
                )
            } else if (result.sha256Verified == true) {
                anyVerified = true
            }
        }
        InstallResult(success = true, sha256Status = if (anyVerified) true else null)
    }

    /**
     * 从 market 目录安装已下载的方案到 rime 目录（解压/复制 + 依赖补齐）。
     * 返回安装结果，调用方据此更新已安装列表。
     */
    suspend fun installFromMarket(
        context: Context,
        scheme: MarketScheme,
        resolveDepUrl: (String) -> String? = { null },
    ): InstallResult = withContext(Dispatchers.IO) {
        if (!SchemaManager.isSchemeDownloaded(context, scheme.id)) {
            return@withContext InstallResult(false, failureReason = "压缩包不存在，请先下载")
        }
        val before = SchemaManager.discoverSchemas(context).map { it.schemaId }.toSet()
        val ok = SchemaManager.installFromMarketToRime(context, scheme.id)
        if (!ok) return@withContext InstallResult(false, failureReason = "安装失败")

        val after = SchemaManager.discoverSchemas(context).map { it.schemaId }.toSet()
        val newIds = after - before
        val installedSchemaId = when {
            scheme.id in newIds -> scheme.id
            newIds.size == 1 -> newIds.first()
            newIds.isNotEmpty() -> {
                Log.w(TAG, "installFromMarket ${scheme.id}: multiple new schemas detected: $newIds, using ${newIds.first()}")
                newIds.first()
            }
            else -> {
                Log.w(TAG, "installFromMarket ${scheme.id}: no new schema detected, falling back to market id")
                scheme.id
            }
        }

        val completion = RimeDependencyResolver.complete(
            context = context,
            schemaId = installedSchemaId,
            dependencies = scheme.dependencies,
            resolveUrl = resolveDepUrl,
        )
        val unresolved = (completion.unresolved + completion.stillMissingFiles).distinct()
        if (unresolved.isNotEmpty()) Log.w(TAG, "installFromMarket ${scheme.id}: unresolved=$unresolved")
        InstallResult(success = true, unresolvedDeps = unresolved)
    }
}
