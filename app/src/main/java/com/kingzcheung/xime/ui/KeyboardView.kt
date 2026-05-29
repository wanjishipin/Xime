package com.kingzcheung.xime.ui

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
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
    themeId: String = "ocean_blue",
    showBottomButtons: Boolean = false,
    clipboardItems: List<ClipboardItem> = emptyList(),
    quickSendItems: List<ClipboardItem> = emptyList(),
    recentClipboardItems: List<ClipboardItem> = emptyList(),
    associationCandidates: Array<String> = emptyArray(),
    keyboardHeightDp: Int = 290,
    keyboardBottomPaddingDp: Int = 0,
    isDeploying: Boolean = false,
    deploymentMessage: String = "",
    onDismissDeploying: (() -> Unit)? = null,
    onKeyPress: (String, Boolean) -> Unit,
    onKeyPressDown: ((String) -> Unit)? = null,
    onSchemaSwitch: ((String) -> Unit)? = null,
    onT9ReplaceFullPinyin: ((String) -> Unit)? = null,
    onCandidateSelect: (Int) -> Unit,
    onAssociationSelect: ((Int) -> Unit)? = null,
    onToggleDarkMode: (() -> Unit)? = null,
    onClipboard: (() -> Unit)? = null,
    onClipboardSelect: ((String) -> Unit)? = null,
    onClipboardRemove: ((Long) -> Unit)? = null,
    onClipboardTogglePin: ((Long) -> Unit)? = null,
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
    modifier: Modifier = Modifier
) {
    var isShifted by remember { mutableStateOf(false) }
    val state = uiStateProvider()
    val context = androidx.compose.ui.platform.LocalContext.current
    var pendingAscii by remember { mutableStateOf(false) }
    var keyboardMode by remember(state.currentSchemaId, isAsciiMode, pendingAscii) {
        mutableStateOf(
            if (isAsciiMode || pendingAscii) KeyboardMode.FULL
            else InputMode.keyboardModeFor(state.currentSchemaId)
        )
    }
    var showMenu by remember { mutableStateOf(false) }
    var showCandidatePage by remember { mutableStateOf(false) }
    var showClipboard by remember { mutableStateOf(false) }
    var showEmoji by remember { mutableStateOf(false) }
    var showSchemaList by remember { mutableStateOf(false) }
    var clipboardTab by remember { mutableStateOf(0) }

    val keyBgColor = if (isDarkTheme) KeyBackgroundDark else KeyBackground
    val keyboardBgColor = if (isDarkTheme) KeyboardBackgroundDark else KeyboardBackground
    val keyTextColor = if (isDarkTheme) KeyTextColorDark else KeyTextColor
    val specialKeyBgColor = KeyboardThemes.getSpecialKeyColor(themeId, isDarkTheme)
    val accentColor = KeyboardThemes.getAccentColor(themeId, isDarkTheme)
    val candidateBarBg = keyboardBgColor
    val candidateTextColor = keyTextColor
    val dividerColor = if (isDarkTheme) DividerColorDark else DividerColor
    
    // 每次重新开始输入、切换方案或切换中英文时，重置面板状态
    LaunchedEffect(state.inputSessionId, state.currentSchemaId, isAsciiMode) {
        showCandidatePage = false
        showClipboard = false
        showEmoji = false
        showSchemaList = false
        showMenu = false
        // 同步键盘模式（兜底，remember 已处理同步初始化）
        keyboardMode = if (isAsciiMode || pendingAscii) {
            KeyboardMode.FULL
        } else {
            InputMode.keyboardModeFor(state.currentSchemaId)
        }
    }

    // 等待 ASCII 模式实际追上后清除乐观状态
    LaunchedEffect(isAsciiMode) {
        if (isAsciiMode && pendingAscii) {
            pendingAscii = false
        }
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
                showCandidatePage = showCandidatePage,
                onToggleDarkMode = onToggleDarkMode,
                onLogoClick = { showMenu = true },
                showMenu = showMenu,
                showSchemaList = showSchemaList,
                onDismissMenu = {
                    if (showSchemaList) {
                        showSchemaList = false
                        showMenu = true
                    } else if (showClipboard) {
                        showClipboard = false
                    } else if (showCandidatePage) {
                        showCandidatePage = false
                    } else {
                        showMenu = false
                    }
                },
                onHideKeyboard = {
                    onHideKeyboard?.invoke()
                    keyboardMode = KeyboardMode.FULL
                    showMenu = false
                    showCandidatePage = false
                    showClipboard = false
                    showSchemaList = false
                    showEmoji = false
                    isShifted = false
                },
                onShowMoreCandidates = { showCandidatePage = true },
                showClipboardTabs = showClipboard,
                clipboardTab = clipboardTab,
                onClipboardTabChange = { clipboardTab = it },
                onInputTextClick = {
                    if (inputText.isNotEmpty()) {
                        onClipboardSelect?.invoke(inputText)
                    }
                },
                associationCandidates = associationCandidates.toList(),
                onAssociationSelect = onAssociationSelect
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

                showMenu -> {
                    MenuBar(
                        isVisible = true,
                        isDarkTheme = isDarkTheme,
                        backgroundColor = keyboardBgColor,
                        onDismiss = { showMenu = false },
                        onClipboard = {
                            showClipboard = true
                            clipboardTab = 0
                            showMenu = false
                            onClipboard?.invoke()
                        },
                        onQuickSend = {
                            showClipboard = true
                            clipboardTab = 1
                            showMenu = false
                            onQuickSend?.invoke()
                        },
                        onKeyboardResize = {
                            showMenu = false
                            onKeyboardResize?.invoke()
                        },
                        onEmoji = {
                            showEmoji = true
                            showMenu = false
                        },
                        onReloadConfig = { onReloadConfig?.invoke(); showMenu = false },
                        onSettings = { onSettings?.invoke(); showMenu = false },
                        onSchemaList = {
                            showSchemaList = true
                            showMenu = false
                        },
                        onToggleDarkMode = { onToggleDarkMode?.invoke() },
                        modifier = Modifier.weight(1f)
                    )
                }

                showClipboard -> {
                    ClipboardView(
                        clipboardItems = clipboardItems,
                        quickSendItems = quickSendItems,
                        selectedTab = clipboardTab,
                        isDarkTheme = isDarkTheme,
                        backgroundColor = keyboardBgColor,
                        onSelectItem = { text ->
                            onClipboardSelect?.invoke(text)
                            showClipboard = false
                        },
                        onRemoveItem = { id -> onClipboardRemove?.invoke(id) },
                        onTogglePin = { id -> onClipboardTogglePin?.invoke(id) },
                        onAddToQuickSend = { id -> onAddToQuickSend?.invoke(id) },
                        onRemoveFromQuickSend = { id -> onRemoveFromQuickSend?.invoke(id) },
                        modifier = Modifier.weight(1f)
                    )
                }

                showSchemaList -> {
                    SchemaListView(
                        schemas = schemas,
                        currentSchemaId = currentSchemaId,
                        currentLayoutId = null,
                        isDarkTheme = isDarkTheme,
                        backgroundColor = keyboardBgColor,
                        accentColor = accentColor,
                        onSelectSchema = { schemaId, _ ->
                            onSwitchSchema?.invoke(schemaId)
                            keyboardMode = InputMode.keyboardModeFor(schemaId)
                            showSchemaList = false
                        },
                        modifier = Modifier.weight(1f)
                    )
                }

                showEmoji -> {
                    EmojiKeyboardLayout(
                        onEmojiSelect = { emoji ->
                            if (emoji == "delete") {
                                onKeyPress("delete", false)
                            } else {
                                onClipboardSelect?.invoke(emoji)
                            }
                        },
                        onImageEmojiSelect = onCommitImage,
                        onBack = { showEmoji = false },
                        backgroundColor = candidateBarBg,
                        textColor = keyTextColor,
                        modifier = Modifier.weight(1f)
                    )
                }

                showCandidatePage -> {
                    CandidatePage(
                        candidates = candidates.toList(),
                        candidateComments = candidateComments.toList(),
                        associationCandidates = associationCandidates.toList(),
                        inputText = inputText,
                        onCandidateSelect = { index ->
                            onCandidateSelect(index)
                            showCandidatePage = false
                        },
                        onAssociationSelect = { index ->
                            onAssociationSelect?.invoke(index)
                            showCandidatePage = false
                        },
                        backgroundColor = candidateBarBg,
                        textColor = candidateTextColor,
                        hasNextPage = state.hasNextPage,
                        hasPrevPage = state.hasPrevPage,
                        onPageDown = onPageDown,
                        onPageUp = onPageUp,
                        modifier = Modifier.weight(1f)
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
                                            "emoji" -> showEmoji = true
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
                                            "emoji" -> showEmoji = true
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
                                    onKeyPressDown = onKeyPressDown
                                )
                            }
                        }
                        KeyboardMode.NUMBER -> {
                            NumberKeyboardLayout(
                                onKeyPress = { key ->
                                    when (key) {
                                        "abc" -> {
                                            keyboardMode = KeyboardMode.FULL
                                            onSchemaSwitch?.invoke(currentSchemaId)
                                        }
                                        "symbol" -> keyboardMode = KeyboardMode.SYMBOL
                                        "t9" -> {
                                            keyboardMode = KeyboardMode.NINEKEY
                                            onSchemaSwitch?.invoke(InputMode.T9_PINYIN.schemaId)
                                        }
                                        "emoji" -> showEmoji = true
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
                        KeyboardMode.NINEKEY -> {
                            NineKeyKeyboardLayout(
                                onReplaceFullPinyin = { pinyin ->
                                    onT9ReplaceFullPinyin?.invoke(pinyin)
                                },
                                onKeyPress = { key ->
                                    when (key) {
                                        "abc" -> {
                                            pendingAscii = true
                                            keyboardMode = KeyboardMode.FULL
                                            onKeyPress("ime_switch", false)
                                        }
                                        "mode_change" -> {
                                            keyboardMode = KeyboardMode.NUMBER
                                            onSchemaSwitch?.invoke(currentSchemaId)
                                        }
                                        "symbol" -> {
                                            keyboardMode = KeyboardMode.SYMBOL
                                            onSchemaSwitch?.invoke(currentSchemaId)
                                        }
                                        "emoji" -> showEmoji = true
                                        "delete" -> onKeyPress("delete", false)
                                        "space" -> onKeyPress("space", false)
                                        "enter" -> onKeyPress("enter", false)
                                        "'" -> onKeyPress("'", false)
                                        else -> onKeyPress(key, false)
                                    }
                                },
                                keyBackgroundColor = keyBgColor,
                                keyTextColor = keyTextColor,
                                specialKeyBackgroundColor = specialKeyBgColor,
                                keyboardBackgroundColor = keyboardBgColor,
                                modifier = Modifier.weight(1f).then(cursorMod),
                                onKeyPressDown = onKeyPressDown,
                                resetSignal = state.inputSessionId
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
            // 位移间距（键盘区和候选栏向上位移时在此处增加空白）
            Spacer(modifier = Modifier.height(keyboardBottomPaddingDp.dp))
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
                                    showMenu = false
                                    showCandidatePage = false
                                    showClipboard = false
                                    showSchemaList = false
                                    showEmoji = false
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
                Spacer(modifier = Modifier.height(40.dp))
            } else if (!isVoiceMode && isLandscapeBottom) {
                Spacer(modifier = Modifier.height(15.dp))
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
    }
}