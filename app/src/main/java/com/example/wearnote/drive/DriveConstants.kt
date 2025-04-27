package com.example.wearnote.drive

import android.content.Context
import com.example.wearnote.R

object DriveConstants {
    // OAuth related
    fun getClientId(context: Context): String = context.getString(R.string.android_client_id)
    const val REDIRECT_URI = "com.example.wearnote:/oauth2callback"
    const val AUTH_ENDPOINT = "https://accounts.google.com/o/oauth2/v2/auth"
    const val TOKEN_ENDPOINT = "https://oauth2.googleapis.com/token"
    
    // Drive API related
    const val DRIVE_SCOPE = "https://www.googleapis.com/auth/drive.file"
    const val PROFILE_SCOPE = "profile"
    const val EMAIL_SCOPE = "email"
    
    // SharedPreferences keys
    const val PREF_NAME = "drive_auth_prefs"
    const val PREF_ACCESS_TOKEN = "access_token"
    const val PREF_REFRESH_TOKEN = "refresh_token"
    const val PREF_EXPIRY_TIME = "expiry_time"
    
    // Local storage paths
    const val NOTES_DIRECTORY = "WearNote"
}
