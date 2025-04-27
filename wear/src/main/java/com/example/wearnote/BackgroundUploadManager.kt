package com.example.wearnote

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Manages background uploads and persists their state between app restarts
 */
object BackgroundUploadManager {
    private const val TAG = "BackgroundUploadManager"
    private const val PREFS_NAME = "upload_manager_prefs"
    private const val KEY_PENDING_UPLOADS = "pending_uploads"
    
    // In-memory tracking of uploads
    private val pendingUploads = ConcurrentHashMap<String, Boolean>() // filename -> isCompleted
    
    /**
     * Adds a new file to the upload queue
     */
    fun queueUpload(context: Context, filePath: String) {
        pendingUploads[filePath] = false
        savePendingUploads(context)
        Log.d(TAG, "Added file to upload queue: $filePath")
    }
    
    /**
     * Marks an upload as completed
     */
    fun markUploadComplete(context: Context, filePath: String, success: Boolean) {
        pendingUploads[filePath] = true
        savePendingUploads(context)
        Log.d(TAG, "Marked upload complete (success=$success): $filePath")
    }
    
    /**
     * Returns the number of pending uploads
     */
    fun getPendingUploadCount(): Int {
        return pendingUploads.count { !it.value }
    }
    
    /**
     * Checks if there are any pending uploads
     */
    fun hasPendingUploads(): Boolean {
        return pendingUploads.any { !it.value }
    }
    
    /**
     * Load pending uploads from persistent storage
     */
    fun loadPendingUploads(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val uploadSet = prefs.getStringSet(KEY_PENDING_UPLOADS, emptySet()) ?: emptySet()
        
        pendingUploads.clear()
        for (entry in uploadSet) {
            if (entry.contains(":")) {
                val (path, completed) = entry.split(":")
                pendingUploads[path] = completed.toBoolean()
            }
        }
        
        Log.d(TAG, "Loaded ${pendingUploads.size} pending uploads, ${getPendingUploadCount()} active")
    }
    
    /**
     * Save pending uploads to persistent storage
     */
    private fun savePendingUploads(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val uploadSet = pendingUploads.map { "${it.key}:${it.value}" }.toSet()
        prefs.edit().putStringSet(KEY_PENDING_UPLOADS, uploadSet).apply()
    }
    
    /**
     * Get the next file to upload, or null if none
     */
    fun getNextPendingUpload(): String? {
        return pendingUploads.entries.firstOrNull { !it.value }?.key
    }
    
    /**
     * Reset all pending uploads
     */
    fun reset(context: Context) {
        pendingUploads.clear()
        savePendingUploads(context)
    }
}
