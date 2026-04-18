package com.kingzcheung.kime.plugin.core.api

import android.os.Bundle

interface IPluginActivity {
    fun onAttach(proxyActivity: android.app.Activity)
    fun onCreate(savedInstanceState: Bundle?)
    fun onStart()
    fun onResume()
    fun onPause()
    fun onStop()
    fun onDestroy()
    fun onSaveInstanceState(outState: Bundle)
    fun onBackPressed(): Boolean
}