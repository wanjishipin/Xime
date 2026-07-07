package com.kingzcheung.xime.settings

import android.content.Context
import android.util.Log
import com.charleskorn.kaml.Yaml
import com.charleskorn.kaml.YamlException
import com.charleskorn.kaml.YamlConfiguration
import com.charleskorn.kaml.YamlMap
import com.charleskorn.kaml.YamlNode
import com.charleskorn.kaml.YamlScalar
import com.charleskorn.kaml.YamlList
import com.kingzcheung.xime.BuildConfig
import com.kingzcheung.xime.keyboard.GestureAction
import androidx.compose.runtime.mutableStateOf
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader

// ── 键盘手势配置 ──

enum class DisplayMode(val value: String) {
    KEY("key"), BUBBLE("bubble"), BOTH("both");

    companion object {
        fun fromValue(value: String): DisplayMode = entries.firstOrNull { it.value == value } ?: BOTH
    }
}

/** 按键布局模式，从 xime.yaml keyboard.button_layout 加载。 */
enum class ButtonLayout(val value: String) {
    STANDARD("standard"),
    COMPACT("compact");

    companion object {
        fun fromValue(value: String): ButtonLayout =
            entries.firstOrNull { it.value == value } ?: STANDARD
    }
}

data class GestureDef(
    val label: String = "",
    /** 多行标签（YAML 数组格式），每行作为独立字符串，用于多行显示 */
    val labels: List<String> = emptyList(),
    val action: GestureAction? = GestureAction.COMMIT,
    val value: String = "",
    val display: DisplayMode = DisplayMode.BOTH,
)

data class LongPressConfig(
    val display: String = "key", // "key"（默认）显示在按键上, "bubble" 气泡弹出
    val values: List<GestureDef> = emptyList(),
)

data class KeyGestureConfig(
    val tap: GestureDef? = null,
    val swipeUp: GestureDef? = null,
    val swipeDown: GestureDef? = null,
    val longPress: LongPressConfig? = null,
)

data class KeyboardConfig(
    val keys: Map<String, KeyGestureConfig> = emptyMap(),
)

/**
 * 键盘阴影配置，从 xime.yaml keyboard.shadow 加载。
 */
data class KeyboardShadowConfig(
    val enabled: Boolean = true,
    val elevation: Int = 1,
    val shapeRadius: Int = 8,
)

/**
 * 键盘按键配置，从 xime.yaml keyboard.key 加载。
 */
data class KeyboardKeyConfig(
    val cornerRadius: Int = 8,
)

/**
 * 键盘颜色配置，从 xime.yaml keyboard.colors 加载。
 * 所有颜色值为 0xRRGGBB 格式（不含 alpha）。
 */
data class KeyboardColorsConfig(
    val keyboardBgColor: Long = 0xE3E4E8,
    val keyboardBgColorDark: Long = 0x202020,
    val keyBgColor: Long = 0xFFFFFF,
    val keyBgColorDark: Long = 0x4A4A4A,
    val specialKeyBgColor: Long? = null,
    val specialKeyBgColorDark: Long? = null,
    val candidateBarBgColor: Long = 0xE3E4E8,
    val candidateBarBgColorDark: Long = 0x202020,
    val keyTextColor: Long = 0x202124,
    val keyTextColorDark: Long = 0xE8EAED,
    val candidateTextColor: Long = 0x1A73E8,
    val candidateTextColorDark: Long = 0x8AB4F8,
)

/**
 * 从 YAML node 解析 KeyboardConfig。
 *
 * 支持两种格式：
 * - 字符串 `"q"` → 等价于 GestureDef(label="q", action="commit", value="q")
 * - 对象 `{ label: "复制", action: "copy" }` → 完整定义
 */
private fun parseKeyboardConfig(raw: com.charleskorn.kaml.YamlMap?): KeyboardConfig? {
    if (raw == null) return null
    val keysNode = raw["keys"] as? com.charleskorn.kaml.YamlMap ?: return KeyboardConfig()
    val keys = mutableMapOf<String, KeyGestureConfig>()
    for ((keyNode, valueNode) in keysNode.entries) {
        val key = (keyNode as? com.charleskorn.kaml.YamlScalar)?.content ?: continue
        val gestureMap = valueNode as? com.charleskorn.kaml.YamlMap ?: continue
        keys[key] = parseKeyGestureConfig(gestureMap)
    }
    return KeyboardConfig(keys)
}

private fun parseKeyGestureConfig(map: com.charleskorn.kaml.YamlMap): KeyGestureConfig {
    var tap: GestureDef? = null
    var swipeUp: GestureDef? = null
    var swipeDown: GestureDef? = null
    var longPress: LongPressConfig? = null
    for ((kNode, vNode) in map.entries) {
        val name = (kNode as? com.charleskorn.kaml.YamlScalar)?.content ?: continue
        when (name) {
            "tap" -> tap = parseGestureNode(vNode)
            "swipe_up" -> swipeUp = parseGestureNode(vNode)
            "swipe_down" -> swipeDown = parseGestureNode(vNode)
            "long_press" -> longPress = parseLongPress(vNode)
        }
    }
    return KeyGestureConfig(tap, swipeUp, swipeDown, longPress)
}

/**
 * 解析 long_press，支持两种格式：
 *   新格式（推荐）：{ display: "bubble", values: ["q", "Q"] }
 *   旧格式（兼容）：["q", "Q"]
 */
private fun parseLongPress(node: com.charleskorn.kaml.YamlNode): LongPressConfig? {
    // 旧格式：纯数组 → 默认 display="key"
    if (node is YamlList) {
        val values = node.items.map { parseGestureNode(it) }.take(10)
        return LongPressConfig(display = "key", values = values)
    }
    // 新格式：对象 { display, values }
    if (node is com.charleskorn.kaml.YamlMap) {
        var display = "key"
        var values: List<GestureDef> = emptyList()
        for ((k, v) in node.entries) {
            val key = (k as? com.charleskorn.kaml.YamlScalar)?.content ?: continue
            when (key) {
                "display" -> display = (v as? com.charleskorn.kaml.YamlScalar)?.content ?: "key"
                "values" -> if (v is YamlList) values = v.items.map { parseGestureNode(it) }.take(10)
            }
        }
        return LongPressConfig(display = display, values = values)
    }
    return null
}

private fun parseGestureNode(node: com.charleskorn.kaml.YamlNode): GestureDef {
    // 字符串 → commit
    if (node is com.charleskorn.kaml.YamlScalar) {
        val text = node.content
        return GestureDef(label = text, action = GestureAction.COMMIT, value = text)
    }
    // 映射 → 完整定义
    if (node is com.charleskorn.kaml.YamlMap) {
        var label = ""
        var labels: List<String> = emptyList()
        var action: GestureAction? = GestureAction.COMMIT
        var value = ""
        var display = "key"
        for ((k, v) in node.entries) {
            val key = (k as? com.charleskorn.kaml.YamlScalar)?.content ?: continue
            when (key) {
                "label" -> {
                    if (v is YamlList) {
                        labels = v.items.mapNotNull { (it as? YamlScalar)?.content }
                        label = labels.joinToString("\n")
                    } else {
                        val vStr = (v as? YamlScalar)?.content ?: continue
                        label = vStr
                    }
                }
                "action" -> {
                    val vStr = (v as? YamlScalar)?.content ?: continue
                    action = if (vStr == "null") null else GestureAction.fromValue(vStr)
                }
                "value" -> {
                    val vStr = (v as? YamlScalar)?.content ?: continue
                    value = vStr
                }
                "display" -> {
                    val vStr = (v as? YamlScalar)?.content ?: continue
                    display = vStr
                }
            }
        }
        return GestureDef(label = label, labels = labels, action = action, value = value, display = DisplayMode.fromValue(display))
    }
    return GestureDef()
}

// ── 原有配置类 ──

@Serializable
data class ColorSchemeEntry(
    val name: String = "",
    @SerialName("primary_color")
    val primaryColor: Long = 0,
    @SerialName("keyboard_bg_color")
    val keyboardBgColor: Long? = null,
    @SerialName("key_bg_color")
    val keyBgColor: Long? = null,
    @SerialName("special_key_bg_color")
    val specialKeyBgColor: Long? = null,
    @SerialName("candidate_bar_bg_color")
    val candidateBarBgColor: Long? = null,
    @SerialName("key_text_color")
    val keyTextColor: Long? = null,
    @SerialName("candidate_text_color")
    val candidateTextColor: Long? = null,
)

@Serializable
data class MetadataConfig(
    @SerialName("app_name")
    val appName: String = "Xime",
    @SerialName("app_version")
    val appVersion: String = "",
    @SerialName("platform")
    val platform: String = "android",
    @SerialName("config_version")
    val configVersion: Int = 1,
    @SerialName("generator")
    val generator: String = "",
    @SerialName("modified_time")
    val modifiedTime: String = "",
)

@Serializable
data class StyleConfig(
    @SerialName("color_scheme")
    val colorScheme: String? = null,
)

@Serializable
data class XimeConfig(
    @SerialName("xime_index")
    val ximeIndex: XimeIndexConfig? = null,
    @SerialName("color_schemes")
    val colorSchemes: Map<String, ColorSchemeEntry>? = null,
    @SerialName("style")
    val style: StyleConfig? = null,
    @SerialName("metadata")
    val metadata: MetadataConfig? = null,
)

@Serializable
data class XimeIndexConfig(
    @SerialName("base_urls")
    val baseUrls: List<String> = listOf("https://index.ximei.me/")
)

data class KeysConfig(
    val swipeUp: Map<String, String> = emptyMap(),
    val swipeDownEnglish: Map<String, String> = emptyMap()
)

object KeysConfigHelper {
    private const val TAG = "KeysConfigHelper"
    private const val XIME_CONFIG_FILE = "xime.yaml"
    private const val XIME_CUSTOM_CONFIG_FILE = "xime.custom.yaml"
    
    private val yaml = Yaml(configuration = YamlConfiguration(strictMode = false))
    
    private var config: KeysConfig = KeysConfig(
        swipeUp = getDefaultSwipeUp(),
        swipeDownEnglish = getDefaultSwipeDownEnglish()
    )

    // 手势配置缓存（mutableStateOf 让 Compose 直接观察变更）
    // 中文键盘（qwerty）手势配置缓存
    private val _keyGestureConfig = mutableStateOf<Map<String, KeyGestureConfig>>(emptyMap())
    val keyGestureConfig: Map<String, KeyGestureConfig> get() = _keyGestureConfig.value
    
    // 英文键盘（qwerty_en）手势配置缓存
    private val _keyGestureConfigEn = mutableStateOf<Map<String, KeyGestureConfig>>(emptyMap())
    val keyGestureConfigEn: Map<String, KeyGestureConfig> get() = _keyGestureConfigEn.value
    
    // 键盘颜色配置缓存
    private var keyboardColorsConfig: KeyboardColorsConfig = KeyboardColorsConfig()
    
    // 键盘阴影配置缓存
    private var keyboardShadowConfig: KeyboardShadowConfig = KeyboardShadowConfig()

    // 键盘按键配置缓存
    private var keyboardKeyConfig: KeyboardKeyConfig = KeyboardKeyConfig()
    
    // 按键布局模式缓存（中文 qwerty / 英文 qwerty_en）
    private var _buttonLayoutZh: ButtonLayout = ButtonLayout.STANDARD
    private var _buttonLayoutEn: ButtonLayout = ButtonLayout.STANDARD
    fun getButtonLayout(isAsciiMode: Boolean): ButtonLayout =
        if (isAsciiMode) _buttonLayoutEn else _buttonLayoutZh
    
    /** 配置版本号，每次 loadConfig 时递增，用于 Compose 感知配置变更。 */
    private val _configVersion = MutableStateFlow(0)
    val configVersion: StateFlow<Int> = _configVersion.asStateFlow()
    
    private var mergedConfigCache: XimeConfig? = null
    private var mergedConfigVersion = 0
    
    fun loadConfig(context: Context): KeysConfig {
        loadXimeConfig(context)
        config = config.copy(
            swipeUp = getDefaultSwipeUp(),
            swipeDownEnglish = getDefaultSwipeDownEnglish()
        )
        return config
    }
    
    private fun loadXimeConfig(context: Context) {
        try {
            // 键盘手势（从原始 YAML 手动解析）
            val parsed = parseKeyboardFromAssets(context)
            _keyGestureConfig.value = parsed?.first ?: emptyMap()
            _keyGestureConfigEn.value = parsed?.second ?: emptyMap()
            // 键盘颜色（从原始 YAML 手动解析）
            keyboardColorsConfig = parseKeyboardColorsFromAssets(context)
            // 键盘阴影（从原始 YAML 手动解析）
            keyboardShadowConfig = parseKeyboardShadowFromAssets(context)
            // 键盘按键（从原始 YAML 手动解析）
            keyboardKeyConfig = parseKeyboardKeyFromAssets(context)
            // 按键布局（从原始 YAML 手动解析，中英文分开）
            val parsedLayouts = parseButtonLayoutFromAssets(context)
            _buttonLayoutZh = parsedLayouts.first
            _buttonLayoutEn = parsedLayouts.second
            // 校验配置版本兼容性
            val merged = try { loadMergedConfig(context) } catch (_: YamlException) { null }
            val meta = merged?.metadata
            if (meta != null && meta.appVersion.isNotBlank()) {
                if (!checkVersionConstraint(BuildConfig.VERSION_NAME, meta.appVersion)) {
                    Log.w(TAG, "Config requires app_version ${meta.appVersion}, current is ${BuildConfig.VERSION_NAME}")
                }
            }
            _configVersion.value++
            Log.d(TAG, "Loaded config: ${keyGestureConfig.size} keys (v${_configVersion.value})")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to load xime config", e)
        }
    }

    private fun checkVersionConstraint(current: String, constraint: String): Boolean {
        val operator = when {
            constraint.startsWith(">=") -> constraint.substring(0, 2)
            constraint.startsWith("<=") -> constraint.substring(0, 2)
            constraint.startsWith(">") -> constraint.substring(0, 1)
            constraint.startsWith("<") -> constraint.substring(0, 1)
            constraint.startsWith("^") -> constraint.substring(0, 1)
            constraint.startsWith("~") -> constraint.substring(0, 1)
            else -> return true
        }
        val targetVerStr = constraint.removePrefix(operator).trim('"', '\'', ' ')
        val targetParts = targetVerStr.split('.').mapNotNull { it.toIntOrNull() }
        val currentParts = current.split('.').mapNotNull { it.toIntOrNull() }
        if (targetParts.size < 2 || currentParts.size < 2) return true
        val t = Triple(targetParts.getOrElse(0) { 0 }, targetParts.getOrElse(1) { 0 }, targetParts.getOrElse(2) { 0 })
        val c = Triple(currentParts[0], currentParts.getOrElse(1) { 0 }, currentParts.getOrElse(2) { 0 })
        val cmp = compareVersions(c, t)
        return when (operator) {
            ">=" -> cmp >= 0
            "<=" -> cmp <= 0
            ">"  -> cmp > 0
            "<"  -> cmp < 0
            "^"  -> c.first == t.first && (c.first != 0 || cmp >= 0)
            "~"  -> c.first == t.first && c.second >= t.second
            else -> true
        }
    }

    private fun compareVersions(a: Triple<Int, Int, Int>, b: Triple<Int, Int, Int>): Int {
        return when {
            a.first != b.first -> a.first.compareTo(b.first)
            a.second != b.second -> a.second.compareTo(b.second)
            else -> a.third.compareTo(b.third)
        }
    }

    /** 从 xime.yaml + xime.custom.yaml 合并解析键盘手势配置。 */
    private fun parseKeyboardFromAssets(context: Context): Pair<Map<String, KeyGestureConfig>, Map<String, KeyGestureConfig>>? {
        val defaultText = readAssetText(context, XIME_CONFIG_FILE) ?: return null
        val defaultZh = parseKeyboardYamlSection(defaultText, "qwerty") ?: return null
        val defaultEn = parseKeyboardYamlSection(defaultText, "qwerty_en") ?: emptyMap()
        // 支持两种来源：files/rime/（浏览器导入）或 assets/（内置），自动 fallback
        val userData = readUserDataText(context, XIME_CUSTOM_CONFIG_FILE)
        val customZh: Map<String, KeyGestureConfig>?
        val customEn: Map<String, KeyGestureConfig>?
        if (userData != null) {
            customZh = parseKeyboardYamlSection(userData, "qwerty")
            customEn = parseKeyboardYamlSection(userData, "qwerty_en")
        } else {
            val assetText = readAssetText(context, XIME_CUSTOM_CONFIG_FILE)
            customZh = assetText?.let { parseKeyboardYamlSection(it, "qwerty") }
            customEn = assetText?.let { parseKeyboardYamlSection(it, "qwerty_en") }
        }
        val zh = if (customZh != null) defaultZh + customZh else defaultZh
        val en = if (customEn != null) defaultEn + customEn else defaultEn
        Log.d(TAG, "parseKeyboardFromAssets: zh=${zh.size}keys, en=${en.size}keys")
        return Pair(zh, en)
    }

    /** 从 xime.yaml + xime.custom.yaml 合并解析键盘颜色配置。 */
    private fun parseKeyboardColorsFromAssets(context: Context): KeyboardColorsConfig {
        val defaultText = readAssetText(context, XIME_CONFIG_FILE) ?: return KeyboardColorsConfig()
        val default = parseKeyboardColorsYamlText(defaultText) ?: return KeyboardColorsConfig()
        val custom = readUserDataText(context, XIME_CUSTOM_CONFIG_FILE)
            ?.let { parseKeyboardColorsYamlText(it) }
            ?: readAssetText(context, XIME_CUSTOM_CONFIG_FILE)
                ?.let { parseKeyboardColorsYamlText(it) }
        return custom ?: default
    }

    /** 从 YAML 文本中提取 keyboard.colors 段。 */
    private fun parseKeyboardColorsYamlText(yamlText: String): KeyboardColorsConfig? {
        val root = yaml.parseToYamlNode(yamlText) as? YamlMap ?: return null
        val keyboardNode = root["keyboard"] as? YamlMap ?: return null
        val colorsNode = keyboardNode["colors"] as? YamlMap ?: return null
        var kbBg = 0xE3E4E8L
        var kbBgDark = 0x202020L
        var kBg = 0xFFFFFFL
        var kBgDark = 0x4A4A4AL
        var spKeyBg: Long? = null
        var spKeyBgDark: Long? = null
        var candBg = 0xE3E4E8L
        var candBgDark = 0x202020L
        var kTxt = 0x202124L
        var kTxtDark = 0xE8EAEDL
        var candTxt = 0x1A73E8L
        var candTxtDark = 0x8AB4F8L
        for ((kNode, vNode) in colorsNode.entries) {
            val key = (kNode as? YamlScalar)?.content ?: continue
            val value = (vNode as? YamlScalar)?.content ?: continue
            val hex = value.removePrefix("0x").toLongOrNull(16) ?: continue
            when (key) {
                "keyboard_bg_color" -> kbBg = hex
                "keyboard_bg_color_dark" -> kbBgDark = hex
                "key_bg_color" -> kBg = hex
                "key_bg_color_dark" -> kBgDark = hex
                "special_key_bg_color" -> spKeyBg = hex
                "special_key_bg_color_dark" -> spKeyBgDark = hex
                "candidate_bar_bg_color" -> candBg = hex
                "candidate_bar_bg_color_dark" -> candBgDark = hex
                "key_text_color" -> kTxt = hex
                "key_text_color_dark" -> kTxtDark = hex
                "candidate_text_color" -> candTxt = hex
                "candidate_text_color_dark" -> candTxtDark = hex
            }
        }
        return KeyboardColorsConfig(
            keyboardBgColor = kbBg,
            keyboardBgColorDark = kbBgDark,
            keyBgColor = kBg,
            keyBgColorDark = kBgDark,
            specialKeyBgColor = spKeyBg,
            specialKeyBgColorDark = spKeyBgDark,
            candidateBarBgColor = candBg,
            candidateBarBgColorDark = candBgDark,
            keyTextColor = kTxt,
            keyTextColorDark = kTxtDark,
            candidateTextColor = candTxt,
            candidateTextColorDark = candTxtDark,
        )
    }

    /** 从 xime.yaml + xime.custom.yaml 合并解析键盘阴影配置。 */
    private fun parseKeyboardShadowFromAssets(context: Context): KeyboardShadowConfig {
        val defaultText = readAssetText(context, XIME_CONFIG_FILE) ?: return KeyboardShadowConfig()
        val default = parseKeyboardShadowYamlText(defaultText) ?: return KeyboardShadowConfig()
        val custom = readUserDataText(context, XIME_CUSTOM_CONFIG_FILE)
            ?.let { parseKeyboardShadowYamlText(it) }
            ?: readAssetText(context, XIME_CUSTOM_CONFIG_FILE)
                ?.let { parseKeyboardShadowYamlText(it) }
        return custom ?: default
    }

    /** 从 YAML 文本中提取 keyboard.shadow 段。 */
    private fun parseKeyboardShadowYamlText(yamlText: String): KeyboardShadowConfig? {
        val root = yaml.parseToYamlNode(yamlText) as? YamlMap ?: return null
        val keyboardNode = root["keyboard"] as? YamlMap ?: return null
        val shadowNode = keyboardNode["shadow"] as? YamlMap ?: return null
        var enabled = true
        var elevation = 1
        var shapeRadius = 8
        for ((kNode, vNode) in shadowNode.entries) {
            val key = (kNode as? YamlScalar)?.content ?: continue
            val value = (vNode as? YamlScalar)?.content ?: continue
            when (key) {
                "enabled" -> enabled = value.toBooleanStrictOrNull() ?: true
                "elevation" -> elevation = value.toIntOrNull() ?: 1
                "shape_radius" -> shapeRadius = value.toIntOrNull() ?: 8
            }
        }
        return KeyboardShadowConfig(enabled = enabled, elevation = elevation, shapeRadius = shapeRadius)
    }

    /** 从 xime.yaml + xime.custom.yaml 合并解析键盘按键配置。 */
    private fun parseKeyboardKeyFromAssets(context: Context): KeyboardKeyConfig {
        val defaultText = readAssetText(context, XIME_CONFIG_FILE) ?: return KeyboardKeyConfig()
        val default = parseKeyboardKeyYamlText(defaultText) ?: return KeyboardKeyConfig()
        val custom = readUserDataText(context, XIME_CUSTOM_CONFIG_FILE)
            ?.let { parseKeyboardKeyYamlText(it) }
            ?: readAssetText(context, XIME_CUSTOM_CONFIG_FILE)
                ?.let { parseKeyboardKeyYamlText(it) }
        return custom ?: default
    }

    /** 从 YAML 文本中提取 keyboard.key 段。
     *  兼容旧版：若 key.corner_radius 未设置，回退读取 shadow.shape_radius。 */
    private fun parseKeyboardKeyYamlText(yamlText: String): KeyboardKeyConfig? {
        val root = yaml.parseToYamlNode(yamlText) as? YamlMap ?: return null
        val keyboardNode = root["keyboard"] as? YamlMap ?: return null
        var cornerRadius = 8
        // 优先读取 key.corner_radius
        val keyNode = keyboardNode["key"] as? YamlMap
        if (keyNode != null) {
            for ((kNode, vNode) in keyNode.entries) {
                val key = (kNode as? YamlScalar)?.content ?: continue
                val value = (vNode as? YamlScalar)?.content ?: continue
                if (key == "corner_radius") {
                    cornerRadius = value.toIntOrNull() ?: 8
                }
            }
        }
        // 未设置 key.corner_radius 时，回退读取 shadow.shape_radius
        if (cornerRadius == 8) {
            val shadowNode = keyboardNode["shadow"] as? YamlMap
            if (shadowNode != null) {
                for ((kNode, vNode) in shadowNode.entries) {
                    val key = (kNode as? YamlScalar)?.content ?: continue
                    val value = (vNode as? YamlScalar)?.content ?: continue
                    if (key == "shape_radius") {
                        cornerRadius = value.toIntOrNull() ?: 8
                    }
                }
            }
        }
        return KeyboardKeyConfig(cornerRadius = cornerRadius)
    }

    /** 从 xime.yaml + xime.custom.yaml 合并解析按键布局模式（中英文分开）。 */
    private fun parseButtonLayoutFromAssets(context: Context): Pair<ButtonLayout, ButtonLayout> {
        val defaultText = readAssetText(context, XIME_CONFIG_FILE) ?: return Pair(ButtonLayout.STANDARD, ButtonLayout.STANDARD)
        val defaultZh = parseButtonLayoutYamlText(defaultText, "qwerty")
        val defaultEn = parseButtonLayoutYamlText(defaultText, "qwerty_en")
        Log.d(TAG, "parseButtonLayout: defaultZh=$defaultZh, defaultEn=$defaultEn")
        val customText = readUserDataText(context, XIME_CUSTOM_CONFIG_FILE)
            ?: readAssetText(context, XIME_CUSTOM_CONFIG_FILE)
        Log.d(TAG, "parseButtonLayout: customText exists=${customText != null}, length=${customText?.length ?: 0}")
        val customZh = customText?.let { parseButtonLayoutYamlText(it, "qwerty") }
        val customEn = customText?.let { parseButtonLayoutYamlText(it, "qwerty_en") }
        Log.d(TAG, "parseButtonLayout: customZh=$customZh, customEn=$customEn")
        val result = Pair(
            customZh ?: defaultZh ?: ButtonLayout.STANDARD,
            customEn ?: defaultEn ?: ButtonLayout.STANDARD,
        )
        Log.d(TAG, "parseButtonLayout: result zh=${result.first}, en=${result.second}")
        return result
    }

    /** 从 YAML 文本中提取 keyboard.<section>.button_layout。 */
    private fun parseButtonLayoutYamlText(yamlText: String, section: String): ButtonLayout? {
        val root = yaml.parseToYamlNode(yamlText) as? YamlMap ?: return null
        val keyboardNode = root["keyboard"] as? YamlMap ?: return null
        val sectionNode = keyboardNode[section] as? YamlMap ?: return null
        val layoutNode = sectionNode["button_layout"] as? YamlScalar ?: return null
        return ButtonLayout.fromValue(layoutNode.content)
    }

    /** 从 YAML 文本中提取 keyboard.<section>.keys 段。 */
    private fun parseKeyboardYamlSection(yamlText: String, section: String): Map<String, KeyGestureConfig>? {
        val root = yaml.parseToYamlNode(yamlText) as? YamlMap ?: return null
        val keyboardNode = root["keyboard"] as? YamlMap ?: return null
        val sectionNode = keyboardNode[section] as? YamlMap ?: return null
        val keysNode = sectionNode["keys"] as? YamlMap ?: return null
        val result = mutableMapOf<String, KeyGestureConfig>()
        for ((kNode, vNode) in keysNode.entries) {
            val key = (kNode as? YamlScalar)?.content ?: continue
            val gestureMap = vNode as? YamlMap ?: continue
            result[key] = parseKeyGestureConfig(gestureMap)
        }
        Log.d(TAG, "parseKeyboardYamlSection($section): keys=${result.size} [${result.keys.joinToString("")}]")
        return result
    }

    private fun loadMergedConfig(context: Context): XimeConfig {
        val currentVersion = _configVersion.value
        if (mergedConfigCache != null && mergedConfigVersion == currentVersion) {
            return mergedConfigCache!!
        }
        val default = parseConfig(readAssetText(context, XIME_CONFIG_FILE))
        val custom = readUserDataText(context, XIME_CUSTOM_CONFIG_FILE)
            ?.let { parseConfig(it) }
            ?: readAssetText(context, XIME_CUSTOM_CONFIG_FILE)
                ?.let { parseConfig(it) }
        Log.d(TAG, "loadMergedConfig: custom=${custom != null}")
        val config = mergeConfig(default, custom)
        mergedConfigCache = config
        mergedConfigVersion = currentVersion
        return config
    }

    private fun parseConfig(content: String?): XimeConfig? {
        if (content == null) return null
        return try {
            yaml.decodeFromString(XimeConfig.serializer(), content)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse xime config", e)
            null
        }
    }

    private fun mergeConfig(default: XimeConfig?, custom: XimeConfig?): XimeConfig {
        if (custom == null) return default ?: XimeConfig()
        if (default == null) return custom
        return XimeConfig(
            ximeIndex = custom.ximeIndex ?: default.ximeIndex,
            colorSchemes = custom.colorSchemes ?: default.colorSchemes,
            style = custom.style ?: default.style,
            metadata = custom.metadata ?: default.metadata,
        )
    }

    private fun readAssetText(context: Context, fileName: String): String? {
        return try {
            val inputStream = context.assets.open(fileName)
            val reader = BufferedReader(InputStreamReader(inputStream))
            val content = reader.readText()
            reader.close()
            inputStream.close()
            content
        } catch (e: Exception) {
            null
        }
    }

    /** 从用户数据目录 (context.filesDir/rime/) 读取文件。 */
    private fun readUserDataText(context: Context, fileName: String): String? {
        val file = File(context.filesDir, "rime/$fileName")
        Log.d(TAG, "readUserDataText: path=${file.absolutePath}, exists=${file.exists()}")
        if (!file.exists()) return null
        return try {
            val text = file.readText().trimStart('\uFEFF')
            Log.d(TAG, "readUserDataText: read ${text.length} chars, first 80=${text.take(80)}")
            text
        } catch (e: Exception) {
            Log.w(TAG, "readUserDataText: failed", e)
            null
        }
    }

    fun loadXimeIndexConfig(context: Context): XimeIndexConfig {
        val merged = loadMergedConfig(context)
        return merged.ximeIndex ?: XimeIndexConfig()
    }

    /** 从 xime.yaml 加载 color_schemes 配置。 */
    fun loadColorSchemes(context: Context): Map<String, ColorSchemeEntry> {
        val merged = loadMergedConfig(context)
        return merged.colorSchemes ?: emptyMap()
    }

    /** 从 xime.yaml 加载默认主题 ID（style.color_scheme）。 */
    fun loadDefaultThemeId(context: Context): String {
        val merged = loadMergedConfig(context)
        return merged.style?.colorScheme ?: "lavender_purple"
    }

    // ── 新公开 API ──

    /** 获取键盘颜色配置（从 xime.yaml keyboard.colors 加载）。 */
    fun getKeyboardColors(): KeyboardColorsConfig = keyboardColorsConfig

    /** 获取键盘阴影配置（从 xime.yaml keyboard.shadow 加载）。 */
    fun getKeyboardShadow(): KeyboardShadowConfig = keyboardShadowConfig

    /** 获取键盘按键配置（从 xime.yaml keyboard.key 加载）。 */
    fun getKeyboardKeyConfig(): KeyboardKeyConfig = keyboardKeyConfig

    /** 获取某个按键的手势配置。 */
    fun getKeyGesture(key: String): KeyGestureConfig? = keyGestureConfig[key.lowercase()]

    /** 根据输入模式获取某个按键的手势配置。 */
    fun getKeyGesture(key: String, isAsciiMode: Boolean): KeyGestureConfig? {
        val config = if (isAsciiMode) _keyGestureConfigEn.value else _keyGestureConfig.value
        return config[key.lowercase()]
    }

    fun getKeyDisplayLabel(key: String, isAsciiMode: Boolean = false): String {
        val config = if (isAsciiMode) _keyGestureConfigEn.value else _keyGestureConfig.value
        val label = config[key.lowercase()]?.tap?.label
        if (label.isNullOrEmpty()) return key.uppercase()
        return if (label.any { it in 'a'..'z' || it in 'A'..'Z' }) label.uppercase() else label
    }

    fun getKeyCommitValue(key: String, isAsciiMode: Boolean = false): String {
        val config = if (isAsciiMode) _keyGestureConfigEn.value else _keyGestureConfig.value
        val value = config[key.lowercase()]?.tap?.value
        return value?.takeIf { it.isNotEmpty() } ?: key
    }

    /** 获取某个按键指定手势的显示标签。 */
    fun getGestureLabel(key: String, gesture: String, isAsciiMode: Boolean = false): String? {
        val config = if (isAsciiMode) _keyGestureConfigEn.value else _keyGestureConfig.value
        val kc = config[key.lowercase()] ?: return null
        return when (gesture) {
            "tap" -> kc.tap?.label
            "swipe_up" -> kc.swipeUp?.label
            "swipe_down" -> kc.swipeDown?.label
            "long_press" -> kc.longPress?.values?.firstOrNull()?.label
            else -> null
        }
    }

    // ── 旧公开 API（兼容） ──
    
    fun getConfig(): KeysConfig = config
    
    fun getSwipeUpText(key: String, isAsciiMode: Boolean = false): String? {
        val configMap = if (isAsciiMode) _keyGestureConfigEn.value else _keyGestureConfig.value
        val gesture = configMap[key.lowercase()]?.swipeUp
        if (gesture != null) {
            if (gesture.value.isNotEmpty()) return gesture.value
            if (gesture.label.isNotEmpty()) return gesture.label
        }
        return config.swipeUp[key.lowercase()]
    }

    fun getSwipeUpAction(key: String, isAsciiMode: Boolean = false): GestureAction? {
        val configMap = if (isAsciiMode) _keyGestureConfigEn.value else _keyGestureConfig.value
        return configMap[key.lowercase()]?.swipeUp?.action
    }

    /** 获取上滑显示文本（优先 label，fallback value） */
    fun getSwipeUpLabel(key: String, isAsciiMode: Boolean = false): String? {
        val configMap = if (isAsciiMode) _keyGestureConfigEn.value else _keyGestureConfig.value
        val gesture = configMap[key.lowercase()]?.swipeUp
        if (gesture != null) {
            if (gesture.label.isNotEmpty()) return gesture.label
            if (gesture.value.isNotEmpty()) return gesture.value
        }
        return config.swipeUp[key.lowercase()]
    }

    /** 获取上滑提交值（优先 value，fallback label） */
    fun getSwipeUpCommitValue(key: String, isAsciiMode: Boolean = false): String? {
        val configMap = if (isAsciiMode) _keyGestureConfigEn.value else _keyGestureConfig.value
        val gesture = configMap[key.lowercase()]?.swipeUp
        if (gesture != null) {
            if (gesture.value.isNotEmpty()) return gesture.value
            if (gesture.label.isNotEmpty()) return gesture.label
        }
        return config.swipeUp[key.lowercase()]
    }
    
    fun getSwipeDownEnglishText(key: String, isAsciiMode: Boolean = false): String? {
        val configMap = if (isAsciiMode) _keyGestureConfigEn.value else _keyGestureConfig.value
        val fromYaml = configMap[key.lowercase()]?.swipeDown?.label
        if (fromYaml != null && fromYaml.isNotEmpty()) return fromYaml
        return config.swipeDownEnglish[key.lowercase()]
    }

    /** 获取下滑动作类型 */
    fun getSwipeDownAction(key: String, isAsciiMode: Boolean = false): GestureAction? {
        val configMap = if (isAsciiMode) _keyGestureConfigEn.value else _keyGestureConfig.value
        return configMap[key.lowercase()]?.swipeDown?.action
    }

    /** 获取下滑显示位置：key（按键上）或 bubble（气泡） */
    fun getSwipeDownDisplay(key: String, isAsciiMode: Boolean = false): DisplayMode {
        val configMap = if (isAsciiMode) _keyGestureConfigEn.value else _keyGestureConfig.value
        return configMap[key.lowercase()]?.swipeDown?.display ?: DisplayMode.BOTH
    }

    /** 获取上滑显示位置：key（按键上）或 bubble（气泡） */
    fun getSwipeUpDisplay(key: String, isAsciiMode: Boolean = false): DisplayMode {
        val configMap = if (isAsciiMode) _keyGestureConfigEn.value else _keyGestureConfig.value
        return configMap[key.lowercase()]?.swipeUp?.display ?: DisplayMode.BOTH
    }


    private fun getDefaultSwipeUp(): Map<String, String> = mapOf(
        "q" to "1", "w" to "2", "e" to "3", "r" to "4", "t" to "5",
        "y" to "6", "u" to "7", "i" to "8", "o" to "9", "p" to "0",
        "a" to "!", "s" to "@", "d" to "#", "f" to "$", "g" to "%",
        "h" to "^", "j" to "&", "k" to "(", "l" to ")",
        "z" to "|", "x" to "*", "c" to "\\", "v" to "?", "b" to "_",
        "n" to "-", "m" to "+"
    )
    
    private fun getDefaultSwipeDownEnglish(): Map<String, String> = mapOf(
        "q" to "Q", "w" to "W", "e" to "E", "r" to "R", "t" to "T",
        "y" to "Y", "u" to "U", "i" to "I", "o" to "O", "p" to "P",
        "a" to "A", "s" to "S", "d" to "D", "f" to "F", "g" to "G",
        "h" to "H", "j" to "J", "k" to "K", "l" to "L",
        "z" to "Z", "x" to "X", "c" to "C", "v" to "V", "b" to "B",
        "n" to "N", "m" to "M"
    )
    

}