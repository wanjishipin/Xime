package com.kingzcheung.xime.ui

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kingzcheung.xime.clipboard.ClipboardItem
import com.kingzcheung.xime.service.InputUIState
import com.kingzcheung.xime.settings.SchemaInfo
import com.kingzcheung.xime.speech.RecognitionState
import com.kingzcheung.xime.ui.theme.DividerColor
import com.kingzcheung.xime.ui.theme.DividerColorDark
import com.kingzcheung.xime.ui.theme.KeyBackground
import com.kingzcheung.xime.ui.theme.KeyBackgroundDark
import com.kingzcheung.xime.ui.theme.KeyTextColor
import com.kingzcheung.xime.ui.theme.KeyTextColorDark
import com.kingzcheung.xime.ui.theme.KeyboardBackground
import com.kingzcheung.xime.ui.theme.KeyboardBackgroundDark
import com.kingzcheung.xime.ui.theme.KeyboardThemes

val LocalStretchFactor = compositionLocalOf { 1f }

@Composable
fun KeyboardView(
    candidates: Array<String> = emptyArray(),
    candidateComments: Array<String> = emptyArray(),
    inputText: String = "",
    isComposing: Boolean = false,
    isAsciiMode: Boolean = false,
    schemaName: String = "",
    currentSchemaId: String = "",
    schemas: List<SchemaInfo> = emptyList(),
    enterKeyText: String = "发送",
    isDarkTheme: Boolean = false,
    darkMode: Int = 2,
    themeId: String = "ocean_blue",
    showBottomButtons: Boolean = false,
    clipboardItems: List<ClipboardItem> = emptyList(),
    quickSendItems: List<ClipboardItem> = emptyList(),
    recentClipboardItems: List<ClipboardItem> = emptyList(),
    associationCandidates: Array<String> = emptyArray(),
    keyboardHeightDp: Int = com.kingzcheung.xime.settings.SettingsPreferences.DEFAULT_KEYBOARD_HEIGHT_DP,
    keyboardBottomPaddingDp: Int = 0,
    isDeploying: Boolean = false,
    deploymentMessage: String = "",
    onDismissDeploying: (() -> Unit)? = null,
    onKeyPress: (String, Boolean) -> Unit,
    onKeyPressDown: ((String) -> Unit)? = null,
    onCandidateSelect: (Int) -> Unit,
    onAssociationSelect: ((Int) -> Unit)? = null,
    onToggleDarkMode: (() -> Unit)? = null,
    onClipboard: (() -> Unit)? = null,
    onClipboardSelect: ((String) -> Unit)? = null,
    onClipboardRemove: ((Long) -> Unit)? = null,
    onClipboardSplitWords: ((Long) -> Unit)? = null,
    onAddToQuickSend: ((Long) -> Unit)? = null,
    onRemoveFromQuickSend: ((Long) -> Unit)? = null,
    onQuickSend: (() -> Unit)? = null,
    onKeyboardResize: (() -> Unit)? = null,
    onReloadConfig: (() -> Unit)? = null,
    onSettings: (() -> Unit)? = null,
    onSwitchSchema: ((String) -> Unit)? = null,
    onHideKeyboard: (() -> Unit)? = null,
    onSwitchKeyboard: (() -> Unit)? = null,
    onCommitImage: ((String) -> Unit)? = null,
    isVoiceMode: Boolean = false,
    voiceBottomActive: Boolean = false,
    voiceLeftActive: Boolean = false,
    voiceRightActive: Boolean = false,
    onVoiceModeChange: ((Boolean) -> Unit)? = null,
    voicePluginName: String = "",
    voiceRecognitionState: RecognitionState = RecognitionState.IDLE,
    voiceRecognizedText: String = "",
    voiceAmplitude: Float = 0f,
    uiStateProvider: () -> InputUIState,
    onPageDown: (() -> Unit)? = null,
    onPageUp: (() -> Unit)? = null,
    onCursorMove: ((Int) -> Unit)? = null,
    toolbarButtons: List<String> = ToolbarButton.DEFAULT_VISIBLE.map { it.id },
    onUpdateToolbarButtons: ((List<String>) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    var isShifted by remember { mutableStateOf(false) }
    var keyboardMode by remember { mutableStateOf(KeyboardMode.FULL) }
    var currentRoute by remember { mutableStateOf<KeyboardRoute>(KeyboardRoute.Keyboard) }

    val keyBgColor = if (isDarkTheme) KeyBackgroundDark else KeyBackground
    val keyboardBgColor = if (isDarkTheme) KeyboardBackgroundDark else KeyboardBackground
    val keyTextColor = if (isDarkTheme) KeyTextColorDark else KeyTextColor
    val specialKeyBgColor = KeyboardThemes.getSpecialKeyColor(themeId, isDarkTheme)
    val accentColor = KeyboardThemes.getAccentColor(themeId, isDarkTheme)
    val candidateBarBg = keyboardBgColor
    val candidateTextColor = keyTextColor
    val dividerColor = if (isDarkTheme) DividerColorDark else DividerColor
    val state = uiStateProvider()
    val clipboardTab = (currentRoute as? KeyboardRoute.Clipboard)?.tab ?: 0
    // 每次重新开始输入时（inputSessionId 变化），重置导航状态到全键盘
    LaunchedEffect(state.inputSessionId) {
        currentRoute = KeyboardRoute.Keyboard
    }

    Box(modifier = modifier.background(keyboardBgColor)) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight()
        ) {
            CandidateBar(
                candidates = candidates.toList(),
                candidateComments = candidateComments.toList(),
                inputText = inputText,
                isComposing = isComposing,
                onCandidateSelect = onCandidateSelect,
                backgroundColor = candidateBarBg,
                showClipboardHeader = state.isShowingRecentClipboard,
                textColor = candidateTextColor,
                dividerColor = dividerColor,
                accentColor = accentColor,
                isDarkTheme = isDarkTheme,
                currentRoute = currentRoute,
                onLogoClick = { currentRoute = KeyboardRoute.Menu },
                onBack = {
                    currentRoute = when (currentRoute) {
                        is KeyboardRoute.SchemaList -> KeyboardRoute.Menu
                        is KeyboardRoute.Clipboard -> KeyboardRoute.Keyboard
                        is KeyboardRoute.CandidatePage -> KeyboardRoute.Keyboard
                        is KeyboardRoute.ToolbarCustomize -> KeyboardRoute.Keyboard
                        is KeyboardRoute.Emoji -> KeyboardRoute.Keyboard
                        else -> KeyboardRoute.Keyboard
                    }
                },
                onHideKeyboard = {
                    onHideKeyboard?.invoke()
                    keyboardMode = KeyboardMode.FULL
                    currentRoute = KeyboardRoute.Keyboard
                    isShifted = false
                },
                onShowMoreCandidates = { currentRoute = KeyboardRoute.CandidatePage },
                onInputTextClick = {
                    if (inputText.isNotEmpty()) {
                        onClipboardSelect?.invoke(inputText)
                    }
                },
                associationCandidates = associationCandidates.toList(),
                onAssociationSelect = onAssociationSelect,
                toolbarActions = toolbarButtons.mapNotNull { id ->
                    val button = ToolbarButton.fromId(id) ?: return@mapNotNull null
                    val onClick: () -> Unit = when (button) {
                        ToolbarButton.EMOJI -> ({ currentRoute = KeyboardRoute.Emoji })
                        ToolbarButton.CLIPBOARD -> ({ currentRoute = KeyboardRoute.Clipboard(0) })
                        ToolbarButton.SCHEMA -> ({ currentRoute = KeyboardRoute.SchemaList })
                        ToolbarButton.QUICK_PHRASE -> ({ currentRoute = KeyboardRoute.Clipboard(1) })
                    }
                    ToolbarAction(button, onClick)
                }
            )

            // 显示菜单、剪切板、候选词页面或键盘
            when {
                isVoiceMode -> {
                    VoiceKeyboardLayout(
                        keyBackgroundColor = keyBgColor,
                        keyTextColor = keyTextColor,
                        specialKeyBackgroundColor = specialKeyBgColor,
                        keyboardBackgroundColor = keyboardBgColor,
                        modifier = Modifier.weight(1f),
                        isDarkTheme = isDarkTheme,
                        themeId = themeId,
                        bottomActive = voiceBottomActive,
                        leftActive = voiceLeftActive,
                        rightActive = voiceRightActive,
                        pluginName = voicePluginName,
                        recognitionState = voiceRecognitionState,
                        recognizedText = voiceRecognizedText,
                        amplitude = voiceAmplitude
                    )
                }

                else -> {
                    val cursorMod = if (!isComposing && inputText.isEmpty() && onCursorMove != null)
                        Modifier.pointerInput(Unit) {
                            awaitEachGesture {
                                val down = awaitFirstDown(requireUnconsumed = false)
                                var totalDrag = 0f
                                var lastPosition = down.position
                                do {
                                    val event = awaitPointerEvent(PointerEventPass.Initial)
                                    val change = event.changes.firstOrNull() ?: break
                                    if (!change.pressed) break
                                    val dx = change.position.x - lastPosition.x
                                    totalDrag += dx
                                    lastPosition = change.position
                                    // 每超过阈值就触发一次光标移动并重置累计距离
                                    while (kotlin.math.abs(totalDrag) > 50f) {
                                        change.consume()
                                        onCursorMove(if (totalDrag > 0f) 1 else -1)
                                        totalDrag = if (totalDrag > 0f) totalDrag - 50f else totalDrag + 50f
                                    }
                                } while (true)
                            }
                        } else Modifier
                    when (keyboardMode) {
                        KeyboardMode.FULL -> {
                            val configuration = LocalConfiguration.current
                            val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
                            if (isLandscape) {
                                SplitKeyboardLayout(
                                    onKeyPress = { key ->
                                        when (key) {
                                            "shift" -> isShifted = !isShifted
                                            "mode_change" -> keyboardMode = KeyboardMode.NUMBER
                                            "emoji" -> currentRoute = KeyboardRoute.Emoji
                                            else -> onKeyPress(key, isShifted)
                                        }
                                    },
                                    isShifted = isShifted,
                                    isAsciiMode = isAsciiMode,
                                    schemaName = schemaName,
                                    enterKeyText = enterKeyText,
                                    isDarkTheme = isDarkTheme,
                                    keyBackgroundColor = keyBgColor,
                                    keyTextColor = keyTextColor,
                                    specialKeyBackgroundColor = specialKeyBgColor,
                                    keyboardBackgroundColor = keyboardBgColor,
                                    modifier = Modifier.weight(1f).then(cursorMod),
                                    onKeyPressDown = onKeyPressDown
                                )
                            } else {
                                KeyboardLayout(
                                    onKeyPress = { key ->
                                        when (key) {
                                            "shift" -> isShifted = !isShifted
                                            "mode_change" -> keyboardMode = KeyboardMode.NUMBER
                                            "emoji" -> currentRoute = KeyboardRoute.Emoji
                                            else -> onKeyPress(key, isShifted)
                                        }
                                    },
                                    isShifted = isShifted,
                                    isAsciiMode = isAsciiMode,
                                    schemaName = schemaName,
                                    currentSchemaId = currentSchemaId,
                                    enterKeyText = enterKeyText,
                                    isDarkTheme = isDarkTheme,
                                    keyBackgroundColor = keyBgColor,
                                    keyTextColor = keyTextColor,
                                    specialKeyBackgroundColor = specialKeyBgColor,
                                    keyboardBackgroundColor = keyboardBgColor,
                                    modifier = Modifier.weight(1f).then(cursorMod),
                                    onVoiceModeChange = onVoiceModeChange,
                                    isVoiceMode = isVoiceMode,
                                    onKeyPressDown = onKeyPressDown,
                                    onCursorMove = onCursorMove
                                )
                            }
                        }
                        KeyboardMode.NUMBER -> {
                            NumberKeyboardLayout(
                                onKeyPress = { key ->
                                    when (key) {
                                        "abc" -> keyboardMode = KeyboardMode.FULL
                                        "symbol" -> keyboardMode = KeyboardMode.SYMBOL
                                        "emoji" -> currentRoute = KeyboardRoute.Emoji
                                        else -> onKeyPress(key, false)
                                    }
                                },
                                keyBackgroundColor = keyBgColor,
                                keyTextColor = keyTextColor,
                                specialKeyBackgroundColor = specialKeyBgColor,
                                keyboardBackgroundColor = keyboardBgColor,
                                modifier = Modifier.weight(1f).then(cursorMod),
                                onKeyPressDown = onKeyPressDown
                            )
                        }
                        KeyboardMode.SYMBOL -> {
                            SymbolKeyboardLayout(
                                onKeyPress = { key ->
                                    when (key) {
                                        "abc" -> keyboardMode = KeyboardMode.FULL
                                        "123" -> keyboardMode = KeyboardMode.NUMBER
                                        else -> onKeyPress(key, false)
                                    }
                                },
                                keyBackgroundColor = keyBgColor,
                                keyTextColor = keyTextColor,
                                specialKeyBackgroundColor = specialKeyBgColor,
                                keyboardBackgroundColor = keyboardBgColor,
                                modifier = Modifier.weight(1f),
                                onKeyPressDown = onKeyPressDown
                            )
                        }
                    }
                }
            }
            // 间距：正值=键盘与底部的间隙，负值=缩减底部固定空白区
            val gapAbove = maxOf(0, keyboardBottomPaddingDp)
            val bottomReduction = minOf(0, keyboardBottomPaddingDp) // 负值
            Spacer(modifier = Modifier.height(gapAbove.dp))
            // 底部按钮区（独立于键盘区，键盘调节时不拉伸此区域）
            // 横屏下不显示底部按钮，让分体键盘占满空间
            val configuration = LocalConfiguration.current
            val isLandscapeBottom = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
            if (showBottomButtons && !isVoiceMode && !isLandscapeBottom) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clickable(
                                onClick = {
                                    onHideKeyboard?.invoke()
                                    keyboardMode = KeyboardMode.FULL
                                    currentRoute = KeyboardRoute.Keyboard
                                    isShifted = false
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
                            .clickable(onClick = { onSwitchKeyboard?.invoke() }),
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
            } else if (!isVoiceMode && !isLandscapeBottom) {
                val bottomSpacer = (40 + bottomReduction).coerceAtLeast(0)
                Spacer(modifier = Modifier.height(bottomSpacer.dp))
            } else if (!isVoiceMode && isLandscapeBottom) {
                val bottomSpacer = (15 + bottomReduction).coerceAtLeast(0)
                Spacer(modifier = Modifier.height(bottomSpacer.dp))
            }
        }

        if (isDeploying) {
            val isError = deploymentMessage.contains("超时") || deploymentMessage.contains("失败")
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight()
                    .background(keyboardBgColor.copy(alpha = 0.9f))
                    .clickable(enabled = isError && onDismissDeploying != null) {
                        onDismissDeploying?.invoke()
                    },
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    androidx.compose.material3.Text(
                        text = deploymentMessage.ifEmpty { "正在初始化..." },
                        color = keyTextColor,
                        style = androidx.compose.material3.MaterialTheme.typography.bodyLarge
                    )
                    if (isError) {
                        Spacer(modifier = Modifier.height(12.dp))
                        androidx.compose.material3.Text(
                            text = "点击关闭",
                            color = keyTextColor.copy(alpha = 0.5f),
                            style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                            textAlign = TextAlign.Center
                        )
                    } else {
                        androidx.compose.material3.Text(
                            text = "请稍候",
                            color = keyTextColor.copy(alpha = 0.7f),
                            style = androidx.compose.material3.MaterialTheme.typography.bodyMedium
                        )
                    }
                }
            }
        }

        // 菜单覆盖层：覆盖整个键盘视图（包括候选栏）
        if (currentRoute !is KeyboardRoute.Keyboard && !isVoiceMode) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight()
                    .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null
                ) { } // 拦截穿透点击，无 ripple
            ) {
            when (currentRoute) {
                is KeyboardRoute.Menu -> MenuBar(
                isVisible = true,
                isDarkTheme = isDarkTheme,
                darkMode = darkMode,
                backgroundColor = keyboardBgColor,
                bottomPaddingDp = keyboardBottomPaddingDp,
                onDismiss = { currentRoute = KeyboardRoute.Keyboard },
                onClipboard = { currentRoute = KeyboardRoute.Clipboard(0); onClipboard?.invoke() },
                onQuickSend = { currentRoute = KeyboardRoute.Clipboard(1); onQuickSend?.invoke() },
                onKeyboardResize = { onKeyboardResize?.invoke(); currentRoute = KeyboardRoute.Keyboard },
                onEmoji = { currentRoute = KeyboardRoute.Emoji },
                onReloadConfig = { onReloadConfig?.invoke(); currentRoute = KeyboardRoute.Keyboard },
                onSettings = { onSettings?.invoke(); currentRoute = KeyboardRoute.Keyboard },
                onSchemaList = { currentRoute = KeyboardRoute.SchemaList },
                onToggleDarkMode = { onToggleDarkMode?.invoke() },
                onToolbarCustomize = { currentRoute = KeyboardRoute.ToolbarCustomize },
                    modifier = Modifier.fillMaxWidth().fillMaxHeight()
                )
                is KeyboardRoute.Clipboard -> ClipboardView(
                    clipboardItems = clipboardItems,
                    quickSendItems = quickSendItems,
                    selectedTab = clipboardTab,
                    isDarkTheme = isDarkTheme,
                    backgroundColor = keyboardBgColor,
                    onSelectItem = { text ->
                        onClipboardSelect?.invoke(text)
                        currentRoute = KeyboardRoute.Keyboard
                    },
                    onRemoveItem = { id -> onClipboardRemove?.invoke(id) },
                    onAddToQuickSend = { id -> onAddToQuickSend?.invoke(id) },
                    onSplitWords = { id -> onClipboardSplitWords?.invoke(id) },
                    onRemoveFromQuickSend = { id -> onRemoveFromQuickSend?.invoke(id) },
                    onBack = { currentRoute = KeyboardRoute.Keyboard },
                    onClipboardTabChange = { currentRoute = KeyboardRoute.Clipboard(it) },
                    modifier = Modifier.fillMaxWidth().fillMaxHeight()
                )
                is KeyboardRoute.SchemaList -> SchemaListView(
                    schemas = schemas,
                    currentSchemaId = currentSchemaId,
                    isDarkTheme = isDarkTheme,
                    backgroundColor = keyboardBgColor,
                    accentColor = accentColor,
                    onSelectSchema = { schemaId ->
                        onSwitchSchema?.invoke(schemaId)
                        currentRoute = KeyboardRoute.Keyboard
                    },
                    onBack = { currentRoute = KeyboardRoute.Menu },
                    modifier = Modifier.fillMaxWidth().fillMaxHeight()
                )
                is KeyboardRoute.ToolbarCustomize -> ToolbarCustomizeView(
                    toolbarButtons = toolbarButtons,
                    keyTextColor = keyTextColor,
                    keyBgColor = keyboardBgColor,
                    accentColor = accentColor,
                    onUpdateToolbarButtons = onUpdateToolbarButtons,
                    onDismiss = { currentRoute = KeyboardRoute.Keyboard },
                    bottomPaddingDp = keyboardBottomPaddingDp,
                    modifier = Modifier.fillMaxWidth().fillMaxHeight()
                )
                is KeyboardRoute.CandidatePage -> CandidatePage(
                    candidates = candidates.toList(),
                    candidateComments = candidateComments.toList(),
                    associationCandidates = associationCandidates.toList(),
                    inputText = inputText,
                    onCandidateSelect = { index ->
                        onCandidateSelect(index)
                        currentRoute = KeyboardRoute.Keyboard
                    },
                    onAssociationSelect = { index ->
                        onAssociationSelect?.invoke(index)
                        currentRoute = KeyboardRoute.Keyboard
                    },
                    backgroundColor = candidateBarBg,
                    textColor = candidateTextColor,
                    hasNextPage = state.hasNextPage,
                    hasPrevPage = state.hasPrevPage,
                    onPageDown = onPageDown,
                    onPageUp = onPageUp,
                    onBack = { currentRoute = KeyboardRoute.Keyboard },
                    bottomPaddingDp = keyboardBottomPaddingDp,
                    modifier = Modifier.fillMaxWidth().fillMaxHeight()
                )
                is KeyboardRoute.Emoji -> EmojiKeyboardLayout(
                    onEmojiSelect = { emoji ->
                        if (emoji == "delete") {
                            onKeyPress("delete", false)
                        } else {
                            onClipboardSelect?.invoke(emoji)
                        }
                    },
                    onImageEmojiSelect = onCommitImage,
                    onBack = { currentRoute = KeyboardRoute.Keyboard },
                    backgroundColor = candidateBarBg,
                    textColor = keyTextColor,
                    bottomPaddingDp = keyboardBottomPaddingDp,
                    modifier = Modifier.fillMaxWidth().fillMaxHeight()
                )
                else -> {}
            }
        }
        }
    }
}