package com.example.wearnote.auth

import android.content.Context
import android.content.pm.PackageManager
import android.util.Base64
import android.util.Log
import com.example.wearnote.drive.DriveConstants
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
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
        val googleApi = GoogleApiAvailability.getInstance()
        val resultCode = googleApi.isGooglePlayServicesAvailable(context)
        if (resultCode != ConnectionResult.SUCCESS) {
            Log.e(TAG, "Google Play Services 不可用: $resultCode")
            isConfigValid = false
        } else {
            Log.i(TAG, "Google Play Services 可用")
        }
        
        // 輸出應用包名
        Log.d(TAG, "應用包名: ${context.packageName}")
        
        // 輸出當前 CLIENT_ID
        Log.d(TAG, "當前 CLIENT_ID: ${DriveConstants.CLIENT_ID}")
        
        // 檢查 SHA-1 證書指紋 (用於診斷 ApiException: 10)
        try {
            val info = context.packageManager.getPackageInfo(
                context.packageName,
                PackageManager.GET_SIGNATURES
            )
            for (signature in info.signatures) {
                val md = MessageDigest.getInstance("SHA-1")
                md.update(signature.toByteArray())
                val digestBytes = md.digest()
                val sha1 = Base64.encodeToString(digestBytes, Base64.DEFAULT)
                Log.d(TAG, "應用 SHA-1 證書指紋: $sha1")
                
                val hexString = bytesToHexString(digestBytes)
                Log.d(TAG, "應用 SHA-1 證書指紋 (Hex): $hexString")
            }
        } catch (e: Exception) {
            Log.e(TAG, "無法獲取簽名信息", e)
            isConfigValid = false
        }
        
        // 檢查網絡權限
        checkNetworkPermissions(context)
        
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
}
