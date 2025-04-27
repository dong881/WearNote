package com.example.wearnote.drive

import android.content.Context

object DriveConstants {
    // OAuth 相關
    fun getClientId(context: Context): String = context.getString(com.example.wearnote.R.string.android_client_id)
    const val REDIRECT_URI = "com.example.wearnote:/oauth2callback"
    const val AUTH_ENDPOINT = "https://accounts.google.com/o/oauth2/v2/auth"
    const val TOKEN_ENDPOINT = "https://oauth2.googleapis.com/token"
    
    // Drive API 相關
    const val DRIVE_SCOPE = "https://www.googleapis.com/auth/drive.file"
    const val PROFILE_SCOPE = "profile"
    const val EMAIL_SCOPE = "email"
    
    // SharedPreferences 鍵值
    const val PREF_NAME = "drive_auth_prefs"
    const val PREF_ACCESS_TOKEN = "access_token"
    const val PREF_REFRESH_TOKEN = "refresh_token"
    const val PREF_EXPIRY_TIME = "expiry_time"
    
    // 本地檔案存儲路徑
    const val NOTES_DIRECTORY = "WearNote"
}
