package com.kingzcheung.xime

import android.app.Application
import android.util.Log
import com.kingzcheung.xime.plugin.ExtensionManager
import com.kingzcheung.xime.util.FileLogger
import com.kingzcheung.xime.plugin.core.runtime.PluginManager
import com.kingzcheung.xime.rime.RimeConfigHelper
import com.kingzcheung.xime.model.ModelRuntime
import com.kingzcheung.xime.rime.RimeEngine
import com.kingzcheung.xime.settings.KeysConfigHelper
import com.kingzcheung.xime.settings.SettingsPreferences
import com.kingzcheung.xime.ui.theme.KeyboardThemes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class XimeApplication : Application() {
    
    companion object {
        private const val TAG = "XimeApplication"
        const val HOST_PROVIDER_AUTHORITY = "com.wanjishipin.xime.plugin.proxy"
    }
    
    private val applicationScope = CoroutineScope(Dispatchers.IO)
    
    override fun onCreate() {
        super.onCreate()

        FileLogger.init(this)
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                FileLogger.e("CrashHandler", "Uncaught exception on thread: ${thread.name}", throwable)
                FileLogger.flush()
            } catch (_: Exception) {
            }
            defaultHandler?.uncaughtException(thread, throwable)
        }

        Log.d(TAG, "Initializing PluginManager...")
        PluginManager.initialize(this, HOST_PROVIDER_AUTHORITY) {
            Log.d(TAG, "PluginManager onSetup callback executing...")
            
            Log.d(TAG, "Scanning system installed plugins...")
            val systemInstalled = PluginManager.scanAndInstallSystemPlugins()
            Log.d(TAG, "Installed $systemInstalled plugins from system")
            
            if (BuildConfig.DEBUG) {
                val assetInstalled = PluginManager.installPluginsFromAssetsForDebug("plugins")
                Log.d(TAG, "Installed $assetInstalled plugins from assets")
            }
            
            Log.d(TAG, "Loading enabled plugins...")
            val loaded = PluginManager.loadEnabledPlugins()
            Log.d(TAG, "Loaded $loaded plugins")
            
            Log.d(TAG, "All installed plugins: ${PluginManager.getAllInstallPlugins().map { it.id }}")
            Log.d(TAG, "All plugin instances: ${PluginManager.getAllPluginInstances().keys}")
        }
        
        Log.d(TAG, "Initializing ExtensionManager...")
        ExtensionManager.initialize(this)

        // 从 xime.yaml 加载配色方案
        KeyboardThemes.initFromConfig(this)

        // 从 xime.yaml 的 style.color_scheme 读取默认主题
        SettingsPreferences.defaultKeyboardTheme = KeysConfigHelper.loadDefaultThemeId(this)
        Log.d(TAG, "Default keyboard theme: ${SettingsPreferences.defaultKeyboardTheme}")

        // 初始化模型运行时（内存管理 + 生命周期）
        ModelRuntime.attach(this)
        Log.d(TAG, "ModelRuntime initialized")

        preInitializeRimeEngine()
        
        Log.d(TAG, "Initialization complete")
    }
    
    private fun preInitializeRimeEngine() {
        if (RimeEngine.isInitialized()) {
            Log.d(TAG, "Rime engine already initialized")
            return
        }
        
        Log.d(TAG, "Pre-initializing Rime engine...")
        applicationScope.launch {
            try {
                val (userDataDir, sharedDataDir) = RimeConfigHelper.initializeRimeDataAsync(this@XimeApplication)
                val engine = RimeEngine.getInstance()
                engine.initialize(userDataDir, sharedDataDir)

                // 首次启动时静默编译词库，避免用户在设置中手动点「部署」
                if (!SettingsPreferences.isDeploymentDone(this@XimeApplication)) {
                    Log.d(TAG, "First launch: silently deploying schemas...")
                    engine.deploy()
                    SettingsPreferences.setDeploymentDone(this@XimeApplication, true)
                    RimeConfigHelper.storeDeploymentHash(this@XimeApplication)
                    Log.d(TAG, "Silent deploy completed")
                }

                Log.d(TAG, "Rime engine pre-initialization completed")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to pre-initialize Rime engine", e)
            }
        }
    }
}