package com.example.wearnote.util

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.util.Log
import com.google.api.client.http.FileContent
import com.google.api.services.drive.Drive
import com.google.api.services.drive.model.File
import com.google.api.services.drive.model.Permission
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException

object GoogleDriveUploader {
    private const val TAG = "GoogleDriveUploader"
    private const val FOLDER_NAME = "WearNote_Recordings"
    private var folderId: String? = null
    
    /**
     * Uploads a file to Google Drive in the WearNote_Recordings folder
     * @return The Google Drive file ID if successful, null otherwise
     */
    suspend fun uploadToDrive(
        context: Context, 
        localFile: java.io.File, 
        driveService: Drive
    ): String? = withContext(Dispatchers.IO) {
        try {
            // First check for internet connectivity
            if (!isNetworkAvailable(context)) {
                Log.w(TAG, "No internet connection available. Cannot upload file.")
                return@withContext null
            }

            Log.d(TAG, "Starting file upload: ${localFile.name}")

            // Get or create the folder
            val parentFolderId = getOrCreateFolder(driveService)
            if (parentFolderId == null) {
                Log.e(TAG, "Failed to create or find parent folder")
                return@withContext null
            }

            // Create file metadata
            val fileMetadata = File()
            fileMetadata.name = localFile.name
            fileMetadata.parents = listOf(parentFolderId)
            // Change MIME type to match .m4a format
            fileMetadata.mimeType = "audio/mp4" // MIME type for M4A audio files

            // File content
            val mediaContent = FileContent(fileMetadata.mimeType, localFile)

            // Execute the upload
            val uploadedFile = driveService.files().create(fileMetadata, mediaContent)
                .setFields("id, name, webViewLink")
                .execute()

            val fileId = uploadedFile.id
            Log.i(TAG, "File uploaded successfully! ID: $fileId, Link: ${uploadedFile.webViewLink}")

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
            
            return@withContext fileId
        } catch (e: IOException) {
            Log.e(TAG, "Error uploading file to Drive", e)
            return@withContext null
        } catch (e: Exception) {
            Log.e(TAG, "Unexpected error during upload", e)
            return@withContext null
        }
    }

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
                return@withContext folderId
            }

            // Create new folder if not found
            val folderMetadata = File()
            folderMetadata.name = FOLDER_NAME
            folderMetadata.mimeType = "application/vnd.google-apps.folder"

            val folder = driveService.files().create(folderMetadata)
                .setFields("id")
                .execute()

            folderId = folder.id
            return@withContext folderId
        } catch (e: Exception) {
            Log.e(TAG, "Error creating/finding folder", e)
            return@withContext null
        }
    }

    private fun isNetworkAvailable(context: Context): Boolean {
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val network = connectivityManager.activeNetwork ?: return false
            val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
            
            return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                   capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        } else {
            @Suppress("DEPRECATION")
            return connectivityManager.activeNetworkInfo?.isConnected ?: false
        }
    }
}
