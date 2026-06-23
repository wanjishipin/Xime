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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kingzcheung.xime.keyboard.KeyboardRoute
import com.kingzcheung.xime.keyboard.ToolbarAction
import com.kingzcheung.xime.keyboard.ToolbarButton
import com.kingzcheung.xime.settings.KeysConfigHelper
import com.kingzcheung.xime.ui.settings.SchemaListView
import com.kingzcheung.xime.ui.theme.KeyboardThemes
import com.kingzcheung.xime.viewmodel.KeyboardUiState
import com.kingzcheung.xime.viewmodel.KeyboardViewModel
import kotlin.math.abs

val LocalStretchFactor = compositionLocalOf { 1f }
val LocalSuppressCursorMove = compositionLocalOf { mutableStateOf(false) }

@Composable
fun KeyboardView(
    viewModel: KeyboardViewModel,
    state: KeyboardUiState,
    callbacks: KeyboardCallbacks,
    modifier: Modifier = Modifier,
) {
    val isShifted by viewModel.isShifted.collectAsStateWithLifecycle()
    val keyboardState by viewModel.keyboardState.collectAsStateWithLifecycle()
    val currentRoute by viewModel.currentRoute.collectAsStateWithLifecycle()
    val isLandscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE

    SideEffect {
        val active = (keyboardState is KeyboardLayoutState.Chinese || keyboardState is KeyboardLayoutState.Stroke) && currentRoute == KeyboardRoute.Keyboard
        callbacks.onKeyboardModeChange?.invoke(active)
    }

    LaunchedEffect(state.inputSessionId) {
        viewModel.setRoute(KeyboardRoute.Keyboard)
    }

    LaunchedEffect(state.isAsciiMode, state.currentSchemaId) {
        val newState = initialKeyboardLayoutState(state.isAsciiMode, state.currentSchemaId)
        viewModel.setKeyboardState(newState)
    }

    val kbColors = KeysConfigHelper.getKeyboardColors()
    val kbShadow = KeysConfigHelper.getKeyboardShadow()
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

    val clipboardTab = (currentRoute as? KeyboardRoute.Clipboard)?.tab ?: 0

    Box(modifier = modifier.background(keyboardBgColor)) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight()
        ) {
            val candidateBarState = remember(
                state.candidates, state.candidateComments, state.inputText, state.isComposing,
                state.associationCandidates, state.isShowingRecentClipboard, state.hasNextPage,
                state.isCalculatorMode,
            ) {
                CandidateBarState.from(
                    candidates = state.candidates,
                    candidateComments = state.candidateComments,
                    inputText = state.inputText,
                    isComposing = state.isComposing,
                    associationCandidates = state.associationCandidates,
                    isShowingRecentClipboard = state.isShowingRecentClipboard,
                    hasNextPage = state.hasNextPage,
                    isCalculatorActive = state.isCalculatorMode,
                )
            }

            CandidateBar(
                state = candidateBarState,
                currentRoute = currentRoute,
                toolbarActions = state.toolbarButtons.mapNotNull { id ->
                    val button = ToolbarButton.fromId(id) ?: return@mapNotNull null
                    val onClick: () -> Unit = when (button) {
                        ToolbarButton.EMOJI -> ({ viewModel.setRoute(KeyboardRoute.Emoji) })
                        ToolbarButton.CLIPBOARD -> ({ viewModel.setRoute(KeyboardRoute.Clipboard(0)) })
                        ToolbarButton.SCHEMA -> ({ viewModel.setRoute(KeyboardRoute.SchemaList) })
                        ToolbarButton.QUICK_PHRASE -> ({ viewModel.setRoute(KeyboardRoute.Clipboard(1)) })
                        ToolbarButton.SYMBOL -> ({ viewModel.setRoute(KeyboardRoute.Symbol) })
                        ToolbarButton.SELECT_ALL -> ({ callbacks.onToolbarEditingAction?.invoke("select_all") })
                        ToolbarButton.COPY -> ({ callbacks.onToolbarEditingAction?.invoke("copy") })
                        ToolbarButton.PASTE -> ({ callbacks.onToolbarEditingAction?.invoke("paste") })
                        ToolbarButton.HOME -> ({ callbacks.onToolbarEditingAction?.invoke("home") })
                        ToolbarButton.END -> ({ callbacks.onToolbarEditingAction?.invoke("end") })
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
                    onCandidateSelect = callbacks.onCandidateSelect,
                    onLogoClick = { viewModel.setRoute(KeyboardRoute.Menu) },
                    onBack = {
                        viewModel.setRoute(when (currentRoute) {
                            is KeyboardRoute.SchemaList -> KeyboardRoute.Menu
                            is KeyboardRoute.Clipboard -> KeyboardRoute.Keyboard
                            is KeyboardRoute.CandidatePage -> KeyboardRoute.Keyboard
                            is KeyboardRoute.ToolbarCustomize -> KeyboardRoute.Keyboard
                            is KeyboardRoute.Emoji -> KeyboardRoute.Keyboard
                            is KeyboardRoute.Symbol -> KeyboardRoute.Keyboard
                            is KeyboardRoute.SplitWords -> KeyboardRoute.Keyboard
                            else -> KeyboardRoute.Keyboard
                        })
                    },
                    onHideKeyboard = {
                        callbacks.onHideKeyboard?.invoke()
                        viewModel.resetKeyboard(state.isAsciiMode, state.currentSchemaId)
                    },
                    onShowMoreCandidates = { viewModel.setRoute(KeyboardRoute.CandidatePage) },
                    onInputTextClick = {
                        if (state.inputText.isNotEmpty()) {
                            callbacks.onClipboardSelect?.invoke(state.inputText)
                        }
                    },
                    onAssociationSelect = callbacks.onAssociationSelect,
                    onClearAssociation = callbacks.onClearAssociation
                )
            )

            when {
                currentRoute is KeyboardRoute.Voice -> {
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

                else -> {
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

                    val fullScreenOnKeyPress: (String) -> Unit = { key ->
                        when (key) {
                            "shift" -> viewModel.toggleShift()
                            "shift_single" -> viewModel.singleTapShift()
                            "shift_caps" -> viewModel.doubleTapShift()
                            "mode_change" -> {
                                viewModel.setKeyboardState(keyboardState.transition(
                                    KeyboardLayoutAction.SwitchToCommonSymbol, state.isAsciiMode
                                ))
                                callbacks.onKeyPress("clear_composition", false)
                            }
                            "mode_change_symbol" -> viewModel.setRoute(KeyboardRoute.Symbol)
                            "emoji" -> viewModel.setRoute(KeyboardRoute.Emoji)
                            else -> {
                                callbacks.onKeyPress(key, isShifted)
                                viewModel.onCharacterTyped()
                            }
                        }
                    }
                    val numberOnKeyPress: (String) -> Unit = { key ->
                        when (key) {
                            "abc" -> viewModel.setKeyboardState(
                                initialKeyboardLayoutState(state.isAsciiMode, state.currentSchemaId)
                            )
                            "symbol" -> viewModel.setRoute(KeyboardRoute.Symbol)
                            "emoji" -> viewModel.setRoute(KeyboardRoute.Emoji)
                            else -> callbacks.onKeyPress(key, false)
                        }
                    }
                    val symbolOnKeyPress: (String) -> Unit = { key ->
                        when (key) {
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
                        when (key) {
                            "abc" -> viewModel.setKeyboardState(
                                initialKeyboardLayoutState(state.isAsciiMode, state.currentSchemaId)
                            )
                            "number" -> viewModel.setKeyboardState(keyboardState.transition(
                                KeyboardLayoutAction.SwitchToNumber, state.isAsciiMode
                            ))
                            "symbol" -> viewModel.setRoute(KeyboardRoute.Symbol)
                            "emoji" -> viewModel.setRoute(KeyboardRoute.Emoji)
                            else -> callbacks.onKeyPress(key, false)
                        }
                    }
                    val strokeOnKeyPress: (String) -> Unit = { key ->
                        when (key) {
                            "abc" -> viewModel.setKeyboardState(keyboardState.transition(
                                KeyboardLayoutAction.SwitchToFull, state.isAsciiMode
                            ))
                            "number" -> viewModel.setKeyboardState(keyboardState.transition(
                                KeyboardLayoutAction.SwitchToNumber, state.isAsciiMode
                            ))
                            "symbol" -> viewModel.setRoute(KeyboardRoute.Symbol)
                            "emoji" -> viewModel.setRoute(KeyboardRoute.Emoji)
                            else -> callbacks.onKeyPress(key, false)
                        }
                    }
                    val currentOnKeyPress = when (keyboardState) {
                        is KeyboardLayoutState.Chinese,
                        is KeyboardLayoutState.English -> fullScreenOnKeyPress
                        is KeyboardLayoutState.Number -> numberOnKeyPress
                        is KeyboardLayoutState.CommonSymbol -> commonSymbolOnKeyPress
                        is KeyboardLayoutState.Stroke -> strokeOnKeyPress
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
                        )
                    }
                }
            }

            val gapAbove = maxOf(0, state.keyboardBottomPaddingDp)
            val bottomReduction = minOf(0, state.keyboardBottomPaddingDp)
            Spacer(modifier = Modifier.height(gapAbove.dp))

            val configuration = LocalConfiguration.current
            val isLandscapeBottom = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
            if (state.showBottomButtons && currentRoute !is KeyboardRoute.Voice && !isLandscapeBottom) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clickable(
                                onClick = {
                                    callbacks.onHideKeyboard?.invoke()
                                    viewModel.resetKeyboard(state.isAsciiMode, state.currentSchemaId)
                                }
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.KeyboardArrowDown,
                            contentDescription = "收起键盘",
                            tint = keyTextColor,
                            modifier = Modifier.size(24.dp)
                        )
                    }

                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clickable(onClick = { callbacks.onSwitchKeyboard?.invoke() }),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Keyboard,
                            contentDescription = "切换键盘",
                            tint = keyTextColor,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }
            } else if (currentRoute !is KeyboardRoute.Voice && !isLandscapeBottom) {
                val bottomSpacer = (40 + bottomReduction).coerceAtLeast(0)
                Spacer(modifier = Modifier.height(bottomSpacer.dp))
            } else if (currentRoute !is KeyboardRoute.Voice && isLandscapeBottom) {
                val bottomSpacer = (15 + bottomReduction).coerceAtLeast(0)
                Spacer(modifier = Modifier.height(bottomSpacer.dp))
            }
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
                        text = state.deploymentMessage.ifEmpty { "正在初始化..." },
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
                            text = "请稍候",
                            color = keyTextColor.copy(alpha = 0.7f),
                            style = androidx.compose.material3.MaterialTheme.typography.bodyMedium
                        )
                    }
                }
            }
        }

        if (currentRoute !is KeyboardRoute.Keyboard && currentRoute !is KeyboardRoute.Voice) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight()
                    .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null
                ) { }
            ) {
            when (currentRoute) {
                is KeyboardRoute.Menu -> MenuBar(
                isVisible = true,
                isDarkTheme = state.isDarkTheme,
                darkMode = state.darkMode,
                backgroundColor = keyboardBgColor,
                bottomPaddingDp = state.keyboardBottomPaddingDp,
                onDismiss = { viewModel.setRoute(KeyboardRoute.Keyboard) },
                onClipboard = { viewModel.setRoute(KeyboardRoute.Clipboard(0)); callbacks.onClipboard?.invoke() },
                onQuickSend = { viewModel.setRoute(KeyboardRoute.Clipboard(1)); callbacks.onQuickSend?.invoke() },
                onKeyboardResize = { callbacks.onKeyboardResize?.invoke(); viewModel.setRoute(KeyboardRoute.Keyboard) },
                onEmoji = { viewModel.setRoute(KeyboardRoute.Emoji) },
                onReloadConfig = { callbacks.onReloadConfig?.invoke(); viewModel.setRoute(KeyboardRoute.Keyboard) },
                onSettings = { callbacks.onSettings?.invoke(); viewModel.setRoute(KeyboardRoute.Keyboard) },
                onSchemaList = { viewModel.setRoute(KeyboardRoute.SchemaList) },
                onToggleDarkMode = { callbacks.onToggleDarkMode?.invoke() },
                onToolbarCustomize = { viewModel.setRoute(KeyboardRoute.ToolbarCustomize) },
                    modifier = Modifier.fillMaxWidth().fillMaxHeight()
                )
                is KeyboardRoute.Clipboard -> ClipboardView(
                    clipboardItems = state.clipboardItems,
                    quickSendItems = state.quickSendItems,
                    selectedTab = clipboardTab,
                    isDarkTheme = state.isDarkTheme,
                    backgroundColor = keyboardBgColor,
                    viewModel = viewModel,
                    onSelectItem = { text ->
                        callbacks.onClipboardSelect?.invoke(text)
                        viewModel.setRoute(KeyboardRoute.Keyboard)
                    },
                    onSplitWords = { text, _ -> viewModel.setRoute(KeyboardRoute.SplitWords(text)) },
                    onBack = { viewModel.setRoute(KeyboardRoute.Keyboard) },
                    onClipboardTabChange = { viewModel.setRoute(KeyboardRoute.Clipboard(it)) },
                    bottomPaddingDp = state.keyboardBottomPaddingDp,
                    modifier = Modifier.fillMaxWidth().fillMaxHeight()
                )
                is KeyboardRoute.SchemaList -> SchemaListView(
                    schemas = state.schemas,
                    currentSchemaId = state.currentSchemaId,
                    isDarkTheme = state.isDarkTheme,
                    backgroundColor = keyboardBgColor,
                    accentColor = accentColor,
                    onSelectSchema = { schemaId ->
                        callbacks.onSwitchSchema?.invoke(schemaId)
                        viewModel.setRoute(KeyboardRoute.Keyboard)
                    },
                    onBack = { viewModel.setRoute(KeyboardRoute.Menu) },
                    modifier = Modifier.fillMaxWidth().fillMaxHeight()
                )
                is KeyboardRoute.ToolbarCustomize -> ToolbarCustomizeView(
                    toolbarButtons = state.toolbarButtons,
                    keyTextColor = keyTextColor,
                    keyBgColor = keyBgColor,
                    accentColor = accentColor,
                    onUpdateToolbarButtons = callbacks.onUpdateToolbarButtons,
                    onDismiss = { viewModel.setRoute(KeyboardRoute.Keyboard) },
                    bottomPaddingDp = state.keyboardBottomPaddingDp,
                    modifier = Modifier.fillMaxWidth().fillMaxHeight()
                )
                is KeyboardRoute.CandidatePage -> CandidatePage(
                    candidates = state.candidates.toList(),
                    candidateComments = state.candidateComments.toList(),
                    associationCandidates = state.associationCandidates.toList(),
                    inputText = state.inputText,
                    onCandidateSelect = { index ->
                        callbacks.onCandidateSelect(index)
                        viewModel.setRoute(KeyboardRoute.Keyboard)
                    },
                    onAssociationSelect = { index ->
                        callbacks.onAssociationSelect?.invoke(index)
                        viewModel.setRoute(KeyboardRoute.Keyboard)
                    },
                    backgroundColor = candidateBarBg,
                    textColor = candidateTextColor,
                    hasNextPage = state.hasNextPage,
                    hasPrevPage = state.hasPrevPage,
                    onPageDown = callbacks.onPageDown,
                    onPageUp = callbacks.onPageUp,
                    onBack = { viewModel.setRoute(KeyboardRoute.Keyboard) },
                    bottomPaddingDp = state.keyboardBottomPaddingDp,
                    modifier = Modifier.fillMaxWidth().fillMaxHeight()
                )
                is KeyboardRoute.SplitWords -> SplitWordsView(
                    text = (currentRoute as KeyboardRoute.SplitWords).text,
                    backgroundColor = keyboardBgColor,
                    viewModel = viewModel,
                    onBack = { viewModel.setRoute(KeyboardRoute.Clipboard(clipboardTab)) },
                    onNavigateToQuickSend = { viewModel.setRoute(KeyboardRoute.Clipboard(1)) },
                    onSelectChar = { char -> callbacks.onCommitText?.invoke(char) },
                    onDeleteText = { count -> callbacks.onDeleteText?.invoke(count) },
                    bottomPaddingDp = state.keyboardBottomPaddingDp,
                    modifier = Modifier.fillMaxWidth().fillMaxHeight()
                )
                is KeyboardRoute.Symbol -> SymbolKeyboardLayout(
                    onSelect = { symbol ->
                        if (symbol == "delete") {
                            callbacks.onKeyPress("delete", false)
                        } else {
                            callbacks.onCommitText?.invoke(symbol)
                        }
                    },
                    onBack = { viewModel.setRoute(KeyboardRoute.Keyboard) },
                    backgroundColor = candidateBarBg,
                    textColor = keyTextColor,
                    accentColor = accentColor,
                    modifier = Modifier.fillMaxWidth().fillMaxHeight()
                )
                is KeyboardRoute.Emoji -> EmojiKeyboardLayout(
                    onEmojiSelect = { emoji ->
                        if (emoji == "delete") {
                            callbacks.onKeyPress("delete", false)
                        } else {
                            callbacks.onCommitText?.invoke(emoji)
                        }
                    },
                    onImageEmojiSelect = callbacks.onCommitImage,
                    onBack = { viewModel.setRoute(KeyboardRoute.Keyboard) },
                    backgroundColor = candidateBarBg,
                    textColor = keyTextColor,
                    accentColor = accentColor,
                    bottomPaddingDp = state.keyboardBottomPaddingDp,
                    modifier = Modifier.fillMaxWidth().fillMaxHeight()
                )
                else -> {}
            }
        }
        }
    }
}
