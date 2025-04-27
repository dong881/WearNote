package com.example.wearnote.service

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.util.Log
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.api.client.googleapis.extensions.android.gms.auth.GoogleAccountCredential
import com.google.api.client.http.FileContent
import com.google.api.client.http.javanet.NetHttpTransport
import com.google.api.client.json.gson.GsonFactory
import com.google.api.services.drive.Drive
import com.google.api.services.drive.DriveScopes
import com.google.api.services.drive.model.File as DriveFile
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object GoogleDriveUploader {
    private const val TAG = "GoogleDriveUploader"
    private const val PENDING_DIR = "pending"
    private const val APP_NAME = "WearNote"
    
    // Upload file to Google Drive
    suspend fun upload(context: Context, file: File): String? = withContext(Dispatchers.IO) {
        // Check network connectivity
        if (!isNetworkAvailable(context)) {
            Log.w(TAG, "No network available, enqueueing for later upload: ${file.name}")
            enqueuePending(context, file)
            return@withContext null
        }

        // Get signed-in Google account
        val account = GoogleSignIn.getLastSignedInAccount(context)
        if (account == null) {
            Log.e(TAG, "Upload failed: Not signed in to Google Account.")
            enqueuePending(context, file)
            return@withContext null
        }

        // Perform Google Drive upload
        try {
            Log.d(TAG, "Attempting to upload file: ${file.name} for account: ${account.email}")
            
            // Create Drive service
            val credential = GoogleAccountCredential.usingOAuth2(
                context, setOf(DriveScopes.DRIVE_FILE)
            ).setSelectedAccount(account.account)

            val driveService = Drive.Builder(
                NetHttpTransport(),
                GsonFactory.getDefaultInstance(),
                credential
            )
                .setApplicationName(APP_NAME)
                .build()

            // Prepare file metadata and content
            val fileMetadata = DriveFile().apply {
                name = file.name
            }
            val mediaContent = FileContent("audio/3gpp", file)

            // Execute upload
            val uploadedFile = driveService.files().create(fileMetadata, mediaContent)
                .setFields("id")
                .execute()

            val fileId = uploadedFile.id
            Log.i(TAG, "Upload successful! File ID: $fileId for ${file.name}")

            // Delete the original file after successful upload
            try {
                if (file.exists()) {
                    file.delete()
                    Log.d(TAG, "Deleted original file after successful upload: ${file.name}")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to delete original file after upload: ${file.name}", e)
            }

            return@withContext fileId

        } catch (e: Exception) {
            Log.e(TAG, "Google Drive upload failed for ${file.name}", e)
            enqueuePending(context, file)
            return@withContext null
        }
    }

    // Check if network is available
    fun isNetworkAvailable(context: Context): Boolean {
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val network = connectivityManager.activeNetwork
            val capabilities = connectivityManager.getNetworkCapabilities(network)
            capabilities != null && capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) && capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        } else {
            @Suppress("DEPRECATION")
            val networkInfo = connectivityManager.activeNetworkInfo
            @Suppress("DEPRECATION")
            networkInfo != null && networkInfo.isConnected
        }
    }

    // Add file to pending queue
    private fun enqueuePending(context: Context, file: File) {
        if (!file.exists()) {
            Log.w(TAG, "Cannot enqueue non-existent file: ${file.absolutePath}")
            return
        }
        try {
            val queueDir = File(context.filesDir, PENDING_DIR)
            if (!queueDir.exists()) {
                queueDir.mkdirs()
            }
            
            val destinationFile = File(queueDir, file.name)
            if (!destinationFile.exists() || file.lastModified() > destinationFile.lastModified()) {
                file.copyTo(destinationFile, overwrite = true)
                Log.i(TAG, "Enqueued file for later upload: ${destinationFile.absolutePath}")
            } else {
                Log.d(TAG, "File already enqueued: ${destinationFile.absolutePath}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to enqueue file ${file.name}", e)
        }
    }

    // Process all pending uploads
    suspend fun processPending(context: Context) = withContext(Dispatchers.IO) {
        if (!isNetworkAvailable(context)) {
            Log.d(TAG, "Network unavailable, cannot process pending uploads.")
            return@withContext
        }

        val account = GoogleSignIn.getLastSignedInAccount(context)
        if (account == null) {
            Log.w(TAG, "Cannot process pending uploads: Not signed in.")
            return@withContext
        }

        val queueDir = File(context.filesDir, PENDING_DIR)
        if (!queueDir.exists() || !queueDir.isDirectory) return@withContext

        val pendingFiles = queueDir.listFiles()
        if (pendingFiles.isNullOrEmpty()) {
            Log.d(TAG, "No pending files to upload.")
            return@withContext
        }

        Log.i(TAG, "Processing ${pendingFiles.size} pending file(s)...")

        pendingFiles.forEach { pendingFile ->
            if (!pendingFile.isFile) return@forEach

            try {
                Log.d(TAG, "Attempting to upload pending file: ${pendingFile.name}")
                val fileId = upload(context, pendingFile)

                if (fileId != null) {
                    Log.i(TAG, "Successfully uploaded pending file: ${pendingFile.name} (ID: $fileId)")
                } else {
                    Log.w(TAG, "Failed to upload pending file: ${pendingFile.name}. Will retry later.")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error processing pending file: ${pendingFile.name}", e)
            }
        }
        Log.d(TAG, "Finished processing pending files.")
    }
}
