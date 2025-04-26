package com.example.wearnote.auth

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.util.Log
import androidx.activity.ComponentActivity
import com.example.wearnote.drive.DriveConstants
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.common.api.Scope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

class AuthManager(private val context: Context) {
    private val sharedPreferences: SharedPreferences = context.getSharedPreferences(
        DriveConstants.PREF_NAME, Context.MODE_PRIVATE
    )
    private val httpClient = OkHttpClient()
    
    // PKCE 驗證相關變數
    private var codeVerifier: String? = null
    
    // 檢查是否已授權
    fun isAuthorized(): Boolean {
        val accessToken = getAccessToken()
        val expiryTime = sharedPreferences.getLong(DriveConstants.PREF_EXPIRY_TIME, 0)
        val currentTime = System.currentTimeMillis()
        
        // 如果 token 存在且未過期，則認為已授權
        return accessToken != null && currentTime < expiryTime
    }
    
    // 獲取存儲的 access token
    fun getAccessToken(): String? {
        return if (isTokenExpired()) {
            refreshToken()
        } else {
            sharedPreferences.getString(DriveConstants.PREF_ACCESS_TOKEN, null)
        }
    }
    
    // 檢查 token 是否過期
    private fun isTokenExpired(): Boolean {
        val expiryTime = sharedPreferences.getLong(DriveConstants.PREF_EXPIRY_TIME, 0)
        return expiryTime <= System.currentTimeMillis()
    }
    
    // 刷新 token
    private fun refreshToken(): String? {
        val refreshToken = sharedPreferences.getString(DriveConstants.PREF_REFRESH_TOKEN, null) 
            ?: return null
            
        try {
            val requestBody = FormBody.Builder()
                .add("client_id", DriveConstants.CLIENT_ID)
                .add("refresh_token", refreshToken)
                .add("grant_type", "refresh_token")
                .build()
                
            val request = Request.Builder()
                .url(DriveConstants.TOKEN_ENDPOINT)
                .post(requestBody)
                .build()
                
            httpClient.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val jsonObject = JSONObject(response.body?.string() ?: "")
                    val newAccessToken = jsonObject.getString("access_token")
                    val expiresIn = jsonObject.getInt("expires_in")
                    
                    // 儲存新的 access token 和過期時間
                    saveTokenData(newAccessToken, null, expiresIn)
                    return newAccessToken
                }
            }
        } catch (e: Exception) {
            Log.e("AuthManager", "Failed to refresh token", e)
        }
        
        return null
    }
    
    // 啟動 OAuth 流程
    fun startAuthorizationFlow(activity: ComponentActivity) {
        // 檢查是否可以使用 Google Sign-In API
        if (GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(context) == ConnectionResult.SUCCESS) {
            startGoogleSignIn(activity)
        } else {
            // 無法使用 Google Play Services，改用標準 OAuth 流程
            startStandardOAuth(activity)
        }
    }

    // 使用 Google Sign-In API 進行授權
    private fun startGoogleSignIn(activity: ComponentActivity) {
        val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestEmail()
            .requestScopes(Scope(DriveConstants.DRIVE_SCOPE))
            .requestServerAuthCode(DriveConstants.CLIENT_ID, true) // 添加 force flag
            .requestIdToken(DriveConstants.CLIENT_ID) // 請求 ID token
            .build()
            
        val client = GoogleSignIn.getClient(activity, gso)
        // 先登出以避免舊有的登入狀態
        client.signOut().addOnCompleteListener {
            activity.startActivityForResult(client.signInIntent, RC_SIGN_IN)
        }
        
        // 使用 PKCE 增強安全性
        codeVerifier = generateCodeVerifier()
    }

    // 使用標準 OAuth 流程（當 Google Play Services 不可用時）
    private fun startStandardOAuth(activity: ComponentActivity) {
        // 生成 PKCE code challenge
        codeVerifier = generateCodeVerifier()
        val codeChallenge = generateCodeChallenge(codeVerifier!!)
        
        // 構建 OAuth 授權 URL
        val authUri = Uri.parse(DriveConstants.AUTH_ENDPOINT).buildUpon()
            .appendQueryParameter("client_id", DriveConstants.CLIENT_ID)
            .appendQueryParameter("redirect_uri", DriveConstants.REDIRECT_URI)
            .appendQueryParameter("response_type", "code")
            .appendQueryParameter("scope", "${DriveConstants.DRIVE_SCOPE} ${DriveConstants.PROFILE_SCOPE} ${DriveConstants.EMAIL_SCOPE}")
            .appendQueryParameter("code_challenge", codeChallenge)
            .appendQueryParameter("code_challenge_method", "S256")
            .build()
            
        // 打開瀏覽器進行授權
        val intent = Intent(Intent.ACTION_VIEW, authUri)
        activity.startActivity(intent)
    }
    
    // PKCE: 生成 code_verifier
    private fun generateCodeVerifier(): String {
        val secureRandom = SecureRandom()
        val bytes = ByteArray(64)
        secureRandom.nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }
    
    // PKCE: 生成 code_challenge
    private fun generateCodeChallenge(verifier: String): String {
        val bytes = verifier.toByteArray(Charsets.US_ASCII)
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(digest)
    }
    
    // 處理授權碼交換 access token
    suspend fun handleAuthorizationResponse(authCode: String): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                val requestBody = FormBody.Builder()
                    .add("code", authCode)
                    .add("client_id", DriveConstants.CLIENT_ID)
                    .add("redirect_uri", DriveConstants.REDIRECT_URI)
                    .add("grant_type", "authorization_code")
                    .also { 
                        // 如果有 codeVerifier，加入 PKCE 驗證
                        codeVerifier?.let { verifier ->
                            it.add("code_verifier", verifier)
                        }
                    }
                    .build()
                    
                val request = Request.Builder()
                    .url(DriveConstants.TOKEN_ENDPOINT)
                    .post(requestBody)
                    .build()
                    
                httpClient.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        val jsonObject = JSONObject(response.body?.string() ?: "")
                        val accessToken = jsonObject.getString("access_token")
                        val refreshToken = jsonObject.optString("refresh_token", null)
                        val expiresIn = jsonObject.getInt("expires_in")
                        
                        // 保存 token 數據
                        saveTokenData(accessToken, refreshToken, expiresIn)
                        true
                    } else {
                        Log.e("AuthManager", "Failed to exchange auth code: ${response.code}")
                        false
                    }
                }
            } catch (e: Exception) {
                Log.e("AuthManager", "Exception during token exchange", e)
                false
            }
        }
    }
    
    // 保存 token 數據到 SharedPreferences
    private fun saveTokenData(accessToken: String, refreshToken: String?, expiresIn: Int) {
        sharedPreferences.edit().apply {
            putString(DriveConstants.PREF_ACCESS_TOKEN, accessToken)
            refreshToken?.let { putString(DriveConstants.PREF_REFRESH_TOKEN, it) }
            putLong(DriveConstants.PREF_EXPIRY_TIME, System.currentTimeMillis() + expiresIn * 1000)
            apply()
        }
    }
    
    // 清除所有授權資料
    fun clearAuthData() {
        sharedPreferences.edit().clear().apply()
    }
    
    companion object {
        const val RC_SIGN_IN = 9001
    }
}
