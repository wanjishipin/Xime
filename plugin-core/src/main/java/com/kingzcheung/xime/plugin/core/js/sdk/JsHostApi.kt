package com.kingzcheung.xime.plugin.core.js.sdk

import com.kingzcheung.xime.plugin.core.config.PluginConfigStore
import java.io.File

/**
 * 宿主注入给 JS 插件的白名单 API。
 *
 * 这是 JS 插件能够触及宿主能力的**唯一入口**：QuickJS 沙箱没有文件/网络/进程能力，
 * 插件只能调用这里暴露的函数。新增能力必须在此接口中显式声明（安全审计点）。
 *
 * 命名遵循 JavaScript 生态惯例（camelCase），注入形态见 [JsPluginContract]。
 */
interface JsHostApi {

    /** SDK 版本号，插件可用 host.sdkVersion 查询。 */
    val sdkVersion: String

    /** 输出日志（转到宿主 Logcat，tag 含插件 id）。 */
    fun log(message: String)

    /** 输出错误日志（Log.e 级别，关键失败用，避免被日志配额丢弃）。 */
    fun logError(message: String)

    /** 读取插件配置（不存在返回 null）。 */
    fun configGet(key: String): String?

    /** 写入插件配置。 */
    fun configSet(key: String, value: String)

    /** 删除插件配置。 */
    fun configRemove(key: String)

    /** 列出已存在的配置 key。 */
    fun configKeys(): Set<String>

    /**
     * 返回插件包 resources/ 下资源的绝对路径。
     * 插件只能拿到路径字符串，**无法读取文件内容**（图片渲染由宿主完成）。
     * 路径不存在返回 null。
     */
    fun resourcePath(name: String): String?

    /** 列出插件包 resources/ 下指定目录的文件名（不含子目录）。目录不存在返回空列表。 */
    fun resourceList(dir: String): List<String>

    /** 生成唯一 id（用于 ASR task_id 等）。 */
    fun uuid(): String
}

/**
 * 默认宿主 API 实现。
 *
 * @param pluginDir 插件解压目录（resources/ 在其下）
 * @param configStore 插件独立配置存储
 */
class JsHostApiImpl(
    private val pluginId: String,
    private val pluginDir: File,
    private val configStore: PluginConfigStore
) : JsHostApi {

    override val sdkVersion: String = JsPluginContract.SDK_VERSION

    override fun log(message: String) {
        android.util.Log.d("JsPlugin", "[$pluginId] $message")
        // 调试落盘通道（仅 app debug 构建注册；release Noop）——规避 ROM 后台日志限流
        PluginDevConsole.log(pluginId, "log", message)
    }

    override fun logError(message: String) {
        android.util.Log.e("JsPlugin", "[$pluginId] $message")
        PluginDevConsole.log(pluginId, "error", message)
        com.kingzcheung.xime.plugin.core.security.PluginErrorLog.logError(
            pluginId = pluginId,
            operation = "插件运行错误",
            message = message
        )
    }

    override fun configGet(key: String): String? = configStore.get(key)

    override fun configSet(key: String, value: String) {
        configStore.set(key, value)
    }

    override fun configRemove(key: String) {
        configStore.remove(key)
    }

    override fun configKeys(): Set<String> = configStore.keys()

    override fun resourcePath(name: String): String? {
        if (!com.kingzcheung.xime.plugin.core.runtime.installer.InstallerManager.isValidResourcePath(name)) {
            return null
        }
        val file = File(pluginDir, "resources/$name")
        return if (file.exists()) file.absolutePath else null
    }

    override fun resourceList(dir: String): List<String> {
        if (!com.kingzcheung.xime.plugin.core.runtime.installer.InstallerManager.isValidResourcePath(dir)) {
            return emptyList()
        }
        val dirFile = File(pluginDir, "resources/$dir")
        if (!dirFile.isDirectory) return emptyList()
        return dirFile.listFiles()
            ?.filter { it.isFile }
            ?.map { it.name }
            ?.sorted()
            ?: emptyList()
    }

    override fun uuid(): String {
        return java.util.UUID.randomUUID().toString()
    }
}