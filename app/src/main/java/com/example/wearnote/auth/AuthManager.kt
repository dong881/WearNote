package com.example.wearnote.auth

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.example.wearnote.drive.DriveConstants
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.Scope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

class AuthManager(private val context: Context) {
    companion object {
        const val RC_SIGN_IN = 9001
        private const val TAG = "AuthManager"
    }
    
    private val sharedPreferences: SharedPreferences = context.getSharedPreferences(
        DriveConstants.PREF_NAME, Context.MODE_PRIVATE
    )
    private val httpClient = OkHttpClient()
    
    // Create and configure GoogleSignInClient
    fun getGoogleSignInClient(): GoogleSignInClient {
        val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestEmail()
            .requestScopes(Scope(DriveConstants.DRIVE_SCOPE))
            .requestServerAuthCode(DriveConstants.getClientId(context), true)
            .requestIdToken(DriveConstants.getClientId(context))
            .build()
            
        return GoogleSignIn.getClient(context, gso)
    }
    
    // Exchange auth code for tokens
    suspend fun handleAuthorizationResponse(authCode: String): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                // Build token request
                val requestBody = FormBody.Builder()
                    .add("code", authCode)
                    .add("client_id", DriveConstants.getClientId(context))
                    .add("grant_type", "authorization_code")
                    .add("redirect_uri", DriveConstants.REDIRECT_URI)
                    .build()
                
                val request = Request.Builder()
                    .url(DriveConstants.TOKEN_ENDPOINT)
                    .post(requestBody)
                    .build()
                
                // Execute request
                val response = httpClient.newCall(request).execute()
                val responseBody = response.body?.string()
                
                if (response.isSuccessful && responseBody != null) {
                    // Parse response
                    val jsonObject = JSONObject(responseBody)
                    val accessToken = jsonObject.getString("access_token")
                    val refreshToken = jsonObject.optString("refresh_token")
                    val expiresIn = jsonObject.getLong("expires_in")
                    
                    // Calculate expiry time
                    val expiryTime = System.currentTimeMillis() + (expiresIn * 1000)
                    
                    // Save tokens
                    sharedPreferences.edit()
                        .putString(DriveConstants.PREF_ACCESS_TOKEN, accessToken)
                        .putString(DriveConstants.PREF_REFRESH_TOKEN, refreshToken)
                        .putLong(DriveConstants.PREF_EXPIRY_TIME, expiryTime)
                        .apply()
                    
                    true
                } else {
                    Log.e(TAG, "Token exchange failed: ${response.code} - ${responseBody}")
                    false
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error exchanging auth code for tokens", e)
                false
            }
        }
    }
    
    // Check if user is authorized
    suspend fun isAuthorized(): Boolean {
        // First check if we have a valid access token
        val expiryTime = sharedPreferences.getLong(DriveConstants.PREF_EXPIRY_TIME, 0)
        val accessToken = sharedPreferences.getString(DriveConstants.PREF_ACCESS_TOKEN, null)
        
        // If token exists and not expired
        if (accessToken != null && System.currentTimeMillis() < expiryTime) {
            return true
        }
        
        // Try to refresh token
        val refreshToken = sharedPreferences.getString(DriveConstants.PREF_REFRESH_TOKEN, null)
        return if (refreshToken != null) {
            refreshAccessToken(refreshToken)
        } else {
            false
        }
    }
    
    // Refresh access token using refresh token
    private suspend fun refreshAccessToken(refreshToken: String): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                val requestBody = FormBody.Builder()
                    .add("client_id", DriveConstants.getClientId(context))
                    .add("refresh_token", refreshToken)
                    .add("grant_type", "refresh_token")
                    .build()
                
                val request = Request.Builder()
                    .url(DriveConstants.TOKEN_ENDPOINT)
                    .post(requestBody)
                    .build()
                
                val response = httpClient.newCall(request).execute()
                val responseBody = response.body?.string()
                
                if (response.isSuccessful && responseBody != null) {
                    val jsonObject = JSONObject(responseBody)
                    val newAccessToken = jsonObject.getString("access_token")
                    val expiresIn = jsonObject.getLong("expires_in")
                    val expiryTime = System.currentTimeMillis() + (expiresIn * 1000)
                    
                    sharedPreferences.edit()
                        .putString(DriveConstants.PREF_ACCESS_TOKEN, newAccessToken)
                        .putLong(DriveConstants.PREF_EXPIRY_TIME, expiryTime)
                        .apply()
                    
                    true
                } else {
                    Log.e(TAG, "Token refresh failed: ${response.code} - ${responseBody}")
                    false
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error refreshing token", e)
                false
            }
        }
    }
    
    // Get current access token
    fun getAccessToken(): String? {
        return sharedPreferences.getString(DriveConstants.PREF_ACCESS_TOKEN, null)
    }
    
    // Sign out
    fun signOut() {
        sharedPreferences.edit()
            .remove(DriveConstants.PREF_ACCESS_TOKEN)
            .remove(DriveConstants.PREF_REFRESH_TOKEN)
            .remove(DriveConstants.PREF_EXPIRY_TIME)
            .apply()
        
        // Also sign out from Google
        getGoogleSignInClient().signOut()
    }
}
