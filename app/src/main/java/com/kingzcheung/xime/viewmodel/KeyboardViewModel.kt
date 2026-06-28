package com.kingzcheung.xime.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import com.kingzcheung.xime.clipboard.ClipboardItem
import com.kingzcheung.xime.clipboard.ClipboardManager
import com.kingzcheung.xime.keyboard.KeyboardPage
import com.kingzcheung.xime.keyboard.MainType
import com.kingzcheung.xime.keyboard.OverlayRoute
import com.kingzcheung.xime.keyboard.PanelType
import com.kingzcheung.xime.keyboard.ToolbarButton
import com.kingzcheung.xime.settings.SchemaInfo
import com.kingzcheung.xime.speech.RecognitionState
import com.kingzcheung.xime.ui.keyboard.KeyboardLayoutState
import com.kingzcheung.xime.ui.keyboard.initialKeyboardLayoutState

enum class ShiftMode { OFF, SINGLE, CAPS }

data class KeyboardUiState(
    val candidates: List<String> = emptyList(),
    val candidateComments: List<String> = emptyList(),
    val inputText: String = "",
    val preeditText: String = "",
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
    val keyboardHeightDp: Int = 0,
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
    val isFloatingMode: Boolean = false,
    val isHandwritingMode: Boolean = false,
    val floatingOffsetX: Int = 0,
    val floatingOffsetY: Int = 0,
    val floatingMinOffsetY: Int = 0,
)

class KeyboardViewModel(application: Application) : AndroidViewModel(application) {

    val clipboardManager = ClipboardManager.getInstance(application)

    private val _isShifted = MutableStateFlow(false)
    val isShifted: StateFlow<Boolean> = _isShifted.asStateFlow()

    private val _shiftMode = MutableStateFlow(ShiftMode.OFF)
    val shiftMode: StateFlow<ShiftMode> = _shiftMode.asStateFlow()

    private val _keyboardState = MutableStateFlow<KeyboardLayoutState>(KeyboardLayoutState.Chinese)
    val keyboardState: StateFlow<KeyboardLayoutState> = _keyboardState.asStateFlow()

    private val _page = MutableStateFlow<KeyboardPage>(KeyboardPage.Main(MainType.FULL))
    val page: StateFlow<KeyboardPage> = _page.asStateFlow()

    fun toggleShift() {
        _isShifted.update { !it }
    }

    fun setShifted(shifted: Boolean) {
        _isShifted.value = shifted
    }

    fun singleTapShift() {
        when (_shiftMode.value) {
            ShiftMode.OFF -> {
                _isShifted.value = true
                _shiftMode.value = ShiftMode.SINGLE
            }
            ShiftMode.SINGLE, ShiftMode.CAPS -> {
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

    // ── Page Navigation ──

    /** Level 1: 切换主键盘类型 */
    fun switchMain(type: MainType) {
        _page.value = KeyboardPage.Main(type)
        if (type == MainType.FULL) {
            _keyboardState.value = KeyboardLayoutState.Chinese
        }
    }

    /** Level 2: 进入面板（编号/符号等），可从任意页面进入 */
    fun enterPanel(type: PanelType) {
        val current = _page.value
        val mainType = when (current) {
            is KeyboardPage.Main -> current.type
            is KeyboardPage.Panel -> current.returnTo
            is KeyboardPage.Overlay -> {
                val behind = current.behind
                if (behind is KeyboardPage.Main) behind.type
                else MainType.FULL
            }
        }
        _page.value = KeyboardPage.Panel(type, mainType)
    }

    /** Level 2: 退出面板，回到主键盘 */
    fun exitPanel() {
        val current = _page.value
        if (current is KeyboardPage.Panel) {
            _page.value = KeyboardPage.Main(current.returnTo)
        }
    }

    /** Level 3: 打开覆盖页面，可从任意页面进入 */
    fun showOverlay(route: OverlayRoute, initialBackStack: List<OverlayRoute> = emptyList()) {
        val current = _page.value
        val behind = if (current is KeyboardPage.Overlay) current.behind else current
        _page.value = KeyboardPage.Overlay(route, initialBackStack, behind)
    }

    /** Level 3: 在覆盖页面内推入子页 */
    fun pushOverlay(route: OverlayRoute) {
        val current = _page.value
        if (current is KeyboardPage.Overlay) {
            _page.value = current.copy(
                route = route,
                backStack = current.backStack + current.route
            )
        }
    }

    /** Level 3: 在覆盖页面内回退 */
    fun popOverlay() {
        val current = _page.value
        if (current is KeyboardPage.Overlay && current.backStack.isNotEmpty()) {
            val prev = current.backStack.last()
            _page.value = current.copy(
                route = prev,
                backStack = current.backStack.dropLast(1)
            )
        }
    }

    /** Level 3: 关闭覆盖页面，回到背后页面 */
    fun closeOverlay() {
        val current = _page.value
        if (current is KeyboardPage.Overlay) {
            _page.value = current.behind
        }
    }

    fun resetKeyboard(isAsciiMode: Boolean, schemaId: String = "") {
        _isShifted.value = false
        _shiftMode.value = ShiftMode.OFF
        _keyboardState.value = initialKeyboardLayoutState(isAsciiMode, schemaId)
        if (_page.value !is KeyboardPage.Main) {
            _page.value = KeyboardPage.Main(MainType.FULL)
        }
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
