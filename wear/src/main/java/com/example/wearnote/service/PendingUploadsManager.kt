package com.example.wearnote.service

import android.content.Context
import android.content.Intent
import android.os.Build
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
        file: File?, 
        uploadType: PendingUpload.UploadType = PendingUpload.UploadType.DRIVE,
        fileId: String? = null,
        failureReason: String? = null
    ) {
        if (file == null) {
            Log.w(TAG, "Attempted to add pending upload with null file, ignoring")
            return
        }
        
        // Only add to pending uploads if there's no internet or we've already tried once
        if (!GoogleDriveUploader.isNetworkAvailable(context) || failureReason != null) {
            addPendingUploadByPath(
                context,
                file.name,
                file.absolutePath,
                uploadType,
                fileId,
                failureReason
            )
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
     * Add a pending upload by path without requiring a File object
     */
    fun addPendingUploadByPath(
        context: Context,
        fileName: String,
        filePath: String,
        uploadType: PendingUpload.UploadType = PendingUpload.UploadType.DRIVE,
        fileId: String? = null,
        failureReason: String? = null
    ) {
        // Create and store the pending upload
        val pendingUpload = PendingUpload(
            fileName = fileName,
            filePath = filePath,
            uploadType = uploadType,
            fileId = fileId,
            failureReason = failureReason
        )
        
        pendingUploads[filePath] = pendingUpload
        saveToPrefs(context)
        updateFlow()
        Log.d(TAG, "Added pending upload by path: $fileName, type: $uploadType")
    }
    
    /**
     * Remove pending uploads by fileId
     */
    fun removeByFileId(context: Context, fileId: String) {
        val toRemove = pendingUploads.values.filter { it.fileId == fileId }
        toRemove.forEach { upload ->
            pendingUploads.remove(upload.filePath)
            Log.d(TAG, "Removed pending upload with fileId: $fileId, path: ${upload.filePath}")
        }
        
        if (toRemove.isNotEmpty()) {
            saveToPrefs(context)
            updateFlow()
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
            // Only remove if no fileId is available
            if (pendingUpload.fileId == null) {
                Log.e(TAG, "File no longer exists and no fileId available: ${pendingUpload.filePath}")
                removePendingUpload(context, pendingUpload.filePath)
                return@withContext false
            } else {
                // File doesn't exist but we have a fileId
                Log.d(TAG, "File no longer exists but fileId is available: ${pendingUpload.filePath}")
                
                // For AI_PROCESSING we can continue without the local file
                if (pendingUpload.uploadType == PendingUpload.UploadType.AI_PROCESSING) {
                    // Launch AI processing retry without requiring local file
                    val intent = Intent(context, RecorderService::class.java).apply {
                        action = RecorderService.ACTION_START_AI_PROCESSING
                        putExtra("file_id", pendingUpload.fileId)
                        putExtra("file_path", pendingUpload.filePath)
                        putExtra("is_retry", true)
                    }
                    
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        context.startForegroundService(intent)
                    } else {
                        context.startService(intent)
                    }
                    
                    Log.d(TAG, "Started AI processing retry without local file for ${pendingUpload.fileName}")
                    return@withContext true
                } else {
                    // For DRIVE or BOTH types, we need the local file
                    Log.e(TAG, "Local file needed for ${pendingUpload.uploadType} but not found: ${pendingUpload.filePath}")
                    // Don't remove it - let the user decide to delete it manually
                    return@withContext false
                }
            }
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
                // Don't remove it automatically - actually retry the AI processing
                if (pendingUpload.fileId != null) {
                    // Launch a service to retry AI processing
                    val intent = Intent(context, RecorderService::class.java).apply {
                        action = RecorderService.ACTION_START_AI_PROCESSING
                        putExtra("file_id", pendingUpload.fileId)
                        putExtra("file_path", pendingUpload.filePath)
                        // Add a flag to indicate this is a retry
                        putExtra("is_retry", true)
                    }
                    
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        context.startForegroundService(intent)
                    } else {
                        context.startService(intent)
                    }
                    
                    Log.d(TAG, "Started AI processing retry for ${pendingUpload.fileName}")
                    return@withContext true // Just indicate we started the retry, not that it succeeded
                    // Note: The actual success/failure will be handled by handleAIProcessingResult
                } else {
                    Log.e(TAG, "Cannot retry AI processing without a fileId")
                    return@withContext false
                }
            }
            PendingUpload.UploadType.BOTH -> {
                // First retry upload to Drive, then AI processing if successful
                val driveSuccess = retryDriveUpload(context, file)
                if (driveSuccess && pendingUpload.fileId != null) {
                    // Now attempt AI processing
                    val intent = Intent(context, RecorderService::class.java).apply {
                        action = RecorderService.ACTION_START_AI_PROCESSING
                        putExtra("file_id", pendingUpload.fileId)
                        putExtra("file_path", pendingUpload.filePath)
                        putExtra("is_retry", true)
                    }
                    
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        context.startForegroundService(intent)
                    } else {
                        context.startService(intent)
                    }
                    
                    // Remove from BOTH type but may be added again as AI_PROCESSING if that part fails
                    removePendingUpload(context, pendingUpload.filePath)
                    Log.d(TAG, "Drive upload succeeded, started AI processing for ${pendingUpload.fileName}")
                    return@withContext true
                } else {
                    Log.e(TAG, "Drive upload failed or no fileId for ${pendingUpload.fileName}")
                    return@withContext false
                }
            }
        }
    }
    
    /**
     * Handle the result of AI processing
     * To be called from RecorderService after the AI processing API call
     */
    fun handleAIProcessingResult(
        context: Context, 
        filePath: String, 
        fileId: String?, 
        isSuccess: Boolean, 
        message: String?
    ) {
        Log.d(TAG, "Handling AI processing result - success: $isSuccess, message: $message, fileId: $fileId")
        
        if (isSuccess) {
            // If successful, first try to remove by filePath
            val removed = pendingUploads.remove(filePath) != null
            
            // If that didn't work and we have a fileId, find and remove by fileId
            if (!removed && fileId != null) {
                removeByFileId(context, fileId)
                Log.d(TAG, "AI processing succeeded, removed by fileId: $fileId")
            } else if (removed) {
                Log.d(TAG, "AI processing succeeded, removed from pending list: $filePath")
            } else {
                Log.d(TAG, "AI processing succeeded but no matching entry found to remove")
            }
        } else {
            // If failed, update or add to pending uploads with the error message
            val existingUpload = pendingUploads[filePath]
            
            if (existingUpload != null) {
                // Update existing entry with new error message
                val updatedUpload = existingUpload.copy(
                    failureReason = message ?: "Unknown AI processing error",
                    fileId = fileId ?: existingUpload.fileId
                )
                pendingUploads[filePath] = updatedUpload
                Log.d(TAG, "Updated existing AI processing entry: $filePath")
            } else if (fileId != null) {
                // Try to find by fileId if direct path lookup failed
                val foundByFileId = findByFileId(fileId)
                if (foundByFileId != null) {
                    // Update existing entry found by fileId
                    val updatedUpload = foundByFileId.copy(
                        failureReason = message ?: "Unknown AI processing error"
                    )
                    pendingUploads[foundByFileId.filePath] = updatedUpload
                    Log.d(TAG, "Updated existing AI processing entry found by fileId: ${foundByFileId.filePath}")
                } else {
                    // Create a new entry if nothing found
                    val displayName = "Processing_$fileId"
                    addPendingUploadByPath(
                        context,
                        displayName,
                        "$fileId.m4a", // Consistent path format for AI processing tasks
                        PendingUpload.UploadType.AI_PROCESSING,
                        fileId,
                        message ?: "Unknown AI processing error"
                    )
                    Log.d(TAG, "Added new AI processing entry as no existing one found")
                }
            } else {
                // Create a new entry if it doesn't exist and no fileId provided
                val displayName = File(filePath).name
                addPendingUploadByPath(
                    context,
                    displayName,
                    filePath,
                    PendingUpload.UploadType.AI_PROCESSING,
                    fileId,
                    message ?: "Unknown AI processing error"
                )
                Log.d(TAG, "Added new AI processing entry: $filePath")
            }
            Log.d(TAG, "AI processing failed, kept in pending list")
        }
        
        // Save changes and update flow
        saveToPrefs(context)
        updateFlow()
    }
    
    /**
     * Find a pending upload by fileId
     */
    private fun findByFileId(fileId: String): PendingUpload? {
        return pendingUploads.values.firstOrNull { it.fileId == fileId }
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
            
            // Map to track uploads by fileId to detect duplicates
            val fileIdMap = mutableMapOf<String, PendingUpload>()
            
            // First add all uploads and track by fileId if available
            loadedUploads.forEach { upload ->
                pendingUploads[upload.filePath] = upload
                
                // Track uploads by fileId to detect duplicates
                if (upload.fileId != null && upload.fileId.isNotEmpty()) {
                    val existing = fileIdMap[upload.fileId]
                    if (existing == null || existing.timestamp < upload.timestamp) {
                        // Keep the newer one if duplicate
                        fileIdMap[upload.fileId] = upload
                    }
                }
            }
            
            // Remove duplicates keeping only the newest one for each fileId
            if (fileIdMap.isNotEmpty()) {
                // Identify and remove duplicates (older entries with same fileId)
                pendingUploads.entries.removeIf { entry ->
                    val upload = entry.value
                    upload.fileId != null && 
                    fileIdMap[upload.fileId] != null && 
                    fileIdMap[upload.fileId] !== upload // Not the same instance
                }
                
                Log.d(TAG, "Removed ${loadedUploads.size - pendingUploads.size} duplicate fileId entries")
            }
            
            // Only clean up uploads that have no fileId AND the local file doesn't exist
            val iterator = pendingUploads.entries.iterator()
            while (iterator.hasNext()) {
                val entry = iterator.next()
                val file = File(entry.key)
                val upload = entry.value
                
                // Only remove if the file doesn't exist AND we have no fileId to retry with
                if (!file.exists() && upload.fileId == null) {
                    Log.d(TAG, "Removing pending upload with no file and no fileId: ${upload.fileName}")
                    iterator.remove()
                } else if (!file.exists()) {
                    // File doesn't exist but we have a fileId, so we can still retry AI processing
                    Log.d(TAG, "Keeping pending upload despite missing file because it has fileId: ${upload.fileName}")
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
