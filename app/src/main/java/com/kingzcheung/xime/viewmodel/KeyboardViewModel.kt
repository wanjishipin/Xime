package com.kingzcheung.xime.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import com.kingzcheung.xime.clipboard.ClipboardItem
import com.kingzcheung.xime.clipboard.ClipboardManager
import com.kingzcheung.xime.keyboard.KeyboardRoute
import com.kingzcheung.xime.keyboard.ToolbarButton
import com.kingzcheung.xime.settings.SchemaInfo
import com.kingzcheung.xime.settings.SettingsPreferences
import com.kingzcheung.xime.speech.RecognitionState
import com.kingzcheung.xime.ui.keyboard.KeyboardLayoutState
import com.kingzcheung.xime.ui.keyboard.initialKeyboardLayoutState

enum class ShiftMode { OFF, SINGLE, CAPS }

data class KeyboardUiState(
    val candidates: List<String> = emptyList(),
    val candidateComments: List<String> = emptyList(),
    val inputText: String = "",
    val isComposing: Boolean = false,
    val associationCandidates: List<String> = emptyList(),
    val hasNextPage: Boolean = false,
    val hasPrevPage: Boolean = false,
    val isAsciiMode: Boolean = false,
    val schemaName: String = "",
    val currentSchemaId: String = "",
    val schemas: List<SchemaInfo> = emptyList(),
    val enterKeyText: String = "发送",
    val isDarkTheme: Boolean = false,
    val darkMode: Int = 2,
    val themeId: String = "ocean_blue",
    val showBottomButtons: Boolean = false,
    val keyboardHeightDp: Int = SettingsPreferences.DEFAULT_KEYBOARD_HEIGHT_DP,
    val keyboardBottomPaddingDp: Int = 0,
    val isDeploying: Boolean = false,
    val deploymentMessage: String = "",
    val clipboardItems: List<ClipboardItem> = emptyList(),
    val quickSendItems: List<ClipboardItem> = emptyList(),
    val recentClipboardItems: List<ClipboardItem> = emptyList(),
    val isVoiceMode: Boolean = false,
    val voiceBottomActive: Boolean = false,
    val voiceLeftActive: Boolean = false,
    val voiceRightActive: Boolean = false,
    val voicePluginName: String = "",
    val voiceRecognitionState: RecognitionState = RecognitionState.IDLE,
    val voiceRecognizedText: String = "",
    val voiceAmplitude: Float = 0f,
    val isSttEnabled: Boolean = true,
    val toolbarButtons: List<String> = ToolbarButton.DEFAULT_VISIBLE.map { it.id },
    val isCalculatorMode: Boolean = false,
    val inputSessionId: Long = 0L,
    val isShowingRecentClipboard: Boolean = false,
)

class KeyboardViewModel(application: Application) : AndroidViewModel(application) {

    val clipboardManager = ClipboardManager.getInstance(application)

    private val _isShifted = MutableStateFlow(false)
    val isShifted: StateFlow<Boolean> = _isShifted.asStateFlow()

    private val _shiftMode = MutableStateFlow(ShiftMode.OFF)
    val shiftMode: StateFlow<ShiftMode> = _shiftMode.asStateFlow()

    private val _keyboardState = MutableStateFlow<KeyboardLayoutState>(KeyboardLayoutState.Chinese)
    val keyboardState: StateFlow<KeyboardLayoutState> = _keyboardState.asStateFlow()

    private val _currentRoute = MutableStateFlow<KeyboardRoute>(KeyboardRoute.Keyboard)
    val currentRoute: StateFlow<KeyboardRoute> = _currentRoute.asStateFlow()

    fun toggleShift() {
        _isShifted.update { !it }
    }

    fun setShifted(shifted: Boolean) {
        _isShifted.value = shifted
    }

    fun singleTapShift() {
        when (_shiftMode.value) {
            ShiftMode.OFF, ShiftMode.SINGLE -> {
                _isShifted.value = true
                _shiftMode.value = ShiftMode.SINGLE
            }
            ShiftMode.CAPS -> {
                _isShifted.value = false
                _shiftMode.value = ShiftMode.OFF
            }
        }
    }

    fun doubleTapShift() {
        when (_shiftMode.value) {
            ShiftMode.CAPS -> {
                _isShifted.value = false
                _shiftMode.value = ShiftMode.OFF
            }
            else -> {
                _isShifted.value = true
                _shiftMode.value = ShiftMode.CAPS
            }
        }
    }

    fun onCharacterTyped() {
        if (_shiftMode.value == ShiftMode.SINGLE) {
            _isShifted.value = false
            _shiftMode.value = ShiftMode.OFF
        }
    }

    fun setKeyboardState(state: KeyboardLayoutState) {
        if (state is KeyboardLayoutState.English) {
            _isShifted.value = false
            _shiftMode.value = ShiftMode.OFF
        }
        _keyboardState.value = state
    }

    fun resetShift() {
        _isShifted.value = false
        _shiftMode.value = ShiftMode.OFF
    }

    fun setRoute(route: KeyboardRoute) {
        _currentRoute.value = route
    }

    fun resetKeyboard(isAsciiMode: Boolean, schemaId: String = "") {
        _isShifted.value = false
        _shiftMode.value = ShiftMode.OFF
        _keyboardState.value = initialKeyboardLayoutState(isAsciiMode, schemaId)
        _currentRoute.value = KeyboardRoute.Keyboard
    }

    // Clipboard operations

    fun removeClipboardItem(id: Long) {
        clipboardManager.removeItem(id)
    }

    fun splitClipboardItem(id: Long) {
        clipboardManager.splitItem(id)
    }

    fun clearClipboard() {
        clipboardManager.clearAll()
    }

    fun addToQuickSend(id: Long) {
        clipboardManager.addToQuickSend(id)
    }

    fun addQuickSendText(text: String) {
        clipboardManager.addQuickSendItem(text)
    }

    fun removeQuickSendItem(id: Long) {
        clipboardManager.removeFromQuickSend(id)
    }

    fun togglePinQuickSend(id: Long) {
        clipboardManager.togglePinQuickSend(id)
    }
}
