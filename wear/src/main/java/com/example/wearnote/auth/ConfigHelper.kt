package com.example.wearnote.auth

import android.content.Context
import android.content.pm.PackageManager
import android.util.Base64
import android.util.Log
import java.security.MessageDigest

/**
 * 配置輔助類，用於驗證與診斷 Google Sign-In 相關設定
 */
object ConfigHelper {
    private const val TAG = "ConfigHelper"

    /**
     * 檢查開發者配置
     * 輸出關鍵資訊以幫助診斷 ApiException: 10 錯誤
     */
    fun checkDeveloperConfiguration(context: Context): Boolean {
        var isConfigValid = true
        
        // 檢查 Google Play Services 是否可用
        try {
            val googleApiClass = Class.forName("com.google.android.gms.common.GoogleApiAvailability")
            val instanceMethod = googleApiClass.getMethod("getInstance")
            val availabilityInstance = instanceMethod.invoke(null)
            val availabilityMethod = googleApiClass.getMethod("isGooglePlayServicesAvailable", Context::class.java)
            val resultCode = availabilityMethod.invoke(availabilityInstance, context) as Int
            
            Log.i(TAG, "Google Play Services 狀態碼: $resultCode")
            if (resultCode != 0) { // ConnectionResult.SUCCESS = 0
                Log.e(TAG, "Google Play Services 不可用: $resultCode")
                isConfigValid = false
            } else {
                Log.i(TAG, "Google Play Services 可用")
            }
        } catch (e: Exception) {
            Log.e(TAG, "檢查 Google Play Services 時出錯", e)
            isConfigValid = false
        }
        
        // 輸出應用包名
        Log.d(TAG, "應用包名: ${context.packageName}")
        
        // 檢查 CLIENT_ID
        checkClientId(context)
        
        // 檢查 SHA-1 證書指紋 (用於診斷 ApiException: 10)
        try {
            val info = context.packageManager.getPackageInfo(
                context.packageName,
                PackageManager.GET_SIGNATURES
            )
            for (signature in info.signatures) {
                val md = MessageDigest.getInstance("SHA-1")
                md.update(signature.toByteArray())
                val sha1 = Base64.encodeToString(md.digest(), Base64.DEFAULT)
                Log.d(TAG, "應用 SHA-1 證書指紋: $sha1")
                
                val hexString = bytesToHexString(md.digest())
                Log.d(TAG, "應用 SHA-1 證書指紋 (Hex): $hexString")
            }
        } catch (e: Exception) {
            Log.e(TAG, "無法獲取簽名信息", e)
            isConfigValid = false
        }
        
        // 檢查網絡權限
        checkNetworkPermissions(context)
        
        // 檢查 Google Sign-In 配置
        KeystoreHelper.checkGoogleSignInConfig(context)
        
        return isConfigValid
    }
    
    /**
     * 檢查網絡權限
     */
    private fun checkNetworkPermissions(context: Context) {
        val pm = context.packageManager
        val internetPermission = pm.checkPermission("android.permission.INTERNET", context.packageName)
        val networkStatePermission = pm.checkPermission("android.permission.ACCESS_NETWORK_STATE", context.packageName)
        
        if (internetPermission != PackageManager.PERMISSION_GRANTED) {
            Log.e(TAG, "缺少 INTERNET 權限")
        } else {
            Log.i(TAG, "已授權 INTERNET 權限")
        }
        
        if (networkStatePermission != PackageManager.PERMISSION_GRANTED) {
            Log.e(TAG, "缺少 ACCESS_NETWORK_STATE 權限")
        } else {
            Log.i(TAG, "已授權 ACCESS_NETWORK_STATE 權限")
        }
    }
    
    /**
     * 將位元組陣列轉換為十六進制字串
     */
    private fun bytesToHexString(bytes: ByteArray): String {
        val stringBuilder = StringBuilder()
        for (b in bytes) {
            stringBuilder.append(String.format("%02X:", b))
        }
        if (stringBuilder.isNotEmpty()) {
            stringBuilder.setLength(stringBuilder.length - 1) // 移除最後一個冒號
        }
        return stringBuilder.toString()
    }
    
    /**
     * 檢查 CLIENT_ID 配置
     */
    private fun checkClientId(context: Context) {
        try {
            // Check if the client ID is in resources
            val possibleResNames = listOf(
                "default_web_client_id", "google_client_id", "client_id", "oauth_client_id"
            )
            
            for (name in possibleResNames) {
                val resId = context.resources.getIdentifier(name, "string", context.packageName)
                if (resId != 0) {
                    val clientId = context.getString(resId)
                    Log.d(TAG, "找到 CLIENT_ID: ${clientId.take(15)}...${clientId.takeLast(15)}")
                    return
                }
            }
            
            // If we reach here, we didn't find a client ID
            Log.w(TAG, "在資源中找不到 CLIENT_ID，請確認您已經正確配置 GoogleSignInOptions")
            
        } catch (e: Exception) {
            Log.e(TAG, "檢查 CLIENT_ID 時出錯", e)
        }
    }
}
