package com.kingzcheung.kime.plugin.core.component

import android.os.Bundle
import com.kingzcheung.kime.plugin.core.api.IPluginActivity

open class BasePluginActivity : IPluginActivity {
    
    protected var proxyActivity: android.app.Activity? = null
    
    override fun onAttach(proxyActivity: android.app.Activity) {
        this.proxyActivity = proxyActivity
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {}
    override fun onStart() {}
    override fun onResume() {}
    override fun onPause() {}
    override fun onStop() {}
    override fun onDestroy() {}
    override fun onSaveInstanceState(outState: Bundle) {}
    override fun onBackPressed(): Boolean = false
}