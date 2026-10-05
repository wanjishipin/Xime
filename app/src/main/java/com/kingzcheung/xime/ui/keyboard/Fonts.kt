package com.kingzcheung.xime.ui.keyboard

import android.content.Context
import android.content.res.AssetManager
import android.graphics.Typeface
import android.util.Log
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import com.kingzcheung.xime.settings.KeyboardFontConfig
import java.io.File

/**
 * 字体管理器，管理键盘相关字体的加载和缓存。
 *
 * 字体路径查找规则：
 * 1. 绝对路径（以 / 开头）：从根目录找起，如 /fonts/myfont.ttf
 * 2. 相对路径（包含 / 但不以 / 开头）：相对于 rime/ 目录，如 fonts/myfont.ttf
 * 3. 仅文件名（不包含 /）：相对于 rime/ 目录查找，如 myfont.ttf
 */
object AppFonts {
    private const val TAG = "AppFonts"
    private const val CHAI_PUA_FONT = "ChaiPUA-0.2.7-snow.ttf"

    private var initialized = false
    private lateinit var assetManager: AssetManager
    private lateinit var filesDir: File
    private lateinit var rimeDir: File

    // 当前已加载的字体配置（用于跳过未变更的重复加载）
    private var loadedConfigHash: Int = 0

    /** ChaiPUA 字体，用于显示 CJK 扩展区字符（如五笔字根） */
    val chaiPuaTypeface: Typeface by lazy {
        Typeface.createFromAsset(assetManager, CHAI_PUA_FONT)
    }

    /** ChaiPUA FontFamily，用于 Compose Text */
    val chaiPuaFontFamily: FontFamily by lazy {
        FontFamily(Font(CHAI_PUA_FONT, assetManager))
    }

    // ── 自定义字体缓存 ──
    // Typeface 用于 native Canvas 绘制（如 SwipeBubble）
    private var _keyTypeface: Typeface? = null
    private var _keyLabelTypeface: Typeface? = null
    private var _candidateTypeface: Typeface? = null
    private var _commentTypeface: Typeface? = null

    // FontFamily 用于 Compose Text（如 CandidateBar）
    private var _keyFontFamily: FontFamily? = null
    private var _keyLabelFontFamily: FontFamily? = null
    private var _candidateFontFamily: FontFamily? = null
    private var _commentFontFamily: FontFamily? = null

    // 粗体 Typeface 缓存（避免每次访问都新建）
    private var _keyFontTypeface: Typeface? = null

    /** 按键主字 Typeface */
    val keyTypeface: Typeface get() = _keyTypeface ?: Typeface.DEFAULT
    /** 按键主字粗体 Typeface（用于编辑键盘方向标签等） */
    val keyFontTypeface: Typeface get() {
        val cached = _keyFontTypeface
        if (cached != null) return cached
        val created = Typeface.create(keyTypeface, Typeface.BOLD)
        _keyFontTypeface = created
        return created
    }
    /** 字根标签 Typeface */
    val keyLabelTypeface: Typeface get() = _keyLabelTypeface ?: chaiPuaTypeface
    /** 候选项 Typeface */
    val candidateTypeface: Typeface get() = _candidateTypeface ?: Typeface.DEFAULT
    /** 注释 Typeface */
    val commentTypeface: Typeface get() = _commentTypeface ?: Typeface.DEFAULT

    /** 按键主字 FontFamily */
    val keyFontFamily: FontFamily get() = _keyFontFamily ?: FontFamily.Default
    /** 字根标签 FontFamily */
    val keyLabelFontFamily: FontFamily get() = _keyLabelFontFamily ?: chaiPuaFontFamily
    /** 候选项 FontFamily */
    val candidateFontFamily: FontFamily get() = _candidateFontFamily ?: FontFamily.Default
    /** 注释 FontFamily */
    val commentFontFamily: FontFamily get() = _commentFontFamily ?: FontFamily.Default

    fun initialize(context: Context) {
        if (initialized) return
        initialized = true
        assetManager = context.assets
        filesDir = context.filesDir
        rimeDir = File(filesDir, "rime")
    }

    /**
     * 加载自定义字体配置。
     * 在 KeysConfigHelper.loadConfig() 时调用，配置变更时也会重新调用。
     * 配置未变更时跳过重新解析（避免 reloadConfig 重复触发时反复加载大字体文件）。
     * 字体加载后缓存在内存中，后续直接使用缓存的 Typeface/FontFamily。
     */
    fun loadCustomFonts(config: KeyboardFontConfig) {
        if (!initialized) return
        val hash = config.hashCode()
        if (hash == loadedConfigHash) return
        loadedConfigHash = hash

        _keyTypeface = loadTypeface(config.keyFont)
        _keyLabelTypeface = loadTypeface(config.keyLabelFont)
        _candidateTypeface = loadTypeface(config.candidateFont)
        _commentTypeface = loadTypeface(config.commentFont)

        // 复用已解析的 Typeface 构造 FontFamily，避免同一字体文件被解析两次
        _keyFontFamily = loadFontFamily(config.keyFont, _keyTypeface)
        _keyLabelFontFamily = loadFontFamily(config.keyLabelFont, _keyLabelTypeface)
        _candidateFontFamily = loadFontFamily(config.candidateFont, _candidateTypeface)
        _commentFontFamily = loadFontFamily(config.commentFont, _commentTypeface)

        // 粗体缓存失效
        _keyFontTypeface = null

        Log.d(TAG, "Custom fonts loaded: key=${config.keyFont}, keyLabel=${config.keyLabelFont}, candidate=${config.candidateFont}, comment=${config.commentFont}")
    }

    /**
     * 解析字体路径为 File 对象。
     * 支持四种格式：
     * - 绝对路径（以 / 开头）：相对于应用数据根目录，如 /fonts/myfont.ttf → files/fonts/myfont.ttf
     * - rime/ 开头：相对于应用数据根目录，如 rime/fonts/myfont.ttf → files/rime/fonts/myfont.ttf
     * - 其他相对路径（包含 /）：相对于 rime/ 目录，如 fonts/myfont.ttf → files/rime/fonts/myfont.ttf
     * - 仅文件名（不包含 /）：仅在 rime/ 目录（配置文件同目录）查找
     */
    private fun resolveFontPath(fontPath: String): File {
        return when {
            fontPath.startsWith("/") -> File(filesDir, fontPath.removePrefix("/"))
            fontPath.startsWith("rime/") -> File(filesDir, fontPath)
            fontPath.contains("/") -> File(rimeDir, fontPath)
            else -> File(rimeDir, fontPath)
        }
    }

    /**
     * 加载 Typeface（用于 native Canvas）。
     * @return Typeface 或 null（文件不存在或加载失败）
     */
    private fun loadTypeface(fontPath: String): Typeface? {
        if (fontPath.isBlank()) return null
        val fontFile = resolveFontPath(fontPath)
        if (!fontFile.exists()) {
            Log.w(TAG, "Font file not found: ${fontFile.absolutePath}")
            return null
        }
        return try {
            Typeface.createFromFile(fontFile)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load typeface from ${fontFile.absolutePath}", e)
            null
        }
    }

    /**
     * 加载 FontFamily（用于 Compose Text）。
     * 优先复用已解析的 [typeface]，避免同一字体文件被重复解析。
     * @return FontFamily 或 null（文件不存在或加载失败）
     */
    private fun loadFontFamily(fontPath: String, typeface: Typeface?): FontFamily? {
        if (fontPath.isBlank()) return null
        if (typeface != null) return FontFamily(typeface)
        val fontFile = resolveFontPath(fontPath)
        if (!fontFile.exists()) {
            return null
        }
        return try {
            FontFamily(Typeface.createFromFile(fontFile))
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load font family from ${fontFile.absolutePath}", e)
            null
        }
    }
}
