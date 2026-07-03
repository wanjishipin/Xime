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
import com.kingzcheung.xime.ui.keyboard.KeyboardDispatchAction
import com.kingzcheung.xime.ui.keyboard.KeyboardLayoutState
import com.kingzcheung.xime.ui.keyboard.transition
import com.kingzcheung.xime.ui.keyboard.KeyboardLayoutAction
import com.kingzcheung.xime.ui.keyboard.KeyboardViewState
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
    val t9ResetSignal: Long = 0L,
    val t9RightCandidateSelectedCount: Long = 0L,
    val t9SelectedCandidatePinyin: String = "",
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


    /** 是否从 handwriting 进入英文键盘，用于 ASCII 切回时恢复 handwriting */
    var handwritingShouldReturn: Boolean = false

    /** 进入面板前保存的 keyboardState，用于 exitPanel 恢复 */
    private var _savedKbStateBeforePanel: KeyboardLayoutState? = null

    /** 统一视图状态（替换 keyboardState + page 双轴） */
    private val _viewState = MutableStateFlow<KeyboardViewState>(KeyboardViewState.ChineseFull)
    val viewState: StateFlow<KeyboardViewState> = _viewState.asStateFlow()

    /**
     * 单⼀状态转移入口 — 替代所有散落的 LaunchedEffect、setKeyboardState、switchMain 等。
     */
    fun dispatch(
        action: KeyboardDispatchAction,
        isAsciiMode: Boolean = false,
        schemaId: String = "",
    ) {
        val current = _viewState.value
        val (newState, newPage, newKbState) = when (action) {
            is KeyboardDispatchAction.ToggleChineseEnglish -> {
                when (current) {
                    KeyboardViewState.ChineseFull -> Triple(KeyboardViewState.EnglishFull, KeyboardPage.Main(MainType.FULL), KeyboardLayoutState.English)
                    KeyboardViewState.EnglishFull -> Triple(KeyboardViewState.ChineseFull, KeyboardPage.Main(MainType.FULL), KeyboardLayoutState.Chinese)
                    is KeyboardViewState.NumberPanel -> {
                        Triple(KeyboardViewState.ChineseFull, KeyboardPage.Main(current.returnTo), initialKeyboardLayoutState(isAsciiMode, schemaId))
                    }
                    is KeyboardViewState.CommonSymbolPanel -> {
                        Triple(KeyboardViewState.ChineseFull, KeyboardPage.Main(current.returnTo), initialKeyboardLayoutState(isAsciiMode, schemaId))
                    }
                    else -> Triple(current, _page.value, _keyboardState.value)
                }
            }
            is KeyboardDispatchAction.ModeChange -> {
                val target = if (action.targetIsNumber) KeyboardLayoutAction.SwitchToNumber
                    else KeyboardLayoutAction.SwitchToCommonSymbol
                val newKb = initialKeyboardLayoutState(isAsciiMode, schemaId).transition(target, isAsciiMode, schemaId)
                val newVs: KeyboardViewState = when (newKb) {
                    KeyboardLayoutState.Number -> KeyboardViewState.NumberPanel(MainType.FULL)
                    KeyboardLayoutState.CommonSymbol -> KeyboardViewState.CommonSymbolPanel(MainType.FULL)
                    else -> when (newKb) {
                        is KeyboardLayoutState.Chinese -> KeyboardViewState.ChineseFull
                        is KeyboardLayoutState.English -> KeyboardViewState.EnglishFull
                        is KeyboardLayoutState.T9Pinyin -> KeyboardViewState.T9PinyinFull
                        is KeyboardLayoutState.Stroke -> KeyboardViewState.StrokeFull
                        else -> current
                    }
                }
                val newPage = if (newKb is KeyboardLayoutState.Number || newKb is KeyboardLayoutState.CommonSymbol)
                    KeyboardPage.Panel(
                        if (newKb is KeyboardLayoutState.Number) PanelType.NUMBER else PanelType.COMMON_SYMBOL,
                        MainType.FULL
                    )
                else KeyboardPage.Main(MainType.FULL)
                Triple(newVs, newPage, newKb)
            }
            is KeyboardDispatchAction.ShowNumber -> {
                val returnTo = when (current) {
                    is KeyboardViewState.NumberPanel -> current.returnTo
                    is KeyboardViewState.CommonSymbolPanel -> current.returnTo
                    else -> MainType.FULL
                }
                Triple(KeyboardViewState.NumberPanel(returnTo), KeyboardPage.Panel(PanelType.NUMBER, returnTo), KeyboardLayoutState.Number)
            }
            is KeyboardDispatchAction.ShowCommonSymbol -> {
                val returnTo = when (current) {
                    is KeyboardViewState.NumberPanel -> current.returnTo
                    is KeyboardViewState.CommonSymbolPanel -> current.returnTo
                    else -> MainType.FULL
                }
                Triple(KeyboardViewState.CommonSymbolPanel(returnTo), KeyboardPage.Panel(PanelType.COMMON_SYMBOL, returnTo), KeyboardLayoutState.CommonSymbol)
            }
            is KeyboardDispatchAction.ExitPanel -> {
                when (current) {
                    is KeyboardViewState.NumberPanel, is KeyboardViewState.CommonSymbolPanel -> {
                        val returnTo = (current as? KeyboardViewState.NumberPanel)?.returnTo
                            ?: (current as KeyboardViewState.CommonSymbolPanel).returnTo
                        val (vs, kb) = when (returnTo) {
                            MainType.FULL -> {
                                val kb = initialKeyboardLayoutState(isAsciiMode, schemaId)
                                val vs = when (kb) {
                                    is KeyboardLayoutState.Chinese -> KeyboardViewState.ChineseFull
                                    is KeyboardLayoutState.English -> KeyboardViewState.EnglishFull
                                    is KeyboardLayoutState.T9Pinyin -> KeyboardViewState.T9PinyinFull
                                    is KeyboardLayoutState.Stroke -> KeyboardViewState.StrokeFull
                                    else -> KeyboardViewState.ChineseFull
                                }
                                vs to kb
                            }
                            MainType.HANDWRITING -> KeyboardViewState.Handwriting to KeyboardLayoutState.Chinese
                            MainType.STROKE -> KeyboardViewState.StrokeFull to KeyboardLayoutState.Stroke
                            MainType.VOICE -> KeyboardViewState.Voice to KeyboardLayoutState.English
                        }
                        Triple(vs, KeyboardPage.Main(returnTo), kb)
                    }
                    else -> Triple(current, _page.value, _keyboardState.value)
                }
            }
            is KeyboardDispatchAction.SwitchToT9Pinyin -> {
                Triple(KeyboardViewState.T9PinyinFull, KeyboardPage.Main(MainType.FULL), KeyboardLayoutState.T9Pinyin)
            }
            is KeyboardDispatchAction.SwitchToStroke -> {
                Triple(KeyboardViewState.StrokeFull, KeyboardPage.Main(MainType.FULL), KeyboardLayoutState.Stroke)
            }
            is KeyboardDispatchAction.SwitchToHandwriting -> {
                Triple(KeyboardViewState.Handwriting, KeyboardPage.Main(MainType.HANDWRITING), KeyboardLayoutState.Chinese)
            }
            is KeyboardDispatchAction.AsciiModeChanged -> {
                if (current is KeyboardViewState.NumberPanel || current is KeyboardViewState.CommonSymbolPanel) {
                    Triple(current, _page.value, _keyboardState.value)
                } else if (!action.isAsciiMode && action.schemaId == "handwriting") {
                    Triple(KeyboardViewState.Handwriting, KeyboardPage.Main(MainType.HANDWRITING), KeyboardLayoutState.Chinese)
                } else {
                    val kb = initialKeyboardLayoutState(action.isAsciiMode, action.schemaId)
                    val vs = when (kb) {
                        is KeyboardLayoutState.Chinese -> KeyboardViewState.ChineseFull
                        is KeyboardLayoutState.English -> KeyboardViewState.EnglishFull
                        is KeyboardLayoutState.T9Pinyin -> KeyboardViewState.T9PinyinFull
                        is KeyboardLayoutState.Stroke -> KeyboardViewState.StrokeFull
                        else -> KeyboardViewState.ChineseFull
                    }
                    Triple(vs, KeyboardPage.Main(MainType.FULL), kb)
                }
            }
            is KeyboardDispatchAction.InputSessionStarted -> {
                val kb = initialKeyboardLayoutState(action.isAsciiMode, action.schemaId)
                val vs = when (kb) {
                    is KeyboardLayoutState.Chinese -> KeyboardViewState.ChineseFull
                    is KeyboardLayoutState.English -> KeyboardViewState.EnglishFull
                    is KeyboardLayoutState.T9Pinyin -> KeyboardViewState.T9PinyinFull
                    is KeyboardLayoutState.Stroke -> KeyboardViewState.StrokeFull
                    else -> KeyboardViewState.ChineseFull
                }
                Triple(vs, KeyboardPage.Main(MainType.FULL), kb)
            }
            is KeyboardDispatchAction.ShowOverlay -> {
                Triple(KeyboardViewState.Overlay(action.route, action.backStack, current), KeyboardPage.Overlay(action.route, action.backStack, _page.value), _keyboardState.value)
            }
            is KeyboardDispatchAction.CloseOverlay -> {
                val behind = (current as? KeyboardViewState.Overlay)?.behind ?: current
                val behindPage = (_page.value as? KeyboardPage.Overlay)?.behind ?: _page.value
                Triple(behind, behindPage, _keyboardState.value)
            }
            is KeyboardDispatchAction.PushOverlay -> {
                val ov = current as? KeyboardViewState.Overlay ?: return
                val ovPage = _page.value as? KeyboardPage.Overlay ?: return
                Triple(
                    KeyboardViewState.Overlay(action.route, ov.backStack + ov.route, ov.behind),
                    KeyboardPage.Overlay(action.route, ovPage.backStack + ovPage.route, ovPage.behind),
                    _keyboardState.value
                )
            }
            is KeyboardDispatchAction.PopOverlay -> {
                val ov = current as? KeyboardViewState.Overlay ?: return
                val ovPage = _page.value as? KeyboardPage.Overlay ?: return
                if (ov.backStack.isEmpty()) return
                val prevRoute = ov.backStack.last()
                Triple(
                    KeyboardViewState.Overlay(prevRoute, ov.backStack.dropLast(1), ov.behind),
                    KeyboardPage.Overlay(prevRoute, ovPage.backStack.dropLast(1), ovPage.behind),
                    _keyboardState.value
                )
            }
        }
        _viewState.value = newState
        _page.value = newPage
        if (newKbState is KeyboardLayoutState.English) {
            _isShifted.value = false
            _shiftMode.value = ShiftMode.OFF
        }
        _keyboardState.value = newKbState
    }

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
        } else {
            handwritingShouldReturn = false
        }
        _keyboardState.value = state
        _syncViewState()
    }
    
    private fun _syncViewState() {
        val kb = _keyboardState.value
        val p = _page.value
        val vs: KeyboardViewState = when (p) {
            is KeyboardPage.Overlay -> KeyboardViewState.Overlay(p.route, p.backStack, _viewState.value.let { if (it is KeyboardViewState.Overlay) it.behind else it })
            is KeyboardPage.Panel -> when (p.type) {
                com.kingzcheung.xime.keyboard.PanelType.NUMBER -> KeyboardViewState.NumberPanel(p.returnTo)
                com.kingzcheung.xime.keyboard.PanelType.COMMON_SYMBOL -> KeyboardViewState.CommonSymbolPanel(p.returnTo)
            }
            is KeyboardPage.Main -> when (p.type) {
                com.kingzcheung.xime.keyboard.MainType.FULL -> when (kb) {
                    is KeyboardLayoutState.Chinese -> KeyboardViewState.ChineseFull
                    is KeyboardLayoutState.English -> KeyboardViewState.EnglishFull
                    is KeyboardLayoutState.T9Pinyin -> KeyboardViewState.T9PinyinFull
                    is KeyboardLayoutState.Stroke -> KeyboardViewState.StrokeFull
                    is KeyboardLayoutState.Number -> KeyboardViewState.NumberPanel(com.kingzcheung.xime.keyboard.MainType.FULL)
                    is KeyboardLayoutState.CommonSymbol -> KeyboardViewState.CommonSymbolPanel(com.kingzcheung.xime.keyboard.MainType.FULL)
                    else -> KeyboardViewState.ChineseFull
                }
                com.kingzcheung.xime.keyboard.MainType.HANDWRITING -> KeyboardViewState.Handwriting
                com.kingzcheung.xime.keyboard.MainType.STROKE -> KeyboardViewState.StrokeFull
                com.kingzcheung.xime.keyboard.MainType.VOICE -> KeyboardViewState.Voice
            }
        }
        _viewState.value = vs
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
        _syncViewState()
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
        if (_savedKbStateBeforePanel == null) {
            _savedKbStateBeforePanel = _keyboardState.value
        }
        _page.value = KeyboardPage.Panel(type, mainType)
        _keyboardState.value = when (type) {
            PanelType.NUMBER -> KeyboardLayoutState.Number
            PanelType.COMMON_SYMBOL -> KeyboardLayoutState.CommonSymbol
        }
        _syncViewState()
    }

    /** Level 2: 退出面板，回到主键盘 */
    fun exitPanel() {
        val current = _page.value
        if (current is KeyboardPage.Panel) {
            _page.value = KeyboardPage.Main(current.returnTo)
            val saved = _savedKbStateBeforePanel
            _savedKbStateBeforePanel = null
            _keyboardState.value = when (current.returnTo) {
                com.kingzcheung.xime.keyboard.MainType.FULL -> saved ?: KeyboardLayoutState.Chinese
                else -> KeyboardLayoutState.Chinese
            }
            _syncViewState()
        }
    }

    /** Level 3: 打开覆盖页面，可从任意页面进入 */
    fun showOverlay(route: OverlayRoute, initialBackStack: List<OverlayRoute> = emptyList()) {
        val current = _page.value
        val behind = if (current is KeyboardPage.Overlay) current.behind else current
        _page.value = KeyboardPage.Overlay(route, initialBackStack, behind)
        _syncViewState()
    }

    /** Level 3: 在覆盖页面内推入子页 */
    fun pushOverlay(route: OverlayRoute) {
        val current = _page.value
        if (current is KeyboardPage.Overlay) {
            _page.value = current.copy(
                route = route,
                backStack = current.backStack + current.route
            )
            _syncViewState()
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
            _syncViewState()
        }
    }

    /** Level 3: 关闭覆盖页面，回到背后页面 */
    fun closeOverlay() {
        val current = _page.value
        if (current is KeyboardPage.Overlay) {
            _page.value = current.behind
            _syncViewState()
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
