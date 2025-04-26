package com.example.wearnote.utils

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Environment
import android.util.Log
import com.google.api.client.googleapis.json.GoogleJsonResponseException
import com.google.api.client.http.FileContent
import com.google.api.services.drive.Drive
import com.google.api.services.drive.model.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.Queue
import java.util.concurrent.ConcurrentLinkedQueue

class AudioFileManager(private val context: Context) {

    companion object {
        private const val TAG = "AudioFileManager"
        private const val SERVER_URL = "http://140.118.123.107:5000/process"
        private const val DRIVE_FOLDER_NAME = "WearNoteRecordings"
    }
    
    private val uploadQueue: Queue<java.io.File> = ConcurrentLinkedQueue()
    private val httpClient = OkHttpClient()
    private val preferenceManager = PreferenceManager(context)
    
    /**
     * Creates a new audio file for recording
     */
    fun createAudioFile(): java.io.File {
        val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val fileName = "WEARNOTE_$timeStamp.wav"
        
        val storageDir = context.getExternalFilesDir(Environment.DIRECTORY_MUSIC)
        return java.io.File(storageDir, fileName)
    }
    
    /**
     * Checks if network is available for uploads
     */
    fun isNetworkAvailable(): Boolean {
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }
    
    /**
     * Adds a file to the upload queue for later retry
     */
    fun addFileToUploadQueue(file: java.io.File) {
        uploadQueue.add(file)
        Log.d(TAG, "Added file to upload queue: ${file.absolutePath}")
    }
    
    /**
     * Processes any pending files in the upload queue
     */
    suspend fun processPendingUploads() {
        if (!isNetworkAvailable()) {
            Log.d(TAG, "Network unavailable, skipping pending uploads")
            return
        }
        
        while (uploadQueue.isNotEmpty()) {
            val file = uploadQueue.peek() ?: break
            
            try {
                Log.d(TAG, "Attempting to upload queued file: ${file.absolutePath}")
                val fileId = uploadFileToDrive(file)
                
                if (fileId != null) {
                    Log.d(TAG, "Queued file uploaded successfully, triggering server: $fileId")
                    triggerServerProcess(fileId)
                    uploadQueue.poll() // Remove from queue only on success
                } else {
                    Log.e(TAG, "Failed to upload queued file, will retry later")
                    break // Stop trying if we encounter an error
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error processing queued upload", e)
                break
            }
        }
    }
    
    /**
     * Uploads a file to Google Drive
     * Returns the file ID if successful, null otherwise
     */
    suspend fun uploadFileToDrive(file: java.io.File): String? = withContext(Dispatchers.IO) {
        try {
            // Get Drive service from DriveServiceHelper (implementation depends on your auth approach)
            val driveService = DriveServiceHelper.getDriveService(context) ?: return@withContext null
            
            // Find or create WearNote folder
            val folderId = getFolderIdOrCreateFolder(driveService)
            
            // Create file metadata
            val fileMetadata = File().apply {
                name = file.name
                parents = listOf(folderId)
            }
            
            // Set file content
            val mediaContent = FileContent("audio/wav", file)
            
            // Upload file
            val uploadedFile = driveService.files().create(fileMetadata, mediaContent)
                .setFields("id")
                .execute()
            
            Log.d(TAG, "File uploaded to Drive with ID: ${uploadedFile.id}")
            return@withContext uploadedFile.id
            
        } catch (e: GoogleJsonResponseException) {
            Log.e(TAG, "Unable to upload file to Drive: ${e.details}")
            null
        } catch (e: IOException) {
            Log.e(TAG, "Error uploading file to Drive", e)
            null
        }
    }
    
    /**
     * Gets the folder ID for WearNote recordings or creates it if it doesn't exist
     */
    private suspend fun getFolderIdOrCreateFolder(driveService: Drive): String = withContext(Dispatchers.IO) {
        try {
            // Check if folder already exists
            val result = driveService.files().list()
                .setQ("mimeType='application/vnd.google-apps.folder' and name='$DRIVE_FOLDER_NAME' and trashed=false")
                .setSpaces("drive")
                .setFields("files(id, name)")
                .execute()
            
            // If folder exists, return its ID
            if (result.files.isNotEmpty()) {
                return@withContext result.files[0].id
            }
            
            // If folder doesn't exist, create it
            val folderMetadata = File().apply {
                name = DRIVE_FOLDER_NAME
                mimeType = "application/vnd.google-apps.folder"
            }
            
            val folder = driveService.files().create(folderMetadata)
                .setFields("id")
                .execute()
                
            return@withContext folder.id
            
        } catch (e: Exception) {
            Log.e(TAG, "Error accessing/creating Drive folder", e)
            throw e
        }
    }
    
    /**
     * Triggers the server to process the uploaded file
     */
    suspend fun triggerServerProcess(fileId: String): Boolean = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "Triggering server to process fileId: $fileId")
            
            // Create JSON payload
            val jsonObject = JSONObject()
            jsonObject.put("file_id", fileId)
            val json = jsonObject.toString()
            
            // Create request
            val body = json.toRequestBody("application/json".toMediaType())
            val request = Request.Builder()
                .url(SERVER_URL)
                .post(body)
                .header("Content-Type", "application/json")
                .build()
            
            // Execute request
            httpClient.newCall(request).execute().use { response ->
                val success = response.isSuccessful
                if (success) {
                    Log.d(TAG, "Server processing triggered successfully")
                } else {
                    Log.e(TAG, "Server returned error: ${response.code}")
                }
                return@withContext success
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Error triggering server process", e)
            return@withContext false
        }
    }
    
    /**
     * Cleans up old recordings to free up space
     * Retains files that still need to be uploaded
     */
    fun cleanupOldRecordings(maxAgeDays: Int = 7) {
        try {
            val storageDir = context.getExternalFilesDir(Environment.DIRECTORY_MUSIC)
            storageDir?.listFiles()?.forEach { file ->
                // Skip files in the upload queue
                if (uploadQueue.contains(file)) {
                    return@forEach
                }
                
                val lastModified = file.lastModified()
                val now = System.currentTimeMillis()
                val maxAgeMillis = maxAgeDays * 24 * 60 * 60 * 1000L
                
                if (now - lastModified > maxAgeMillis) {
                    if (file.delete()) {
                        Log.d(TAG, "Deleted old recording: ${file.name}")
                    } else {
                        Log.e(TAG, "Failed to delete old recording: ${file.name}")
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error cleaning up old recordings", e)
        }
    }
}