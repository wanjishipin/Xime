package com.kingzcheung.kime

import android.app.Application
import android.util.Log
import com.kingzcheung.kime.plugin.ExtensionManager
import com.kingzcheung.kime.plugin.core.runtime.PluginManager

class KimeApplication : Application() {
    
    companion object {
        private const val TAG = "KimeApplication"
        const val HOST_PROVIDER_AUTHORITY = "com.kingzcheung.kime.plugin.proxy"
    }
    
    override fun onCreate() {
        super.onCreate()
        
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
        Log.d(TAG, "Initialization complete")
    }
}