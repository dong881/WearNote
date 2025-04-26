package com.example.wearnote.util

import android.content.Context
import android.content.pm.PackageManager
import android.util.Base64
import android.util.Log
import java.io.File
import java.security.MessageDigest

/**
 * Helper class for keystore diagnostics and related operations
 */
object KeystoreHelper {
    private const val TAG = "KeystoreHelper"
    
    /**
     * Prints detailed keystore debug information to help diagnose
     * OAuth authentication issues related to SHA-1 fingerprint mismatches
     */
    fun printKeystoreDebugInfo(context: Context) {
        try {
            Log.d(TAG, "=== App Signature Information ===")
            Log.d(TAG, "Package: ${context.packageName}")
            
            // Get signature of the app
            val info = context.packageManager.getPackageInfo(
                context.packageName,
                PackageManager.GET_SIGNATURES
            )
            
            for (signature in info.signatures) {
                val md = MessageDigest.getInstance("SHA-1")
                md.update(signature.toByteArray())
                val sha1Base64 = Base64.encodeToString(md.digest(), Base64.DEFAULT).trim()
                val sha1Hex = bytesToHexString(md.digest())
                
                Log.d(TAG, "Signature SHA-1 (Base64): $sha1Base64")
                Log.d(TAG, "Signature SHA-1 (Hex): $sha1Hex")
            }
            
            Log.d(TAG, "=== Common Keystore Locations ===")
            logFileExistence("~/.android/debug.keystore", 
                System.getProperty("user.home") + File.separator + ".android" + File.separator + "debug.keystore")
            
            val projectDir = File(context.applicationInfo.sourceDir).parentFile?.parentFile?.parentFile?.parentFile
            if (projectDir != null) {
                logFileExistence("Project keystore", 
                    projectDir.absolutePath + File.separator + "app" + File.separator + "debug.keystore")
                logFileExistence("Project keystore", 
                    projectDir.absolutePath + File.separator + "keystore.jks")
            }
            
            Log.d(TAG, "=== Android Studio Fingerprint Command ===")
            Log.d(TAG, "To generate the correct SHA-1, run this command in your terminal:")
            Log.d(TAG, "keytool -list -v -keystore ~/.android/debug.keystore -alias androiddebugkey -storepass android -keypass android")
        } catch (e: Exception) {
            Log.e(TAG, "Error generating keystore diagnostics", e)
        }
    }
    
    /**
     * Log whether a keystore file exists or not
     */
    private fun logFileExistence(name: String, path: String) {
        val file = File(path)
        if (file.exists()) {
            Log.d(TAG, "$name exists at: $path")
        } else {
            Log.d(TAG, "$name does not exist at: $path")
        }
    }
    
    /**
     * Convert byte array to hex string with colons
     */
    private fun bytesToHexString(bytes: ByteArray): String {
        val stringBuilder = StringBuilder()
        for (i in bytes.indices) {
            stringBuilder.append(String.format("%02X", bytes[i]))
            if (i < bytes.size - 1) {
                stringBuilder.append(":")
            }
        }
        return stringBuilder.toString()
    }
    
    /**
     * Provides instructions on how to fix SHA-1 fingerprint issues
     */
    fun logShaFixInstructions() {
        Log.d(TAG, "=== How to fix SHA-1 fingerprint issues ===")
        Log.d(TAG, "1. Go to Google Cloud Console: https://console.cloud.google.com/")
        Log.d(TAG, "2. Select your project")
        Log.d(TAG, "3. Go to 'APIs & Services' > 'Credentials'")
        Log.d(TAG, "4. Find your Android client ID and click 'Edit'")
        Log.d(TAG, "5. Add the SHA-1 fingerprint shown above")
        Log.d(TAG, "6. Click 'Save'")
        Log.d(TAG, "7. Wait a few minutes for changes to propagate")
    }
}
