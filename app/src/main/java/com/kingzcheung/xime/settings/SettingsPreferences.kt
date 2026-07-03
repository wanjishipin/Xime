package com.kingzcheung.xime.settings

import android.content.Context
import android.content.SharedPreferences
import com.kingzcheung.xime.plugin.core.runtime.PluginManager

object SettingsPreferences {
    private const val PREFS_NAME = "kime_settings"
    private const val KEY_CURRENT_SCHEMA = "current_schema"
    private const val KEY_DEPLOYMENT_DONE = "deployment_done"
    private const val KEY_DEPLOYMENT_HASH = "deployment_hash"
    private const val KEY_SETUP_COMPLETED = "setup_completed"
    private const val KEY_DARK_MODE = "dark_mode"
    
    private const val KEY_SOUND_ENABLED = "sound_enabled"
    private const val KEY_SOUND_VOLUME = "sound_volume"
    private const val KEY_VIBRATION_ENABLED = "vibration_enabled"
    private const val KEY_VIBRATION_INTENSITY = "vibration_intensity"
    private const val KEY_KEYBOARD_THEME = "keyboard_theme"
    private const val KEY_GLASS_EFFECT = "glass_effect"
    
    private const val KEY_SMART_PREDICTION_ENABLED = "smart_prediction_enabled"
    private const val KEY_PREDICTION_MODEL_REPO = "prediction_model_repo"
    private const val KEY_PREDICTION_SELECTED_MODEL = "prediction_selected_model"
    
    private const val KEY_STT_ENABLED = "stt_enabled"
    private const val KEY_STT_PROVIDER = "stt_provider"
    private const val KEY_FUNASR_API_KEY = "funasr_api_key"
    private const val KEY_STT_USE_LOCAL = "stt_use_local"
    private const val KEY_STT_KEEP_MODEL_IN_RAM = "stt_keep_model_in_ram"
    
    private const val KEY_PUNCTUATION_MODEL_ENABLED = "punctuation_model_enabled"
    
    /** 默认主题 ID，可从 xime.yaml 的 style.color_scheme 初始化。 */
    @JvmStatic
    var defaultKeyboardTheme: String = "lavender_purple"
    
    const val KEY_SWIPE_UP_HINTS_ENABLED = "swipe_up_hints_enabled"
    const val KEY_SWIPE_DOWN_HINTS_ENABLED = "swipe_down_hints_enabled"
    const val KEY_SHOW_PRESS_BUBBLE = "show_press_bubble"

    private const val KEY_MODE_CHANGE_TARGET = "mode_change_target"

    fun getModeChangeTargetIsNumber(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_MODE_CHANGE_TARGET, false)
    }

    fun setModeChangeTargetIsNumber(context: Context, isNumber: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_MODE_CHANGE_TARGET, isNumber).apply()
    }
    
    private const val KEY_LAYOUT_PREFIX = "layout_pref_"
    
    private const val KEY_KEYBOARD_HEIGHT_DP = "keyboard_height_dp"
    private const val KEY_KEYBOARD_HEIGHT_DP_LANDSCAPE = "keyboard_height_dp_landscape"
    const val DEFAULT_KEYBOARD_HEIGHT_PERCENT = 35
    const val DEFAULT_KEYBOARD_HEIGHT_PERCENT_LANDSCAPE = 49

    private const val KEY_TOOLBAR_BUTTONS = "toolbar_buttons"
    private val DEFAULT_TOOLBAR_BUTTONS = com.kingzcheung.xime.keyboard.ToolbarButton.DEFAULT_VISIBLE.joinToString(",") { it.id }

    fun getToolbarButtons(context: Context): List<String> {
        val raw = getPrefs(context).getString(KEY_TOOLBAR_BUTTONS, DEFAULT_TOOLBAR_BUTTONS) ?: DEFAULT_TOOLBAR_BUTTONS
        return raw.split(",").filter { it.isNotEmpty() }
    }

    fun setToolbarButtons(context: Context, buttons: List<String>) {
        getPrefs(context).edit().putString(KEY_TOOLBAR_BUTTONS, buttons.joinToString(",")).apply()
    }

    private const val KEY_WEBDAV_URL = "webdav_url"
    private const val KEY_WEBDAV_USERNAME = "webdav_username"
    private const val KEY_WEBDAV_PASSWORD = "webdav_password"
    private const val KEY_WEBDAV_PATH = "webdav_path"

    private const val KEY_SCHEMA_IMPORT_WARNING_DISMISSED = "schema_import_warning_dismissed"

    private const val KEY_INSTALLED_MARKET_IDS = "installed_market_ids"
    private const val KEY_COMPACT_MODE = "compact_mode"
    private const val KEY_SHOW_CANDIDATE_COMMENTS = "show_candidate_comments"
    private const val KEY_PAGE_SIZE = "page_size"
    const val DEFAULT_PAGE_SIZE = 0 // 0 表示使用 Rime schema 默认值

    fun isCompactModeEnabled(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_COMPACT_MODE, true)
    }

    fun setCompactModeEnabled(context: Context, enabled: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_COMPACT_MODE, enabled).apply()
    }

    fun showCandidateComments(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_SHOW_CANDIDATE_COMMENTS, true)
    }

    fun setShowCandidateComments(context: Context, show: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_SHOW_CANDIDATE_COMMENTS, show).apply()
    }

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }
    
    fun getPrefsPublic(context: Context): SharedPreferences {
        return getPrefs(context)
    }
    
    fun getCurrentSchema(context: Context): String {
        return getPrefs(context).getString(KEY_CURRENT_SCHEMA, "wubi86") ?: "wubi86"
    }
    
    fun setCurrentSchema(context: Context, schemaId: String) {
        getPrefs(context).edit().putString(KEY_CURRENT_SCHEMA, schemaId).apply()
    }
    
    fun isDeploymentDone(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_DEPLOYMENT_DONE, false)
    }
    
    fun setDeploymentDone(context: Context, done: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_DEPLOYMENT_DONE, done).apply()
    }

    fun getDeploymentHash(context: Context): String {
        return getPrefs(context).getString(KEY_DEPLOYMENT_HASH, "") ?: ""
    }

    fun setDeploymentHash(context: Context, hash: String) {
        getPrefs(context).edit().putString(KEY_DEPLOYMENT_HASH, hash).apply()
    }

    fun isSetupCompleted(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_SETUP_COMPLETED, false)
    }

    fun setSetupCompleted(context: Context, completed: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_SETUP_COMPLETED, completed).apply()
    }

    fun getDarkMode(context: Context): Int {
        // 0 = 浅色, 1 = 深色, 2 = 跟随系统（默认）
        return getPrefs(context).getInt(KEY_DARK_MODE, 2)
    }
    
    fun setDarkMode(context: Context, mode: Int) {
        getPrefs(context).edit().putInt(KEY_DARK_MODE, mode).apply()
    }
    
    fun isSoundEnabled(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_SOUND_ENABLED, true)
    }
    
    fun setSoundEnabled(context: Context, enabled: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_SOUND_ENABLED, enabled).apply()
    }
    
    fun getSoundVolume(context: Context): Int {
        return getPrefs(context).getInt(KEY_SOUND_VOLUME, 20)
    }
    
    fun setSoundVolume(context: Context, volume: Int) {
        getPrefs(context).edit().putInt(KEY_SOUND_VOLUME, volume).apply()
    }
    
    fun isVibrationEnabled(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_VIBRATION_ENABLED, true)
    }
    
    fun setVibrationEnabled(context: Context, enabled: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_VIBRATION_ENABLED, enabled).apply()
    }
    
    fun getVibrationIntensity(context: Context): Int {
        return getPrefs(context).getInt(KEY_VIBRATION_INTENSITY, 30)
    }
    
    fun setVibrationIntensity(context: Context, intensity: Int) {
        getPrefs(context).edit().putInt(KEY_VIBRATION_INTENSITY, intensity).apply()
    }


    private const val KEY_HAPTIC_MODE = "haptic_mode"
    private const val KEY_HAPTIC_ON_KEYUP = "haptic_on_keyup"
    private const val KEY_VIBRATION_PRESS_DURATION = "vibration_press_duration"
    private const val KEY_VIBRATION_LONG_PRESS_DURATION = "vibration_long_press_duration"
    private const val KEY_VIBRATION_PRESS_AMPLITUDE = "vibration_press_amplitude"
    private const val KEY_VIBRATION_LONG_PRESS_AMPLITUDE = "vibration_long_press_amplitude"

    fun getHapticMode(context: Context): String {
        return getPrefs(context).getString(KEY_HAPTIC_MODE, "following_system") ?: "following_system"
    }

    fun setHapticMode(context: Context, mode: String) {
        getPrefs(context).edit().putString(KEY_HAPTIC_MODE, mode).apply()
    }

    fun isHapticOnKeyUp(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_HAPTIC_ON_KEYUP, false)
    }

    fun setHapticOnKeyUp(context: Context, enabled: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_HAPTIC_ON_KEYUP, enabled).apply()
    }

    fun getVibrationPressDuration(context: Context): Int {
        return getPrefs(context).getInt(KEY_VIBRATION_PRESS_DURATION, 0)
    }

    fun setVibrationPressDuration(context: Context, duration: Int) {
        getPrefs(context).edit().putInt(KEY_VIBRATION_PRESS_DURATION, duration).apply()
    }

    fun getVibrationLongPressDuration(context: Context): Int {
        return getPrefs(context).getInt(KEY_VIBRATION_LONG_PRESS_DURATION, 0)
    }

    fun setVibrationLongPressDuration(context: Context, duration: Int) {
        getPrefs(context).edit().putInt(KEY_VIBRATION_LONG_PRESS_DURATION, duration).apply()
    }

    fun getVibrationPressAmplitude(context: Context): Int {
        return getPrefs(context).getInt(KEY_VIBRATION_PRESS_AMPLITUDE, 0)
    }

    fun setVibrationPressAmplitude(context: Context, amplitude: Int) {
        getPrefs(context).edit().putInt(KEY_VIBRATION_PRESS_AMPLITUDE, amplitude).apply()
    }

    fun getVibrationLongPressAmplitude(context: Context): Int {
        return getPrefs(context).getInt(KEY_VIBRATION_LONG_PRESS_AMPLITUDE, 0)
    }

    fun setVibrationLongPressAmplitude(context: Context, amplitude: Int) {
        getPrefs(context).edit().putInt(KEY_VIBRATION_LONG_PRESS_AMPLITUDE, amplitude).apply()
    }

    fun getKeyboardTheme(context: Context): String {
        return getPrefs(context).getString(KEY_KEYBOARD_THEME, defaultKeyboardTheme) ?: defaultKeyboardTheme
    }
    
    fun setKeyboardTheme(context: Context, themeId: String) {
        getPrefs(context).edit().putString(KEY_KEYBOARD_THEME, themeId).apply()
    }

    fun isGlassEffectEnabled(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_GLASS_EFFECT, false)
    }

    fun setGlassEffectEnabled(context: Context, enabled: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_GLASS_EFFECT, enabled).apply()
    }
    
    fun isPluginEnabled(context: Context, pluginId: String): Boolean {
        val prefs = getPrefs(context)
        val key = "plugin_enabled_$pluginId"
        
        if (prefs.contains(key)) {
            return prefs.getBoolean(key, false)
        }
        
        val pluginInfo = PluginManager.getAllInstallPlugins().find { it.id == pluginId }
        return pluginInfo?.enabled ?: true
    }
    
    fun setPluginEnabled(context: Context, pluginId: String, enabled: Boolean) {
        getPrefs(context).edit().putBoolean("plugin_enabled_$pluginId", enabled).apply()
    }
    
    fun isSmartPredictionEnabled(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_SMART_PREDICTION_ENABLED, false)
    }
    
    fun setSmartPredictionEnabled(context: Context, enabled: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_SMART_PREDICTION_ENABLED, enabled).apply()
    }
    
    fun getPredictionModelRepo(context: Context): String {
        return getPrefs(context).getString(KEY_PREDICTION_MODEL_REPO, "https://www.modelscope.cn/models/bikeand/predictive-text-small") 
            ?: "https://www.modelscope.cn/models/bikeand/predictive-text-small"
    }
    
    fun setPredictionModelRepo(context: Context, repo: String) {
        getPrefs(context).edit().putString(KEY_PREDICTION_MODEL_REPO, repo).apply()
    }
    
    fun getPredictionSelectedModel(context: Context): String {
        return getPrefs(context).getString(KEY_PREDICTION_SELECTED_MODEL, "predictive-text-small")
            ?: "predictive-text-small"
    }
    
    fun setPredictionSelectedModel(context: Context, modelId: String) {
        getPrefs(context).edit().putString(KEY_PREDICTION_SELECTED_MODEL, modelId).apply()
    }
    
    fun isSttEnabled(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_STT_ENABLED, false)
    }
    
    fun setSttEnabled(context: Context, enabled: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_STT_ENABLED, enabled).apply()
    }
    
    fun getSttProvider(context: Context): String {
        return getPrefs(context).getString(KEY_STT_PROVIDER, "funasr") ?: "funasr"
    }
    
    fun setSttProvider(context: Context, provider: String) {
        getPrefs(context).edit().putString(KEY_STT_PROVIDER, provider).apply()
    }
    
    fun getFunAsrApiKey(context: Context): String {
        return getPrefs(context).getString(KEY_FUNASR_API_KEY, "") ?: ""
    }
    
    fun setFunAsrApiKey(context: Context, apiKey: String) {
        getPrefs(context).edit().putString(KEY_FUNASR_API_KEY, apiKey).apply()
    }
    
    fun isSttUseLocal(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_STT_USE_LOCAL, false)
    }
    
    fun setSttUseLocal(context: Context, useLocal: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_STT_USE_LOCAL, useLocal).apply()
    }
    
    fun isSttKeepModelInRam(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_STT_KEEP_MODEL_IN_RAM, true)
    }
    
    fun setSttKeepModelInRam(context: Context, keep: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_STT_KEEP_MODEL_IN_RAM, keep).apply()
    }
    
    fun isPunctuationModelEnabled(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_PUNCTUATION_MODEL_ENABLED, false)
    }
    
    fun setPunctuationModelEnabled(context: Context, enabled: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_PUNCTUATION_MODEL_ENABLED, enabled).apply()
    }
    
    fun isSwipeUpHintsEnabled(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_SWIPE_UP_HINTS_ENABLED, true)
    }
    
    fun setSwipeUpHintsEnabled(context: Context, enabled: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_SWIPE_UP_HINTS_ENABLED, enabled).apply()
    }
    
    fun isSwipeDownHintsEnabled(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_SWIPE_DOWN_HINTS_ENABLED, true)
    }

    fun setSwipeDownHintsEnabled(context: Context, enabled: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_SWIPE_DOWN_HINTS_ENABLED, enabled).apply()
    }

    fun shouldShowPressBubble(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_SHOW_PRESS_BUBBLE, true)
    }

    fun setShowPressBubble(context: Context, show: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_SHOW_PRESS_BUBBLE, show).apply()
    }
    
    /** 获取方案偏好的键盘布局，默认全键盘 */
    fun getLayoutPreference(context: Context, schemaId: String): String {
        return getPrefs(context).getString("$KEY_LAYOUT_PREFIX$schemaId", "full") ?: "full"
    }
    
    /** 保存方案偏好的键盘布局 */
    fun setLayoutPreference(context: Context, schemaId: String, layout: String) {
        getPrefs(context).edit().putString("$KEY_LAYOUT_PREFIX$schemaId", layout).apply()
    }
    
    fun getKeyboardHeightDp(context: Context): Int {
        return getKeyboardHeightDp(context, false)
    }

    fun getKeyboardHeightDp(context: Context, isLandscape: Boolean): Int {
        val key = if (isLandscape) KEY_KEYBOARD_HEIGHT_DP_LANDSCAPE else KEY_KEYBOARD_HEIGHT_DP
        val alt = if (isLandscape) KEY_KEYBOARD_HEIGHT_DP else KEY_KEYBOARD_HEIGHT_DP_LANDSCAPE
        val stored = getPrefs(context).getInt(key, -1)
        if (stored > 0) return stored
        val altStored = getPrefs(context).getInt(alt, -1)
        if (altStored > 0) return altStored
        return getDefaultKeyboardHeightDp(context, isLandscape)
    }

    fun setKeyboardHeightDp(context: Context, heightDp: Int, isLandscape: Boolean = false) {
        val key = if (isLandscape) KEY_KEYBOARD_HEIGHT_DP_LANDSCAPE else KEY_KEYBOARD_HEIGHT_DP
        getPrefs(context).edit().putInt(key, heightDp).apply()
    }

    fun getDefaultKeyboardHeightDp(context: Context, isLandscape: Boolean = false): Int {
        val percent = if (isLandscape) DEFAULT_KEYBOARD_HEIGHT_PERCENT_LANDSCAPE else DEFAULT_KEYBOARD_HEIGHT_PERCENT
        return context.resources.configuration.screenHeightDp * percent / 100
    }

    private const val KEY_KEYBOARD_BOTTOM_PADDING_DP = "keyboard_bottom_padding_dp"
    private const val DEFAULT_KEYBOARD_BOTTOM_PADDING_DP = 0

    fun getKeyboardBottomPaddingDp(context: Context): Int {
        return getPrefs(context).getInt(KEY_KEYBOARD_BOTTOM_PADDING_DP, DEFAULT_KEYBOARD_BOTTOM_PADDING_DP)
    }

    fun setKeyboardBottomPaddingDp(context: Context, paddingDp: Int) {
        getPrefs(context).edit().putInt(KEY_KEYBOARD_BOTTOM_PADDING_DP, paddingDp).apply()
    }

    fun getWebDavUrl(context: Context): String {
        return getPrefs(context).getString(KEY_WEBDAV_URL, "") ?: ""
    }

    fun setWebDavUrl(context: Context, url: String) {
        getPrefs(context).edit().putString(KEY_WEBDAV_URL, url).apply()
    }

    fun getWebDavUsername(context: Context): String {
        return getPrefs(context).getString(KEY_WEBDAV_USERNAME, "") ?: ""
    }

    fun setWebDavUsername(context: Context, username: String) {
        getPrefs(context).edit().putString(KEY_WEBDAV_USERNAME, username).apply()
    }

    fun getWebDavPassword(context: Context): String {
        return getPrefs(context).getString(KEY_WEBDAV_PASSWORD, "") ?: ""
    }

    fun setWebDavPassword(context: Context, password: String) {
        getPrefs(context).edit().putString(KEY_WEBDAV_PASSWORD, password).apply()
    }

    fun getWebDavPath(context: Context): String {
        return getPrefs(context).getString(KEY_WEBDAV_PATH, "xime") ?: "xime"
    }

    fun setWebDavPath(context: Context, path: String) {
        getPrefs(context).edit().putString(KEY_WEBDAV_PATH, path).apply()
    }

    fun isSchemaImportWarningDismissed(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_SCHEMA_IMPORT_WARNING_DISMISSED, false)
    }

    fun setSchemaImportWarningDismissed(context: Context, dismissed: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_SCHEMA_IMPORT_WARNING_DISMISSED, dismissed).apply()
    }

    // ── 悬浮键盘设置 ──

    private const val KEY_FLOATING_MODE = "floating_mode"
    private const val KEY_FLOATING_MODE_LANDSCAPE = "floating_mode_landscape"
    private const val KEY_FLOATING_OFFSET_X = "floating_offset_x"
    private const val KEY_FLOATING_OFFSET_X_LANDSCAPE = "floating_offset_x_landscape"
    private const val KEY_FLOATING_OFFSET_Y = "floating_offset_y"
    private const val KEY_FLOATING_OFFSET_Y_LANDSCAPE = "floating_offset_y_landscape"

    fun isFloatingMode(context: Context, isLandscape: Boolean = false): Boolean {
        val key = if (isLandscape) KEY_FLOATING_MODE_LANDSCAPE else KEY_FLOATING_MODE
        return getPrefs(context).getBoolean(key, false)
    }

    fun setFloatingMode(context: Context, enabled: Boolean, isLandscape: Boolean = false) {
        val key = if (isLandscape) KEY_FLOATING_MODE_LANDSCAPE else KEY_FLOATING_MODE
        getPrefs(context).edit().putBoolean(key, enabled).apply()
    }

    fun getFloatingOffsetX(context: Context, isLandscape: Boolean = false): Int {
        val key = if (isLandscape) KEY_FLOATING_OFFSET_X_LANDSCAPE else KEY_FLOATING_OFFSET_X
        return getPrefs(context).getInt(key, 0)
    }

    fun setFloatingOffsetX(context: Context, offset: Int, isLandscape: Boolean = false) {
        val key = if (isLandscape) KEY_FLOATING_OFFSET_X_LANDSCAPE else KEY_FLOATING_OFFSET_X
        getPrefs(context).edit().putInt(key, offset).apply()
    }

    fun getFloatingOffsetY(context: Context, isLandscape: Boolean = false): Int {
        val key = if (isLandscape) KEY_FLOATING_OFFSET_Y_LANDSCAPE else KEY_FLOATING_OFFSET_Y
        return getPrefs(context).getInt(key, 0)
    }

    fun setFloatingOffsetY(context: Context, offset: Int, isLandscape: Boolean = false) {
        val key = if (isLandscape) KEY_FLOATING_OFFSET_Y_LANDSCAPE else KEY_FLOATING_OFFSET_Y
        getPrefs(context).edit().putInt(key, offset).apply()
    }

    fun getPageSize(context: Context): Int {
        return getPrefs(context).getInt(KEY_PAGE_SIZE, DEFAULT_PAGE_SIZE)
    }

    fun setPageSize(context: Context, pageSize: Int) {
        getPrefs(context).edit().putInt(KEY_PAGE_SIZE, pageSize).apply()
    }

    // ── 方案市场「已安装」的持久记录 ──
    // 记录用户通过市场主动安装过的方案 id；与本地文件存在性解耦（方案可能仅作为依赖落盘，
    // 文件存在不代表用户装过它），且跨重启保持。
    fun getInstalledMarketIds(context: Context): Set<String> =
        getPrefs(context).getStringSet(KEY_INSTALLED_MARKET_IDS, emptySet())?.toSet() ?: emptySet()

    fun addInstalledMarketId(context: Context, id: String) {
        val cur = getInstalledMarketIds(context).toMutableSet()
        if (cur.add(id)) {
            getPrefs(context).edit().putStringSet(KEY_INSTALLED_MARKET_IDS, cur).apply()
        }
    }

    fun removeInstalledMarketId(context: Context, id: String) {
        val cur = getInstalledMarketIds(context).toMutableSet()
        if (cur.remove(id)) {
            getPrefs(context).edit().putStringSet(KEY_INSTALLED_MARKET_IDS, cur).apply()
        }
    }
}