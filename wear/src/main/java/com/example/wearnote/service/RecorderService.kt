package com.example.wearnote.service

import android.app.*
import android.content.Context
import android.content.Intent
import android.media.MediaRecorder
import android.net.Uri
import android.os.*
import android.util.Log
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.example.wearnote.MainActivity
import com.example.wearnote.R
import com.example.wearnote.service.GoogleDriveUploader  // Changed from util to service package
import com.example.wearnote.service.PendingUploadsManager  // Changed from util to service package
import com.example.wearnote.model.PendingUpload
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.api.client.googleapis.extensions.android.gms.auth.GoogleAccountCredential
import com.google.api.client.http.javanet.NetHttpTransport
import com.google.api.client.json.gson.GsonFactory
import com.google.api.services.drive.Drive
import com.google.api.services.drive.DriveScopes
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class RecorderService : Service() {
    companion object {
        private const val TAG = "RecorderService"
        private const val NOTIFICATION_ID = 1
        private const val CHANNEL_ID = "RecorderServiceChannel"
        private const val NOTIFICATION_ID_AI_PROCESS = 3000
        private const val DEBUG_TAG = "RecorderServiceDebug" // New debug tag

        const val ACTION_START_RECORDING = "com.example.wearnote.ACTION_START_RECORDING"
        const val ACTION_STOP_RECORDING = "com.example.wearnote.ACTION_STOP_RECORDING"
        const val ACTION_PAUSE_RECORDING = "com.example.wearnote.ACTION_PAUSE_RECORDING"
        const val ACTION_RESUME_RECORDING = "com.example.wearnote.ACTION_RESUME_RECORDING"
        const val ACTION_DISCARD_RECORDING = "com.example.wearnote.ACTION_DISCARD_RECORDING"
        const val ACTION_FORCE_STOP = "com.example.wearnote.ACTION_FORCE_STOP"
        const val ACTION_CHECK_UPLOAD_STATUS = "com.example.wearnote.ACTION_CHECK_UPLOAD_STATUS"
        const val ACTION_START_AI_PROCESSING = "com.example.wearnote.ACTION_START_AI_PROCESSING" // New action
        const val ACTION_UPLOAD_AND_PROCESS = "com.example.wearnote.ACTION_UPLOAD_AND_PROCESS"

        private val pendingUploads = ConcurrentHashMap<String, Boolean>()

        // Create a global processing scope that is not tied to the service lifecycle
        private val processingScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

        // Track active processing jobs
        private val isProcessingActive = AtomicBoolean(false)

        // This keeps track of files being processed
        private val processingFiles = ConcurrentHashMap<String, File>()
    }

    private var mediaRecorder: MediaRecorder? = null
    private lateinit var outputFile: File
    private var isRecording = false
    private var isPaused = false
    private var wakeLock: PowerManager.WakeLock? = null

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val client = OkHttpClient.Builder()
        .connectTimeout(120, TimeUnit.SECONDS)  // 2 minutes for connection
        .readTimeout(300, TimeUnit.SECONDS)     // 5 minutes for response reading
        .writeTimeout(60, TimeUnit.SECONDS)     // 1 minute for request writing
        .build()

    private val API_URL = "http://140.118.123.107:5000/process"

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "WearNote::RecorderWakeLock"
        ).apply {
            // Ensure the wake lock doesn't time out
            setReferenceCounted(false)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        releaseWakeLock()

        // Always release MediaRecorder resource if it exists when service is destroyed
        if (mediaRecorder != null) {
            try {
                if (isRecording) {
                    mediaRecorder?.stop()
                }
                mediaRecorder?.release()
                mediaRecorder = null
                Log.d(TAG, "MediaRecorder released in onDestroy")
            } catch (e: Exception) {
                Log.e(TAG, "Error releasing MediaRecorder in onDestroy", e)
            }
        }

        serviceScope.cancel() // Cancel only service scope, not processing scope
        Log.d(TAG, "Service onDestroy")
        Log.d(TAG, "Recorder resources released, job cancelled.")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "onStartCommand received: ${intent?.action}")

        // Special handling for foreground service restarts to maintain pause state
        if (intent?.action == null && isPaused) {
            Log.d(DEBUG_TAG, "Service restarted without action while paused - maintaining pause state")
            startForeground(NOTIFICATION_ID, createNotification("Recording paused"))
            return START_STICKY
        }

        when (intent?.action) {
            ACTION_START_RECORDING -> startRecording()
            ACTION_STOP_RECORDING -> stopRecordingAndUpload()
            ACTION_PAUSE_RECORDING -> pauseRecording()
            ACTION_RESUME_RECORDING -> resumeRecording()
            ACTION_DISCARD_RECORDING -> discardRecording()  // Add this handler
            ACTION_CHECK_UPLOAD_STATUS -> checkUploadStatus()
            ACTION_START_AI_PROCESSING -> {
                val fileId = intent.getStringExtra("file_id")
                val isRetry = intent.getBooleanExtra("is_retry", false)
                
                if (fileId != null) {
                    processingScope.launch {
                        sendToAIProcessing(fileId, isRetry)
                    }
                } else {
                    Log.e(TAG, "Missing fileId for AI processing")
                }
            }
            ACTION_UPLOAD_AND_PROCESS -> {
                val filePath = intent.getStringExtra("file_path")
                if (filePath != null) {
                    val file = File(filePath)
                    if (file.exists()) {
                        // Start foreground notification to keep service alive
                        startForeground(NOTIFICATION_ID, createNotification("Uploading file..."))
                        
                        // Launch in service scope to handle the upload
                        serviceScope.launch {
                            // Use GoogleDriveUploader directly
                            val fileId = GoogleDriveUploader.upload(
                                this@RecorderService, 
                                file
                            ) { status ->
                                // Update notification with current status
                                updateNotification(status)
                            }
                            
                            // Process result
                            if (fileId != null) {
                                Log.i(TAG, "Upload successful! Google Drive File ID: $fileId")
                                
                                // Send HTTP POST to the AI processing endpoint
                                sendToAIProcessing(fileId, false)
                                notifyUploadComplete(fileId)
                            } else {
                                // Error handling already done in GoogleDriveUploader.upload
                                notifyUploadComplete(null)
                            }
                        }
                    } else {
                        Log.e(TAG, "File not found: $filePath")
                    }
                } else {
                    Log.e(TAG, "No file path provided")
                }
            }
            else -> Log.w(TAG, "Unknown action received or null intent")
        }
        return START_STICKY
    }

    private fun discardRecording() {
        if (!isRecording && mediaRecorder == null) {
            Log.w(TAG, "No active recording to discard")
            notifyRecordingDiscarded()
            stopSelf()
            return
        }

        Log.d(TAG, "Discarding recording...")
        try {
            if (mediaRecorder != null) {
                try {
                    if (isRecording && !isPaused) {
                        mediaRecorder?.stop()
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error stopping recorder", e)
                }

                mediaRecorder?.reset()
                mediaRecorder?.release()
                mediaRecorder = null
            }

            isRecording = false
            isPaused = false

            if (::outputFile.isInitialized && outputFile.exists()) {
                if (outputFile.delete()) {
                    Log.d(TAG, "Deleted recording file: ${outputFile.absolutePath}")
                } else {
                    Log.e(TAG, "Failed to delete recording file: ${outputFile.absolutePath}")
                }
            }

            notifyRecordingDiscarded()

        } catch (e: Exception) {
            Log.e(TAG, "Error discarding recording", e)
            notifyRecordingDiscarded()
        } finally {
            releaseWakeLock()
            stopSelf()
        }
    }

    private fun notifyRecordingDiscarded() {
        val intent = Intent(MainActivity.ACTION_RECORDING_STATUS).apply {
            putExtra(MainActivity.EXTRA_STATUS, MainActivity.STATUS_RECORDING_DISCARDED)
        }
        intent.addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
        Log.d(TAG, "Sending recording discarded broadcast")
        sendBroadcast(intent)
    }

    private fun checkUploadStatus() {
        if (pendingUploads.isNotEmpty()) {
            val notification = NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Uploads in Progress")
                .setContentText("${pendingUploads.count { !it.value }} files uploading")
                .setSmallIcon(R.drawable.ic_mic)
                .build()

            startForeground(NOTIFICATION_ID, notification)

            val intent = Intent(MainActivity.ACTION_RECORDING_STATUS).apply {
                putExtra(MainActivity.EXTRA_STATUS, "uploads_pending")
                putExtra("count", pendingUploads.count { !it.value })
            }
            intent.addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
            sendBroadcast(intent)
        }
    }

    private fun startRecording() {
        if (isRecording) {
            Log.w(TAG, "Recording already in progress")
            return
        }

        // Don't restart recording if we're in paused state
        if (isPaused) {
            Log.d(DEBUG_TAG, "startRecording called while paused - ignoring to maintain pause state")
            return
        }

        Log.d(TAG, "Starting recording...")
        startForeground(NOTIFICATION_ID, createNotification("Recording..."))

        // Acquire wake lock with no timeout to keep recording in background
        if (wakeLock?.isHeld == false) {
            wakeLock?.acquire(0) // 0 means acquire indefinitely
            Log.d(TAG, "Wake lock acquired indefinitely for recording")
        }

        try {
            outputFile = createOutputFile()
            Log.i(TAG, "Audio file will be saved at: ${outputFile.absolutePath}")

            mediaRecorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(this)
            } else {
                @Suppress("DEPRECATION")
                MediaRecorder()
            }

            // Configure MediaRecorder with settings known to support pause/resume
            mediaRecorder?.apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                // AAC audio in MP4 container is known to support pause
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioEncodingBitRate(128000)
                setAudioSamplingRate(44100)
                setOutputFile(outputFile.absolutePath)
                setMaxDuration(24 * 60 * 60 * 1000) // Set a very long max duration (24 hours)

                // Important: On some devices, setting these audio parameters improves pause support
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    // Set audio channels for better compatibility
                    setAudioChannels(1) // Mono recording
                }

                try {
                    prepare()
                    start()
                    isRecording = true
                    isPaused = false
                    Log.d(TAG, "Recording started successfully")
                    
                    // Send confirmation broadcast that recording has actually started
                    Handler(Looper.getMainLooper()).post {
                        val intent = Intent(MainActivity.ACTION_RECORDING_STATUS).apply {
                            putExtra(MainActivity.EXTRA_STATUS, MainActivity.STATUS_RECORDING_STARTED)
                        }
                        intent.addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
                        sendBroadcast(intent)
                        Log.d(TAG, "Broadcast sent: Recording started confirmation")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to start media recorder", e)
                    throw e
                }
            }
        } catch (e: IOException) {
            Log.e(TAG, "MediaRecorder prepare() failed", e)
            stopSelf()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start recording", e)
            stopSelf()
        }
    }

    private fun pauseRecording() {
        Log.d(TAG, "Attempting to pause recording: isRecording=$isRecording, isPaused=$isPaused")

        if (!isRecording || isPaused) {
            Log.w(TAG, "Cannot pause: Not recording or already paused.")
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            try {
                // Capture current state before pausing for debugging
                Log.d(DEBUG_TAG, "About to pause and release MediaRecorder")

                // Actually stop and release the MediaRecorder to free microphone
                try {
                    mediaRecorder?.stop()
                    Log.d(DEBUG_TAG, "MediaRecorder stop() successful")
                } catch (e: Exception) {
                    Log.e(TAG, "Error stopping mediaRecorder during pause", e)
                }

                try {
                    mediaRecorder?.release()
                    Log.d(DEBUG_TAG, "MediaRecorder release() successful")
                } catch (e: Exception) {
                    Log.e(TAG, "Error releasing mediaRecorder during pause", e)
                }

                mediaRecorder = null
                isPaused = true
                // Note: isRecording remains true to indicate we're in a paused state

                // Log confirmation message
                Log.d(DEBUG_TAG, "Recording definitely paused and microphone released")
                updateNotification("Recording paused")

                // Send a broadcast to update any UI components
                val intent = Intent(MainActivity.ACTION_RECORDING_STATUS).apply {
                    putExtra("recording_state", "paused")
                }
                sendBroadcast(intent)

                // Extra verification that we're truly paused
                verifyPausedState()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to pause recording", e)
                // Try to recover by continuing recording
                isPaused = false
                updateNotification("Recording... (pause failed)")
                Toast.makeText(applicationContext, "Failed to pause recording", Toast.LENGTH_SHORT).show()
            }
        } else {
            Log.w(TAG, "Pause/Resume not supported on this Android version (requires API 24+)")
        }
    }

    private fun verifyPausedState() {
        if (isPaused) {
            if (mediaRecorder != null) {
                Log.e(DEBUG_TAG, "ERROR: MediaRecorder should be null when paused but isn't!")
                try {
                    mediaRecorder?.release()
                    mediaRecorder = null
                } catch (e: Exception) {
                    Log.e(DEBUG_TAG, "Failed to release lingering MediaRecorder", e)
                }
            } else {
                Log.d(DEBUG_TAG, "Verified paused state correctly: MediaRecorder is null")
            }
        }
    }

    private fun resumeRecording() {
        Log.d(TAG, "Attempting to resume recording: isRecording=$isRecording, isPaused=$isPaused")

        if (!isRecording || !isPaused) {
            Log.w(TAG, "Cannot resume: Not recording or not paused.")
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            try {
                // Log action before attempting
                Log.d(TAG, "About to resume recording by creating new MediaRecorder")

                // Create a new MediaRecorder instance that will append to the existing file
                mediaRecorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    MediaRecorder(this)
                } else {
                    @Suppress("DEPRECATION")
                    MediaRecorder()
                }

                // Setup recorder to append to existing file
                mediaRecorder?.apply {
                    setAudioSource(MediaRecorder.AudioSource.MIC)
                    setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                    setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                    setAudioEncodingBitRate(128000)
                    setAudioSamplingRate(44100)
                    setOutputFile(outputFile.absolutePath)

                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        setAudioChannels(1) // Mono recording
                    }

                    try {
                        prepare()
                        start()
                        isPaused = false
                        Log.d(TAG, "MediaRecorder successfully recreated and started")
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to start mediaRecorder during resume", e)
                        throw e
                    }
                }

                updateNotification("Recording...")

                // Send a broadcast to update any UI components
                val intent = Intent(MainActivity.ACTION_RECORDING_STATUS).apply {
                    putExtra("recording_state", "recording")
                }
                sendBroadcast(intent)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to resume recording", e)
                // Try to recover
                isPaused = true
                updateNotification("Paused (resume failed)")
                Toast.makeText(applicationContext, "Failed to resume recording", Toast.LENGTH_SHORT).show()
            }
        } else {
            Log.w(TAG, "Pause/Resume not supported on this Android version (requires API 24+)")
        }
    }

    private fun stopRecordingAndUpload() {
        if (!isRecording) {
            Log.w(TAG, "Recording not active, cannot stop.")
            stopSelf()
            return
        }

        Log.d(TAG, "Stopping recording...")
        try {
            mediaRecorder?.stop()   
            mediaRecorder?.release()
            mediaRecorder = null
            isRecording = false
            isPaused = false
            Log.d(TAG, "Recording stopped.")

            if (::outputFile.isInitialized && outputFile.exists() && outputFile.length() > 100) {
                Log.d(TAG, "Recording saved: ${outputFile.absolutePath}, size: ${outputFile.length()} bytes. Starting upload process.")

                pendingUploads[outputFile.name] = false

                serviceScope.launch {
                    val file = outputFile // Create a local reference to avoid closure issues
                    
                    // Use GoogleDriveUploader directly with notification updates
                    val fileId = GoogleDriveUploader.upload(
                        this@RecorderService, 
                        file
                    ) { status ->
                        // Update notification with current status
                        updateNotification(status)
                    }
                    
                    // Process result
                    if (fileId != null) {
                        Log.i(TAG, "Upload successful! Google Drive File ID: $fileId")
                        
                        // Send HTTP POST to the AI processing endpoint
                        sendToAIProcessing(fileId, false)
                        notifyUploadComplete(fileId)
                    } else {
                        // Error handling already done in GoogleDriveUploader.upload
                        notifyUploadComplete(null)
                    }
                }

                updateNotification("Starting upload process...")
            } else {
                notifyUploadComplete(null)
                stopSelf()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping recording", e)
            notifyUploadComplete(null)
            stopSelf()
        }
    }

    private fun sendToAIProcessing(fileId: String, isRetry: Boolean = false) {
        try {
            if (isRetry) {
                Log.d(TAG, "Retrying AI processing for fileId: $fileId")
            }
            
            // Show notification that AI analysis has begun
            val notificationBuilder = NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_mic)
                .setContentTitle("AI Analysis")
                .setContentText("Starting AI analysis of your recording...")
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            
            val notificationManager = NotificationManagerCompat.from(this)
            notificationManager.notify(NOTIFICATION_ID_AI_PROCESS, notificationBuilder.build())

            isProcessingActive.set(true)

            // Create JSON payload
            val jsonObject = JSONObject()
            jsonObject.put("file_id", fileId)
            // Add retry flag if it's a retry
            if (isRetry) {
                jsonObject.put("is_retry", true)
            }
            
            val requestBody = jsonObject.toString().toRequestBody("application/json".toMediaType())

            // Create and execute request
            val request = Request.Builder()
                .url(API_URL)
                .post(requestBody)
                .build()

            // Start processing in the global scope that won't be cancelled when service is destroyed
            processingScope.launch(Dispatchers.IO) {
                try {
                    // Make sure service stays alive during processing
                    startForeground(NOTIFICATION_ID_AI_PROCESS, createProcessingNotification())

                    // Create a new OkHttpClient with shorter timeout as requested
                    val processingClient = OkHttpClient.Builder()
                        .connectTimeout(5000, TimeUnit.MILLISECONDS)  // 5 seconds timeout
                        .readTimeout(5000, TimeUnit.MILLISECONDS)     // 5 seconds timeout (changed from 10 minutes)
                        .writeTimeout(5000, TimeUnit.MILLISECONDS)    // 5 seconds timeout (changed from 60 seconds)
                        .build()

                    // Execute request synchronously
                    val response = processingClient.newCall(request).execute()
                    val responseBody = response.body?.string()

                    if (responseBody != null) {
                        try {
                            val jsonResponse = JSONObject(responseBody)
                            val success = jsonResponse.optBoolean("success", false)
                            
                            // Use consistent file path format for AI processing tasks
                            val aiProcessingFilePath = "$fileId.m4a"
                            
                            if (success) {
                                // Successfully submitted for processing
                                val jobId = jsonResponse.optString("job_id", "")
                                val message = jsonResponse.optString("message", "Processing started")
                                Log.d(TAG, "AI processing job submitted: $jobId, message: $message")
                                
                                // Handle success in PendingUploadsManager (remove from pending list)
                                PendingUploadsManager.handleAIProcessingResult(
                                    this@RecorderService,
                                    aiProcessingFilePath,
                                    fileId,
                                    true,
                                    message
                                )
                                
                                // Update notification to show success
                                showProcessingSuccessNotification(message)
                            } else {
                                // Processing failed with a clear error
                                val errorMessage = jsonResponse.optString("error", "Unknown error")
                                Log.e(TAG, "AI processing failed: $errorMessage")
                                
                                // Always add to pending uploads when AI processing fails
                                PendingUploadsManager.handleAIProcessingResult(
                                    this@RecorderService,
                                    aiProcessingFilePath,
                                    fileId,
                                    false,
                                    errorMessage
                                )
                                
                                showProcessingFailureNotification(errorMessage)
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Error parsing AI processing response", e)
                            
                            // Use consistent file path format for AI processing tasks
                            val aiProcessingFilePath = "$fileId.m4a"
                            
                            // Add to pending uploads with the exception message
                            PendingUploadsManager.handleAIProcessingResult(
                                this@RecorderService,
                                aiProcessingFilePath,
                                fileId,
                                false,
                                "JSON parsing error: ${e.message}"
                            )
                            
                            showProcessingFailureNotification("Response parsing error")
                        }
                    } else if (response.isSuccessful) {
                        // Empty success response
                        Log.d(TAG, "AI processing request successful but empty response")
                        
                        // Use consistent file path format for AI processing tasks
                        val aiProcessingFilePath = "$fileId.m4a"
                        
                        PendingUploadsManager.handleAIProcessingResult(
                            this@RecorderService,
                            aiProcessingFilePath,
                            fileId,
                            true,
                            "Processing started"
                        )
                    } else {
                        // HTTP error
                        Log.e(TAG, "AI processing request failed with code: ${response.code}")
                        
                        // Always add to pending uploads for retry
                        PendingUploadsManager.handleAIProcessingResult(
                            this@RecorderService,
                            "$fileId.m4a",
                            fileId,
                            false,
                            "Server error (${response.code})"
                        )
                        showProcessingFailureNotification("Server error (${response.code})")
                    }
                } catch (e: Exception) {
                    // Add to pending uploads without requiring a local file
                    PendingUploadsManager.addPendingUploadByPath(
                        this@RecorderService,
                        "Processing_$fileId.m4a",
                        "$fileId.m4a",
                        PendingUpload.UploadType.AI_PROCESSING,
                        fileId,
                        e.message ?: "Network error"
                    )
                    
                    Log.e(TAG, "Error during AI processing", e)
                    showProcessingFailureNotification("Error: ${e.message}")
                } finally {
                    isProcessingActive.set(false)
                }
            }
        } catch (e: Exception) {
            // Use a dummy filename based on fileId when no file is available
            PendingUploadsManager.addPendingUploadByPath(
                this@RecorderService,
                "Processing_$fileId.m4a",
                "$fileId.m4a",
                PendingUpload.UploadType.AI_PROCESSING,
                fileId,
                e.message ?: "Error preparing request"
            )
            
            Log.e(TAG, "Error sending file for AI processing", e)
            showProcessingFailureNotification("Error: ${e.message}")
        }
    }

    private fun createProcessingNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("WearNote AI Processing")
            .setContentText("Processing audio in background...")
            .setSmallIcon(R.drawable.ic_mic)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()
    }
     private fun showProcessingFailureNotification(errorMessage: String) {
        try {
            val notificationBuilder = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_mic)
                .setContentTitle("AI Analysis Failed")
                .setContentText(errorMessage)
                .setStyle(NotificationCompat.BigTextStyle().bigText(errorMessage))
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)

            val notificationManager = ContextCompat.getSystemService(
                applicationContext,
                NotificationManager::class.java
            )
            notificationManager?.notify(NOTIFICATION_ID_AI_PROCESS, notificationBuilder.build())
        } catch (e: Exception) {
            Log.e(TAG, "Error showing failure notification", e)
        }
    }

    private fun showProcessingSuccessNotification(message: String) {
        try {
            val notificationBuilder = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_mic)
                .setContentTitle("AI Analysis Success")
                .setContentText(message)
                .setStyle(NotificationCompat.BigTextStyle().bigText(message))
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)

            val notificationManager = ContextCompat.getSystemService(
                applicationContext,
                NotificationManager::class.java
            )
            notificationManager?.notify(NOTIFICATION_ID_AI_PROCESS, notificationBuilder.build())
        } catch (e: Exception) {
            Log.e(TAG, "Error showing success notification", e)
        }
    }

    private fun notifyUploadComplete(fileId: String?) {
        val intent = Intent(MainActivity.ACTION_RECORDING_STATUS).apply {
            putExtra(MainActivity.EXTRA_STATUS, MainActivity.STATUS_UPLOAD_COMPLETED)
            putExtra(MainActivity.EXTRA_FILE_ID, fileId ?: "")
        }

        intent.addFlags(Intent.FLAG_RECEIVER_FOREGROUND)

        Handler(Looper.getMainLooper()).post {
            try {
                sendBroadcast(intent)
                Log.d(TAG, "Broadcast sent successfully for fileId: ${fileId ?: "null"}")
            } catch (e: Exception) {
                Log.e(TAG, "Error sending broadcast", e)
            }
        }
    }

    private fun createOutputFile(): File {
        val mediaDir = File(getExternalFilesDir(null), "Music")
        if (!mediaDir.exists()) {
            mediaDir.mkdirs()
        }
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        return File(mediaDir, "REC_${timestamp}.m4a")
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                CHANNEL_ID,
                "Recording Service Channel",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(serviceChannel)
        }
    }

    private fun createNotification(contentText: String): Notification {
        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, notificationIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stopIntent = Intent(this, RecorderService::class.java).apply { action = ACTION_STOP_RECORDING }
        val stopPendingIntent = PendingIntent.getService(this, 1, stopIntent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val pauseResumeIntent = Intent(this, RecorderService::class.java).apply { action = if (isPaused) ACTION_RESUME_RECORDING else ACTION_PAUSE_RECORDING }
        val pauseResumePendingIntent = PendingIntent.getService(this, 2, pauseResumeIntent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val pauseResumeActionText = if (isPaused) "Resume" else "Pause"
        val pauseResumeIcon = if (isPaused) R.drawable.ic_play else R.drawable.ic_pause
        val notificationBuilder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("WearNote Recording")
            .setContentText(contentText)
            .setSmallIcon(R.drawable.ic_mic)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setSilent(true)
            .addAction(R.drawable.ic_stop, "Stop", stopPendingIntent)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            notificationBuilder.addAction(pauseResumeIcon, pauseResumeActionText, pauseResumePendingIntent)
        }
        return notificationBuilder.build()
    }

    private fun updateNotification(contentText: String) {
        val notification = createNotification(contentText)
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun releaseWakeLock() {
        if (wakeLock?.isHeld == true) {
            try {
                wakeLock?.release()
                Log.d(TAG, "WakeLock released")
            } catch (e: Exception) {
                Log.e(TAG, "Error releasing wake lock", e)
            }
        }
    }
}
