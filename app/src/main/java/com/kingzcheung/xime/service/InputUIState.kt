package com.kingzcheung.xime.service

import com.kingzcheung.xime.settings.SchemaInfo
import com.kingzcheung.xime.settings.SettingsPreferences
import com.kingzcheung.xime.speech.RecognitionState
import com.kingzcheung.xime.keyboard.ToolbarButton

data class InputUIState(
    val isAsciiMode: Boolean = false,
    val schemaName: String = "",
    val currentSchemaId: String = "",
    val schemas: List<SchemaInfo> = emptyList(),
    val enterKeyText: String = "发送",
    val darkMode: Int = 0,
    val themeId: String = "ocean_blue",
    val isSttEnabled: Boolean = false,
    val keyboardHeightDp: Int = 0,
    val keyboardBottomPaddingDp: Int = 0,
    val showKeyboardResize: Boolean = false,
    val resizePreviewHeightDp: Int = 0,
    val associationEnabled: Boolean = false,
    val isVoiceMode: Boolean = false,
    val voiceButtonState: VoiceButtonState = VoiceButtonState(),
    val voicePluginName: String = "",
    val voiceRecognitionState: RecognitionState = RecognitionState.IDLE,
    val voiceRecognizedText: String = "",
    val voiceAmplitude: Float = 0f,
    val stretchFactor: Float = 1f,
    val isDeploying: Boolean = false,
    val deploymentMessage: String = "",
    val inputSessionId: Long = 0,
    val toolbarButtons: List<String> = ToolbarButton.DEFAULT_VISIBLE.map { it.id },
    val isCompact: Boolean = false,
    val isFloatingMode: Boolean = false,
    val floatingOffsetX: Int = 0,
    val floatingOffsetY: Int = 0,
    val isGlassEffectEnabled: Boolean = false,
)
