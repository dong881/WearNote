package com.example.wearnote.network

import android.content.Context
import android.util.Log
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.api.client.http.javanet.NetHttpTransport
import com.google.api.client.googleapis.extensions.android.gms.auth.GoogleAccountCredential
import com.google.api.client.http.FileContent
import com.google.api.client.json.gson.GsonFactory
import com.google.api.services.drive.Drive
import com.google.api.services.drive.DriveScopes
import com.google.api.services.drive.model.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.Collections

class DriveApiClient(private val context: Context) {

    companion object {
        private const val TAG = "DriveApiClient"
        private const val AUDIO_MIME_TYPE = "audio/mp4"
        // Define jsonFactory in companion object so it's accessible from anywhere in the class
        private val jsonFactory = GsonFactory.getDefaultInstance()
    }

    suspend fun uploadFileToDrive(file: java.io.File): String? = withContext(Dispatchers.IO) {
        try {
            // Initialize the Drive service with modern authentication
            val transport = NetHttpTransport()
            
            // Use GoogleAccountCredential
            val credential = GoogleAccountCredential.usingOAuth2(
                context, Collections.singleton(DriveScopes.DRIVE_FILE)
            )
            
            // In a real app, you would set the selected account
            // credential.selectedAccount = account.account
            
            // Build the Drive service directly with GoogleAccountCredential
            // without casting it to another type
            val service = Drive.Builder(transport, jsonFactory, credential)
                .setApplicationName("WearNote")
                .build()
            
            // Create file metadata
            val fileMetadata = File().apply {
                name = file.name
                mimeType = AUDIO_MIME_TYPE
            }
            
            // Upload file content
            val mediaContent = FileContent(AUDIO_MIME_TYPE, file)
            
            // Execute the upload and get the file ID
            val uploadedFile = service.files().create(fileMetadata, mediaContent)
                .setFields("id")
                .execute()
            
            return@withContext uploadedFile.id
            
        } catch (e: IOException) {
            Log.e(TAG, "Error uploading file to Drive", e)
            return@withContext null
        }
    }
    
    // In a production app, you would implement proper authentication
    private fun getCredentials(account: GoogleSignInAccount): GoogleAccountCredential {
        val credential = GoogleAccountCredential.usingOAuth2(
            context, Collections.singleton(DriveScopes.DRIVE_FILE)
        )
        credential.selectedAccount = account.account
        return credential
    }
}
