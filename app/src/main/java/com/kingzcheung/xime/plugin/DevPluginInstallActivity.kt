package com.kingzcheung.xime.plugin

import android.app.Activity
import android.os.Bundle
import com.kingzcheung.xime.BuildConfig
import com.kingzcheung.xime.settings.SettingsPreferences
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * 插件热安装入口（`xipm dev` 的主通道；debug 直用，release 由"插件开发模式"开关门禁）。
 *
 * ```
 * adb shell am start -n com.kingzcheung.xime/.plugin.DevPluginInstallActivity \
 *   --es path <设备上的 xipk 路径>
 * ```
 *
 * path 来源：debug 包走 `run-as` 写入的 `files/xipm-dev/<plugin>.xipk`；
 * release 包走 CLI 推送的 `/data/local/tmp/xipm-dev/<plugin>.xipk`（安装后由
 * [DevPluginInstaller] 删除）。
 *
 * 安全门禁（仅 release）：设置 → 关于 → 连点设备信息 7 次解锁的"插件开发模式"
 * 开关。开关关闭时本 Activity 立即 finish、不读 path、不安装，组件无注入面。
 * 组件 exported=true：Android 15 起 adb shell 已无法启动非导出组件，热安装通道
 * 依赖 `am start`；普通应用虽可拉起，但在开发模式关闭时会被门禁直接拒绝，仅开发
 * 模式开启的调试窗口内可安装。debug 包 debuggable（run-as 全量开放），门禁无
 * 意义，直接放行。
 *
 * `adb shell am start` 由 shell（特权）发起，不受应用后台执行限制，宿主在后台
 * （如未使用键盘）时也能可靠触发；透明无界面主题、无历史记录，安装完成后立即
 * finish，用户几乎无感。
 */
class DevPluginInstallActivity : Activity() {

    private val scope = MainScope()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 门禁仅约束 release 包（无 adb 之外的注入价值才需要防）；debug 包本身
        // debuggable——run-as 全量开放，再设门禁只有摩擦没有安全收益，直接放行
        if (!BuildConfig.DEBUG && !SettingsPreferences.isPluginDevModeEnabled(this)) {
            finish()
            return
        }
        val path = intent?.getStringExtra(EXTRA_PATH)
        scope.launch {
            try {
                DevPluginInstaller.install(applicationContext, path)
            } finally {
                finish()
            }
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_PATH = "path"
    }
}
