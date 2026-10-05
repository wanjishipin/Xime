package com.kingzcheung.xime.plugin.core.runtime.installer

import android.app.Application
import android.util.Log
import com.kingzcheung.xime.plugin.core.api.ToolResult
import com.kingzcheung.xime.plugin.core.model.PluginCapabilities
import com.kingzcheung.xime.plugin.core.model.PluginInfo
import com.kingzcheung.xime.plugin.core.model.PluginSource
import com.kingzcheung.xime.plugin.core.model.PluginToolbarButton
import com.kingzcheung.xime.plugin.core.util.PluginSignatureUtil
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import java.io.File

/**
 * 插件注册表（`plugins.json`）：已安装插件的元数据持久化与查询。
 *
 * 存储用 kotlinx-serialization-json（与 manifest.json 同栈，领域模型经
 * [RegistryPlugin] DTO 映射，存储格式与模型解耦）。旧版 `plugins.xml`
 * 注册表（Lua 插件时代）不再读取：其中插件入口为已废弃的 main.lua，
 * 与 QuickJS 引擎不兼容，发现即清理。
 */
class PluginRegistry(private val context: Application) {

    companion object {
        private const val TAG = "PluginRegistry"
        private const val REGISTRY_FILE = "plugins.json"
        private const val LEGACY_REGISTRY_FILE = "plugins.xml"
        internal const val REGISTRY_SCHEMA_VERSION = 1
    }

    private val registryFile: File by lazy { File(context.filesDir, REGISTRY_FILE) }

    private val legacyRegistryFile: File by lazy { File(context.filesDir, LEGACY_REGISTRY_FILE) }

    private val plugins = mutableMapOf<String, PluginInfo>()

    init {
        loadFromDisk()
    }

    fun getAllPlugins(): List<PluginInfo> = plugins.values.toList()

    fun getPluginById(id: String): PluginInfo? = plugins[id]

    fun addPlugin(plugin: PluginInfo) {
        plugins[plugin.id] = plugin
    }

    fun updatePlugin(plugin: PluginInfo) {
        plugins[plugin.id] = plugin
    }

    fun removePlugin(id: String) {
        plugins.remove(id)
    }

    fun flushToDisk() {
        try {
            // 原子写入：先写临时文件再 rename，避免崩溃损坏注册表（覆盖式 rename 在 Android 上可靠）
            val tmp = File(registryFile.parentFile, "${registryFile.name}.tmp")
            tmp.writeText(encodeRegistryJson(plugins.values))
            if (registryFile.exists() && !registryFile.delete()) {
                Log.w(TAG, "删除旧注册表失败，rename 将覆盖")
            }
            if (!tmp.renameTo(registryFile)) {
                Log.e(TAG, "注册表写入失败：rename 失败")
            }
        } catch (e: Exception) {
            Log.e(TAG, "注册表写入失败", e)
        }
    }

    private fun loadFromDisk() {
        if (!registryFile.exists()) {
            dropLegacyRegistry()
            return
        }

        try {
            for (info in decodeRegistryJson(registryFile.readText())) {
                plugins[info.id] = info
            }
        } catch (e: Exception) {
            Log.e(TAG, "注册表解析失败：${e.message}", e)
        }
    }

    /** 旧 Lua 时代 XML 注册表：入口 main.lua 已不被 QuickJS 引擎支持，清理文件。 */
    private fun dropLegacyRegistry() {
        if (!legacyRegistryFile.exists()) return
        Log.w(TAG, "发现已废弃的 plugins.xml（Lua 插件注册表），清理并忽略")
        if (!legacyRegistryFile.delete()) {
            Log.w(TAG, "废弃注册表清理失败：${legacyRegistryFile.absolutePath}")
        }
    }
}

// ---- 注册表编解码（纯函数，与 Android 无关，便于单测） ----

private val registryJson: Json by lazy {
    Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }
}

internal fun encodeRegistryJson(plugins: Collection<PluginInfo>): String =
    registryJson.encodeToString(
        RegistryFile.serializer(),
        RegistryFile(
            version = PluginRegistry.REGISTRY_SCHEMA_VERSION,
            plugins = plugins.map { it.toRegistryPlugin() }
        )
    )

internal fun decodeRegistryJson(content: String): List<PluginInfo> =
    registryJson.decodeFromString(RegistryFile.serializer(), content).plugins.map { it.toPluginInfo() }

// ---- 存储 DTO（与 plugins.json 一一对应） ----

@Serializable
internal data class RegistryFile(
    val version: Int = 1,
    val plugins: List<RegistryPlugin> = emptyList()
)

@Serializable
internal data class RegistryPlugin(
    val id: String,
    val name: String = "",
    val description: String = "",
    val versionName: String = "",
    val path: String,
    val type: String = "unknown",
    val enabled: Boolean = true,
    val installTime: Long = 0L,
    val source: String = "SYSTEM",
    val minHostVersion: String? = null,
    val maxHostVersion: String? = null,
    val entryScript: String? = null,
    val declaredHosts: List<String> = emptyList(),
    val allowCustomHosts: Boolean = false,
    val toolbarButtons: List<RegistryToolbarButton> = emptyList(),
    val manifestIcon: String? = null,
    val capabilities: JsonObject? = null,
    /** 目标平台声明（旧条目缺省为空，读取时归一化为 android）。 */
    val platforms: List<String> = emptyList()
)

@Serializable
internal data class RegistryToolbarButton(
    val id: String,
    val label: String = "",
    val icon: String? = null,
    val action: String = "open_panel"
)

// ---- 领域模型 ↔ DTO ----

internal fun PluginInfo.toRegistryPlugin(): RegistryPlugin = RegistryPlugin(
    id = id,
    name = name,
    description = description,
    versionName = versionName,
    path = path,
    type = type,
    enabled = enabled,
    installTime = installTime,
    source = source.name,
    minHostVersion = minHostVersion,
    maxHostVersion = maxHostVersion,
    entryScript = entryScript,
    declaredHosts = declaredHosts,
    allowCustomHosts = allowCustomHosts,
    toolbarButtons = toolbarButtons.map {
        RegistryToolbarButton(id = it.id, label = it.label, icon = it.icon, action = it.action)
    },
    manifestIcon = manifestIcon,
    capabilities = capabilities?.toJson(),
    platforms = platforms
)

internal fun RegistryPlugin.toPluginInfo(): PluginInfo {
    val pluginSource = runCatching { PluginSource.valueOf(source) }.getOrDefault(PluginSource.SYSTEM)
    return PluginInfo(
        id = id,
        name = name,
        iconResId = 0,
        description = description,
        versionCode = 0L,
        versionName = versionName,
        path = path,
        type = type,
        enabled = enabled,
        installTime = installTime,
        source = pluginSource,
        minHostVersion = minHostVersion,
        maxHostVersion = maxHostVersion,
        trustLevel = PluginSignatureUtil.classifyScriptPlugin(pluginSource),
        entryScript = entryScript,
        declaredHosts = declaredHosts,
        allowCustomHosts = allowCustomHosts,
        toolbarButtons = toolbarButtons.map {
            PluginToolbarButton(id = it.id, label = it.label, icon = it.icon, action = it.action)
        },
        manifestIcon = manifestIcon,
        capabilities = capabilities?.toPluginCapabilities(),
        // 旧版 plugins.json 条目无 platforms 字段：归一化为 android（与缺省声明一致）
        platforms = platforms.ifEmpty { listOf(PluginInfo.PLATFORM_ANDROID) }
    )
}

// ---- 能力声明 ↔ JSON（key 与 manifest 契约一致） ----

internal fun PluginCapabilities.toJson(): JsonObject = buildJsonObject {
    emoji?.let { e ->
        put(
            "emoji",
            buildJsonObject {
                put("supportsSearch", e.supportsSearch)
                e.columns?.let { put("columns", it) }
                e.itemHeightDp?.let { put("itemHeightDp", it) }
            }
        )
    }
    speech?.let { s ->
        put(
            "speech",
            buildJsonObject {
                put("inputMode", s.inputMode)
                put("supportsPartialResults", s.supportsPartialResults)
                put("requiresNetwork", s.requiresNetwork)
            }
        )
    }
    tool?.display?.let { display ->
        put("tool", buildJsonObject { put("display", display.name) })
    }
    clipboardSync?.let { c ->
        put(
            "clipboard_sync",
            buildJsonObject {
                put("protocols", buildJsonArray { c.protocols.forEach { add(JsonPrimitive(it)) } })
                if (c.attachments) put("attachments", true)
            }
        )
    }
    backup?.let { b ->
        put(
            "backup",
            buildJsonObject {
                put("protocols", buildJsonArray { b.protocols.forEach { add(JsonPrimitive(it)) } })
            }
        )
    }
    if (events.isNotEmpty()) {
        put("events", buildJsonArray { events.forEach { add(JsonPrimitive(it)) } })
    }
    if (candidateTransform) put("candidate_transform", true)
    if (quickSendRead) put("quick_send_read", true)
    if (clipboardRead) put("clipboard_read", true)
}

private fun JsonObject.optObject(key: String): JsonObject? = this[key] as? JsonObject

private fun JsonObject.optString(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

private fun JsonObject.optBool(key: String): Boolean = (this[key] as? JsonPrimitive)?.booleanOrNull ?: false

private fun JsonObject.optBoolOrNull(key: String): Boolean? = (this[key] as? JsonPrimitive)?.booleanOrNull

private fun JsonObject.optInt(key: String): Int? = (this[key] as? JsonPrimitive)?.intOrNull

private fun JsonObject.optStrings(key: String): List<String> =
    (this[key] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull } ?: emptyList()

internal fun JsonObject.toPluginCapabilities(): PluginCapabilities {
    return PluginCapabilities(
        emoji = optObject("emoji")?.let { e ->
            PluginCapabilities.EmojiCapabilities(
                supportsSearch = e.optBool("supportsSearch"),
                columns = e.optInt("columns")?.takeIf { it > 0 },
                itemHeightDp = e.optInt("itemHeightDp")?.takeIf { it > 0 }
            )
        },
        speech = optObject("speech")?.let { s ->
            PluginCapabilities.SpeechCapabilities(
                inputMode = s.optString("inputMode") ?: "streaming",
                supportsPartialResults = s.optBoolOrNull("supportsPartialResults") ?: true,
                requiresNetwork = s.optBoolOrNull("requiresNetwork") ?: true
            )
        },
        tool = optObject("tool")?.let { t ->
            PluginCapabilities.ToolCapabilities(
                display = t.optString("display")?.let { s ->
                    // 旧契约 "select"（全屏结果页）已并入 passive（InfoPanel 内 items 点选上屏）
                    if (s.equals("select", ignoreCase = true)) {
                        ToolResult.PASSIVE
                    } else {
                        ToolResult.entries.firstOrNull { it.name.equals(s, ignoreCase = true) }
                    }
                }
            )
        },
        clipboardSync = optObject("clipboard_sync")?.let { c ->
            PluginCapabilities.ClipboardSyncCapabilities(
                protocols = c.optStrings("protocols").filter { it.isNotBlank() },
                attachments = c.optBool("attachments")
            )
        },
        backup = optObject("backup")?.let { b ->
            PluginCapabilities.BackupCapabilities(
                protocols = b.optStrings("protocols").filter { it.isNotBlank() }
            )
        },
        events = optStrings("events").map(String::trim).filter(String::isNotBlank).distinct(),
        candidateTransform = optBool("candidate_transform"),
        quickSendRead = optBool("quick_send_read"),
        clipboardRead = optBool("clipboard_read")
    )
}