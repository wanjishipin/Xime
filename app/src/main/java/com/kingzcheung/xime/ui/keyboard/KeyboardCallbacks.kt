package com.kingzcheung.xime.ui.keyboard

import com.kingzcheung.xime.keyboard.GestureAction

data class KeyboardCallbacks(
    val onKeyPress: (String, Boolean) -> Unit,
    val onKeyPressDown: ((String) -> Unit)? = null,
    val onCandidateSelect: (Int) -> Unit,
    val onAssociationSelect: ((Int) -> Unit)? = null,
    val onClearAssociation: (() -> Unit)? = null,
    val onToggleDarkMode: (() -> Unit)? = null,
    val onClipboard: (() -> Unit)? = null,
    val onClipboardSelect: ((String) -> Unit)? = null,
    val onCommitText: ((String) -> Unit)? = null,
    val onDeleteText: ((Int) -> Unit)? = null,
    val onQuickSend: (() -> Unit)? = null,
    val onKeyboardResize: (() -> Unit)? = null,
    val onReloadConfig: (() -> Unit)? = null,
    val onSettings: (() -> Unit)? = null,
    val onSwitchSchema: ((String) -> Unit)? = null,
    val onHideKeyboard: (() -> Unit)? = null,
    val onSwitchKeyboard: (() -> Unit)? = null,
    val onToolbarEditingAction: ((String) -> Unit)? = null,
    val onCommitImage: ((String) -> Unit)? = null,
    val onVoiceModeChange: ((Boolean) -> Unit)? = null,
    val onPageDown: (() -> Unit)? = null,
    val onPageUp: (() -> Unit)? = null,
    val onCursorMove: ((Int) -> Unit)? = null,
    val onGestureAction: ((GestureAction, String) -> Unit)? = null,
    val onUpdateToolbarButtons: ((List<String>) -> Unit)? = null,
    val onKeyboardModeChange: ((Boolean) -> Unit)? = null,
    val onDismissDeploying: (() -> Unit)? = null,
    val onFloatingModeChange: ((Boolean) -> Unit)? = null,
    val onFloatingKeyboardDrag: ((dx: Float, dy: Float) -> Unit)? = null,
    val onFloatingKeyboardDragEnd: (() -> Unit)? = null,
    val onT9ReplaceFullPinyin: ((String) -> Unit)? = null,
    val onT9RightCommitUndone: ((Int) -> Unit)? = null,
    /**
     * 右侧候选词即将被 RIME select 前同步通知 T9 控制器。
     * 返回 true 表示控制器判断输入序列已被该候选词完整消费。
     */
    var onT9RightCandidateWillBeSelected: ((String?) -> Boolean)? = null,
    /**
     * T9 键盘切换离开（至数字/英文键盘）时调用。
     * 服务层负责提交首位候选词并清理 T9 状态。
     */
    val onT9SwitchAway: (() -> Unit)? = null,
)
