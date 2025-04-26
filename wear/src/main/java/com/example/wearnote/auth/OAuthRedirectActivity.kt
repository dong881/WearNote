package com.example.wearnote.auth

import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import com.example.wearnote.MainActivity

/**
 * 處理 OAuth 回調的 Activity
 */
class OAuthRedirectActivity : ComponentActivity() {
    
    private val TAG = "OAuthRedirectActivity"
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // 處理 OAuth 重定向
        val uri = intent.data
        if (uri != null) {
            val code = uri.getQueryParameter("code")
            val error = uri.getQueryParameter("error")
            
            Log.d(TAG, "Received OAuth redirect: ${uri.toString()}")
            
            if (code != null) {
                Log.d(TAG, "Authorization code received")
                // 這裡可以處理授權碼，但目前我們只是簡單返回主活動
                navigateToMain(true)
            } else if (error != null) {
                Log.e(TAG, "Authorization error: $error")
                navigateToMain(false)
            } else {
                Log.e(TAG, "Unknown redirect state")
                navigateToMain(false)
            }
        } else {
            Log.e(TAG, "No URI data in intent")
            navigateToMain(false)
        }
    }
    
    private fun navigateToMain(success: Boolean) {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("auth_success", success)
        }
        startActivity(intent)
        finish()
    }
}
