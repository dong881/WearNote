package com.example.wearnote.auth

import android.content.Context
import android.content.pm.PackageManager
import android.os.Environment
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
        
        // Add specific instructions for error code 10
        logErrorCode10Instructions()
    }
    
    /**
     * Provides detailed instructions specific to error code 10 (DEVELOPER_ERROR)
     */
    private fun logErrorCode10Instructions() {
        Log.d(TAG, "=== Fixing DEVELOPER_ERROR (Error Code 10) ===")
        Log.d(TAG, "This error happens when there's a mismatch between OAuth 2.0 configuration and your app.")
        Log.d(TAG, "Make sure you have:")
        Log.d(TAG, "1. Added the correct package name: com.example.wearnote")
        Log.d(TAG, "2. Added the exact SHA-1 fingerprint shown above")
        Log.d(TAG, "3. Enabled the 'Google Sign In API' in Google Cloud Console")
        Log.d(TAG, "4. Configured an OAuth consent screen")
        Log.d(TAG, "5. Added the correct scopes to your OAuth consent screen")
        Log.d(TAG, "6. If using a web client ID, make sure it's generated for the same project")
        Log.d(TAG, "7. Check if you're using the correct GoogleSignInOptions in your code:")
        Log.d(TAG, "   - Ensure you're requesting the correct scopes")
        Log.d(TAG, "   - If using requestServerAuthCode(), make sure the client ID is correct")
        Log.d(TAG, "8. If using multiple OAuth clients (debug/release), use the right one")
        Log.d(TAG, "9. For debug builds, ensure your debug keystore is being used")
    }
    
    /**
     * Checks if Google Sign-In API is properly configured
     */
    fun checkGoogleSignInConfig(context: Context) {
        Log.d(TAG, "=== Google Sign-In Configuration Check ===")
        
        try {
            // Check for Google Sign-In dependencies
            val gsiClass = Class.forName("com.google.android.gms.auth.api.signin.GoogleSignIn")
            Log.d(TAG, "✓ Google Sign-In API dependency found")
            
            // Look for OAuth client ID in resources or code
            val clientId = findClientId(context)
            if (clientId != null) {
                Log.d(TAG, "Found client ID: ${clientId.take(15)}...${clientId.takeLast(15)}")
                
                // Basic validation of client ID format
                if (clientId.endsWith(".apps.googleusercontent.com")) {
                    Log.d(TAG, "✓ Client ID has correct format")
                } else {
                    Log.d(TAG, "✗ Client ID doesn't have the expected format (.apps.googleusercontent.com)")
                }
            } else {
                Log.d(TAG, "✗ Couldn't find OAuth client ID in common resource IDs")
                Log.d(TAG, "  Make sure you've set the correct client ID in your GoogleSignInOptions")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error checking Google Sign-In configuration", e)
        }
    }
    
    /**
     * Try to find the client ID from common places
     */
    private fun findClientId(context: Context): String? {
        try {
            // Check in string resources with common names
            val possibleResNames = listOf(
                "default_web_client_id", "google_client_id", "client_id", "oauth_client_id"
            )
            
            for (name in possibleResNames) {
                val resId = context.resources.getIdentifier(name, "string", context.packageName)
                if (resId != 0) {
                    return context.getString(resId)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error finding client ID in resources", e)
        }
        return null
    }
}
