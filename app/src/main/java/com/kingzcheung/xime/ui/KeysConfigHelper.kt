package com.kingzcheung.xime.ui

import android.content.Context
import android.util.Log
import com.charleskorn.kaml.Yaml
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName
import java.io.BufferedReader
import java.io.InputStreamReader

@Serializable
data class XimeConfig(
    @SerialName("wubi_radicals")
    val wubiRadicals: WubiRadicalsConfig? = null
)

@Serializable
data class WubiRadicalsConfig(
    val hotkeys: HotkeysConfig? = null,
    val action: String? = null,
    @SerialName("schema_radicals")
    val schemaRadicals: Map<String, Map<String, String>> = emptyMap()
)

@Serializable
data class HotkeysConfig(
    @SerialName("show_key")
    val showKey: String? = null,
    @SerialName("show_all_keys")
    val showAllKeys: String? = null
)

data class KeysConfig(
    val swipeUp: Map<String, String> = emptyMap(),
    val swipeDownEnglish: Map<String, String> = emptyMap(),
    val schemaRadicals: Map<String, Map<String, String>> = emptyMap()
)

object KeysConfigHelper {
    private const val TAG = "KeysConfigHelper"
    private const val XIME_CONFIG_FILE = "xime.yaml"
    
    private val yaml = Yaml.default
    
    private var config: KeysConfig = KeysConfig(
        swipeUp = getDefaultSwipeUp(),
        swipeDownEnglish = getDefaultSwipeDownEnglish()
    )
    
    fun loadConfig(context: Context): KeysConfig {
        loadXimeConfig(context)
        // loadConfig 负责加载 xime.yaml 中的 schemaRadicals（字根配置），
        // swipeUp/swipeDownEnglish 的默认值已在初始值中内置。
        config = config.copy(
            swipeUp = getDefaultSwipeUp(),
            swipeDownEnglish = getDefaultSwipeDownEnglish()
        )
        return config
    }
    
    private fun loadXimeConfig(context: Context) {
        try {
            val inputStream = context.assets.open(XIME_CONFIG_FILE)
            val reader = BufferedReader(InputStreamReader(inputStream))
            val content = reader.readText()
            reader.close()
            inputStream.close()
            
            val ximeConfig = yaml.decodeFromString(XimeConfig.serializer(), content)
            val schemaRadicals = ximeConfig.wubiRadicals?.schemaRadicals ?: emptyMap()
            config = config.copy(schemaRadicals = schemaRadicals)
            Log.d(TAG, "Loaded schema radicals from $XIME_CONFIG_FILE: ${schemaRadicals.keys}")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to load $XIME_CONFIG_FILE: ${e.message}")
            config = config.copy(schemaRadicals = getDefaultSchemaRadicals())
        }
    }
    
    fun getConfig(): KeysConfig = config
    
    fun getSwipeUpText(key: String): String? = config.swipeUp[key.lowercase()]
    
    fun getSwipeDownEnglishText(key: String): String? = config.swipeDownEnglish[key.lowercase()]
    
    fun getSwipeDownWubiText(key: String, schemaId: String): String? {
        val radicals = config.schemaRadicals[schemaId] ?: return null
        return radicals[key.lowercase()]
    }
    
    fun hasSchemaRadicals(schemaId: String): Boolean {
        return config.schemaRadicals.containsKey(schemaId)
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
    
    private fun getDefaultSchemaRadicals(): Map<String, Map<String, String>> = mapOf(
        "wubi86" to mapOf(
            "g" to "王龶五一戋",
            "f" to "土士二干十寸雨",
            "d" to "大犬三古龵镸石厂丆",
            "s" to "木丁西",
            "a" to "工匚戈艹廿龷七弋戈",
            "h" to "目丨卜⺊上止龰",
            "j" to "日曰早廾刂虫丿Ⅱ",
            "k" to "口Ⅲ川",
            "l" to "田甲囗四罒车皿力",
            "m" to "山由贝冂冎几",
            "t" to "禾竹丿𠂉彳夂攵",
            "r" to "白手龵扌斤𰀪𠂆",
            "e" to "月⺼彡乃用爫彡𧘇豕",
            "w" to "人亻八癶",
            "q" to "金 钅𠂊勺㐅 犭𱼀",
            "y" to "言讠文方广亠丶乀",
            "u" to "立六辛冫丬门疒丷䒑",
            "i" to "水氵小氺头𭕄⺌",
            "o" to "火灬米",
            "p" to "之辶冖宀廴礻",
            "n" to "已己巳心忄羽乙𠃜",
            "b" to "子耳了也阝卩㔾凵",
            "v" to "女刀九臼巛彐",
            "c" to "又巴马厶龴ス",
            "x" to "弓匕纟幺弓𠤎"
        ),
        "wubi86_pinyin" to mapOf(
            "g" to "王龶五一戋",
            "f" to "土士二干十寸雨",
            "d" to "大犬三古龵镸石厂丆",
            "s" to "木丁西",
            "a" to "工匚戈艹廿龷七弋戈",
            "h" to "目丨卜⺊上止龰",
            "j" to "日曰早廾刂虫丿Ⅱ",
            "k" to "口Ⅲ川",
            "l" to "田甲囗四罒车皿力",
            "m" to "山由贝冂冎几",
            "t" to "禾竹丿𠂉彳夂攵",
            "r" to "白手龵扌斤𰀪𠂆",
            "e" to "月⺼彡乃用爫彡𧘇豕",
            "w" to "人亻八癶",
            "q" to "金 钅𠂊勺㐅 犭𱼀",
            "y" to "言讠文方广亠丶乀",
            "u" to "立六辛冫丬门疒丷䒑",
            "i" to "水氵小氺头𭕄⺌",
            "o" to "火灬米",
            "p" to "之辶冖宀廴礻",
            "n" to "已己巳心忄羽乙𠃜",
            "b" to "子耳了也阝卩㔾凵",
            "v" to "女刀九臼巛彐",
            "c" to "又巴马厶龴ス",
            "x" to "弓匕纟幺弓𠤎"
        )
    )
}