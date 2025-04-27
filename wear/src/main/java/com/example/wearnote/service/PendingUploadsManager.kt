package com.example.wearnote.service

import android.content.Context
import android.util.Log
import com.example.wearnote.model.PendingUpload
import com.google.api.client.googleapis.extensions.android.gms.auth.GoogleAccountCredential
import com.google.api.client.http.javanet.NetHttpTransport
import com.google.api.client.json.gson.GsonFactory
import com.google.api.services.drive.Drive
import com.google.api.services.drive.DriveScopes
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import com.google.android.gms.auth.api.signin.GoogleSignIn
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch

/**
 * Centralized manager for handling pending uploads
 */
object PendingUploadsManager {
    private const val TAG = "PendingUploadsManager"
    private const val PREFS_NAME = "pending_uploads_prefs"
    private const val KEY_PENDING_UPLOADS = "pending_uploads_list"
    
    // In-memory cache of pending uploads
    private val pendingUploads = ConcurrentHashMap<String, PendingUpload>()
    
    // StateFlow to observe pending uploads changes
    private val _pendingUploadsFlow = MutableStateFlow<List<PendingUpload>>(emptyList())
    val pendingUploadsFlow: StateFlow<List<PendingUpload>> = _pendingUploadsFlow.asStateFlow()
    
    // Last uploaded file ID tracking
    var lastUploadedFileId: String? = null
        set(value) {
            field = value
            Log.d(TAG, "Last uploaded file ID updated: $value")
        }
    
    /**
     * Initialize the manager by loading saved pending uploads
     */
    fun initialize(context: Context) {
        loadFromPrefs(context)
        updateFlow()
        Log.d(TAG, "PendingUploadsManager initialized")
    }
    
    /**
     * Add a file to the pending uploads list
     * Now checks for internet connectivity before adding to pending list
     */
    fun addPendingUpload(
        context: Context, 
        file: File, 
        uploadType: PendingUpload.UploadType = PendingUpload.UploadType.DRIVE,
        fileId: String? = null,
        failureReason: String? = null
    ) {
        // Only add to pending uploads if there's no internet or we've already tried once
        if (!GoogleDriveUploader.isNetworkAvailable(context) || failureReason != null) {
            val pendingUpload = PendingUpload(
                fileName = file.name,
                filePath = file.absolutePath,
                uploadType = uploadType,
                fileId = fileId,
                failureReason = failureReason
            )
            pendingUploads[file.absolutePath] = pendingUpload
            saveToPrefs(context)
            updateFlow()
            Log.d(TAG, "Added pending upload: ${file.name}, type: $uploadType")
        } else {
            Log.d(TAG, "Network available, not adding to pending list: ${file.name}")
            // Attempt immediate upload if we have internet
            if (uploadType == PendingUpload.UploadType.DRIVE) {
                // Launch a coroutine to handle the upload
                GlobalScope.launch(Dispatchers.IO) {
                    retryDriveUpload(context, file)
                }
            }
        }
    }
    
    /**
     * Get count of pending uploads
     */
    fun getPendingUploadCount(): Int {
        return pendingUploads.size
    }
    
    /**
     * Get all pending uploads
     */
    fun getPendingUploads(): List<PendingUpload> {
        return pendingUploads.values.toList()
            .sortedByDescending { it.timestamp }
    }
    
    /**
     * Remove an upload from the pending list
     */
    fun removePendingUpload(context: Context, filePath: String) {
        pendingUploads.remove(filePath)
        saveToPrefs(context)
        updateFlow()
        Log.d(TAG, "Removed pending upload: $filePath")
    }
    
    /**
     * Process all pending uploads
     */
    suspend fun processAllPendingUploads(context: Context) = withContext(Dispatchers.IO) {
        if (!GoogleDriveUploader.isNetworkAvailable(context)) {
            Log.d(TAG, "No network available, skipping batch processing")
            return@withContext
        }
        
        val uploads = getPendingUploads()
        Log.d(TAG, "Processing ${uploads.size} pending uploads")
        
        for (upload in uploads) {
            retryUpload(context, upload)
        }
    }
    
    /**
     * Retry uploading a specific file
     */
    suspend fun retryUpload(context: Context, pendingUpload: PendingUpload): Boolean = withContext(Dispatchers.IO) {
        Log.d(TAG, "Retrying upload for ${pendingUpload.fileName}")
        
        val file = File(pendingUpload.filePath)
        if (!file.exists()) {
            Log.e(TAG, "File no longer exists: ${pendingUpload.filePath}")
            removePendingUpload(context, pendingUpload.filePath)
            return@withContext false
        }
        
        when (pendingUpload.uploadType) {
            PendingUpload.UploadType.DRIVE -> {
                val success = retryDriveUpload(context, file)
                if (success) {
                    removePendingUpload(context, pendingUpload.filePath)
                }
                return@withContext success
            }
            PendingUpload.UploadType.AI_PROCESSING -> {
                // For now, just remove it as the AI processing retry is more complex
                removePendingUpload(context, pendingUpload.filePath)
                return@withContext true
            }
            PendingUpload.UploadType.BOTH -> {
                // For now, just remove it as the combined retry is more complex
                removePendingUpload(context, pendingUpload.filePath)
                return@withContext true
            }
        }
    }
    
    /**
     * Retry uploading to Google Drive
     */
    private suspend fun retryDriveUpload(context: Context, file: File): Boolean = withContext(Dispatchers.IO) {
        try {
            if (!GoogleDriveUploader.isNetworkAvailable(context)) {
                Log.d(TAG, "No network available for Drive upload retry")
                return@withContext false
            }
            
            val account = GoogleSignIn.getLastSignedInAccount(context)
            if (account == null) {
                Log.e(TAG, "No Google account available for retry")
                return@withContext false
            }
            
            val credential = GoogleAccountCredential.usingOAuth2(
                context, listOf(DriveScopes.DRIVE_FILE, DriveScopes.DRIVE)
            ).setSelectedAccount(account.account)
            
            val driveService = Drive.Builder(
                NetHttpTransport(),
                GsonFactory.getDefaultInstance(),
                credential
            ).setApplicationName("WearNote").build()
            
            val fileId = GoogleDriveUploader.upload(context, file)
            
            if (fileId != null) {
                Log.d(TAG, "Successfully uploaded file to Drive: ${file.name}, ID: $fileId")
                return@withContext true
            } else {
                Log.e(TAG, "Drive upload retry failed for ${file.name}")
                return@withContext false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error during Drive upload retry", e)
            return@withContext false
        }
    }
    
    /**
     * Save pending uploads to SharedPreferences
     */
    private fun saveToPrefs(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val gson = Gson()
        val json = gson.toJson(pendingUploads.values.toList())
        prefs.edit().putString(KEY_PENDING_UPLOADS, json).apply()
    }
    
    /**
     * Load pending uploads from SharedPreferences
     */
    private fun loadFromPrefs(context: Context) {
        try {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val gson = Gson()
            val json = prefs.getString(KEY_PENDING_UPLOADS, null) ?: return
            
            val type = object : TypeToken<List<PendingUpload>>() {}.type
            val loadedUploads: List<PendingUpload> = gson.fromJson(json, type)
            
            pendingUploads.clear()
            loadedUploads.forEach { upload ->
                pendingUploads[upload.filePath] = upload
            }
            
            // Clean up uploads for files that no longer exist
            val iterator = pendingUploads.entries.iterator()
            while (iterator.hasNext()) {
                val entry = iterator.next()
                val file = File(entry.key)
                if (!file.exists()) {
                    Log.d(TAG, "Removing non-existent file from pending uploads: ${entry.value.fileName}")
                    iterator.remove()
                }
            }
            
            Log.d(TAG, "Loaded ${pendingUploads.size} pending uploads")
        } catch (e: Exception) {
            Log.e(TAG, "Error loading pending uploads", e)
        }
    }
    
    /**
     * Update the StateFlow with the latest pending uploads
     */
    private fun updateFlow() {
        _pendingUploadsFlow.value = getPendingUploads()
    }
}
