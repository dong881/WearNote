package com.example.wearnote.auth

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import android.net.Uri
import android.util.Log
import androidx.core.os.bundleOf
import com.example.wearnote.MainActivity
import kotlinx.coroutines.launch

class OAuthRedirectActivity : ComponentActivity() {
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // 處理 OAuth 重定向
        val uri = intent.data
        if (uri != null) {
            handleRedirectUri(uri)
        } else {
            navigateToMain(false)
        }
    }
    
    private fun handleRedirectUri(uri: Uri) {
        try {
            // 從 URI 獲取授權碼
            val authCode = uri.getQueryParameter("code")
            
            if (authCode != null) {
                // 處理成功授權
                lifecycleScope.launch {
                    val authManager = AuthManager(applicationContext)
                    val success = authManager.handleAuthorizationResponse(authCode)
                    navigateToMain(success)
                }
            } else {
                // 處理授權錯誤
                val error = uri.getQueryParameter("error") ?: "Unknown error"
                Log.e("OAuth", "Authorization failed: $error")
                navigateToMain(false)
            }
        } catch (e: Exception) {
            Log.e("OAuth", "Error processing OAuth redirect", e)
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
