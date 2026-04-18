package com.kingzcheung.kime.plugin.funasr

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import com.kingzcheung.kime.plugin.funasr.ui.FunAsrSettingsScreen

class PluginSettingsActivity : ComponentActivity() {
    
    private lateinit var prefs: FunAsrPreferences
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // 插件 Activity 直接访问 Provider（不用代理）
        // Provider 内部会通过 sharedUserId 访问宿主的 SharedPreferences
        prefs = FunAsrPreferences.forPlugin(this)
        
        setContent {
            val initialApiKey = remember { prefs.getApiKey() }
            
            MaterialTheme {
                Surface {
                    FunAsrSettingsScreen(
                        initialApiKey = initialApiKey,
                        onSaveApiKey = { key -> prefs.saveApiKey(key) },
                        onNavigateBack = { finish() }
                    )
                }
            }
        }
    }
}