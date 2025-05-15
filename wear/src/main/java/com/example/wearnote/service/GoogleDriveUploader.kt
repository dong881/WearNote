package com.example.wearnote.service

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.util.Log
import com.example.wearnote.model.PendingUpload
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.api.client.googleapis.extensions.android.gms.auth.GoogleAccountCredential
import com.google.api.client.googleapis.extensions.android.gms.auth.UserRecoverableAuthIOException
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
import com.google.api.client.http.InputStreamContent
import com.google.api.client.util.IOUtils
import java.io.BufferedInputStream
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream
import kotlinx.coroutines.delay

object GoogleDriveUploader {
    private const val TAG = "GoogleDriveUploader"
    private const val APP_NAME = "WearNote"
    private const val FOLDER_NAME = "WearNote_Recordings"
    private var folderId: String? = null
    
    private var permissionIntent: Intent? = null
    const val REQUEST_AUTHORIZATION = 1001
    
    private const val CHUNK_SIZE_THRESHOLD = 5 * 1024 * 1024L
    private const val CHUNK_SIZE = 1024 * 1024
    private const val MAX_RETRIES = 3
    private const val RETRY_DELAY_MS = 2000L
    
    fun hasPermissionRequest(): Boolean = permissionIntent != null
    
    fun getPermissionIntent(): Intent? = permissionIntent
    
    fun clearPermissionIntent() {
        permissionIntent = null
    }
    
    suspend fun upload(
        context: Context, 
        file: File,
        updateStatus: ((String) -> Unit)? = null
    ): String? = withContext(Dispatchers.IO) {
        clearPermissionIntent()
        
        updateStatus?.invoke("Checking connection...")
        if (!isNetworkAvailable(context)) {
            Log.w(TAG, "No internet connection available. Cannot upload file.")
            updateStatus?.invoke("Upload failed: No internet connection")
            PendingUploadsManager.addPendingUpload(
                context, 
                file, 
                PendingUpload.UploadType.BOTH, 
                null, 
                "No internet connection"
            )
            return@withContext null
        }

        Log.d(TAG, "Starting file upload: ${file.name}, size: ${file.length()} bytes")
        updateStatus?.invoke("Preparing upload...")

        val account = GoogleSignIn.getLastSignedInAccount(context)
        if (account == null) {
            Log.e(TAG, "Upload failed: Not signed in to Google Account.")
            updateStatus?.invoke("Upload failed: Not signed in to Google Account")
            PendingUploadsManager.addPendingUpload(
                context, 
                file, 
                PendingUpload.UploadType.BOTH, 
                null, 
                "Not signed in to Google Account"
            )
            return@withContext null
        }

        try {
            Log.d(TAG, "Attempting to upload file: ${file.name} for account: ${account.email}")
            updateStatus?.invoke("Uploading to Google Drive...")
            
            val credential = GoogleAccountCredential.usingOAuth2(
                context, setOf(DriveScopes.DRIVE_FILE, DriveScopes.DRIVE)
            ).setSelectedAccount(account.account)

            val httpTransport = NetHttpTransport.Builder()
                .doNotValidateCertificate()
                .build()
            
            val driveService = Drive.Builder(
                httpTransport,
                GsonFactory.getDefaultInstance(),
                credential
            )
                .setApplicationName(APP_NAME)
                .build()
                
            val parentFolderId = getOrCreateFolder(driveService)
            if (parentFolderId == null) {
                if (hasPermissionRequest()) {
                    Log.i(TAG, "Need user authorization for Drive access")
                    updateStatus?.invoke("Authorization needed")
                    return@withContext null
                }
                
                Log.e(TAG, "Failed to create or find WearNote_Recordings folder")
                updateStatus?.invoke("Upload failed: Could not create folder")
                PendingUploadsManager.addPendingUpload(
                    context, 
                    file, 
                    PendingUpload.UploadType.BOTH, 
                    null, 
                    "Failed to create folder"
                )
                return@withContext null
            }

            val fileMetadata = DriveFile().apply {
                name = file.name
                parents = listOf(parentFolderId)
            }
            
            val fileSize = file.length()
            val fileId = if (fileSize > CHUNK_SIZE_THRESHOLD) {
                updateStatus?.invoke("Large file detected (${formatFileSize(fileSize)}), using chunked upload...")
                uploadLargeFile(driveService, file, fileMetadata, updateStatus)
            } else {
                updateStatus?.invoke("Uploading file (${formatFileSize(fileSize)})...")
                uploadSimpleFile(driveService, file, fileMetadata)
            }

            if (fileId != null) {
                Log.i(TAG, "Upload successful! File ID: $fileId for ${file.name}")
                updateStatus?.invoke("Upload successful!")
                
                PendingUploadsManager.lastUploadedFileId = fileId

                try {
                    val permission = Permission()
                        .setType("anyone")
                        .setRole("writer")

                    driveService.permissions().create(fileId, permission)
                        .setFields("id")
                        .execute()

                    Log.i(TAG, "Set file permission to anyone with link (with edit access): $fileId")
                } catch (e: Exception) {
                    Log.e(TAG, "Error setting file permissions: ${e.message}")
                }

                try {
                    if (file.exists()) {
                        file.delete()
                        Log.d(TAG, "Deleted original file after successful upload: ${file.name}")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to delete original file after upload: ${file.name}", e)
                }
            } else {
                Log.e(TAG, "Upload failed for ${file.name}")
                updateStatus?.invoke("Upload failed")
                PendingUploadsManager.addPendingUpload(
                    context, 
                    file, 
                    PendingUpload.UploadType.BOTH, 
                    null, 
                    "Upload failed"
                )
            }

            return@withContext fileId

        } catch (e: Exception) {
            if (e is UserRecoverableAuthIOException) {
                permissionIntent = e.intent
                Log.i(TAG, "Need user authorization for Drive access")
                updateStatus?.invoke("Authorization needed")
                return@withContext null
            }
            
            Log.e(TAG, "Google Drive upload failed for ${file.name}", e)
            updateStatus?.invoke("Error during upload: ${e.message}")
            PendingUploadsManager.addPendingUpload(
                context, 
                file, 
                PendingUpload.UploadType.BOTH, 
                null, 
                e.message ?: "Upload error"
            )
            return@withContext null
        }
    }
    
    private suspend fun uploadSimpleFile(
        driveService: Drive, 
        file: File, 
        fileMetadata: DriveFile
    ): String? = withContext(Dispatchers.IO) {
        var retryCount = 0
        var lastError: Exception? = null
        
        while (retryCount < MAX_RETRIES) {
            try {
                val mediaContent = FileContent("audio/mp4", file)
                
                val uploadedFile = driveService.files().create(fileMetadata, mediaContent)
                    .setFields("id")
                    .execute()
                
                return@withContext uploadedFile.id
            } catch (e: Exception) {
                lastError = e
                retryCount++
                Log.w(TAG, "Upload retry $retryCount/$MAX_RETRIES for ${file.name}: ${e.message}")
                
                if (retryCount < MAX_RETRIES) {
                    delay(RETRY_DELAY_MS)
                }
            }
        }
        
        Log.e(TAG, "All upload attempts failed for ${file.name}", lastError)
        return@withContext null
    }
    
    private suspend fun uploadLargeFile(
        driveService: Drive,
        file: File,
        fileMetadata: DriveFile,
        updateStatus: ((String) -> Unit)? = null
    ): String? = withContext(Dispatchers.IO) {
        var fileId: String? = null
        var retryCount = 0
        val fileSize = file.length()
        
        while (retryCount < MAX_RETRIES) {
            try {
                Log.d(TAG, "Starting chunked upload for ${file.name} (size: ${formatFileSize(fileSize)})")
                
                val inputStream = ChunkedFileInputStream(file, CHUNK_SIZE)
                val mediaContent = InputStreamContent("audio/mp4", inputStream)
                mediaContent.length = fileSize
                
                val uploadRequest = driveService.files().create(fileMetadata, mediaContent)
                uploadRequest.mediaHttpUploader.isDirectUploadEnabled = false
                uploadRequest.mediaHttpUploader.chunkSize = CHUNK_SIZE
                
                // Fix: Properly implement MediaHttpUploaderProgressListener
                uploadRequest.mediaHttpUploader.progressListener = 
                    com.google.api.client.googleapis.media.MediaHttpUploaderProgressListener { uploader ->  
                        val bytesUploaded = uploader.numBytesUploaded
                        val progress = (bytesUploaded * 100 / fileSize)
                        Log.d(TAG, "Upload progress: $progress% ($bytesUploaded / $fileSize bytes)")
                        updateStatus?.invoke("Uploading: $progress%")
                    }
                
                val uploadedFile = uploadRequest.execute()
                fileId = uploadedFile.id
                
                if (fileId != null) {
                    Log.d(TAG, "Chunked upload completed successfully for ${file.name}")
                    break
                }
            } catch (e: Exception) {
                retryCount++
                Log.w(TAG, "Chunked upload retry $retryCount/$MAX_RETRIES for ${file.name}: ${e.message}")
                
                if (retryCount < MAX_RETRIES) {
                    delay(RETRY_DELAY_MS * retryCount)
                    updateStatus?.invoke("Retrying upload (attempt $retryCount)...")
                }
            }
        }
        
        return@withContext fileId
    }
    
    private fun formatFileSize(size: Long): String {
        return when {
            size < 1024 -> "$size B"
            size < 1024 * 1024 -> "${size / 1024} KB"
            size < 1024 * 1024 * 1024 -> "${size / (1024 * 1024)} MB"
            else -> "${size / (1024 * 1024 * 1024)} GB"
        }
    }
    
    private class ChunkedFileInputStream(
        private val file: File,
        private val chunkSize: Int
    ) : InputStream() {
        private val inputStream = BufferedInputStream(FileInputStream(file), chunkSize)
        private var bytesRead = 0L
        private var closed = false
        
        override fun read(): Int {
            if (closed) return -1
            
            val result = inputStream.read()
            if (result != -1) bytesRead++
            return result
        }
        
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (closed) return -1
            
            val result = inputStream.read(b, off, len)
            if (result > 0) bytesRead += result
            return result
        }
        
        override fun close() {
            if (!closed) {
                closed = true
                inputStream.close()
            }
        }
        
        fun getProgress(): Long = bytesRead
    }
    
    private suspend fun getOrCreateFolder(driveService: Drive): String? = withContext(Dispatchers.IO) {
        try {
            if (folderId != null) {
                return@withContext folderId
            }

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
            if (e is UserRecoverableAuthIOException) {
                permissionIntent = e.intent
                Log.i(TAG, "Need user authorization for folder access")
                return@withContext null
            }
            
            Log.e(TAG, "Error creating/finding folder", e)
            return@withContext null
        }
    }
    
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

    suspend fun processPending(context: Context) = withContext(Dispatchers.IO) {
        PendingUploadsManager.processAllPendingUploads(context)
    }
    
    fun handleAuthResult(resultCode: Int): Boolean {
        if (resultCode == Activity.RESULT_OK) {
            clearPermissionIntent()
            return true
        }
        return false
    }
}
