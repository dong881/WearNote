package com.example.wearnote.utils

import android.content.Context
import android.content.SharedPreferences
import com.example.wearnote.model.AudioSource

class PreferenceManager(context: Context) {
    companion object {
        private const val PREF_NAME = "wearnote_preferences"
        private const val KEY_AUDIO_SOURCE = "audio_source"
        private const val KEY_RECORDING_ACTIVE = "recording_active"
        private const val KEY_CURRENT_RECORDING_PATH = "current_recording_path"
        private const val KEY_AUTO_START = "auto_start_recording"
        private const val KEY_DRIVE_CREDENTIALS_SETUP = "drive_credentials_setup"
    }
    
    private val preferences: SharedPreferences = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
    
    fun getAudioSource(): AudioSource {
        val sourceOrdinal = preferences.getInt(KEY_AUDIO_SOURCE, AudioSource.PHONE.ordinal)
        return AudioSource.values().getOrElse(sourceOrdinal) { AudioSource.PHONE }
    }
    
    fun setAudioSource(audioSource: AudioSource) {
        preferences.edit().putInt(KEY_AUDIO_SOURCE, audioSource.ordinal).apply()
    }
    
    fun isRecordingActive(): Boolean {
        return preferences.getBoolean(KEY_RECORDING_ACTIVE, false)
    }
    
    fun setRecordingActive(active: Boolean) {
        preferences.edit().putBoolean(KEY_RECORDING_ACTIVE, active).apply()
    }
    
    fun getCurrentRecordingPath(): String? {
        return preferences.getString(KEY_CURRENT_RECORDING_PATH, null)
    }
    
    fun setCurrentRecordingPath(path: String?) {
        if (path == null) {
            preferences.edit().remove(KEY_CURRENT_RECORDING_PATH).apply()
        } else {
            preferences.edit().putString(KEY_CURRENT_RECORDING_PATH, path).apply()
        }
    }
    
    fun isAutoStartEnabled(): Boolean {
        return preferences.getBoolean(KEY_AUTO_START, true)
    }
    
    fun setAutoStartEnabled(enabled: Boolean) {
        preferences.edit().putBoolean(KEY_AUTO_START, enabled).apply()
    }
    
    fun isDriveCredentialsSetup(): Boolean {
        return preferences.getBoolean(KEY_DRIVE_CREDENTIALS_SETUP, false)
    }
    
    fun setDriveCredentialsSetup(setup: Boolean) {
        preferences.edit().putBoolean(KEY_DRIVE_CREDENTIALS_SETUP, setup).apply()
    }
}