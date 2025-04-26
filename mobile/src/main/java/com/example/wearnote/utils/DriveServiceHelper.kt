package com.example.wearnote.utils

import android.content.Context
import android.util.Log
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.api.client.googleapis.extensions.android.gms.auth.GoogleAccountCredential
import com.google.api.client.http.javanet.NetHttpTransport
import com.google.api.client.json.gson.GsonFactory
import com.google.api.services.drive.Drive
import com.google.api.services.drive.DriveScopes
import java.util.Collections

/**
 * Helper class for working with Google Drive API
 */
object DriveServiceHelper {
    private const val TAG = "DriveServiceHelper"
    
    /**
     * Gets a Drive service instance using the stored account credentials
     */
    fun getDriveService(context: Context): Drive? {
        try {
            val googleAccount = GoogleSignIn.getLastSignedInAccount(context) ?: return null
            
            val credential = GoogleAccountCredential.usingOAuth2(
                context,
                Collections.singleton(DriveScopes.DRIVE_FILE)
            )
            credential.selectedAccount = googleAccount.account
            
            return Drive.Builder(
                NetHttpTransport(),
                GsonFactory.getDefaultInstance(),
                credential
            )
                .setApplicationName("WearNote")
                .build()
                
        } catch (e: Exception) {
            Log.e(TAG, "Error creating Drive service", e)
            return null
        }
    }
    
    /**
     * Checks if the user is already signed in with Google
     */
    fun isSignedIn(context: Context): Boolean {
        val account = GoogleSignIn.getLastSignedInAccount(context)
        return account != null
    }
}