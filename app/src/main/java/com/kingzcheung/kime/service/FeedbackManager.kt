package com.kingzcheung.kime.service

import android.content.Context
import android.content.SharedPreferences
import android.media.AudioManager
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import com.kingzcheung.kime.settings.SettingsPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class FeedbackManager(private val context: Context) {
    
    private val audioManager: AudioManager by lazy { 
        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager 
    }
    
    private val vibrator: Vibrator by lazy {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vibratorManager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
            vibratorManager.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }
    }
    
    private val feedbackScope = CoroutineScope(Dispatchers.Default)
    
    private var soundEnabled = AtomicBoolean(true)
    private var soundVolume = AtomicInteger(50)
    private var vibrationEnabled = AtomicBoolean(true)
    private var vibrationIntensity = AtomicInteger(50)
    
    private var prefsListener: SharedPreferences.OnSharedPreferenceChangeListener? = null
    
    fun initialize() {
        loadSettings()
        registerPrefsListener()
    }
    
    fun release() {
        prefsListener?.let {
            SettingsPreferences.getPrefsPublic(context).unregisterOnSharedPreferenceChangeListener(it)
        }
    }
    
    private fun loadSettings() {
        soundEnabled.set(SettingsPreferences.isSoundEnabled(context))
        soundVolume.set(SettingsPreferences.getSoundVolume(context))
        vibrationEnabled.set(SettingsPreferences.isVibrationEnabled(context))
        vibrationIntensity.set(SettingsPreferences.getVibrationIntensity(context))
    }
    
    private fun registerPrefsListener() {
        val prefs = SettingsPreferences.getPrefsPublic(context)
        prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            when (key) {
                "sound_enabled" -> soundEnabled.set(SettingsPreferences.isSoundEnabled(context))
                "sound_volume" -> soundVolume.set(SettingsPreferences.getSoundVolume(context))
                "vibration_enabled" -> vibrationEnabled.set(SettingsPreferences.isVibrationEnabled(context))
                "vibration_intensity" -> vibrationIntensity.set(SettingsPreferences.getVibrationIntensity(context))
            }
        }
        prefs.registerOnSharedPreferenceChangeListener(prefsListener)
    }
    
    fun playKeySound(keyType: String = "standard") {
        if (!soundEnabled.get()) return
        
        feedbackScope.launch {
            val volume = soundVolume.get() / 100f
            
            val effectType = when (keyType) {
                "delete" -> AudioManager.FX_KEYPRESS_DELETE
                "enter" -> AudioManager.FX_KEYPRESS_RETURN
                "space" -> AudioManager.FX_KEYPRESS_SPACEBAR
                else -> AudioManager.FX_KEYPRESS_STANDARD
            }
            
            audioManager.playSoundEffect(effectType, volume)
        }
    }
    
    fun performVibration() {
        if (!vibrationEnabled.get()) return
        if (!vibrator.hasVibrator()) return
        
        feedbackScope.launch {
            val intensity = vibrationIntensity.get()
            val duration = 10L + (intensity * 0.4).toLong()
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val amplitude = (intensity * 2.55).toInt().coerceIn(1, 255)
                vibrator.vibrate(VibrationEffect.createOneShot(duration, amplitude))
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(duration)
            }
        }
    }
    
    fun performKeyPressEffect(keyType: String = "standard") {
        playKeySound(keyType)
        performVibration()
    }
    
    fun performKeyPressDownEffect(key: String) {
        val keyType = when (key) {
            "delete", "clear_composition" -> "delete"
            "enter" -> "enter"
            "space" -> "space"
            else -> "standard"
        }
        playKeySound(keyType)
        performVibration()
    }
}