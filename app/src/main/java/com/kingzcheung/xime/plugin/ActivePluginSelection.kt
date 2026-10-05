package com.kingzcheung.xime.plugin

/**
 * 单选类插件（语音识别 / 剪贴板同步 / 备份）"当前使用中"的**统一判定规则**。
 *
 * 背景：这类插件的"当前使用中"有两个读者——**引擎**（真正启动哪一个）与
 * **插件管理页**（展示"当前使用中/未使用"）。两边各自实现时容易分叉：
 * 引擎在偏好为空、或偏好指向一个已卸载/已停用的插件时会回退到"首个已启用插件"，
 * 而页面若只比较持久化偏好，就会出现"同步明明在跑，插件页却显示未使用"。
 *
 * 因此两边必须共用本规则：**优先用户偏好，偏好不可用则取首个已启用项**。
 * 调用方（引擎）在解析结果与偏好不一致时回填偏好，让状态收敛而不是长期并存两种"真相"。
 */
object ActivePluginSelection {

    /**
     * @param preferredId 持久化的用户选择；可能为空串，或指向一个已卸载/未启用的插件
     * @param enabledIds 当前已启用的候选插件 id，**顺序即回退顺序**（与各自引擎的候选顺序一致）
     * @return 实际生效的插件 id；无任何已启用候选时返回空串
     */
    fun resolve(preferredId: String?, enabledIds: List<String>): String {
        val preferred = preferredId?.trim().orEmpty()
        if (preferred.isNotEmpty() && enabledIds.contains(preferred)) return preferred
        return enabledIds.firstOrNull() ?: ""
    }
}