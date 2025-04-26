package com.example.wearnote.network

import android.accounts.AccountManager
import android.content.Context
import android.util.Log
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
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
import java.util.Collections

class DriveApiClient(private val context: Context) {

    companion object {
        private const val TAG = "DriveApiClient"
        private const val AUDIO_MIME_TYPE = "audio/mp4"
        
        // Define WEB_CLIENT_ID - this is critical for OAuth flow on WearOS
        private const val WEB_CLIENT_ID = "your-web-client-id.apps.googleusercontent.com"
    }

    // Store pending upload file
    private var pendingUploadFile: File? = null

    // Add a proper authentication check method
    fun checkGoogleSignInAccount(context: Context): GoogleSignInAccount? {
        try {
            // Get all available Google accounts first
            val am = AccountManager.get(context)
            val accounts = am.getAccountsByType("com.google")
            
            Log.d(TAG, "Found ${accounts.size} Google accounts")
            accounts.forEach { account ->
                Log.d(TAG, "Available Google account: ${account.name}")
            }
            
            // Try to get last signed in account
            val signInAccount = GoogleSignIn.getLastSignedInAccount(context)
            
            if (signInAccount != null) {
                Log.d(TAG, "Found signed in account: ${signInAccount.email}")
                
                // Check if we have the Drive scope
                val hasDriveScope = GoogleSignIn.hasPermissions(
                    signInAccount,
                    Scope(DriveScopes.DRIVE_FILE)
                )
                
                Log.d(TAG, "Account has Drive scope: $hasDriveScope")
                return if (hasDriveScope) signInAccount else null
            } else {
                Log.d(TAG, "No signed in Google account found")
                
                // If we have accounts but no signed in account, recommend signing in
                if (accounts.isNotEmpty()) {
                    Log.d(TAG, "User should sign in with one of the available accounts")
                } else {
                    Log.d(TAG, "No Google accounts on device")
                }
                
                return null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error checking Google sign-in: ${e.message}", e)
            return null
        }
    }

    // Update the needsDrivePermission method
    fun needsDrivePermission(): Boolean {
        val account = checkGoogleSignInAccount(context)
        return account == null
    }

    // Enhance the uploadFileToDrive method with better logging
    suspend fun uploadFileToDrive(file: File): String? = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "Starting Drive upload for file: ${file.absolutePath}")
            
            // Create a backup copy
            val backupFile = createBackupFile(file)
            Log.d(TAG, "Created backup file at: ${backupFile?.absolutePath}")
            
            // Store as pending upload
            pendingUploadFile = file
            
            // Check Google sign-in status
            val account = GoogleSignIn.getLastSignedInAccount(context)
            if (account == null) {
                Log.d(TAG, "No Google account - can't upload to Drive")
                return@withContext null
            }
            
            // Check if we have Drive scope permission
            val hasDriveScope = GoogleSignIn.hasPermissions(account, Scope(DriveScopes.DRIVE_FILE))
            if (!hasDriveScope) {
                Log.d(TAG, "Missing Drive permission for account: ${account.email}")
                return@withContext null
            }
            
            Log.d(TAG, "Uploading with account: ${account.email}")
            
            // Create credential from the signed in account
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
            
            // Create file metadata
            val fileMetadata = com.google.api.services.drive.model.File().apply {
                name = file.name
                mimeType = AUDIO_MIME_TYPE
            }
            
            // Upload content
            val mediaContent = FileContent(AUDIO_MIME_TYPE, file)
            
            Log.d(TAG, "Executing Drive API upload")
            
            // Execute upload
            val uploadedFile = service.files().create(fileMetadata, mediaContent)
                .setFields("id, name")
                .execute()
            
            Log.d(TAG, "Drive upload successful! ID=${uploadedFile.id}")
            return@withContext uploadedFile.id
            
        } catch (e: Exception) {
            Log.e(TAG, "Drive upload error: ${e.message}", e)
            return@withContext null
        }
    }
    
    fun getPendingUploadFile(): File? {
        return pendingUploadFile
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
}
