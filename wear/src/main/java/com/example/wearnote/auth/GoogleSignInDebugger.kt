package com.example.wearnote.auth

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import com.example.wearnote.R
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException

/**
 * 用於診斷和解決 Google Sign-In 錯誤的工具類
 */
object GoogleSignInDebugger {
    private const val TAG = "GoogleSignInDebugger"
    
    /**
     * 診斷 ApiException 錯誤
     */
    fun diagnoseApiException(e: ApiException, context: Context) {
        Log.e(TAG, "Google Sign-In 失敗: 錯誤碼 ${e.statusCode}")
        Log.e(TAG, "錯誤訊息: ${e.message}")
        
        when (e.statusCode) {
            10 -> diagnoseDevError(context)  // DEVELOPER_ERROR
            12500 -> Log.e(TAG, "Play Services 版本太舊，需要更新")
            12501 -> Log.e(TAG, "使用者取消登入")
            7 -> Log.e(TAG, "網路連線錯誤")
            else -> Log.e(TAG, "未知的錯誤碼: ${e.statusCode}")
        }
    }
    
    /**
     * 專門診斷開發者錯誤 (錯誤碼 10)
     */
    private fun diagnoseDevError(context: Context) {
        Log.e(TAG, "開發者錯誤 (錯誤碼 10) - 這通常是由於 SHA-1 指紋或 Client ID 配置錯誤")
        
        // 打印當前使用的 Client ID
        try {
            val clientId = context.getString(R.string.android_client_id)
            Log.d(TAG, "APP 使用的 CLIENT_ID: $clientId")
        } catch (e: Exception) {
            Log.e(TAG, "無法取得 android_client_id", e)
        }
        
        // 顯示修復步驟
        Log.d(TAG, "請執行以下步驟:")
        Log.d(TAG, "1. 確保 Google Cloud Console 上已添加您的 APP 的 SHA-1 指紋")
        Log.d(TAG, "2. 確保套件名稱正確: ${context.packageName}")
        Log.d(TAG, "3. 確保在 Google Cloud Console 中已啟用 Google Sign-In API")
        Log.d(TAG, "4. 如您在 'OAuth 同意畫面' 中還在測試模式，請確保您的測試帳號已被添加")
        Log.d(TAG, "5. 變更後可能需要 5-10 分鐘生效")
        
        // 檢查當前登入狀態
        val account = GoogleSignIn.getLastSignedInAccount(context)
        if (account != null) {
            Log.d(TAG, "目前有已登入的帳號: ${account.email}")
        } else {
            Log.d(TAG, "目前無登入帳號")
        }
    }
    
    /**
     * 嘗試備用登入方法
     * 當 Google Sign-In API 失敗時可以試試
     */
    fun attemptAlternativeSignIn(context: Context): Boolean {
        try {
            val clientId = context.getString(R.string.android_client_id)
            
            // 建立 OAuth 網址 (這是標準 OAuth2 流程，不依賴 Google Play Services)
            val authUrl = Uri.parse("https://accounts.google.com/o/oauth2/auth").buildUpon()
                .appendQueryParameter("client_id", clientId)
                .appendQueryParameter("redirect_uri", "com.example.wearnote:/oauth2callback")
                .appendQueryParameter("response_type", "code")
                .appendQueryParameter("scope", "https://www.googleapis.com/auth/drive.file email profile")
                .build()
                
            // 使用瀏覽器開啟授權頁面
            val intent = Intent(Intent.ACTION_VIEW, authUrl)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            
            return true
        } catch (e: Exception) {
            Log.e(TAG, "嘗試備用登入方法失敗", e)
            return false
        }
    }

    /**
     * 重置登入狀態
     * 在遇到問題時可以先清除狀態再重試
     */
    fun resetSignIn(context: Context) {
        try {
            // 使用基本設定建立客戶端
            val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN).build()
            val client = GoogleSignIn.getClient(context, gso)
            
            // 登出
            client.signOut()
            
            // 清除緩存
            client.revokeAccess()
            
            Log.d(TAG, "已重置登入狀態")
        } catch (e: Exception) {
            Log.e(TAG, "重置登入狀態時出錯", e)
        }
    }
}
