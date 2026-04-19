package com.kingzcheung.kime.service

import android.content.Intent
import android.inputmethodservice.InputMethodService
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputContentInfo
import android.widget.FrameLayout
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.kingzcheung.kime.MainActivity
import com.kingzcheung.kime.clipboard.ClipboardManager
import com.kingzcheung.kime.plugin.ExtensionManager
import com.kingzcheung.kime.plugin.core.api.RecognitionState
import com.kingzcheung.kime.rime.RimeConfigHelper
import com.kingzcheung.kime.rime.RimeEngine
import com.kingzcheung.kime.settings.SchemaConfigHelper
import com.kingzcheung.kime.settings.SettingsPreferences
import com.kingzcheung.kime.speech.SpeechRecognitionManager
import com.kingzcheung.kime.ui.KeyboardView
import com.kingzcheung.kime.ui.KeysConfigHelper
import com.kingzcheung.kime.ui.theme.KimeTheme
import com.kingzcheung.kime.util.FileLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream

data class InputUIState(
    val candidates: Array<String> = emptyArray(),
    val candidateComments: Array<String> = emptyArray(),
    val inputText: String = "",
    val isComposing: Boolean = false,
    val isAsciiMode: Boolean = false,
    val schemaName: String = "",
    val enterKeyText: String = "发送",
    val darkMode: Int = 0,
    val themeId: String = "ocean_blue",
    val showBottomButtons: Boolean = false,
    val associationCandidates: Array<String> = emptyArray(),
    val associationEnabled: Boolean = false,
    val isVoiceMode: Boolean = false,
    val voiceButtonState: VoiceButtonState = VoiceButtonState(),
    val voicePluginName: String = "",
    val voiceRecognitionState: RecognitionState = RecognitionState.IDLE,
    val voiceRecognizedText: String = ""
)

data class VoiceButtonState(
    val bottomActive: Boolean = false,  // 底部按钮是否激活（白色）
    val leftActive: Boolean = false,    // 左按钮是否激活（白色）
    val rightActive: Boolean = false    // 右按钮是否激活（白色）
)

/**
 * Kime 输入法服务
 * 使用 Jetpack Compose 构建输入法 UI
 * 集成 Rime 引擎实现五笔输入
 * 
 * 参考 trime 的 LifecycleInputMethodService 实现
 */
class KimeInputMethodService : InputMethodService(), LifecycleOwner, SavedStateRegistryOwner {

    companion object {
        private const val TAG = "KimeInputMethodService"
        private const val DARK_MODE_LIGHT = 0
        private const val DARK_MODE_DARK = 1
    }

    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedStateRegistryController = SavedStateRegistryController.create(this)

    override val lifecycle: Lifecycle
        get() = lifecycleRegistry

    override val savedStateRegistry: SavedStateRegistry
        get() = savedStateRegistryController.savedStateRegistry

    // Rime 引擎实例
    private val rimeEngine = RimeEngine.getInstance()
    
    private lateinit var clipboardManager: ClipboardManager
    
    // 协程作用域
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    
    // 主线程 Handler
    private val mainHandler = Handler(Looper.getMainLooper())
    
    // UI 状态 - 合并为单一状态对象，减少Compose重组
    private val uiState = mutableStateOf(InputUIState())
    private val clipboardItemsState = mutableStateOf<List<com.kingzcheung.kime.clipboard.ClipboardItem>>(emptyList())
    private val quickSendItemsState = mutableStateOf<List<com.kingzcheung.kime.clipboard.ClipboardItem>>(emptyList())
    
    // 语音模式状态
    private var isTrackingVoiceButtons = false
    private var voiceRecordingStarted = false
    
    // 最近上屏的文本（用于联想）
    private var lastCommittedText = ""
    
    // 语音识别管理器
    private lateinit var speechRecognitionManager: SpeechRecognitionManager
    
    // 音频和振动
    private val audioManager: AudioManager by lazy { getSystemService(AUDIO_SERVICE) as AudioManager }
    private val vibrator: Vibrator by lazy {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vibratorManager = getSystemService(VIBRATOR_MANAGER_SERVICE) as VibratorManager
            vibratorManager.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(VIBRATOR_SERVICE) as Vibrator
        }
    }
    
    private fun playKeySound(keyType: String = "standard") {
        if (!SettingsPreferences.isSoundEnabled(this)) return
        
        val volume = SettingsPreferences.getSoundVolume(this) / 100f
        val soundVolume = (volume * 100).toInt()
        
        val effectType = when (keyType) {
            "delete" -> AudioManager.FX_KEYPRESS_DELETE
            "enter" -> AudioManager.FX_KEYPRESS_RETURN
            "space" -> AudioManager.FX_KEYPRESS_SPACEBAR
            else -> AudioManager.FX_KEYPRESS_STANDARD
        }
        
        audioManager.playSoundEffect(effectType, soundVolume / 100f)
    }
    
    private fun performVibration() {
        if (!SettingsPreferences.isVibrationEnabled(this)) return
        if (!vibrator.hasVibrator()) return
        
        val intensity = SettingsPreferences.getVibrationIntensity(this)
        val duration = 10L + (intensity * 0.4).toLong()
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val amplitude = (intensity * 2.55).toInt().coerceIn(1, 255)
            vibrator.vibrate(VibrationEffect.createOneShot(duration, amplitude))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(duration)
        }
    }
    
    private fun performKeyPressEffect(keyType: String = "standard") {
        playKeySound(keyType)
        performVibration()
    }
    
    private fun performKeyPressDownEffect(key: String) {
        val keyType = when (key) {
            "delete", "clear_composition" -> "delete"
            "enter" -> "enter"
            "space" -> "space"
            else -> "standard"
        }
        playKeySound(keyType)
        performVibration()
    }
    
    private fun loadDarkModePreference() {
        uiState.value = uiState.value.copy(
            darkMode = SettingsPreferences.getDarkMode(this),
            themeId = SettingsPreferences.getKeyboardTheme(this),
            showBottomButtons = SettingsPreferences.showBottomButtons(this)
        )
    }
    
    private fun saveDarkModePreference(mode: Int) {
        SettingsPreferences.setDarkMode(this, mode)
        uiState.value = uiState.value.copy(darkMode = mode)
    }
    
    fun toggleDarkMode() {
        val newMode = if (uiState.value.darkMode == DARK_MODE_LIGHT) DARK_MODE_DARK else DARK_MODE_LIGHT
        saveDarkModePreference(newMode)
    }
    
    fun isDarkTheme(): Boolean {
        return uiState.value.darkMode == DARK_MODE_DARK
    }

override fun onCreate() {
        super.onCreate()
        savedStateRegistryController.performRestore(null)
        window.window?.decorView?.setViewTreeLifecycleOwner(this)
        window.window?.decorView?.setViewTreeSavedStateRegistryOwner(this)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        
        // 初始化文件日志系统
        FileLogger.init(this)
        FileLogger.i(TAG, "KimeInputMethodService created")
        
        loadDarkModePreference()
        initRimeEngine()
        initClipboardManager()
        initAssociationEngine()
        initSpeechRecognition()
        
        FileLogger.i(TAG, "Service initialization completed")
    }
    
    /**
     * 初始化语音识别系统
     */
    private fun initSpeechRecognition() {
        FileLogger.i(TAG, "Initializing speech recognition system")
        
        speechRecognitionManager = SpeechRecognitionManager(this)
        
        speechRecognitionManager.setCallbacks(
            onResult = { text ->
                handleSpeechResult(text)
            },
            onStateChange = { state ->
                handleSpeechStateChange(state)
            },
            onError = { error ->
                handleSpeechError(error)
            }
        )
        
        val plugins = speechRecognitionManager.getAvailablePlugins()
        if (plugins.isNotEmpty()) {
            val (pluginId, plugin) = plugins.first()
            val pluginInfo = ExtensionManager.getAllInstalledPlugins()
                .firstOrNull { it.id == pluginId }
            uiState.value = uiState.value.copy(voicePluginName = pluginInfo?.name ?: "语音插件")
            speechRecognitionManager.setCurrentPlugin(plugin)
            FileLogger.i(TAG, "Speech plugin available: ${pluginInfo?.name ?: "unknown"}")
        } else {
            FileLogger.w(TAG, "No speech plugins available")
        }
    }
    
    private fun handleSpeechResult(text: String) {
        Log.d(TAG, "Speech result: $text")
        
        if (text.isNotEmpty() && !text.startsWith("错误:")) {
            currentInputConnection?.commitText(text, 1)
            lastCommittedText = text
            uiState.value = uiState.value.copy(voiceRecognizedText = text)
        }
    }
    
    private fun handleSpeechStateChange(state: RecognitionState) {
        Log.d(TAG, "Speech state changed: $state")
        uiState.value = uiState.value.copy(voiceRecognitionState = state)
    }
    
    private fun handleSpeechError(error: String) {
        Log.e(TAG, "Speech error: $error")
        android.widget.Toast.makeText(this, error, android.widget.Toast.LENGTH_SHORT).show()
        uiState.value = uiState.value.copy(
            voiceRecognitionState = RecognitionState.ERROR,
            voiceRecognizedText = ""
        )
    }
    
/**
     * 初始化插件系统（包括联想插件）
     */
    private fun initAssociationEngine() {
        FileLogger.i(TAG, "Initializing plugin system")
        
        if (!ExtensionManager.isInitialized()) {
            FileLogger.d(TAG, "ExtensionManager not initialized, initializing...")
            ExtensionManager.initialize(this)
        }
        
        if (ExtensionManager.hasPredictionPlugins(this)) {
            FileLogger.i(TAG, "Prediction plugins available")
        } else {
            FileLogger.w(TAG, "No prediction plugins available")
        }
    }
    
    /**
     * 检查并初始化插件系统
     */
    private fun checkAndInitializeAssociationEngine() {
        if (!FileLogger.isInitialized()) {
            FileLogger.init(this)
        }
        
        if (!ExtensionManager.isInitialized()) {
            FileLogger.i(TAG, "ExtensionManager not initialized, initializing now...")
            ExtensionManager.initialize(this)
        }
    }
    
    /**
     * 从插件获取联想词
     */
private fun getPredictionFromPlugin(contextText: String) {
        if (contextText.isEmpty()) {
            uiState.value = uiState.value.copy(associationCandidates = emptyArray())
            return
        }
        
        serviceScope.launch {
            try {
                val candidates = ExtensionManager.predict(this@KimeInputMethodService, contextText, 5)
                
                Log.d(TAG, "Prediction candidates: ${candidates.joinToString()}")
                
                withContext(Dispatchers.Main) {
                    uiState.value = uiState.value.copy(associationCandidates = candidates.toTypedArray())
                }
            } catch (e: Exception) {
                Log.e(TAG, "Prediction failed", e)
                withContext(Dispatchers.Main) {
                    uiState.value = uiState.value.copy(associationCandidates = emptyArray())
                }
            }
}
    }
    
    /**
     * 初始化 Rime 引擎
     */
    private fun initRimeEngine() {
        Log.d(TAG, "initRimeEngine: Starting initialization...")
        try {
            KeysConfigHelper.loadConfig(this)
            
            val (userDataDir, sharedDataDir) = RimeConfigHelper.initializeRimeData(this)
            
            Log.d(TAG, "initRimeEngine: userDataDir=$userDataDir, sharedDataDir=$sharedDataDir")
            
            Log.d(TAG, "initRimeEngine: Calling rimeEngine.initialize...")
            rimeEngine.initialize(userDataDir, sharedDataDir)
            
            val currentSchema = rimeEngine.getCurrentSchema()
            val savedSchema = SettingsPreferences.getCurrentSchema(this)
            Log.d(TAG, "initRimeEngine: currentSchema=$currentSchema, savedSchema=$savedSchema")
            
            val availableSchemas = rimeEngine.getAvailableSchemas()
            Log.d(TAG, "initRimeEngine: availableSchemas=${availableSchemas.joinToString()}")
            
            if (savedSchema in availableSchemas && currentSchema != savedSchema) {
                Log.d(TAG, "initRimeEngine: Switching to saved schema: $savedSchema")
                rimeEngine.switchSchema(savedSchema)
            }
            
            updateSchemaName()
            
            Log.d(TAG, "initRimeEngine: Rime engine initialized successfully")
        } catch (e: Exception) {
            Log.e(TAG, "initRimeEngine: Failed to initialize Rime engine", e)
        }
    }
    
    /**
     * 初始化剪切板管理器
     */
    private fun initClipboardManager() {
        Log.d(TAG, "initClipboardManager: Starting initialization...")
        try {
            clipboardManager = ClipboardManager.getInstance(this)
            clipboardItemsState.value = clipboardManager.clipboardItems.value
            quickSendItemsState.value = clipboardManager.quickSendItems.value
            
            serviceScope.launch {
                clipboardManager.clipboardItems.collect { items ->
                    clipboardItemsState.value = items
                }
            }
            
            serviceScope.launch {
                clipboardManager.quickSendItems.collect { items ->
                    quickSendItemsState.value = items
                }
            }
            Log.d(TAG, "initClipboardManager: Clipboard manager initialized successfully")
        } catch (e: Exception) {
            Log.e(TAG, "initClipboardManager: Failed to initialize clipboard manager", e)
        }
    }

    override fun onCreateInputView(): View {
        // 创建自定义 FrameLayout 处理全局触摸事件
        val container = VoiceKeyboardContainer(this)
        
        // 创建 ComposeView
        val composeView = ComposeView(this).apply {
            setContent {
                val state = uiState.value
                val isDarkTheme = isDarkTheme()
                KimeTheme(darkTheme = isDarkTheme, themeId = state.themeId) {
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(290.dp),
                        color = MaterialTheme.colorScheme.surface
                    ) {
                        KeyboardView(
                            candidates = state.candidates,
                            inputText = state.inputText,
                            isComposing = state.isComposing,
                            isAsciiMode = state.isAsciiMode,
                            schemaName = state.schemaName,
                            enterKeyText = state.enterKeyText,
                            isDarkTheme = isDarkTheme,
                            themeId = state.themeId,
                            showBottomButtons = state.showBottomButtons,
                            clipboardItems = clipboardItemsState.value,
                            quickSendItems = quickSendItemsState.value,
                            candidateComments = state.candidateComments,
                            isVoiceMode = state.isVoiceMode,
                            voiceBottomActive = state.voiceButtonState.bottomActive,
                            voiceLeftActive = state.voiceButtonState.leftActive,
                            voiceRightActive = state.voiceButtonState.rightActive,
                            voicePluginName = state.voicePluginName,
                            voiceRecognitionState = state.voiceRecognitionState,
                            voiceRecognizedText = state.voiceRecognizedText,
                            onKeyPress = { key, isShifted ->
                                handleKeyPress(key, isShifted)
                            },
                            onKeyPressDown = { key ->
                                performKeyPressDownEffect(key)
                            },
                            onCandidateSelect = { index ->
                                selectCandidate(index)
                            },
                            onToggleDarkMode = {
                                toggleDarkMode()
                            },
                            onClipboard = {
                                Log.d(TAG, "Clipboard clicked")
                            },
                            onClipboardSelect = { text ->
                                selectClipboardItem(text)
                            },
                            onClipboardRemove = { id ->
                                removeClipboardItem(id)
                            },
                            onClipboardTogglePin = { id ->
                                toggleClipboardPin(id)
                            },
                            onClipboardClearAll = {
                                clearClipboard()
                            },
                            onAddToQuickSend = { id ->
                                addToQuickSend(id)
                            },
                            onRemoveFromQuickSend = { id ->
                                removeFromQuickSend(id)
                            },
                            onQuickSend = {
                                Log.d(TAG, "QuickSend clicked")
                            },
                            onManageDict = {
                                openManageDict()
                            },
                            onEmoji = {
                                commitText("😊")
                            },
                            onReloadConfig = {
                                reloadConfig()
                            },
                            onSettings = {
                                openSettings()
                            },
                            onMixedInput = {
                                toggleMixedInput()
                            },
                            onHideKeyboard = {
                                hideKeyboard()
                            },
                            onSwitchKeyboard = {
                                val imm = getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
                                @Suppress("DEPRECATION")
                                imm.showInputMethodPicker()
                            },
                            associationCandidates = state.associationCandidates,
                            onAssociationSelect = { index ->
                                if (index >= 0 && index < state.associationCandidates.size) {
                                    val text = state.associationCandidates[index]
                                    commitText(text)
                                    updateUI()
                                }
                            },
                            onCommitImage = { imagePath ->
                                val success = commitImage(imagePath)
                                if (!success) {
                                    android.widget.Toast.makeText(
                                        this@KimeInputMethodService,
                                        "发送失败，已复制到剪贴板",
                                        android.widget.Toast.LENGTH_SHORT
                                    ).show()
                                    clipboardManager.copyImageToSystemClipboard(imagePath)
                                }
                            },
                            onVoiceModeChange = { enabled ->
                                Log.d("VoiceButtons", "onVoiceModeChange called: enabled=$enabled")
                                uiState.value = uiState.value.copy(
                                    isVoiceMode = enabled,
                                    voiceButtonState = if (enabled) VoiceButtonState(bottomActive = true) else VoiceButtonState(),
                                    voiceRecognizedText = ""
                                )
                                if (enabled) {
                                    performVibration()
                                    // 空格键长按触发，立即开始录音
                                    if (::speechRecognitionManager.isInitialized) {
                                        Log.d("VoiceButtons", "Starting speech recognition from onVoiceModeChange")
                                        val started = speechRecognitionManager.startRecognition()
                                        voiceRecordingStarted = started
                                        Log.d("VoiceButtons", "Speech recognition started: $started")
                                        if (!started) {
                                            Log.e(TAG, "Failed to start speech recognition")
                                            uiState.value = uiState.value.copy(
                                                isVoiceMode = false,
                                                voiceRecognitionState = RecognitionState.ERROR
                                            )
                                        }
                                    } else {
                                        Log.e(TAG, "speechRecognitionManager not initialized")
                                    }
                                }
                            }
                        )
                    }
                }
            }
        }
        
        container.addView(composeView)
        
        return container
    }
    
    /**
     * 自定义容器 View，在 View 层级处理触摸事件
     * 这样切换界面不会中断手势监听
     */
    private inner class VoiceKeyboardContainer(context: android.content.Context) : FrameLayout(context) {
        private var isLongPressWaiting = false
        private var isPressing = false
        
        override fun dispatchTouchEvent(ev: MotionEvent?): Boolean {
            ev?.let {
                when (it.action) {
                    MotionEvent.ACTION_DOWN -> {
                        val isVoiceMode = uiState.value.isVoiceMode
                        Log.d("VoiceButtons", "DOWN: isVoiceMode=$isVoiceMode, x=${it.x}, y=${it.y}")
                        
                        if (isVoiceMode) {
                            // 语音键盘已激活，检测底部区域按压
                            val yThreshold = height * 0.6f
                            
                            if (it.y > yThreshold) {
                                isTrackingVoiceButtons = true
                                uiState.value = uiState.value.copy(
                                    voiceButtonState = VoiceButtonState(bottomActive = true)
                                )
                                Log.d("VoiceButtons", "Pressed bottom area in voice mode")
                            }
                        }
                        // 普通键盘的空格键长按由 KeyboardLayout 处理
                    }
                    
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        Log.d("VoiceButtons", "UP: isVoiceMode=${uiState.value.isVoiceMode}, voiceRecordingStarted=$voiceRecordingStarted")
                        
                        // 如果正在录音，停止录音并退出语音模式
                        if (voiceRecordingStarted && uiState.value.isVoiceMode) {
                            Log.d("VoiceButtons", "Stopping recording and exiting voice mode")
                            
                            if (::speechRecognitionManager.isInitialized) {
                                speechRecognitionManager.stopRecognition()
                            }
                            
                            voiceRecordingStarted = false
                            
                            // 处理按钮操作
                            val state = uiState.value.voiceButtonState
                            if (state.leftActive) {
                                performUndo()
                            } else if (state.rightActive) {
                                performSearch()
                            }
                            
                            uiState.value = uiState.value.copy(
                                isVoiceMode = false,
                                voiceButtonState = VoiceButtonState(),
                                voiceRecognitionState = RecognitionState.IDLE
                            )
                        }
                        
                        isTrackingVoiceButtons = false
                    }
                    
                    MotionEvent.ACTION_MOVE -> {
                        val isVoiceMode = uiState.value.isVoiceMode
                        
                        if (isVoiceMode && isTrackingVoiceButtons) {
                            val yThreshold = height * 0.6f
                            val leftButtonEnd = width / 2f - width * 0.04f
                            val rightButtonStart = width / 2f + width * 0.04f
                            
                            if (it.y > yThreshold) {
                                // 底部区域 - 继续录音
                                uiState.value = uiState.value.copy(
                                    voiceButtonState = VoiceButtonState(bottomActive = true)
                                )
                            } else if (it.x < leftButtonEnd) {
                                // 左按钮 - 撤回（停止录音并删除最后输入）
                                uiState.value = uiState.value.copy(
                                    voiceButtonState = VoiceButtonState(leftActive = true)
                                )
                            } else if (it.x > rightButtonStart) {
                                // 右按钮 - 搜索（停止录音并发送 Enter）
                                uiState.value = uiState.value.copy(
                                    voiceButtonState = VoiceButtonState(rightActive = true)
                                )
                            } else {
                                // 中间区域
                                uiState.value = uiState.value.copy(
                                    voiceButtonState = VoiceButtonState()
                                )
                            }
                            
                            Log.d("VoiceButtons", "MOVE: bottom=${uiState.value.voiceButtonState.bottomActive}, left=${uiState.value.voiceButtonState.leftActive}, right=${uiState.value.voiceButtonState.rightActive}")
                        }
                    }
                }
            }
            return super.dispatchTouchEvent(ev)
        }
    }
    
    private fun performUndo() {
        // 撤回操作：删除最后输入的字符
        currentInputConnection?.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DEL))
        currentInputConnection?.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DEL))
    }
    
    private fun performSearch() {
        // 搜索操作：发送 Enter 键
        currentInputConnection?.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER))
        currentInputConnection?.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER))
    }
    
    override fun onStartInput(attribute: EditorInfo?, restarting: Boolean) {
        super.onStartInput(attribute, restarting)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
        loadDarkModePreference()
        
        // 清空上屏文本（新的输入开始）
        lastCommittedText = ""
        Log.d(TAG, "onStartInput: cleared lastCommittedText")
        
        // 动态检查联想功能设置（允许运行时开启，无需重启）
        checkAndInitializeAssociationEngine()
        
        // 更新 Enter 键文字
        attribute?.let { updateEnterKeyText(it) }
    }
    
    private fun updateEnterKeyText(editorInfo: EditorInfo) {
        val action = editorInfo.imeOptions and EditorInfo.IME_MASK_ACTION
        val enterText = when (action) {
            EditorInfo.IME_ACTION_GO -> "前往"
            EditorInfo.IME_ACTION_SEARCH -> "搜索"
            EditorInfo.IME_ACTION_SEND -> "发送"
            EditorInfo.IME_ACTION_NEXT -> "下一项"
            EditorInfo.IME_ACTION_DONE -> "完成"
            else -> "换行"
        }
        uiState.value = uiState.value.copy(enterKeyText = enterText)
    }

    override fun onFinishInput() {
        super.onFinishInput()
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
        clearInputState()
    }
    
    override fun onWindowHidden() {
        super.onWindowHidden()
        clearInputState()
    }
    
    private fun clearInputState() {
        rimeEngine.clearComposition()
        uiState.value = uiState.value.copy(
            candidates = emptyArray(),
            candidateComments = emptyArray(),
            inputText = "",
            isComposing = false
        )
    }

    override fun onDestroy() {
        super.onDestroy()
        rimeEngine.destroy()
        if (::speechRecognitionManager.isInitialized) {
            speechRecognitionManager.release()
        }
        ExtensionManager.release()
        serviceScope.cancel()
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
    }
    
    /**
     * 收起键盘
     */
    private fun hideKeyboard() {
        requestHideSelf(0)
    }
    
/**
      * 更新 UI 状态 - 合并所有状态更新，减少Compose重组次数
      * 联想预测只使用已上屏的文本(lastCommittedText)
      */
 private fun updateUI() {
     val inputText = rimeEngine.getInput()
     val candidatesWithComments = rimeEngine.getCandidatesWithComments()
     
     uiState.value = uiState.value.copy(
         inputText = inputText,
         candidates = candidatesWithComments.map { it.text }.toTypedArray(),
         candidateComments = candidatesWithComments.map { it.comment }.toTypedArray(),
         isComposing = inputText.isNotEmpty(),
         isAsciiMode = rimeEngine.isAsciiMode(),
         associationCandidates = emptyArray()
     )
     
// 联想预测只在已上屏文本存在且没有正在输入编码时触发
     // 正确逻辑：只有上屏后才应该显示联想词
 if (ExtensionManager.hasPredictionPlugins(this) && inputText.isEmpty() && lastCommittedText.isNotEmpty()) {
          serviceScope.launch {
              try {
                  Log.d(TAG, "Predicting association for lastCommittedText='$lastCommittedText'")
                  
                  val candidates = ExtensionManager.predict(this@KimeInputMethodService, lastCommittedText, 5)
                  
                  Log.d(TAG, "Association candidates: ${candidates.joinToString()}")
                  withContext(Dispatchers.Main) {
                      uiState.value = uiState.value.copy(associationCandidates = candidates.toTypedArray())
                  }
              } catch (e: Exception) {
                  Log.e(TAG, "Association prediction failed", e)
              }
          }
      }
 }
    
    private fun updateSchemaName() {
        val currentSchemaId = rimeEngine.getCurrentSchema()
        val schemas = SchemaConfigHelper.loadSchemas(this)
        val schemaInfo = schemas.find { it.schemaId == currentSchemaId }
        uiState.value = uiState.value.copy(schemaName = schemaInfo?.name ?: currentSchemaId)
    }

    private fun handleKeyPress(key: String, isShifted: Boolean) {
        serviceScope.launch(Dispatchers.Default) {
            val state = uiState.value
            var needsUIUpdate = false
            
            when (key) {
                "delete" -> {
                    if (state.isComposing || state.inputText.isNotEmpty()) {
                        // 第一步：删除编码字符
                        rimeEngine.processKey(0xff08, 0)
                        
                        // 检查编码是否已清空
                        val currentInput = rimeEngine.getInput()
                        if (currentInput.isEmpty()) {
                            // 第二步：编码清空后，清空候选词栏
                            rimeEngine.clearComposition()
                            Log.d(TAG, "Delete: encoding cleared, cleared composition and candidates")
                        }
                        
                        needsUIUpdate = true
                    } else {
                        // 第三步：没有编码时，删除输入框的已上屏文本
                        // 同时清空候选词栏（包括联想词）
                        if (lastCommittedText.isNotEmpty()) {
                            lastCommittedText = lastCommittedText.dropLast(1)
                            Log.d(TAG, "Delete committed text, remaining: '$lastCommittedText'")
                        }
                        
                        // 清空候选词栏
                        uiState.value = uiState.value.copy(
                            candidates = emptyArray(),
                            candidateComments = emptyArray(),
                            associationCandidates = emptyArray()
                        )
                        
                        withContext(Dispatchers.Main) {
                            sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL)
                        }
                    }
                }
                "clear_composition" -> {
                    // 上滑清空：清空编码、候选词栏和联想词
                    rimeEngine.clearComposition()
                    uiState.value = uiState.value.copy(
                        candidates = emptyArray(),
                        candidateComments = emptyArray(),
                        associationCandidates = emptyArray()
                    )
                    needsUIUpdate = true
                    Log.d(TAG, "Clear composition: cleared encoding, candidates and association candidates")
                }
                "enter" -> {
                    if (state.isComposing) {
                        val input = state.inputText
                        if (input.isNotEmpty()) {
                            withContext(Dispatchers.Main) {
                                commitText(input)
                            }
                        }
                        rimeEngine.clearComposition()
                        needsUIUpdate = true
                    } else {
                        withContext(Dispatchers.Main) {
                            val action = currentInputEditorInfo?.imeOptions ?: 0
                            when (action and EditorInfo.IME_MASK_ACTION) {
                                EditorInfo.IME_ACTION_GO,
                                EditorInfo.IME_ACTION_SEARCH,
                                EditorInfo.IME_ACTION_SEND,
                                EditorInfo.IME_ACTION_NEXT,
                                EditorInfo.IME_ACTION_DONE -> {
                                    sendDownUpKeyEvents(KeyEvent.KEYCODE_ENTER)
                                }
                                else -> {
                                    sendDownUpKeyEvents(KeyEvent.KEYCODE_ENTER)
                                }
                            }
                        }
                    }
                }
                "space" -> {
                    if (state.isComposing) {
                        // 有编码时：空格键上屏第一个候选词或编码
                        if (state.candidates.isNotEmpty()) {
                            selectCandidateAsync(0)
                        } else {
                            val input = state.inputText
                            if (input.isNotEmpty()) {
                                withContext(Dispatchers.Main) {
                                    commitText(input)
                                }
                                rimeEngine.clearComposition()
                                needsUIUpdate = true
                            }
                        }
                    } else {
                        // 没有编码时：直接输入空格（联想词只能通过点选上屏，不能用空格键）
                        withContext(Dispatchers.Main) {
                            commitText(" ")
                        }
                    }
                }
                "shift" -> {
                }
                "mode_change" -> {
                }
                "ime_switch" -> {
                    withContext(Dispatchers.Main) {
                        switchInputMethod()
                    }
                }
                "abc" -> {
                }
                "emoji" -> {
                    withContext(Dispatchers.Main) {
                        commitText("😊")
                    }
                }
                else -> {
                    if (key.matches(Regex("[0-9]")) ||
                        key in listOf("-", "/", ":", ";", "(", ")", "@", "\"", "'", "#", ".", ",", "!", "?", "，", "。")) {
                        if (state.isComposing) {
                            val committedText = rimeEngine.commit()
                            if (committedText.isNotEmpty()) {
                                withContext(Dispatchers.Main) {
                                    commitText(committedText)
                                }
                            }
                            rimeEngine.clearComposition()
                            needsUIUpdate = true
                        }
                        withContext(Dispatchers.Main) {
                            commitText(key)
                        }
                    } else {
                        val char = if (isShifted) key.uppercase() else key
                        val keyCode = key.lowercase()[0].code
                        val mask = if (isShifted) KeyEvent.META_SHIFT_ON else 0
                        
                        val processed = rimeEngine.processKey(keyCode, mask)
                        
                        if (processed) {
                            needsUIUpdate = true
                            
                            val committedText = rimeEngine.commit()
                            if (committedText.isNotEmpty()) {
                                withContext(Dispatchers.Main) {
                                    commitText(committedText)
                                }
                                needsUIUpdate = true
                            }
                        } else {
                            if (!state.isComposing) {
                                withContext(Dispatchers.Main) {
                                    commitText(char)
                                }
                            }
                        }
                    }
                }
            }
            
            if (needsUIUpdate) {
                withContext(Dispatchers.Main) {
                    updateUI()
                }
            }
        }
    }
    
    private suspend fun selectCandidateAsync(index: Int) {
        val selectedCandidate = if (index < uiState.value.candidates.size) {
            uiState.value.candidates[index]
        } else null
        
        if (rimeEngine.selectCandidate(index)) {
            val committedText = rimeEngine.commit()
            if (committedText.isNotEmpty()) {
                // 学习用户输入
                if (ExtensionManager.hasPredictionPlugins(this@KimeInputMethodService) && selectedCandidate != null) {
                    if (lastCommittedText.isNotEmpty()) {
                        val predictionPlugin = ExtensionManager.getEnabledPredictionPlugins(this@KimeInputMethodService).firstOrNull()?.second
                        
                        if (predictionPlugin != null) {
                            val lastChar = lastCommittedText.last().toString()
                            predictionPlugin.learn(lastChar + selectedCandidate)
                            Log.d(TAG, "Learned: '$lastChar' + '$selectedCandidate'")
                        }
                    }
                }
                withContext(Dispatchers.Main) {
                    commitText(committedText)
                }
            }
            withContext(Dispatchers.Main) {
                updateUI()
            }
        }
    }
    
    private fun selectCandidate(index: Int) {
        serviceScope.launch(Dispatchers.Default) {
            selectCandidateAsync(index)
        }
    }
    
    /**
     * 切换输入法模式（中文/英文）
     */
    private fun switchInputMethod() {
        Log.d(TAG, "Toggling ascii mode")
        rimeEngine.toggleAsciiMode()
        updateUI()
    }
    
    /**
     * 部署方案
     */
    private fun reloadConfig() {
        Log.d(TAG, "Deploying schema...")
        
        // 收起键盘并显示提示
        mainHandler.post {
            requestHideSelf(0)
            android.widget.Toast.makeText(this, "方案部署中...", android.widget.Toast.LENGTH_SHORT).show()
        }
        
        Thread {
            try {
                KeysConfigHelper.loadConfig(this)
                
                val userDataDir = File(filesDir, "rime/user")
                
                // 删除旧的 default.custom.yaml（避免覆盖 assets 中的 schema_list）
                val customFile = File(userDataDir, "default.custom.yaml")
                if (customFile.exists()) {
                    Log.d(TAG, "Removing old default.custom.yaml")
                    customFile.delete()
                }
                
                // 清理 build 目录强制重新部署
                val buildDir = File(userDataDir, "build")
                if (buildDir.exists()) {
                    Log.d(TAG, "Cleaning build directory")
                    buildDir.deleteRecursively()
                }
                
                // 部署
                Log.d(TAG, "Starting deployment...")
                val deployResult = rimeEngine.deploy()
                Log.d(TAG, "Deploy result: $deployResult")
                
                // 获取可用方案列表
                val availableSchemas = rimeEngine.getAvailableSchemas()
                Log.d(TAG, "Available schemas: ${availableSchemas.joinToString()}")
                
                // 切换到保存的方案
                val savedSchema = SettingsPreferences.getCurrentSchema(this)
                Log.d(TAG, "Saved schema: $savedSchema")
                if (savedSchema in availableSchemas) {
                    val switchResult = rimeEngine.switchSchema(savedSchema)
                    Log.d(TAG, "Switch schema result: $switchResult")
                } else {
                    Log.w(TAG, "Schema $savedSchema not found in available schemas")
                }
                
                // 在主线程更新 UI
                mainHandler.post {
                    updateSchemaName()
                    updateUI()
                    android.widget.Toast.makeText(this, "方案部署完成", android.widget.Toast.LENGTH_SHORT).show()
                    Log.d(TAG, "Schema deployed successfully")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to reload config", e)
            }
        }.start()
    }
    
    /**
     * 部署方案
     */
    private fun deploySchema() {
        Log.d(TAG, "Deploying schema...")
        try {
            rimeEngine.deploy()
            val savedSchema = SettingsPreferences.getCurrentSchema(this)
            rimeEngine.switchSchema(savedSchema)
            updateUI()
            Log.d(TAG, "Schema deployed successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to deploy schema", e)
        }
    }
    
    /**
     * 打开输入法设置
     */
    private fun openSettings() {
        Log.d(TAG, "Opening settings...")
        try {
            val intent = Intent(this, MainActivity::class.java)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open settings", e)
        }
    }
    
    private fun openManageDict() {
        Log.d(TAG, "Opening manage dict...")
        try {
            val intent = Intent(this, MainActivity::class.java)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            intent.putExtra("open_fragment", "manage_dict")
            startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open manage dict", e)
        }
    }
    
    /**
     * 切换五笔拼音混输
     */
    private fun toggleMixedInput() {
        Log.d(TAG, "Toggling mixed input...")
        try {
            val currentSchema = rimeEngine.getCurrentSchema()
            val newSchema = if (currentSchema.contains("pinyin")) {
                "wubi86"
            } else {
                "wubi86_pinyin"
            }
            SettingsPreferences.setCurrentSchema(this, newSchema)
            rimeEngine.switchSchema(newSchema)
            rimeEngine.deploy()
            updateSchemaName()
            updateUI()
            Log.d(TAG, "Switched to schema: $newSchema")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to toggle mixed input", e)
        }
    }

private fun commitText(text: String) {
        currentInputConnection?.commitText(text, 1)
        lastCommittedText = text
        
        // 调用插件学习用户输入
        serviceScope.launch {
            try {
                ExtensionManager.getEnabledPredictionPlugins(this@KimeInputMethodService)
                    .forEach { (_, plugin) -> plugin.learn(text) }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to learn from plugin", e)
            }
        }
        
        // 获取联想词
        getPredictionFromPlugin(text)
    }
    
    private fun commitImage(imagePath: String, mimeType: String = "image/jpeg"): Boolean {
        return try {
            val imageFile = File(imagePath)
            if (!imageFile.exists()) {
                Log.e(TAG, "Image file not found: $imagePath")
                return false
            }
            
            val cacheDir = File(cacheDir, "emoji_cache")
            if (!cacheDir.exists()) {
                cacheDir.mkdirs()
            }
            
            val cacheFile = File(cacheDir, imageFile.name)
            FileInputStream(imageFile).use { input ->
                cacheFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            
            val uri = FileProvider.getUriForFile(
                this,
                "$packageName.fileprovider",
                cacheFile
            )
            
            val inputContentInfo = InputContentInfo(
                uri,
                android.content.ClipDescription("emoji_image", arrayOf(mimeType)),
                null
            )
            
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N_MR1) {
                InputConnection.INPUT_CONTENT_GRANT_READ_URI_PERMISSION
            } else {
                0
            }
            
            currentInputConnection?.commitContent(inputContentInfo, flags, null) ?: false
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to commit image", e)
            false
        }
    }
    
    /**
     * 选择剪切板项
     */
    private fun selectClipboardItem(text: String) {
        if (uiState.value.isComposing) {
            rimeEngine.clearComposition()
            updateUI()
        }
        commitText(text)
        clipboardManager.copyToSystemClipboard(text)
    }
    
    /**
     * 删除剪切板项
     */
    private fun removeClipboardItem(id: Long) {
        clipboardManager.removeItem(id)
    }
    
    /**
     * 切换剪切板项置顶状态
     */
    private fun toggleClipboardPin(id: Long) {
        clipboardManager.togglePin(id)
    }
    
    /**
     * 清空剪切板
     */
    private fun clearClipboard() {
        clipboardManager.clearAll()
    }
    
    /**
     * 添加到快捷发送
     */
    private fun addToQuickSend(id: Long) {
        clipboardManager.addToQuickSend(id)
    }
    
    /**
     * 从快捷发送移除
     */
    private fun removeFromQuickSend(id: Long) {
        clipboardManager.removeFromQuickSend(id)
    }
}