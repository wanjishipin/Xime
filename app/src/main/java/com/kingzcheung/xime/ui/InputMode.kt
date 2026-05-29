package com.kingzcheung.xime.ui

/**
 * 输入模式——将键盘布局与 Rime 方案一对一绑定。
 *
 * 每个 [InputMode] 包含：
 * - [schemaId]：Rime 方案 ID，传给 rimeEngine.switchSchema()
 * - [keyboardMode]：对应的键盘布局
 * - [displayName]：显示名称
 *
 * 代替散落在各处的 `if (schemaId == "t9_pinyin")` 硬编码判断，
 * 保证方案和键盘布局始终一致。
 */
enum class InputMode(
    val schemaId: String,
    val keyboardMode: KeyboardMode,
    val displayName: String
) {
    WUBI86("wubi86", KeyboardMode.FULL, "五笔86"),
    WUBI98("wubi98", KeyboardMode.FULL, "五笔98"),
    PINYIN("pinyin", KeyboardMode.FULL, "拼音"),
    T9_PINYIN("t9_pinyin", KeyboardMode.NINEKEY, "拼音九键"),
    DOUBLE_PINYIN("double_pinyin", KeyboardMode.FULL, "双拼"),
    STROKE("stroke", KeyboardMode.FULL, "五笔划"),
    ;

    companion object {
        /** 根据 schemaId 查找对应的输入模式，未知 schema 默认返回全键盘 */
        fun fromSchemaId(schemaId: String): InputMode {
            return entries.find { it.schemaId == schemaId } ?: WUBI86
        }

        /** 根据 schemaId 返回对应的键盘布局 */
        fun keyboardModeFor(schemaId: String): KeyboardMode {
            return fromSchemaId(schemaId).keyboardMode
        }
    }
}
