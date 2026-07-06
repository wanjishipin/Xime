package com.kingzcheung.xime.keyboard

import android.view.KeyEvent

/**
 * 手势动作执行上下文接口。
 *
 * 由 InputMethodService 实现并注入给 [GestureAction.execute]，封装所有执行手势动作所需的能力。
 * 枚举值通过此接口操作编辑器，不直接依赖 InputConnection，便于测试和替换。
 */
interface ActionExecutor {

    /**
     * 上屏指定文本。
     * @param text 要上屏的文本内容
     */
    fun commitText(text: String)

    /**
     * 执行系统编辑器菜单动作。
     * @param actionId Android 内置动作 ID，如 [android.R.id.selectAll]、[android.R.id.copy]、
     *                 [android.R.id.cut]、[android.R.id.paste]、[android.R.id.undo]
     */
    fun performEditorMenuAction(actionId: Int)

    /**
     * 发送按键事件（用于光标移动和特殊按键等操作）。
     * @param keyCode  按键码，如 [KeyEvent.KEYCODE_MOVE_HOME]、[KeyEvent.KEYCODE_MOVE_END]
     * @param metaState 修饰键掩码，0 表示无修饰键。如 [KeyEvent.META_CTRL_ON]
     */
    fun sendKeyEvent(keyCode: Int, metaState: Int = 0)

    /**
     * 执行内置命令。
     * @param name 命令名，如 "clear_composition"（清空输入）
     */
    fun executeCommand(name: String)

    /** 重复上一次输入。 */
    fun repeatLastInput()
}

/**
 * 键盘手势动作枚举。
 *
 * 每个枚举值自包含执行逻辑（[execute]），零分支调度。
 * 新增动作只需添加一个枚举值 + 实现 execute() 即可。
 *
 * YAML 中的 action 字段值通过 [fromValue] 映射到枚举。
 */
enum class GestureAction(val value: String) {

    /** 上屏文本，value 为上屏内容。 */
    COMMIT("commit") {
        override fun execute(context: ActionExecutor, value: String) {
            context.commitText(value)
        }
    },

    /** 执行内置命令，value 为命令名（如 "clear_composition"）。 */
    COMMAND("command") {
        override fun execute(context: ActionExecutor, value: String) {
            context.executeCommand(value)
        }
    },

    /** 全选。 */
    SELECT_ALL("select_all") {
        override fun execute(context: ActionExecutor, value: String) {
            context.performEditorMenuAction(android.R.id.selectAll)
        }
    },

    /** 复制。 */
    COPY("copy") {
        override fun execute(context: ActionExecutor, value: String) {
            context.performEditorMenuAction(android.R.id.copy)
        }
    },

    /** 剪切。 */
    CUT("cut") {
        override fun execute(context: ActionExecutor, value: String) {
            context.performEditorMenuAction(android.R.id.cut)
        }
    },

    /** 粘贴。 */
    PASTE("paste") {
        override fun execute(context: ActionExecutor, value: String) {
            context.performEditorMenuAction(android.R.id.paste)
        }
    },

    /** 移动到行首。 */
    LINE_START("line_start") {
        override fun execute(context: ActionExecutor, value: String) {
            context.sendKeyEvent(KeyEvent.KEYCODE_MOVE_HOME)
        }
    },

    /** 移动到行尾。 */
    LINE_END("line_end") {
        override fun execute(context: ActionExecutor, value: String) {
            context.sendKeyEvent(KeyEvent.KEYCODE_MOVE_END)
        }
    },

    /** 撤销。 */
    UNDO("undo") {
        override fun execute(context: ActionExecutor, value: String) {
            context.performEditorMenuAction(android.R.id.undo)
        }
    },

    /** 仅显示，无操作。 */
    NONE("none") {
        override fun execute(context: ActionExecutor, value: String) { /* no-op */ }
    },

    /** 重复上一次输入。 */
    REPEAT("repeat") {
        override fun execute(context: ActionExecutor, value: String) {
            context.repeatLastInput()
        }
    },

    /** 切换键盘路由/面板（如打开 emoji、符号面板）。
     *  由 KeyboardView 拦截处理，[ActionExecutor] 层 no-op。 */
    SWITCH_ROUTE("switch_route") {
        override fun execute(context: ActionExecutor, value: String) { /* no-op, handled at UI layer */ }
    },

    /** 切换中/英文输入模式。
     *  由 KeyboardView 拦截处理，发送 "ime_switch" 指令到服务层。 */
    TOGGLE_ASCII("toggle_ascii") {
        override fun execute(context: ActionExecutor, value: String) { /* no-op, handled at UI layer */ }
    },

    /** 删除/退格。由 UI 层拦截处理，调用 onKeyPress("delete")。 */
    DELETE("delete") {
        override fun execute(context: ActionExecutor, value: String) { /* no-op, handled at UI layer */ }
    },

    /** 切换符号键盘。由 UI 层拦截处理。 */
    TOGGLE_SYMBOLS("toggle_symbols") {
        override fun execute(context: ActionExecutor, value: String) { /* no-op, handled at UI layer */ }
    },

    /** 发送特殊按键。value 为按键表达式（如 "Escape"、"Control+c"）。 */
    SEND_KEY("send") {
        override fun execute(context: ActionExecutor, value: String) {
            val (keyCode, metaState) = Keycode.parseSend(value)
            if (keyCode != 0) {
                context.sendKeyEvent(keyCode, metaState)
            }
        }
    };

    /**
     * 执行本动作。
     * @param context 执行上下文，提供 InputConnection 等能力
     * @param value   动作参数（如 COMMIT 时的上屏文本、COMMAND 时的命令名）
     */
    abstract fun execute(context: ActionExecutor, value: String)

    companion object {
        private val map = entries.associateBy { it.value }

        /** 根据 YAML 字符串值查找对应的枚举，找不到返回 null。 */
        fun fromValue(value: String): GestureAction? = map[value]
    }
}
