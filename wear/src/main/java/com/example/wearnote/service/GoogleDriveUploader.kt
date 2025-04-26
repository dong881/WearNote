package com.example.wearnote.service

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.util.Log
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.api.client.googleapis.extensions.android.gms.auth.GoogleAccountCredential
import com.google.api.client.http.FileContent
import com.google.api.client.http.javanet.NetHttpTransport
import com.google.api.client.json.gson.GsonFactory
import com.google.api.services.drive.Drive
import com.google.api.services.drive.DriveScopes
import com.google.api.services.drive.model.File as DriveFile // Alias to avoid name clash
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException

object GoogleDriveUploader {
    private const val TAG = "GoogleDriveUploader"
    private const val PENDING_DIR = "pending"
    private const val APP_NAME = "WearNote" // Or your app name

    // --- Google Drive API Implementation (Placeholder) ---
    private fun getDriveService(context: Context, account: GoogleSignInAccount): Drive {
        val credential = GoogleAccountCredential.usingOAuth2(
            context, setOf(DriveScopes.DRIVE_FILE) // Or DRIVE_APPFOLDER
        ).setSelectedAccount(account.account)

        return Drive.Builder(
            NetHttpTransport(),
            GsonFactory.getDefaultInstance(),
            credential
        )
            .setApplicationName(APP_NAME)
            .build()
    }

    // Upload file to Google Drive
    suspend fun upload(context: Context, file: File): String? = withContext(Dispatchers.IO) {
        // 1. Check network connectivity first
        if (!isNetworkAvailable(context)) {
            Log.w(TAG, "No network available, enqueueing for later upload: ${file.name}")
            enqueuePending(context, file)
            return@withContext null
        }

        // 2. Get Signed-In Google Account (Requires Sign-In flow in Activity)
        val account = GoogleSignIn.getLastSignedInAccount(context)
        if (account == null) {
            Log.e(TAG, "Upload failed: Not signed in to Google Account.")
            enqueuePending(context, file) // Enqueue if not signed in
            return@withContext null
            // TODO: Trigger Sign-In flow from Activity/Service notification if needed
        }

        // 3. Perform Actual Google Drive Upload
        try {
            Log.d(TAG, "Attempting to upload file: ${file.name} for account: ${account.email}")
            val driveService = getDriveService(context, account)

            val fileMetadata = DriveFile().apply {
                name = file.name
                // Optional: Set parent folder ID if you want to upload to a specific folder
                // parents = listOf("YOUR_FOLDER_ID")
            }
            val mediaContent = FileContent("audio/3gpp", file) // Adjust MIME type if needed

            val uploadedFile = driveService.files().create(fileMetadata, mediaContent)
                .setFields("id") // Request only the file ID back
                .execute()

            val fileId = uploadedFile.id
            Log.i(TAG, "Upload successful! File ID: $fileId for ${file.name}")

            // 4. Delete the original file after successful upload
            try {
                if (file.exists()) {
                    file.delete()
                    Log.d(TAG, "Deleted original file after successful upload: ${file.name}")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to delete original file after upload: ${file.name}", e)
            }

            return@withContext fileId // Return the Google Drive File ID

        } catch (e: Exception) {
            // Handle specific exceptions like authorization errors, network issues during upload etc.
            Log.e(TAG, "Google Drive upload failed for ${file.name}", e)
            // If upload fails (network error during upload, API error, etc.), enqueue for retry
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
    fun enqueuePending(context: Context, file: File) {
        if (!file.exists()) {
            Log.w(TAG, "Cannot enqueue non-existent file: ${file.absolutePath}")
            return
        }
        try {
            val queueDir = File(context.filesDir, PENDING_DIR)
            if (!queueDir.exists()) {
                queueDir.mkdirs()
            }
            // Copy the file to the pending directory
            val destinationFile = File(queueDir, file.name)
            // Only copy if it doesn't already exist in the queue or if the source is newer (optional)
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
            Log.d(TAG, "Network still unavailable, cannot process pending uploads.")
            return@withContext
        }

        val account = GoogleSignIn.getLastSignedInAccount(context)
        if (account == null) {
            Log.w(TAG, "Cannot process pending uploads: Not signed in.")
            // Optionally trigger sign-in flow here or wait for user interaction
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
            if (!pendingFile.isFile) return@forEach // Skip directories if any

            try {
                Log.d(TAG, "Attempting to upload pending file: ${pendingFile.name}")
                // Use the main upload function which now includes the Drive API logic
                val fileId = upload(context, pendingFile) // Pass the pending file itself

                if (fileId != null) {
                    Log.i(TAG, "Successfully uploaded pending file: ${pendingFile.name} (ID: $fileId)")
                    // Deletion is now handled within the upload function upon success
                    // pendingFile.delete() // No longer needed here
                } else {
                    // Upload function handles re-enqueueing or logging errors
                    Log.w(TAG, "Failed to upload pending file: ${pendingFile.name}. Will retry later.")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error processing pending file: ${pendingFile.name}", e)
                // Keep the file in the queue for the next attempt
            }
        }
        Log.d(TAG, "Finished processing pending files.")
    }
}
