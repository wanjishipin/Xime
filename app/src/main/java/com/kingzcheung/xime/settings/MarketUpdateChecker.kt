package com.kingzcheung.xime.settings

import android.content.Context
import android.content.SharedPreferences
import com.kingzcheung.xime.BuildConfig
import com.kingzcheung.xime.model.ModelManager
import com.kingzcheung.xime.plugin.core.runtime.PluginManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * 扩展商店可更新计数：进设置主页/商店时后台拉取方案/模型/插件/布局四个索引，与本地
 * 已安装状态比对，输出**分市场**的「已安装且版本落后于索引 currentVersion」计数：
 * 设置主页「扩展商店」入口角标显示总数，商店页四个 Tab 分别显示各自计数。
 *
 * 节流与缓存：默认 6 小时内不重复拉取（[maybeRefresh] 判定；进商店时 [invalidate]
 * 清时间戳，返回主页即强制重算，避免商店内更新后角标残留过期计数）；结果持久化到
 * SharedPreferences，进程冷启后离线也可见。单个市场失败时该分项沿用上次计数、其余
 * 分项正常更新，全部失败则整体保留旧值。
 */
object MarketUpdateChecker {

    /** 各市场可更新计数快照（null = 本次检查失败，沿用旧值）。 */
    data class MarketCounts(
        val scheme: Int? = null,
        val model: Int? = null,
        val plugin: Int? = null,
        val layout: Int? = null,
    )

    /** 可更新计数快照。[checkedAtMs] 为 0 表示本次进程尚未成功检查过。 */
    data class Summary(
        val schemeUpdates: Int = 0,
        val modelUpdates: Int = 0,
        val pluginUpdates: Int = 0,
        val layoutUpdates: Int = 0,
        val checkedAtMs: Long = 0L,
    ) {
        /** 总数：设置主页入口角标使用。 */
        val totalUpdates: Int
            get() = schemeUpdates + modelUpdates + pluginUpdates + layoutUpdates
    }

    private const val PREFS_NAME = "market_update_check"
    private const val KEY_LAST_CHECKED_AT = "last_checked_at"
    private const val KEY_SCHEME_COUNT = "scheme_count"
    private const val KEY_MODEL_COUNT = "model_count"
    private const val KEY_PLUGIN_COUNT = "plugin_count"
    private const val KEY_LAYOUT_COUNT = "layout_count"
    /** 节流间隔：6 小时。 */
    private const val CHECK_INTERVAL_MS = 6 * 60 * 60 * 1000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val checkMutex = Mutex()

    private val _summary = MutableStateFlow(Summary())
    val summary: StateFlow<Summary> = _summary.asStateFlow()

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** 供 UI 初始订阅：进程冷启后从持久化恢复上次计数（可能过期，仅作展示）。 */
    fun restoreCached(context: Context) {
        if (_summary.value.checkedAtMs != 0L) return
        val p = prefs(context)
        val at = p.getLong(KEY_LAST_CHECKED_AT, 0L)
        if (at != 0L) {
            _summary.value = Summary(
                schemeUpdates = p.getInt(KEY_SCHEME_COUNT, 0),
                modelUpdates = p.getInt(KEY_MODEL_COUNT, 0),
                pluginUpdates = p.getInt(KEY_PLUGIN_COUNT, 0),
                layoutUpdates = p.getInt(KEY_LAYOUT_COUNT, 0),
                checkedAtMs = at,
            )
        }
    }

    /**
     * 进设置主页/商店调用：距上次成功检查超过 [intervalMs] 才真正拉索引，否则只恢复缓存。
     * 内部去重（并发调用只发一轮请求），失败静默（失败分项沿用旧计数，下次进页重试）。
     */
    fun maybeRefresh(context: Context, intervalMs: Long = CHECK_INTERVAL_MS) {
        restoreCached(context)
        val appContext = context.applicationContext
        scope.launch {
            checkMutex.withLock {
                val last = prefs(appContext).getLong(KEY_LAST_CHECKED_AT, 0L)
                if (!shouldCheck(System.currentTimeMillis(), last, intervalMs)) return@launch
                val counts = withContext(Dispatchers.IO) { countUpdates(appContext) }
                val old = _summary.value
                val now = System.currentTimeMillis()
                val merged = merge(old, counts, now)
                if (merged != old) {
                    prefs(appContext).edit()
                        .putLong(KEY_LAST_CHECKED_AT, merged.checkedAtMs)
                        .putInt(KEY_SCHEME_COUNT, merged.schemeUpdates)
                        .putInt(KEY_MODEL_COUNT, merged.modelUpdates)
                        .putInt(KEY_PLUGIN_COUNT, merged.pluginUpdates)
                        .putInt(KEY_LAYOUT_COUNT, merged.layoutUpdates)
                        .apply()
                    _summary.value = merged
                }
            }
        }
    }

    /**
     * 合并新检查结果：成功的分项用新值，失败的分项沿用旧值；至少一个市场成功才
     * 视为检查成功（更新时间戳），全部失败时整体维持旧快照。
     */
    internal fun merge(old: Summary, counts: MarketCounts, nowMs: Long): Summary {
        val anySuccess = counts.scheme != null || counts.model != null ||
            counts.plugin != null || counts.layout != null
        if (!anySuccess) return old
        return Summary(
            schemeUpdates = counts.scheme ?: old.schemeUpdates,
            modelUpdates = counts.model ?: old.modelUpdates,
            pluginUpdates = counts.plugin ?: old.pluginUpdates,
            layoutUpdates = counts.layout ?: old.layoutUpdates,
            checkedAtMs = nowMs,
        )
    }

    /** 节流判定：从未检查（lastCheckedAtMs=0）或超出间隔返回 true。 */
    internal fun shouldCheck(nowMs: Long, lastCheckedAtMs: Long, intervalMs: Long): Boolean =
        nowMs - lastCheckedAtMs >= intervalMs

    /**
     * 进入扩展商店时调用：清除节流时间戳。用户在商店里安装/更新扩展后返回设置主页时，
     * maybeRefresh 会绕过节流强制重算，避免角标停留在过期计数上。
     */
    fun invalidate(context: Context) {
        prefs(context).edit().putLong(KEY_LAST_CHECKED_AT, 0L).apply()
    }

    /**
     * 强制立即重算（绕过节流）：各市场的下载/更新成功后调用，让 Tab 角标与
     * 设置主页角标实时反映新计数，不必等离开商店再进。Mutex 排队天然去重。
     */
    fun refreshNow(context: Context) {
        maybeRefresh(context, intervalMs = 0L)
    }

    /**
     * 拉取四个索引并统计各市场可更新数。任一市场失败其分项为 null（沿用旧值），
     * 互不影响；四个市场独立并行请求。
     */
    suspend fun countUpdates(context: Context): MarketCounts = withContext(Dispatchers.IO) {
        val appVersion = BuildConfig.VERSION_NAME
        val schemes = scope.async { runCatching { fetchSchemeUpdates(context, appVersion) } }
        val models = scope.async { runCatching { fetchModelUpdates(context) } }
        val plugins = scope.async { runCatching { fetchPluginUpdates(context, appVersion) } }
        val layouts = scope.async { runCatching { fetchLayoutUpdates(context, appVersion) } }

        MarketCounts(
            scheme = schemes.await().getOrNull(),
            model = models.await().getOrNull(),
            plugin = plugins.await().getOrNull(),
            layout = layouts.await().getOrNull(),
        )
    }

    /** 方案：已下载目录 + 版本记录与索引比对（copy 填充 installedVersion 后复用 [MarketSchemeItem.hasUpdate]）。 */
    private suspend fun fetchSchemeUpdates(context: Context, appVersion: String): Int {
        val result = XimeIndexSource.fetchSchemes(context, appVersion).getOrThrow()
        val downloadedIds = SchemaManager.getMarketDir(context).listFiles()
            ?.mapNotNull { sub ->
                if (sub.isDirectory && sub.listFiles()?.any { it.isFile } == true) sub.name else null
            }?.toSet() ?: emptySet()
        val versions = MarketVersionStore.getAllSchemeVersions(context).filterKeys { it in downloadedIds }
        return result.schemes.count { item ->
            item.copy(installedVersion = versions[item.scheme.id]).hasUpdate
        }
    }

    /** 模型：本地下载状态 + 版本记录与索引默认版本比对（与模型市场 [com.kingzcheung.xime.viewmodel.ModelItemState.hasUpdate] 同口径）。 */
    private suspend fun fetchModelUpdates(context: Context): Int {
        ModelManager.loadFromRemote(context)
        val versions = MarketVersionStore.getAllModelVersions(context)
        return ModelManager.getAllModels().count { model ->
            val installed = versions[model.id]
            val latest = model.resolvedVersion()?.version
            installed != null && latest != null && installed != latest &&
                ModelManager.isModelDownloaded(context, model)
        }
    }

    /** 插件：PluginManager 注册表版本与索引 currentVersion 比对。 */
    private suspend fun fetchPluginUpdates(context: Context, appVersion: String): Int {
        val installed = PluginManager.getAllInstallPlugins()
            .filter { it.version.isNotBlank() }
            .associate { it.id to it.version }
        val result = XimeIndexSource.fetchPlugins(context, appVersion, installed).getOrThrow()
        return result.plugins.count { it.hasUpdate }
    }

    /** 布局：当前应用布局的版本与索引 currentVersion 比对（与布局市场同口径）。 */
    private suspend fun fetchLayoutUpdates(context: Context, appVersion: String): Int {
        val appliedId = SettingsPreferences.getAppliedLayoutId(context)
        val appliedVersions = if (appliedId.isBlank()) emptyMap()
        else mapOf(appliedId to SettingsPreferences.getAppliedLayoutVersion(context))
        val installedSchemaIds = SchemaManager.discoverSchemas(context).map { it.schemaId }.toSet()
        val result = XimeIndexSource.fetchLayouts(
            context, appVersion, appliedVersions, installedSchemaIds
        ).getOrThrow()
        return result.layouts.count { it.hasUpdate }
    }
}
