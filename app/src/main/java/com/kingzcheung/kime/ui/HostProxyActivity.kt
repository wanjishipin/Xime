package com.kingzcheung.kime.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import com.kingzcheung.kime.plugin.core.api.IPluginActivity
import com.kingzcheung.kime.plugin.core.runtime.PluginManager

class HostProxyActivity : ComponentActivity() {
    
    companion object {
        const val EXTRA_PLUGIN_ACTIVITY_CLASS = "plugin_activity_class"
    }
    
    private var pluginActivity: IPluginActivity? = null
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        val className = intent.getStringExtra(EXTRA_PLUGIN_ACTIVITY_CLASS)
        if (className == null) {
            finish()
            return
        }
        
        try {
            pluginActivity = PluginManager.getInterface(IPluginActivity::class.java, className)
            if (pluginActivity != null) {
                pluginActivity?.onAttach(this)
                pluginActivity?.onCreate(savedInstanceState)
            } else {
                finish()
            }
        } catch (e: Exception) {
            finish()
        }
    }
    
    override fun onStart() {
        super.onStart()
        pluginActivity?.onStart()
    }
    
    override fun onResume() {
        super.onResume()
        pluginActivity?.onResume()
    }
    
    override fun onPause() {
        super.onPause()
        pluginActivity?.onPause()
    }
    
    override fun onStop() {
        super.onStop()
        pluginActivity?.onStop()
    }
    
    override fun onDestroy() {
        super.onDestroy()
        pluginActivity?.onDestroy()
        pluginActivity = null
    }
    
    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        pluginActivity?.onSaveInstanceState(outState)
    }
    
    override fun onBackPressed() {
        if (pluginActivity?.onBackPressed() == true) {
            return
        }
        super.onBackPressed()
    }
}