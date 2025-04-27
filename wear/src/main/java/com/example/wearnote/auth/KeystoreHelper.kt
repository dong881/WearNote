package com.example.wearnote.auth

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
                val digestBytes = md.digest()
                val sha1Base64 = Base64.encodeToString(digestBytes, Base64.DEFAULT).trim()
                val sha1Hex = bytesToHexString(digestBytes)
                
                Log.d(TAG, "Signature SHA-1 (Base64): $sha1Base64")
                Log.d(TAG, "Signature SHA-1 (Hex): $sha1Hex")
            }
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
     * Checks if Google Sign-In API is properly configured
     */
    fun checkGoogleSignInConfig(context: Context): Boolean {
        try {
            // Check for Google Sign-In dependencies
            val gsiClass = Class.forName("com.google.android.gms.auth.api.signin.GoogleSignIn")
            
            // Look for OAuth client ID in resources or code
            val clientId = findClientId(context)
            if (clientId != null) {
                Log.d(TAG, "Google Sign-In client ID found: ${clientId.take(5)}...")
                return true
            } else {
                Log.w(TAG, "Google Sign-In client ID not found in resources")
                return false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error checking Google Sign-In configuration", e)
            return false
        }
    }
    
    /**
     * Try to find the client ID from common places
     */
    private fun findClientId(context: Context): String? {
        try {
            val resId = context.resources.getIdentifier("android_client_id", "string", context.packageName)
            if (resId != 0) {
                return context.getString(resId)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error finding client ID in resources", e)
        }
        return null
    }
    
    /**
     * Empty method kept for compatibility
     */
    fun logShaFixInstructions() {
        // Logging removed
    }
}