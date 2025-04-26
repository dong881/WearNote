package com.example.wearnote.network

import android.accounts.Account
import android.accounts.AccountManager
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.Scope
import com.google.api.client.googleapis.extensions.android.gms.auth.GoogleAccountCredential
import com.google.api.client.http.FileContent
import com.google.api.client.http.javanet.NetHttpTransport
import com.google.api.client.json.gson.GsonFactory
import com.google.api.services.drive.Drive
import com.google.api.services.drive.DriveScopes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.Collections

class DriveApiClient(private val context: Context) {

    companion object {
        private const val TAG = "DriveApiClient"
        private const val AUDIO_MIME_TYPE = "audio/mp4"
        
        // Store the current pending upload file for when auth is complete
        private var pendingUploadFile: File? = null
        private var authRequested = false
    }

    suspend fun uploadFileToDrive(file: java.io.File): String? = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "Starting actual Drive upload process for file: ${file.absolutePath}")
            
            // Create a local backup copy in a more accessible location
            val backupFile = createBackupFile(file)
            Log.d(TAG, "Created backup file at: ${backupFile?.absolutePath}")
            
            // Verify file exists and has content
            if (!file.exists() || file.length() == 0L) {
                Log.e(TAG, "File doesn't exist or is empty: ${file.absolutePath}")
                return@withContext null
            }
            
            // Store as the current pending upload in case we need to request auth
            pendingUploadFile = file
            
            // Check if we have Google account permission already
            val account = GoogleSignIn.getLastSignedInAccount(context)
            
            if (account == null) {
                Log.d(TAG, "No Google account signed in - requesting auth")
                // We need to request sign-in from the main activity
                authRequested = true
                
                // For now, we can't continue - main activity will need to handle this
                Log.e(TAG, "Authentication needed - please grant Drive access")
                return@withContext null
            }
            
            Log.d(TAG, "Using Google account: ${account.email}")
            
            // Use the account to create a credential
            val credential = GoogleAccountCredential.usingOAuth2(
                context,
                Collections.singleton(DriveScopes.DRIVE_FILE)
            )
            credential.selectedAccount = account.account
            
            // Initialize Drive service
            val transport = NetHttpTransport()
            val jsonFactory = GsonFactory.getDefaultInstance()
            
            val service = Drive.Builder(transport, jsonFactory, credential)
                .setApplicationName("WearNote")
                .build()
            
            Log.d(TAG, "Drive service initialized successfully")
            
            // Create file metadata
            val fileMetadata = com.google.api.services.drive.model.File().apply {
                name = file.name
                mimeType = AUDIO_MIME_TYPE
            }
            
            // Upload file content
            val mediaContent = FileContent(AUDIO_MIME_TYPE, file)
            
            Log.d(TAG, "Starting Drive API upload request for file: ${file.name} (${file.length()} bytes)")
            
            // Execute the upload
            val uploadedFile = service.files().create(fileMetadata, mediaContent)
                .setFields("id, name, size, mimeType")
                .execute()
            
            Log.d(TAG, "Upload successful! ID=${uploadedFile.id}, Name=${uploadedFile.name}")
            return@withContext uploadedFile.id
            
        } catch (e: Exception) {
            Log.e(TAG, "Drive upload error: ${e.message}", e)
            return@withContext null
        }
    }
    
    // For the Activity to check if auth is needed
    fun isAuthenticationRequested(): Boolean {
        return authRequested
    }
    
    // Reset auth request flag
    fun resetAuthRequest() {
        authRequested = false
    }
    
    // For the Activity to check if there's a pending upload
    fun getPendingUploadFile(): File? {
        return pendingUploadFile
    }
    
    // Create a backup copy of the file in an accessible location
    private fun createBackupFile(sourceFile: File): File? {
        try {
            // Create a backup in a more accessible directory
            val downloadsDir = context.getExternalFilesDir(null)
            val backupDir = File(downloadsDir, "WearNoteBackups").apply { 
                if (!exists()) mkdirs() 
            }
            
            // Create the backup file
            val backupFile = File(backupDir, sourceFile.name)
            
            // Copy the file
            sourceFile.inputStream().use { input ->
                FileOutputStream(backupFile).use { output ->
                    input.copyTo(output)
                }
            }
            
            return backupFile
        } catch (e: Exception) {
            Log.e(TAG, "Error creating backup file", e)
            return null
        }
    }
}
