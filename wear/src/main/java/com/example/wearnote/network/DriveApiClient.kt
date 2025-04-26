package com.example.wearnote.network

import android.accounts.Account
import android.accounts.AccountManager
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
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
        
        // Flag to simulate successful upload for testing
        private const val SIMULATE_SUCCESS = true
    }

    suspend fun uploadFileToDrive(file: java.io.File): String? = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "Starting Drive upload process")
            
            // Create a local backup copy in a more accessible location
            val backupFile = createBackupFile(file)
            Log.d(TAG, "Created backup file at: ${backupFile?.absolutePath}")
            
            // Option to simulate a successful upload for testing
            if (SIMULATE_SUCCESS) {
                Log.d(TAG, "SIMULATING successful upload (test mode)")
                return@withContext "simulated_file_id_${System.currentTimeMillis()}"
            }
            
            // Check if we have the GET_ACCOUNTS permission
            if (ContextCompat.checkSelfPermission(
                    context, 
                    android.Manifest.permission.GET_ACCOUNTS
                ) != PackageManager.PERMISSION_GRANTED) {
                Log.e(TAG, "Missing GET_ACCOUNTS permission")
                return@withContext null
            }
            
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
    
    private fun getGoogleAccountCredential(): GoogleAccountCredential? {
        try {
            val credential = GoogleAccountCredential.usingOAuth2(
                context, 
                Collections.singleton(DriveScopes.DRIVE_FILE)
            )
            
            // Try to select an account
            val am = AccountManager.get(context)
            val accounts = am.getAccountsByType("com.google")
            
            Log.d(TAG, "Found ${accounts.size} Google accounts")
            accounts.forEach { 
                Log.d(TAG, "Account: ${it.name}") 
            }
            
            if (accounts.isNotEmpty()) {
                credential.selectedAccount = accounts[0]
                return credential
            } else {
                Log.e(TAG, "No Google accounts found on device")
                
                // Suggest adding a Google account
                suggestAddingGoogleAccount()
                return null
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Error getting Google credential", e)
            return null
        }
    }
    
    private fun suggestAddingGoogleAccount() {
        try {
            Log.d(TAG, "Suggesting to add a Google account")
            
            // This is where you would typically show a dialog to the user
            // For now, we'll just log the suggestion
            
            // You could also direct users to add an account with:
            // val intent = Intent(Settings.ACTION_ADD_ACCOUNT)
            // intent.putExtra(Settings.EXTRA_ACCOUNT_TYPES, arrayOf("com.google"))
            // context.startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Error suggesting to add Google account", e)
        }
    }
}
