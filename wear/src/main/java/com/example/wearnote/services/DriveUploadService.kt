package com.example.wearnote.services

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.wearnote.R
import com.example.wearnote.auth.GoogleSignInHandler
import com.example.wearnote.util.DriveHelper
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.api.client.extensions.android.http.AndroidHttp
import com.google.api.client.googleapis.extensions.android.gms.auth.GoogleAccountCredential
import com.google.api.client.http.FileContent
import com.google.api.client.http.InputStreamContent
import com.google.api.client.json.gson.GsonFactory
import com.google.api.services.drive.Drive
import com.google.api.services.drive.DriveScopes
import com.google.api.services.drive.model.File as DriveFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Collections
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

class DriveUploadService : Service() {

    companion object {
        private const val TAG = "DriveUploadService"
        private const val NOTIFICATION_ID = 2001
        private const val CHANNEL_ID = "upload_channel"
        const val EXTRA_FILE_PATH = "file_path"
        
        // Server URL for processing
        private const val SERVER_URL = "http://140.118.123.107:5000/process"
        
        // Database name for pending uploads
        private const val PENDING_UPLOADS_PREFS = "pending_uploads"
    }
    
    private var uploadJob: Job? = null
    private val pendingUploads = mutableListOf<String>()
    private var currentlyUploading = false
    
    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        
        // Load any previously pending uploads
        loadPendingUploads()
    }
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        intent?.getStringExtra(EXTRA_FILE_PATH)?.let { filePath ->
            // Add to pending uploads
            addToPendingUploads(filePath)
            
            // Start the upload process
            if (!currentlyUploading) {
                startUploads()
            }
        } ?: stopSelf()
        
        return START_STICKY
    }
    
    override fun onBind(intent: Intent?): IBinder? {
        return null
    }
    
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = "WearNote Uploads"
            val importance = NotificationManager.IMPORTANCE_LOW
            val channel = NotificationChannel(CHANNEL_ID, name, importance).apply {
                description = "Upload notifications"
            }
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }
    
    private fun createNotification(status: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("WearNote")
            .setContentText(status)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()
    }
    
    private fun loadPendingUploads() {
        val prefs = getSharedPreferences(PENDING_UPLOADS_PREFS, Context.MODE_PRIVATE)
        val pendingSet = prefs.getStringSet("pending", setOf()) ?: setOf()
        pendingUploads.addAll(pendingSet)
        
        // Remove files that no longer exist
        pendingUploads.removeAll { !File(it).exists() }
    }
    
    private fun savePendingUploads() {
        val prefs = getSharedPreferences(PENDING_UPLOADS_PREFS, Context.MODE_PRIVATE)
        prefs.edit().putStringSet("pending", pendingUploads.toSet()).apply()
    }
    
    private fun addToPendingUploads(filePath: String) {
        if (!pendingUploads.contains(filePath) && File(filePath).exists()) {
            pendingUploads.add(filePath)
            savePendingUploads()
        }
    }
    
    private fun removePendingUpload(filePath: String) {
        pendingUploads.remove(filePath)
        savePendingUploads()
    }
    
    private fun startUploads() {
        if (pendingUploads.isEmpty()) {
            stopSelf()
            return
        }
        
        // Check network connectivity before starting upload
        if (!isNetworkAvailable()) {
            // Schedule a retry later
            scheduleRetry()
            return
        }

        currentlyUploading = true
        
        // Start foreground service with notification
        startForeground(NOTIFICATION_ID, createNotification("Uploading recordings..."))
        
        uploadJob = CoroutineScope(Dispatchers.IO).launch {
            while (pendingUploads.isNotEmpty()) {
                val filePath = pendingUploads.first()
                val file = File(filePath)
                
                if (!file.exists()) {
                    // Skip files that don't exist
                    removePendingUpload(filePath)
                    continue
                }
                
                try {
                    // Update notification
                    updateNotification("Uploading: ${file.name}")
                    
                    // Upload to Drive
                    val fileId = uploadFileToDrive(file)
                    
                    if (fileId != null) {
                        // Notify server
                        notifyServer(fileId)
                        
                        // Remove from pending uploads
                        removePendingUpload(filePath)
                        
                        Log.d(TAG, "Successfully uploaded and processed: ${file.name}")
                    } else {
                        // Failed to upload, try again later
                        Log.e(TAG, "Failed to upload: ${file.name}")
                        updateNotification("Upload failed. Will retry later.")
                        break
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error during upload", e)
                    updateNotification("Upload error. Will retry later.")
                    break
                }
            }
            
            // If there are still pending uploads, schedule a retry
            if (pendingUploads.isNotEmpty()) {
                scheduleRetry()
            }
            
            currentlyUploading = false
            stopSelf()
        }
    }
    
    private suspend fun uploadFileToDrive(file: File): String? = withContext(Dispatchers.IO) {
        try {
            val googleSignInHandler = GoogleSignInHandler(this@DriveUploadService)
            val account = GoogleSignIn.getLastSignedInAccount(this@DriveUploadService) ?: return@withContext null
            
            // Check if we have the required scope
            if (!GoogleSignIn.hasPermissions(account, googleSignInHandler.getDriveScope())) {
                Log.e(TAG, "Missing Drive permissions")
                return@withContext null
            }
            
            // Set up Drive service
            val credential = GoogleAccountCredential.usingOAuth2(
                this@DriveUploadService,
                Collections.singleton(DriveScopes.DRIVE_FILE)
            )
            credential.selectedAccount = account.account
            credential.setSelectedAccount(account).setScopes(Collections.singleton(DriveHelper.getDriveScope()))
            
            val driveService = getDriveService(credential)
            
            // Create file metadata
            val fileMetadata = DriveFile()
                .setName(file.name)
                .setMimeType("audio/wav")
            
            // Upload file
            val content = FileContent("audio/wav", file)
            val uploadedFile = driveService.files().create(fileMetadata, content)
                .setFields("id")
                .execute()
            
            Log.d(TAG, "File uploaded to Drive with ID: ${uploadedFile.id}")
            return@withContext uploadedFile.id
        } catch (e: Exception) {
            Log.e(TAG, "Error uploading to Drive", e)
            return@withContext null
        }
    }
    
    private suspend fun notifyServer(fileId: String) = withContext(Dispatchers.IO) {
        try {
            // Update notification
            updateNotification("Notifying processing server...")
            
            val url = java.net.URL(SERVER_URL)
            val connection = url.openConnection() as java.net.HttpURLConnection
            
            // Configure connection
            connection.requestMethod = "POST"
            connection.setRequestProperty("Content-Type", "application/json")
            connection.doOutput = true
            
            // Create JSON payload
            val jsonPayload = JSONObject().apply {
                put("file_id", fileId)
            }
            
            // Send request
            val outputStream = connection.outputStream
            outputStream.write(jsonPayload.toString().toByteArray())
            outputStream.close()
            
            // Check response
            val responseCode = connection.responseCode
            if (responseCode in 200..299) {
                Log.d(TAG, "Server notified successfully")
            } else {
                Log.e(TAG, "Server notification failed with code: $responseCode")
            }
            
            connection.disconnect()
        } catch (e: Exception) {
            Log.e(TAG, "Error notifying server", e)
        }
    }
    
    private fun updateNotification(status: String) {
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(NOTIFICATION_ID, createNotification(status))
    }
    
    private fun scheduleRetry() {
        CoroutineScope(Dispatchers.IO).launch {
            // Wait for some time before retrying
            delay(TimeUnit.MINUTES.toMillis(5)) // 5-minute retry interval
            
            if (isNetworkAvailable() && !currentlyUploading && pendingUploads.isNotEmpty()) {
                val intent = Intent(this@DriveUploadService, DriveUploadService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(intent)
                } else {
                    startService(intent)
                }
            }
        }
    }
    
    private fun isNetworkAvailable(): Boolean {
        val connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val network = connectivityManager.activeNetwork ?: return false
            val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
            return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        } else {
            @Suppress("DEPRECATION")
            val networkInfo = connectivityManager.activeNetworkInfo ?: return false
            @Suppress("DEPRECATION")
            return networkInfo.isConnected
        }
    }
    
    override fun onDestroy() {
        uploadJob?.cancel()
        savePendingUploads()
        stopForeground(true)
        super.onDestroy()
    }

    // Add this function to initialize the Drive service
    private fun getDriveService(credential: GoogleAccountCredential): Drive {
        val transport = AndroidHttp.newCompatibleTransport()
        return Drive.Builder(
            transport,
            GsonFactory.getDefaultInstance(),
            credential
        )
        .setApplicationName("WearNote")
        .build()
    }
}