package com.example.wearnote.service

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.util.Log
import com.example.wearnote.model.PendingUpload
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.api.client.googleapis.extensions.android.gms.auth.GoogleAccountCredential
import com.google.api.client.http.FileContent
import com.google.api.client.http.javanet.NetHttpTransport
import com.google.api.client.json.gson.GsonFactory
import com.google.api.services.drive.Drive
import com.google.api.services.drive.DriveScopes
import com.google.api.services.drive.model.Permission
import com.google.api.services.drive.model.File as DriveFile
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object GoogleDriveUploader {
    private const val TAG = "GoogleDriveUploader"
    private const val APP_NAME = "WearNote"
    private const val FOLDER_NAME = "WearNote_Recordings"
    private var folderId: String? = null
    
    // Modified upload function with status update callback
    suspend fun upload(
        context: Context, 
        file: File,
        updateStatus: ((String) -> Unit)? = null
    ): String? = withContext(Dispatchers.IO) {
        // Check network connectivity
        updateStatus?.invoke("Checking connection...")
        if (!isNetworkAvailable(context)) {
            Log.w(TAG, "No internet connection available. Cannot upload file.")
            updateStatus?.invoke("Upload failed: No internet connection")
            // Add to pending uploads with new manager
            PendingUploadsManager.addPendingUpload(
                context, 
                file, 
                PendingUpload.UploadType.DRIVE, 
                null, 
                "No internet connection"
            )
            return@withContext null
        }

        Log.d(TAG, "Starting file upload: ${file.name}")
        updateStatus?.invoke("Preparing upload...")

        // Get signed-in Google account
        val account = GoogleSignIn.getLastSignedInAccount(context)
        if (account == null) {
            Log.e(TAG, "Upload failed: Not signed in to Google Account.")
            updateStatus?.invoke("Upload failed: Not signed in to Google Account")
            PendingUploadsManager.addPendingUpload(
                context, 
                file, 
                PendingUpload.UploadType.DRIVE, 
                null, 
                "Not signed in to Google Account"
            )
            return@withContext null
        }

        // Perform Google Drive upload
        try {
            Log.d(TAG, "Attempting to upload file: ${file.name} for account: ${account.email}")
            updateStatus?.invoke("Uploading to Google Drive...")
            
            // Create Drive service
            val credential = GoogleAccountCredential.usingOAuth2(
                context, setOf(DriveScopes.DRIVE_FILE, DriveScopes.DRIVE)
            ).setSelectedAccount(account.account)

            val driveService = Drive.Builder(
                NetHttpTransport(),
                GsonFactory.getDefaultInstance(),
                credential
            )
                .setApplicationName(APP_NAME)
                .build()
                
            // Get or create the WearNote_Recordings folder
            val parentFolderId = getOrCreateFolder(driveService)
            if (parentFolderId == null) {
                Log.e(TAG, "Failed to create or find WearNote_Recordings folder")
                updateStatus?.invoke("Upload failed: Could not create folder")
                PendingUploadsManager.addPendingUpload(
                    context, 
                    file, 
                    PendingUpload.UploadType.DRIVE, 
                    null, 
                    "Failed to create folder"
                )
                return@withContext null
            }

            // Prepare file metadata and content
            val fileMetadata = DriveFile().apply {
                name = file.name
                parents = listOf(parentFolderId)
            }
            val mediaContent = FileContent("audio/mp4", file)

            // Execute upload
            updateStatus?.invoke("Sending file to Google Drive...")
            val uploadedFile = driveService.files().create(fileMetadata, mediaContent)
                .setFields("id")
                .execute()

            val fileId = uploadedFile.id
            Log.i(TAG, "Upload successful! File ID: $fileId for ${file.name}")
            updateStatus?.invoke("Upload successful!")
            
            // Store the last uploaded file ID for reference
            PendingUploadsManager.lastUploadedFileId = fileId

            // Add permission to make the file accessible to anyone with the link
            if (fileId != null) {
                try {
                    // Create a permission for anyone with the link to view the file
                    val permission = Permission()
                        .setType("anyone")
                        .setRole("reader")

                    driveService.permissions().create(fileId, permission)
                        .setFields("id")
                        .execute()

                    Log.i(TAG, "Set file permission to anyone with link: $fileId")
                } catch (e: Exception) {
                    Log.e(TAG, "Error setting file permissions: ${e.message}")
                    // Don't fail the upload if just permission setting fails
                }
            }

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
            updateStatus?.invoke("Error during upload: ${e.message}")
            PendingUploadsManager.addPendingUpload(
                context, 
                file, 
                PendingUpload.UploadType.DRIVE, 
                null, 
                e.message ?: "Upload error"
            )
            return@withContext null
        }
    }
    
    // Get or create the WearNote_Recordings folder
    private suspend fun getOrCreateFolder(driveService: Drive): String? = withContext(Dispatchers.IO) {
        try {
            // Check if we've already found or created the folder in this session
            if (folderId != null) {
                return@withContext folderId
            }

            // Search for existing folder
            val result = driveService.files().list()
                .setQ("name='$FOLDER_NAME' and mimeType='application/vnd.google-apps.folder' and trashed=false")
                .setSpaces("drive")
                .setFields("files(id)")
                .execute()

            if (result.files.isNotEmpty()) {
                folderId = result.files[0].id
                Log.d(TAG, "Found existing folder: $FOLDER_NAME, ID: $folderId")
                return@withContext folderId
            }

            // Create new folder if not found
            val folderMetadata = DriveFile().apply {
                name = FOLDER_NAME
                mimeType = "application/vnd.google-apps.folder"
            }

            val folder = driveService.files().create(folderMetadata)
                .setFields("id")
                .execute()

            folderId = folder.id
            Log.d(TAG, "Created new folder: $FOLDER_NAME, ID: $folderId")
            return@withContext folderId
        } catch (e: Exception) {
            Log.e(TAG, "Error creating/finding folder", e)
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

    // Process all pending uploads
    suspend fun processPending(context: Context) = withContext(Dispatchers.IO) {
        // Delegate to the new manager
        PendingUploadsManager.processAllPendingUploads(context)
    }
}
