package com.kingzcheung.xime.ui.keyboard

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kingzcheung.xime.clipboard.ClipboardItem
import com.kingzcheung.xime.handwriting.HandwritingCandidate
import com.kingzcheung.xime.keyboard.KeyboardPage
import com.kingzcheung.xime.rime.RimeEngine
import com.kingzcheung.xime.keyboard.MainType
import com.kingzcheung.xime.keyboard.OverlayRoute
import com.kingzcheung.xime.keyboard.PanelType
import com.kingzcheung.xime.keyboard.ToolbarAction
import com.kingzcheung.xime.keyboard.ToolbarButton
import com.kingzcheung.xime.keyboard.GestureAction
import com.kingzcheung.xime.keyboard.Keycode
import com.kingzcheung.xime.rime.T9InputController
import com.kingzcheung.xime.settings.KeysConfigHelper
import com.kingzcheung.xime.settings.SettingsPreferences
import com.kingzcheung.xime.ui.settings.SchemaListView
import com.kingzcheung.xime.ui.theme.KeyboardThemes
import com.kingzcheung.xime.viewmodel.KeyboardUiState
import com.kingzcheung.xime.viewmodel.KeyboardViewModel
import kotlin.math.abs
import kotlin.math.roundToInt

val LocalStretchFactor = compositionLocalOf { 1f }
val LocalSuppressCursorMove = compositionLocalOf { mutableStateOf(false) }

@Composable
fun KeyboardView(
    viewModel: KeyboardViewModel,
    state: KeyboardUiState,
    callbacks: KeyboardCallbacks,
    modifier: Modifier = Modifier,
    onCardPositioned: (left: Int, top: Int, right: Int, bottom: Int) -> Unit = { _: Int, _: Int, _: Int, _: Int -> },
) {
    val isShifted by viewModel.isShifted.collectAsStateWithLifecycle()
    val keyboardState by viewModel.keyboardState.collectAsStateWithLifecycle()
    val page by viewModel.page.collectAsStateWithLifecycle()
    val viewState by viewModel.viewState.collectAsStateWithLifecycle()
    val ctrlSticky by viewModel.ctrlSticky.collectAsStateWithLifecycle()
    val altSticky by viewModel.altSticky.collectAsStateWithLifecycle()
    val isClipboardSearching by viewModel.isClipboardSearching.collectAsStateWithLifecycle()
    val clipboardSearchQuery by viewModel.clipboardSearchQuery.collectAsStateWithLifecycle()
    val isLandscape = if (state.isFloatingMode) false
        else LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE

    SideEffect {
        val active = (keyboardState is KeyboardLayoutState.Chinese || keyboardState is KeyboardLayoutState.Stroke || keyboardState is KeyboardLayoutState.T9Pinyin)
            && page is KeyboardPage.Main && (page as KeyboardPage.Main).type == MainType.FULL
        callbacks.onKeyboardModeChange?.invoke(active)
    }

    var savedNumberAsciiMode by remember { mutableStateOf<Boolean?>(null) }

    val t9Controller = remember {
        T9InputController(
            onReplaceFullPinyin = { pinyin ->
                callbacks.onT9ReplaceFullPinyin?.invoke(pinyin)
            },
            onQueryRimeComposition = null,
            onRightCommitUndone = { count ->
                callbacks.onT9RightCommitUndone?.invoke(count)
            }
        )
    }

    LaunchedEffect(state.inputSessionId) {
        t9Controller.reset()
        viewModel.dispatch(
            KeyboardDispatchAction.InputSessionStarted(state.isAsciiMode, state.currentSchemaId)
        )
    }

    LaunchedEffect(state.isAsciiMode, state.currentSchemaId) {
        viewModel.dispatch(
            KeyboardDispatchAction.AsciiModeChanged(state.isAsciiMode, state.currentSchemaId)
        )
    }

    SideEffect {
        callbacks.onT9RightCandidateWillBeSelected = { pinyin ->
            t9Controller.onRightCandidateSelected(pinyin)
            t9Controller.inputBuffer.isEmpty()
        }
    }

    LaunchedEffect(state.t9ResetSignal) {
        t9Controller.reset()
    }

    LaunchedEffect(keyboardState) {
        if (keyboardState is KeyboardLayoutState.Number) {
            if (savedNumberAsciiMode == null) {
                savedNumberAsciiMode = state.isAsciiMode
                if (!state.isAsciiMode && page is KeyboardPage.Main) {
                    callbacks.onKeyPress("ime_switch", false)
                }
            }
        } else {
            savedNumberAsciiMode = null
        }
    }

    val kbColors = KeysConfigHelper.getKeyboardColors()
    val kbShadow = KeysConfigHelper.getKeyboardShadow()
    val kbKey = KeysConfigHelper.getKeyboardKeyConfig()
    val longToColor: (Long) -> androidx.compose.ui.graphics.Color = { androidx.compose.ui.graphics.Color(0xFF000000 or it) }
    val keyboardBgColor = if (state.isDarkTheme) longToColor(kbColors.keyboardBgColorDark)
        else longToColor(kbColors.keyboardBgColor)
    val keyBgColor = if (state.isDarkTheme) longToColor(kbColors.keyBgColorDark)
        else longToColor(kbColors.keyBgColor)
    val keyTextColor = if (state.isDarkTheme) longToColor(kbColors.keyTextColorDark)
        else longToColor(kbColors.keyTextColor)
    val accentColor = KeyboardThemes.getAccentColor(state.themeId, state.isDarkTheme)
    val themeSpecialKeyColor = KeyboardThemes.getSpecialKeyColor(state.themeId, state.isDarkTheme)
    val specialKeyBgColor = if (state.isDarkTheme) kbColors.specialKeyBgColorDark?.let { longToColor(it) }
        ?: themeSpecialKeyColor
        else kbColors.specialKeyBgColor?.let { longToColor(it) } ?: themeSpecialKeyColor
    val candidateBarBg = if (state.isDarkTheme) longToColor(kbColors.candidateBarBgColorDark)
        else longToColor(kbColors.candidateBarBgColor)
    val candidateTextColor = if (state.isDarkTheme) longToColor(kbColors.candidateTextColorDark)
        else longToColor(kbColors.candidateTextColor)
    val dividerColor = if (state.isDarkTheme) androidx.compose.ui.graphics.Color(0xFF3C4043) else androidx.compose.ui.graphics.Color(0xFFDADCE0)

    val clipboardTab = (page as? KeyboardPage.Overlay)?.let {
        (it.route as? OverlayRoute.Clipboard)?.tab
    } ?: 0
    val screenW = LocalConfiguration.current.screenWidthDp
    val screenH = LocalConfiguration.current.screenHeightDp
    val portraitScreenWidth = minOf(screenW, screenH)
    val cardWidthDp = (portraitScreenWidth * 0.85f).roundToInt()
    val floatScaleFactor = if (state.isFloatingMode) cardWidthDp.toFloat() / screenW.toFloat() else 0.85f
    val floatFontScale = if (state.isFloatingMode) cardWidthDp.toFloat() / portraitScreenWidth.toFloat() else 1f

    val contentModifier = modifier.background(keyboardBgColor)
    FloatingKeyboardContainer(
        isFloatingMode = state.isFloatingMode,
        scaleFactor = floatScaleFactor,
        fontScaleFactor = floatFontScale,
        offsetX = state.floatingOffsetX,
        offsetY = state.floatingOffsetY,
        minOffsetY = state.floatingMinOffsetY,
        backgroundColor = keyboardBgColor,
        onDrag = { dx, dy -> callbacks.onFloatingKeyboardDrag?.invoke(dx, dy) },
        onDragEnd = { callbacks.onFloatingKeyboardDragEnd?.invoke() },
        onCardPositioned = onCardPositioned,
    ) {
    Box(modifier = contentModifier) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
        ) {
            var handwritingCandidates by remember { mutableStateOf<List<String>>(emptyList()) }
            var handwritingComments by remember { mutableStateOf<List<String>>(emptyList()) }
            var handwritingClearSignal by remember { mutableIntStateOf(0) }
            var isHandwritingLookup by remember { mutableStateOf(false) }

            val isHandwritingPage = page is KeyboardPage.Main && (page as KeyboardPage.Main).type == MainType.HANDWRITING
            val showHandwritingCandidates = (isHandwritingPage || isHandwritingLookup) && handwritingCandidates.isNotEmpty()

            val candidateBarState = remember(
                state.candidates, state.candidateComments, state.inputText, state.preeditText, state.isComposing,
                state.associationCandidates, state.isShowingRecentClipboard, state.hasNextPage,
                state.isCalculatorMode, handwritingCandidates, handwritingComments, showHandwritingCandidates,
            ) {
                if (showHandwritingCandidates) {
                    CandidateBarState.AssociationOnly(
                        candidates = handwritingCandidates,
                        comments = handwritingComments,
                        highlightIndex = 0,
                    )
                } else {
                    CandidateBarState.from(
                        candidates = state.candidates,
                        candidateComments = state.candidateComments,
                        inputText = state.inputText,
                        preeditText = state.preeditText,
                        isComposing = state.isComposing,
                        associationCandidates = state.associationCandidates,
                        isShowingRecentClipboard = state.isShowingRecentClipboard,
                        hasNextPage = state.hasNextPage,
                        isCalculatorActive = state.isCalculatorMode,
                    )
                }
            }

            if (isClipboardSearching) {
                // ── 搜索模式：搜索框 + 剪贴板列表 ──
                val focusRequester = remember { FocusRequester() }
                val filteredItems = remember(state.clipboardItems, clipboardSearchQuery) {
                    if (clipboardSearchQuery.isEmpty()) state.clipboardItems
                    else state.clipboardItems.filter { it.text.contains(clipboardSearchQuery, ignoreCase = true) }
                }
                LaunchedEffect(Unit) { focusRequester.requestFocus() }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(
                        onClick = {
                            viewModel.exitClipboardSearch()
                            callbacks.onKeyPress("clear_composition", false)
                        },
                        modifier = Modifier.size(30.dp)
                    ) {
                        Icon(Icons.Outlined.Close, "退出搜索", tint = accentColor, modifier = Modifier.size(20.dp))
                    }
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(34.dp)
                            .clip(RoundedCornerShape(17.dp))
                            .background(if (state.isDarkTheme) Color(0xFF374151) else Color(0xFFF3F4F6))
                            .padding(horizontal = 10.dp),
                        contentAlignment = Alignment.CenterStart
                    ) {
                        if (clipboardSearchQuery.isEmpty()) {
                            Text("搜索剪贴板...", color = candidateTextColor.copy(alpha = 0.5f), fontSize = 13.sp)
                        }
                        BasicTextField(
                            value = clipboardSearchQuery,
                            onValueChange = { viewModel.updateClipboardSearchQuery(it) },
                            singleLine = true,
                            textStyle = TextStyle(color = candidateTextColor, fontSize = 13.sp),
                            cursorBrush = SolidColor(accentColor),
                            modifier = Modifier.fillMaxWidth().focusRequester(focusRequester)
                        )
                    }
                    if (clipboardSearchQuery.isNotEmpty()) {
                        Spacer(modifier = Modifier.width(4.dp))
                        IconButton(
                            onClick = { viewModel.updateClipboardSearchQuery("") },
                            modifier = Modifier.size(30.dp)
                        ) {
                            Icon(Icons.Outlined.Close, "清空", tint = candidateTextColor.copy(alpha = 0.6f), modifier = Modifier.size(18.dp))
                        }
                    }
                }
                if (clipboardSearchQuery.isNotEmpty()) {
                    if (filteredItems.isEmpty()) {
                        Box(
                            modifier = Modifier.fillMaxWidth().height(28.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text("无匹配结果", color = candidateTextColor.copy(alpha = 0.5f), fontSize = 12.sp)
                        }
                    } else {
                        LazyColumn(
                            modifier = Modifier.fillMaxWidth().heightIn(max = 56.dp),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                            verticalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            items(filteredItems, key = { it.id }) { item ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(24.dp)
                                        .clip(RoundedCornerShape(6.dp))
                                        .background(candidateBarBg)
                                        .clickable {
                                            callbacks.onClipboardSelect?.invoke(item.text)
                                            viewModel.exitClipboardSearch()
                                        }
                                        .padding(horizontal = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = item.text,
                                        color = candidateTextColor,
                                        fontSize = 12.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f)
                                    )
                                }
                            }
                        }
                    }
                }
            }
            CandidateBar(
                state = candidateBarState,
                page = page,
                isFloatingMode = state.isFloatingMode,
                toolbarActions = state.toolbarButtons.mapNotNull { id ->
                    val button = ToolbarButton.fromId(id) ?: return@mapNotNull null
                    if (button == ToolbarButton.HANDWRITING_LOOKUP) {
                        val mf = java.io.File(LocalContext.current.filesDir, "ochwpro.onnx")
                        val cf = java.io.File(LocalContext.current.filesDir, "char_index.json")
                        if (!mf.exists() || !cf.exists()) return@mapNotNull null
                    }
                    val onClick: () -> Unit = when (button) {
                        ToolbarButton.EMOJI -> ({ viewModel.showOverlay(OverlayRoute.Emoji) })
                        ToolbarButton.CLIPBOARD -> ({ viewModel.showOverlay(OverlayRoute.Clipboard(0)) })
                        ToolbarButton.SCHEMA -> ({ viewModel.showOverlay(OverlayRoute.SchemaList, listOf(OverlayRoute.Menu)) })
                        ToolbarButton.QUICK_PHRASE -> ({ viewModel.showOverlay(OverlayRoute.Clipboard(1)) })
                        ToolbarButton.SYMBOL -> ({ viewModel.showOverlay(OverlayRoute.Symbol) })
                        ToolbarButton.SELECT_ALL -> ({ callbacks.onToolbarEditingAction?.invoke("select_all") })
                        ToolbarButton.COPY -> ({ callbacks.onToolbarEditingAction?.invoke("copy") })
                        ToolbarButton.PASTE -> ({ callbacks.onToolbarEditingAction?.invoke("paste") })
                        ToolbarButton.HOME -> ({ callbacks.onToolbarEditingAction?.invoke("home") })
                        ToolbarButton.END -> ({ callbacks.onToolbarEditingAction?.invoke("end") })
                        ToolbarButton.FLOAT -> ({ callbacks.onFloatingModeChange?.invoke(!state.isFloatingMode) })
                        ToolbarButton.HANDWRITING_LOOKUP -> ({ isHandwritingLookup = !isHandwritingLookup })
                    }
                    ToolbarAction(button, onClick)
                },
                visuals = CandidateBarVisuals(
                    backgroundColor = candidateBarBg,
                    textColor = candidateTextColor,
                    dividerColor = dividerColor,
                    accentColor = accentColor,
                    isDarkTheme = state.isDarkTheme
                ),
                callbacks = CandidateBarCallbacks(
                    onCandidateSelect = { index ->
                        if (isClipboardSearching) {
                            val text = state.candidates.getOrNull(index) ?: return@CandidateBarCallbacks
                            viewModel.updateClipboardSearchQuery(clipboardSearchQuery + text)
                            callbacks.onKeyPress("clear_composition", false)
                        } else if (showHandwritingCandidates && index in handwritingCandidates.indices) {
                            val ch = handwritingCandidates[index]
                            callbacks.onCommitText?.invoke(ch)
                            handwritingCandidates = emptyList()
                            handwritingComments = emptyList()
                            handwritingClearSignal++
                        } else {
                            callbacks.onCandidateSelect(index)
                        }
                    },
                    onClearAssociation = {
                        if (isClipboardSearching) {
                            callbacks.onKeyPress("clear_composition", false)
                        } else if (showHandwritingCandidates) {
                            handwritingCandidates = emptyList()
                            handwritingComments = emptyList()
                            handwritingClearSignal++
                        } else {
                            callbacks.onClearAssociation?.invoke()
                        }
                    },
                    onLogoClick = {
                        if (isClipboardSearching) {
                            viewModel.exitClipboardSearch()
                        } else {
                            viewModel.showOverlay(OverlayRoute.Menu)
                        }
                    },
                    onBack = {
                        if (isClipboardSearching) {
                            viewModel.exitClipboardSearch()
                            callbacks.onKeyPress("clear_composition", false)
                        } else if (showHandwritingCandidates) {
                            handwritingCandidates = emptyList()
                            handwritingComments = emptyList()
                            handwritingClearSignal++
                        } else {
                            when (page) {
                                is KeyboardPage.Overlay -> {
                                    if ((page as KeyboardPage.Overlay).backStack.isEmpty())
                                        viewModel.closeOverlay()
                                    else viewModel.popOverlay()
                                }
                                is KeyboardPage.Panel -> viewModel.exitPanel()
                                is KeyboardPage.Main -> {}
                            }
                        }
                    },
                    onHideKeyboard = {
                        if (isClipboardSearching) {
                            viewModel.exitClipboardSearch()
                        }
                        callbacks.onHideKeyboard?.invoke()
                        viewModel.resetKeyboard(state.isAsciiMode, state.currentSchemaId)
                    },
                    onShowMoreCandidates = {
                        if (!isClipboardSearching) {
                            viewModel.showOverlay(OverlayRoute.CandidatePage)
                        }
                    },
                    onInputTextClick = {
                        if (isClipboardSearching) {
                            viewModel.updateClipboardSearchQuery(clipboardSearchQuery + state.inputText)
                            callbacks.onKeyPress("clear_composition", false)
                        } else if (state.inputText.isNotEmpty()) {
                            callbacks.onClipboardSelect?.invoke(state.inputText)
                        }
                    },
                    onAssociationSelect = { index ->
                        if (isClipboardSearching) {
                            val text = state.associationCandidates.getOrNull(index) ?: return@CandidateBarCallbacks
                            viewModel.updateClipboardSearchQuery(clipboardSearchQuery + text)
                            callbacks.onKeyPress("clear_composition", false)
                        } else if (showHandwritingCandidates && index in handwritingCandidates.indices) {
                            val ch = handwritingCandidates[index]
                            callbacks.onCommitText?.invoke(ch)
                            handwritingCandidates = emptyList()
                            handwritingComments = emptyList()
                            handwritingClearSignal++
                        } else {
                            callbacks.onAssociationSelect?.invoke(index)
                        }
                    },
                )
            )
            // end else (ClipboardSearchBar) — 搜索模式下候选栏也显示

            val isMainKeyboard = page is KeyboardPage.Main
            if (isMainKeyboard) {
                val mainType = (page as KeyboardPage.Main).type
                when (mainType) {
                    MainType.FULL -> {
                        val currentOnCursorMove = rememberUpdatedState(callbacks.onCursorMove)
                        val suppressCursorMove = remember { mutableStateOf(false) }
                        val cursorMod = if (callbacks.onCursorMove != null) {
                            Modifier.pointerInput(Unit) {
                                val stepThresholdPx = 25.dp.toPx()
                                val activationThresholdPx = 60.dp.toPx()
                                awaitEachGesture {
                                    suppressCursorMove.value = false
                                    val down = awaitFirstDown(requireUnconsumed = false)
                                    var isCursorGesture = false
                                    var lastSteps = 0
                                    var activationAnchorX = down.position.x

                                    do {
                                        val event = awaitPointerEvent()
                                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                        val dx = change.position.x - down.position.x
                                        val dy = change.position.y - down.position.y

                                        if (!change.pressed) {
                                            if (isCursorGesture) {
                                                event.changes.forEach { it.consume() }
                                            }
                                            break
                                        }
                                        if (suppressCursorMove.value) break
                                        if (abs(dx) > abs(dy) * 4f) {

                                            if (!isCursorGesture && abs(dx) > activationThresholdPx) {
                                                isCursorGesture = true
                                                activationAnchorX = change.position.x
                                            }

                                            if (isCursorGesture) {
                                                event.changes.forEach { it.consume() }
                                                val dxFromAnchor = change.position.x - activationAnchorX
                                                val steps = (dxFromAnchor / stepThresholdPx).toInt()
                                                if (steps != lastSteps) {
                                                    val delta = steps - lastSteps
                                                    currentOnCursorMove.value?.invoke(delta)
                                                    lastSteps = steps
                                                }
                                            }
                                        }
                                    } while (true)
                                }
                            }
                        } else {
                            Modifier
                        }

                        val context = LocalContext.current

                        var modeChangeTarget: KeyboardLayoutAction by remember {
                            mutableStateOf(
                                if (SettingsPreferences.getModeChangeTargetIsNumber(context))
                                    KeyboardLayoutAction.SwitchToNumber
                                else
                                    KeyboardLayoutAction.SwitchToCommonSymbol
                            )
                        }

                        val fullScreenOnKeyPress: (String) -> Unit = KeyPress@{ key ->
                            when (key) {
                                "shift" -> viewModel.toggleShift()
                                "shift_single" -> viewModel.singleTapShift()
                                "shift_caps" -> viewModel.doubleTapShift()
                                "mode_change" -> {
                                    viewModel.setKeyboardState(keyboardState.transition(
                                        modeChangeTarget, state.isAsciiMode
                                    ))
                                    callbacks.onKeyPress("clear_composition", false)
                                }
                                "mode_change_symbol" -> viewModel.showOverlay(OverlayRoute.Symbol)
                                "mode_change_number" -> {
                                    modeChangeTarget = KeyboardLayoutAction.SwitchToNumber
                                    SettingsPreferences.setModeChangeTargetIsNumber(context, true)
                                    viewModel.setKeyboardState(KeyboardLayoutState.Number)
                                }
                                "mode_change_common_symbol" -> {
                                    modeChangeTarget = KeyboardLayoutAction.SwitchToCommonSymbol
                                    SettingsPreferences.setModeChangeTargetIsNumber(context, false)
                                    viewModel.setKeyboardState(keyboardState.transition(
                                        KeyboardLayoutAction.SwitchToCommonSymbol, state.isAsciiMode
                                    ))
                                }
                                "emoji" -> viewModel.showOverlay(OverlayRoute.Emoji)
                                else -> {
                                    // 搜索模式：BackSpace/delete 优先删除搜索查询内容
                                    if (isClipboardSearching && (key == "BackSpace" || key == "Delete" || key == "delete")) {
                                        if (clipboardSearchQuery.isNotEmpty()) {
                                            viewModel.updateClipboardSearchQuery(clipboardSearchQuery.dropLast(1))
                                            return@KeyPress
                                        }
                                    }
                                    // 搜索模式：ASCII 模式下单字符/空格直接追加到搜索框
                                    if (isClipboardSearching && state.isAsciiMode) {
                                        if (key.length == 1) {
                                            viewModel.updateClipboardSearchQuery(clipboardSearchQuery + key)
                                            return@KeyPress
                                        }
                                        if (key == "space") {
                                            viewModel.updateClipboardSearchQuery(clipboardSearchQuery + " ")
                                            return@KeyPress
                                        }
                                    }
                                    // 粘滞修饰键：Ctrl/Alt 激活时，字母键发送组合键
                                    val sendExpr = buildStickySendExpr(key, ctrlSticky, altSticky, isShifted)
                                    if (sendExpr != null) {
                                        callbacks.onGestureAction?.invoke(GestureAction.SEND_KEY, sendExpr)
                                        viewModel.onCharacterTyped()
                                    } else {
                                        callbacks.onKeyPress(key, isShifted)
                                        viewModel.onCharacterTyped()
                                    }
                                }
                            }
                        }
                        val numberOnKeyPress: (String) -> Unit = { key ->
                            if (isClipboardSearching && state.isAsciiMode && key.length == 1) {
                                viewModel.updateClipboardSearchQuery(clipboardSearchQuery + key)
                            } else when (key) {
                                "abc" -> {
                                    val saved = savedNumberAsciiMode
                                    savedNumberAsciiMode = null
                                    if (saved != null && saved != state.isAsciiMode) {
                                        callbacks.onKeyPress("ime_switch", false)
                                    }
                                    viewModel.setKeyboardState(
                                        initialKeyboardLayoutState(saved ?: state.isAsciiMode, state.currentSchemaId)
                                    )
                                }
                                "symbol" -> {
                                    val saved = savedNumberAsciiMode
                                    savedNumberAsciiMode = null
                                    if (saved != null && saved != state.isAsciiMode) {
                                        callbacks.onKeyPress("ime_switch", false)
                                    }
                                    viewModel.showOverlay(OverlayRoute.Symbol)
                                }
                                "emoji" -> {
                                    val saved = savedNumberAsciiMode
                                    savedNumberAsciiMode = null
                                    if (saved != null && saved != state.isAsciiMode) {
                                        callbacks.onKeyPress("ime_switch", false)
                                    }
                                    viewModel.showOverlay(OverlayRoute.Emoji)
                                }
                                else -> callbacks.onKeyPress(key, false)
                            }
                        }
                        val symbolOnKeyPress: (String) -> Unit = { key ->
                            if (isClipboardSearching && state.isAsciiMode && key.length == 1) {
                                viewModel.updateClipboardSearchQuery(clipboardSearchQuery + key)
                            } else when (key) {
                                "abc" -> viewModel.setKeyboardState(
                                    initialKeyboardLayoutState(state.isAsciiMode, state.currentSchemaId)
                                )
                                "?123" -> {
                                    viewModel.setKeyboardState(keyboardState.transition(
                                        KeyboardLayoutAction.SwitchToNumber, state.isAsciiMode
                                    ))
                                    callbacks.onKeyPress("clear_composition", false)
                                }
                                else -> callbacks.onKeyPress(key, false)
                            }
                        }
                        val commonSymbolOnKeyPress: (String) -> Unit = { key ->
                            if (isClipboardSearching && state.isAsciiMode && key.length == 1) {
                                viewModel.updateClipboardSearchQuery(clipboardSearchQuery + key)
                            } else when (key) {
                                "abc" -> viewModel.setKeyboardState(
                                    initialKeyboardLayoutState(state.isAsciiMode, state.currentSchemaId)
                                )
                                "number" -> viewModel.setKeyboardState(keyboardState.transition(
                                    KeyboardLayoutAction.SwitchToNumber, state.isAsciiMode
                                ))
                                "symbol" -> viewModel.showOverlay(OverlayRoute.Symbol)
                                "emoji" -> viewModel.showOverlay(OverlayRoute.Emoji)
                                else -> callbacks.onKeyPress(key, false)
                            }
                        }
                        val strokeOnKeyPress: (String) -> Unit = { key ->
                            if (isClipboardSearching && state.isAsciiMode && key.length == 1) {
                                viewModel.updateClipboardSearchQuery(clipboardSearchQuery + key)
                            } else when (key) {
                                "abc" -> viewModel.setKeyboardState(keyboardState.transition(
                                    KeyboardLayoutAction.SwitchToFull, state.isAsciiMode
                                ))
                                "number" -> viewModel.setKeyboardState(keyboardState.transition(
                                    KeyboardLayoutAction.SwitchToNumber, state.isAsciiMode
                                ))
                                "symbol" -> viewModel.showOverlay(OverlayRoute.Symbol)
                                "emoji" -> viewModel.showOverlay(OverlayRoute.Emoji)
                                else -> callbacks.onKeyPress(key, false)
                            }
                        }
                        val t9OnKeyPress: (String) -> Unit = { key ->
                            when (key) {
                                "abc" -> viewModel.setKeyboardState(
                                    initialKeyboardLayoutState(state.isAsciiMode, state.currentSchemaId)
                                )
                                "number" -> {
                                    callbacks.onT9SwitchAway?.invoke()
                                    viewModel.setKeyboardState(keyboardState.transition(
                                        KeyboardLayoutAction.SwitchToNumber, state.isAsciiMode
                                    ))
                                }
                                "symbol" -> viewModel.showOverlay(OverlayRoute.Symbol)
                                "emoji" -> viewModel.showOverlay(OverlayRoute.Emoji)
                                "ime_switch" -> {
                                    callbacks.onT9SwitchAway?.invoke()
                                    callbacks.onKeyPress(key, false)
                                }
                                else -> callbacks.onKeyPress(key, false)
                            }
                        }
                        val currentOnKeyPress = when (keyboardState) {
                            is KeyboardLayoutState.Chinese,
                            is KeyboardLayoutState.English -> fullScreenOnKeyPress
                            is KeyboardLayoutState.Number -> numberOnKeyPress
                            is KeyboardLayoutState.CommonSymbol -> commonSymbolOnKeyPress
                            is KeyboardLayoutState.Stroke -> strokeOnKeyPress
                            is KeyboardLayoutState.T9Pinyin -> t9OnKeyPress
                            is KeyboardLayoutState.Symbol -> symbolOnKeyPress
                        }
                        CompositionLocalProvider(LocalSuppressCursorMove provides suppressCursorMove) {
                            KeyboardLayoutScreen(
                                keyboardState = keyboardState,
                                uiState = state,
                                viewModel = viewModel,
                                callbacks = callbacks,
                                onKeyPress = currentOnKeyPress,
                                modifier = Modifier.weight(1f).then(cursorMod),
                                isHandwritingLookup = isHandwritingLookup,
                                onHandwritingCandidates = { candidates ->
                                    val chars = candidates.map { it.char }
                                    handwritingCandidates = chars
                                    handwritingComments = chars.map { RimeEngine.getInstance().lookupText(it) }
                                },
                                onHandwritingButtonFeedback = { key -> callbacks.onKeyPressDown?.invoke(key) },
                                handwritingClearSignal = handwritingClearSignal,
                                onHandwritingLookupExit = { isHandwritingLookup = false },
                                t9Controller = t9Controller,
                            )
                            if (state.keyboardBottomPaddingDp > 0) {
                                Spacer(modifier = Modifier.height(state.keyboardBottomPaddingDp.dp))
                            }
                        }
                    }

                    MainType.HANDWRITING -> {
                        HandwritingKeyboardLayout(
                            onKeyPress = { key ->
                                when (key) {
                                    "delete" -> {
                                        if (handwritingCandidates.isNotEmpty()) {
                                            handwritingCandidates = emptyList()
                                            handwritingComments = emptyList()
                                            handwritingClearSignal++
                                        } else {
                                            callbacks.onKeyPress("delete", false)
                                        }
                                    }
                                    "symbol" -> viewModel.enterPanel(PanelType.COMMON_SYMBOL)
                                    "number" -> viewModel.enterPanel(PanelType.NUMBER)
                                    "ime_switch" -> {
                                        viewModel.switchMain(MainType.FULL)
                                        viewModel.setKeyboardState(KeyboardLayoutState.English)
                                        callbacks.onKeyPress("ime_switch", false)
                                    }
                                    "space" -> {
                                        if (handwritingCandidates.isNotEmpty()) {
                                            val ch = handwritingCandidates[0]
                                            callbacks.onCommitText?.invoke(ch)
                                            handwritingCandidates = emptyList()
                                            handwritingComments = emptyList()
                                            handwritingClearSignal++
                                        } else {
                                            callbacks.onKeyPress("space", false)
                                        }
                                    }
                                    "enter" -> callbacks.onKeyPress("enter", false)
                                    else -> callbacks.onCommitText?.invoke(key)
                                }
                            },
                            onCandidates = { candidates ->
                                handwritingCandidates = candidates.map { it.char }
                                handwritingComments = emptyList()
                            },
                            onButtonFeedback = { key ->
                                callbacks.onKeyPressDown?.invoke(key)
                            },
                            clearSignal = handwritingClearSignal,
                            keyBackgroundColor = keyBgColor,
                            keyTextColor = keyTextColor,
                            specialKeyBackgroundColor = specialKeyBgColor,
                            modifier = Modifier.weight(1f),
                        )
                        if (state.keyboardBottomPaddingDp > 0) {
                            Spacer(modifier = Modifier.height(state.keyboardBottomPaddingDp.dp))
                        }
                    }

                    MainType.STROKE -> {
                        // Stroke is handled via keyboardState within FULL for now
                    }

                    MainType.VOICE -> {
                        VoiceKeyboardLayout(
                            keyBackgroundColor = keyBgColor,
                            keyTextColor = keyTextColor,
                            specialKeyBackgroundColor = specialKeyBgColor,
                            keyboardBackgroundColor = keyboardBgColor,
                            modifier = Modifier.weight(1f),
                            isDarkTheme = state.isDarkTheme,
                            themeId = state.themeId,
                            bottomActive = state.voiceBottomActive,
                            leftActive = state.voiceLeftActive,
                            rightActive = state.voiceRightActive,
                            pluginName = state.voicePluginName,
                            recognitionState = state.voiceRecognitionState,
                            recognizedText = state.voiceRecognizedText,
                            amplitude = state.voiceAmplitude
                        )
                    }
                }
            }

            val isPanelKeyboard = page is KeyboardPage.Panel
            if (isPanelKeyboard) {
                val panelType = (page as KeyboardPage.Panel).type
                when (panelType) {
                    PanelType.NUMBER -> NumberKeyboardLayout(
                        onKeyPress = { key ->
                            when (key) {
                                "abc" -> viewModel.exitPanel()
                                "symbol" -> {
                                    val saved = savedNumberAsciiMode
                                    savedNumberAsciiMode = null
                                    if (saved != null && saved != state.isAsciiMode) {
                                        callbacks.onKeyPress("ime_switch", false)
                                    }
                                    viewModel.showOverlay(OverlayRoute.Symbol)
                                }
                                "emoji" -> {
                                    val saved = savedNumberAsciiMode
                                    savedNumberAsciiMode = null
                                    if (saved != null && saved != state.isAsciiMode) {
                                        callbacks.onKeyPress("ime_switch", false)
                                    }
                                    viewModel.showOverlay(OverlayRoute.Emoji)
                                }
                                else -> callbacks.onKeyPress(key, false)
                            }
                        },
                        keyBackgroundColor = keyBgColor,
                        keyTextColor = keyTextColor,
                        specialKeyBackgroundColor = specialKeyBgColor,
                        keyboardBackgroundColor = keyboardBgColor,
                        shadowEnabled = kbShadow.enabled,
                        shadowElevation = kbShadow.elevation.dp,
                        shadowShapeRadius = kbShadow.shapeRadius.dp,
                        keyCornerRadius = kbKey.cornerRadius.dp,
                        onKeyPressDown = callbacks.onKeyPressDown,
                        isFloatingMode = state.isFloatingMode,
                        modifier = Modifier.weight(1f).fillMaxWidth()
                    )

                    PanelType.COMMON_SYMBOL -> CommonSymbolKeyboardLayout(
                        onKeyPress = { key ->
                            when (key) {
                                "abc" -> viewModel.exitPanel()
                                "number" -> viewModel.enterPanel(PanelType.NUMBER)
                                "symbol" -> viewModel.showOverlay(OverlayRoute.Symbol)
                                "emoji" -> viewModel.showOverlay(OverlayRoute.Emoji)
                                else -> callbacks.onKeyPress(key, false)
                            }
                        },
                        isAsciiMode = state.isAsciiMode,
                        keyBackgroundColor = keyBgColor,
                        keyTextColor = keyTextColor,
                        specialKeyBackgroundColor = specialKeyBgColor,
                        keyboardBackgroundColor = keyboardBgColor,
                        shadowEnabled = kbShadow.enabled,
                        shadowElevation = kbShadow.elevation.dp,
                        shadowShapeRadius = kbShadow.shapeRadius.dp,
                        keyCornerRadius = kbKey.cornerRadius.dp,
                        onKeyPressDown = callbacks.onKeyPressDown,
                        isFloatingMode = state.isFloatingMode,
                        modifier = Modifier.weight(1f).fillMaxWidth()
                    )

                }
            }

            val configuration = LocalConfiguration.current
            val isLandscapeBottom = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        }

        if (state.isDeploying) {
            val isError = state.deploymentMessage.contains("超时") || state.deploymentMessage.contains("失败")
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight()
                    .background(keyboardBgColor.copy(alpha = 0.9f))
                    .clickable(enabled = isError && callbacks.onDismissDeploying != null) {
                        callbacks.onDismissDeploying?.invoke()
                    },
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = state.deploymentMessage.ifEmpty { "正在初始�?.." },
                        color = keyTextColor,
                        style = androidx.compose.material3.MaterialTheme.typography.bodyLarge
                    )
                    if (isError) {
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = "点击关闭",
                            color = keyTextColor.copy(alpha = 0.5f),
                            style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                            textAlign = TextAlign.Center
                        )
                    } else {
                        Text(
                            text = "???",
                            color = keyTextColor.copy(alpha = 0.7f),
                            style = androidx.compose.material3.MaterialTheme.typography.bodyMedium
                        )
                    }
                }
            }
        }

        if (page is KeyboardPage.Overlay) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight()
                    .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null
                ) { }
            ) {
            when (val p = page) {
                is KeyboardPage.Overlay -> when (p.route) {
                    is OverlayRoute.Menu -> MenuBar(
                        state = MenuBarState(
                            isVisible = true,
                            isDarkTheme = state.isDarkTheme,
                            darkMode = state.darkMode,
                            backgroundColor = keyboardBgColor,
                            isFloatingMode = state.isFloatingMode,
                        ),
                        callbacks = MenuBarCallbacks(
                            onDismiss = { viewModel.closeOverlay() },
                            onClipboard = { viewModel.showOverlay(OverlayRoute.Clipboard(0)); callbacks.onClipboard?.invoke() },
                            onQuickSend = { viewModel.showOverlay(OverlayRoute.Clipboard(1)); callbacks.onQuickSend?.invoke() },
                            onKeyboardResize = { callbacks.onKeyboardResize?.invoke(); viewModel.closeOverlay() },
                            onEmoji = { viewModel.showOverlay(OverlayRoute.Emoji) },
                            onReloadConfig = { callbacks.onReloadConfig?.invoke(); viewModel.closeOverlay() },
                            onSettings = { callbacks.onSettings?.invoke(); viewModel.closeOverlay() },
                            onSchemaList = { viewModel.pushOverlay(OverlayRoute.SchemaList) },
                            onToggleDarkMode = { callbacks.onToggleDarkMode?.invoke() },
                            onToolbarCustomize = { viewModel.showOverlay(OverlayRoute.ToolbarCustomize) },
                            onFloatingModeToggle = { callbacks.onFloatingModeChange?.invoke(!state.isFloatingMode); viewModel.closeOverlay() },
                        ),
                        modifier = Modifier.fillMaxWidth().fillMaxHeight()
                    )
                    is OverlayRoute.SchemaList -> SchemaListView(
                        schemas = state.schemas,
                        currentSchemaId = state.currentSchemaId,
                        isDarkTheme = state.isDarkTheme,
                        backgroundColor = keyboardBgColor,
                        accentColor = accentColor,
                        onSelectSchema = { schemaId ->
                            callbacks.onSwitchSchema?.invoke(schemaId)
                            viewModel.closeOverlay()
                        },
                        onBack = { viewModel.popOverlay() },
                        modifier = Modifier.fillMaxWidth().fillMaxHeight()
                    )
                    is OverlayRoute.Clipboard -> ClipboardView(
                        clipboardItems = state.clipboardItems,
                        quickSendItems = state.quickSendItems,
                        selectedTab = p.route.tab,
                        isDarkTheme = state.isDarkTheme,
                        backgroundColor = keyboardBgColor,
                        viewModel = viewModel,
                        onStartSearch = { viewModel.startClipboardSearch() },
                        onSelectItem = { text ->
                            callbacks.onClipboardSelect?.invoke(text)
                            viewModel.closeOverlay()
                        },
                        onSplitWords = { text, _ -> viewModel.pushOverlay(OverlayRoute.SplitWords(text)) },
                        onBack = { viewModel.closeOverlay() },
                        onClipboardTabChange = { viewModel.pushOverlay(OverlayRoute.Clipboard(it)) },
                        bottomPaddingDp = state.keyboardBottomPaddingDp,
                        modifier = Modifier.fillMaxWidth().fillMaxHeight()
                    )
                    is OverlayRoute.ToolbarCustomize -> ToolbarCustomizeView(
                        toolbarButtons = state.toolbarButtons,
                        keyTextColor = keyTextColor,
                        backgroundColor = keyboardBgColor,
                        accentColor = accentColor,
                        onUpdateToolbarButtons = callbacks.onUpdateToolbarButtons,
                        onDismiss = { viewModel.closeOverlay() },
                        bottomPaddingDp = state.keyboardBottomPaddingDp,
                        modifier = Modifier.fillMaxWidth().fillMaxHeight()
                    )
                    is OverlayRoute.Emoji -> EmojiKeyboardLayout(
                        onEmojiSelect = { emoji ->
                            if (emoji == "delete") {
                                callbacks.onKeyPress("delete", false)
                            } else {
                                callbacks.onCommitText?.invoke(emoji)
                            }
                        },
                        onImageEmojiSelect = callbacks.onCommitImage,
                        onBack = { viewModel.closeOverlay() },
                        backgroundColor = candidateBarBg,
                        textColor = keyTextColor,
                        accentColor = accentColor,
                        bottomPaddingDp = state.keyboardBottomPaddingDp,
                        modifier = Modifier.fillMaxWidth().fillMaxHeight()
                    )
                    is OverlayRoute.Symbol -> SymbolKeyboardLayout(
                        onSelect = { symbol ->
                            if (symbol == "delete") {
                                callbacks.onKeyPress("delete", false)
                            } else {
                                callbacks.onCommitText?.invoke(symbol)
                            }
                        },
                        onBack = { viewModel.closeOverlay() },
                        backgroundColor = candidateBarBg,
                        textColor = keyTextColor,
                        accentColor = accentColor,
                        modifier = Modifier.fillMaxWidth().fillMaxHeight()
                    )
                    is OverlayRoute.CandidatePage -> CandidatePage(
                        state = CandidatePageState(
                            candidates = state.candidates.toList(),
                            candidateComments = state.candidateComments.toList(),
                            associationCandidates = state.associationCandidates.toList(),
                            backgroundColor = candidateBarBg,
                            textColor = candidateTextColor,
                            hasNextPage = state.hasNextPage,
                            hasPrevPage = state.hasPrevPage,
                            bottomPaddingDp = state.keyboardBottomPaddingDp,
                        ),
                        callbacks = CandidatePageCallbacks(
                            onCandidateSelect = { index ->
                                callbacks.onCandidateSelect(index)
                                viewModel.closeOverlay()
                            },
                            onAssociationSelect = { index ->
                                callbacks.onAssociationSelect?.invoke(index)
                                viewModel.closeOverlay()
                            },
                            onPageDown = callbacks.onPageDown,
                            onPageUp = callbacks.onPageUp,
                            onBack = { viewModel.closeOverlay() },
                        ),
                        modifier = Modifier.fillMaxWidth().fillMaxHeight()
                    )
                    is OverlayRoute.SplitWords -> SplitWordsView(
                        text = p.route.text,
                        backgroundColor = keyboardBgColor,
                        viewModel = viewModel,
                        onBack = { viewModel.popOverlay() },
                        onNavigateToQuickSend = { viewModel.pushOverlay(OverlayRoute.Clipboard(1)) },
                        onSelectChar = { char -> callbacks.onCommitText?.invoke(char) },
                        onDeleteText = { count -> callbacks.onDeleteText?.invoke(count) },
                        bottomPaddingDp = state.keyboardBottomPaddingDp,
                        modifier = Modifier.fillMaxWidth().fillMaxHeight()
                    )
                }
                else -> {}
            }
        }
        }
    }
    }
}

/**
 * 根据粘滞修饰键状态构建 send 表达式。
 *
 * 当 Ctrl 或 Alt 粘滞激活时，字母键不直接输入字符，而是发送组合键
 *（如 "Control+c"）。Shift 粘滞或大写锁定时也附加 Shift 修饰键。
 *
 * @param key 按下的键名（如 "a"、"z"）
 * @param ctrlSticky Ctrl 粘滞是否激活
 * @param altSticky Alt 粘滞是否激活
 * @param isShifted Shift 是否激活
 * @return send 表达式（如 "Control+Shift+c"），无修饰键时返回 null
 */
private fun buildStickySendExpr(
    key: String,
    ctrlSticky: Boolean,
    altSticky: Boolean,
    isShifted: Boolean,
): String? {
    if (!ctrlSticky && !altSticky && !isShifted) return null
    val keyLower = key.lowercase()
    // 仅对单个字母键应用组合键，非字母键走普通输入
    if (keyLower.length != 1 || !keyLower[0].isLetter()) return null
    val parts = mutableListOf<String>()
    if (ctrlSticky) parts += "Control"
    if (altSticky) parts += "Alt"
    if (isShifted) parts += "Shift"
    parts += keyLower
    return parts.joinToString("+")
}
