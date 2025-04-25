package com.example.wearnote.network

import android.accounts.AccountManager
import android.content.Context
import android.util.Log
import com.google.api.client.googleapis.extensions.android.gms.auth.GoogleAccountCredential
import com.google.api.client.http.FileContent
import com.google.api.client.http.javanet.NetHttpTransport
import com.google.api.client.json.gson.GsonFactory
import com.google.api.services.drive.Drive
import com.google.api.services.drive.DriveScopes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.Collections

class DriveApiClient(private val context: Context) {

    companion object {
        private const val TAG = "DriveApiClient"
        private const val AUDIO_MIME_TYPE = "audio/mp4"
    }

    suspend fun uploadFileToDrive(file: java.io.File): String? = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "Starting Drive upload process")
            
            // Get Google account credential
            val credential = getGoogleAccountCredential()
            
            if (credential == null || credential.selectedAccount == null) {
                Log.e(TAG, "No Google account available")
                return@withContext null
            }
            
            Log.d(TAG, "Using account: ${credential.selectedAccount?.name}")
            
            // Initialize Drive service
            val transport = NetHttpTransport()
            val jsonFactory = GsonFactory.getDefaultInstance()
            
            val service = Drive.Builder(transport, jsonFactory, credential)
                .setApplicationName("WearNote")
                .build()
            
            Log.d(TAG, "Drive service initialized")
            
            // Create file metadata
            val fileMetadata = com.google.api.services.drive.model.File().apply {
                name = file.name
                mimeType = AUDIO_MIME_TYPE
            }
            
            // Upload file content
            val mediaContent = FileContent(AUDIO_MIME_TYPE, file)
            
            Log.d(TAG, "Starting Drive API upload request")
            
            try {
                // Execute the upload
                val uploadedFile = service.files().create(fileMetadata, mediaContent)
                    .setFields("id, name")
                    .execute()
                
                Log.d(TAG, "Upload successful: ID=${uploadedFile.id}, Name=${uploadedFile.name}")
                return@withContext uploadedFile.id
                
            } catch (e: Exception) {
                Log.e(TAG, "Drive upload execution error", e)
                return@withContext null
            }
            
        } catch (e: IOException) {
            Log.e(TAG, "Drive upload error", e)
            return@withContext null
        }
    }
    
    private fun getGoogleAccountCredential(): GoogleAccountCredential? {
        try {
            val credential = GoogleAccountCredential.usingOAuth2(
                context, 
                Collections.singleton(DriveScopes.DRIVE_FILE)
            )
            
            // Try to select an account
            val am = AccountManager.get(context)
            val accounts = am.getAccountsByType("com.google")
            
            if (accounts.isNotEmpty()) {
                credential.selectedAccount = accounts[0]
                return credential
            } else {
                Log.e(TAG, "No Google accounts found on device")
                return null
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Error getting Google credential", e)
            return null
        }
    }
}
