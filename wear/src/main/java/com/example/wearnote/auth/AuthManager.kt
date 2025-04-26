package com.example.wearnote.auth

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.activity.ComponentActivity
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.Scope
import java.security.SecureRandom
import com.example.wearnote.R  // Add this import

/**
 * 管理 OAuth 授權的輔助類
 */
class AuthManager(private val context: Context) {
    
    companion object {
        private const val TAG = "AuthManager"
        const val RC_SIGN_IN = 9001
        
        // OAuth 相關常量 
        private const val REDIRECT_URI = "com.example.wearnote:/oauth2callback"
        private const val AUTH_ENDPOINT = "https://accounts.google.com/o/oauth2/auth"
        private const val TOKEN_ENDPOINT = "https://oauth2.googleapis.com/token"
        private const val DRIVE_SCOPE = "https://www.googleapis.com/auth/drive.file"
    }
    
    // 啟動 Google Sign-In 流程
    fun startAuthorizationFlow(activity: ComponentActivity) {
        try {
            // 使用標準 OAuth 流程（當 Google Play Services 不可用時）
            startStandardOAuth(activity)
        } catch (e: Exception) {
            Log.e(TAG, "啟動授權流程失敗", e)
        }
    }
    
    // 標準 OAuth 授權流程
    private fun startStandardOAuth(activity: ComponentActivity) {
        // 獲取 CLIENT_ID
        val clientId = context.getString(R.string.default_web_client_id)
        
        // 構建 OAuth 授權 URL
        val authUri = Uri.parse(AUTH_ENDPOINT).buildUpon()
            .appendQueryParameter("client_id", clientId)
            .appendQueryParameter("redirect_uri", REDIRECT_URI)
            .appendQueryParameter("response_type", "code")
            .appendQueryParameter("scope", DRIVE_SCOPE)
            .build()
            
        // 打開瀏覽器進行授權
        val intent = Intent(Intent.ACTION_VIEW, authUri)
        activity.startActivity(intent)
    }
    
    // 使用 Google Sign-In API 進行授權
    fun startGoogleSignIn(activity: ComponentActivity) {
        val clientId = context.getString(R.string.default_web_client_id)
        
        val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestEmail()
            .requestScopes(Scope(DRIVE_SCOPE))
            .requestServerAuthCode(clientId)
            .build()
            
        val client = GoogleSignIn.getClient(activity, gso)
        activity.startActivityForResult(client.signInIntent, RC_SIGN_IN)
    }
    
    // 檢查是否已經登入
    fun isSignedIn(): Boolean {
        val account = GoogleSignIn.getLastSignedInAccount(context)
        return account != null
    }
    
    // 登出
    fun signOut(activity: ComponentActivity) {
        val client = GoogleSignIn.getClient(activity, GoogleSignInOptions.DEFAULT_SIGN_IN)
        client.signOut()
    }
}
