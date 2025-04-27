package com.example.wearnote.service

import android.app.*
import android.content.Context
import android.content.Intent
import android.media.MediaRecorder
import android.net.Uri
import android.os.*
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.example.wearnote.MainActivity
import com.example.wearnote.R
import com.example.wearnote.util.GoogleDriveUploader
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

        const val ACTION_START_RECORDING = "com.example.wearnote.ACTION_START_RECORDING"
        const val ACTION_STOP_RECORDING = "com.example.wearnote.ACTION_STOP_RECORDING"
        const val ACTION_PAUSE_RECORDING = "com.example.wearnote.ACTION_PAUSE_RECORDING"
        const val ACTION_RESUME_RECORDING = "com.example.wearnote.ACTION_RESUME_RECORDING"
        const val ACTION_DISCARD_RECORDING = "com.example.wearnote.ACTION_DISCARD_RECORDING"
        const val ACTION_FORCE_STOP = "com.example.wearnote.ACTION_FORCE_STOP"
        const val ACTION_CHECK_UPLOAD_STATUS = "com.example.wearnote.ACTION_CHECK_UPLOAD_STATUS"

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
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "WearNote::RecorderWakeLock")
    }

    override fun onDestroy() {
        super.onDestroy()
        releaseWakeLock()
        serviceScope.cancel() // Cancel only service scope, not processing scope
        Log.d(TAG, "Service onDestroy")
        Log.d(TAG, "Recorder resources released, job cancelled.")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "onStartCommand received: ${intent?.action}")
        when (intent?.action) {
            ACTION_START_RECORDING -> startRecording()
            ACTION_STOP_RECORDING -> stopRecordingAndUpload()
            ACTION_PAUSE_RECORDING -> pauseRecording()
            ACTION_RESUME_RECORDING -> resumeRecording()
            ACTION_DISCARD_RECORDING -> discardRecording()
            ACTION_FORCE_STOP -> forceStop()
            ACTION_CHECK_UPLOAD_STATUS -> checkUploadStatus()
            else -> Log.w(TAG, "Unknown action received or null intent")
        }
        return START_STICKY
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

        Log.d(TAG, "Starting recording...")
        startForeground(NOTIFICATION_ID, createNotification("Recording..."))
        wakeLock?.acquire(10 * 60 * 1000L)

        try {
            outputFile = createOutputFile()
            Log.i(TAG, "Audio file will be saved at: ${outputFile.absolutePath}")

            mediaRecorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(this)
            } else {
                @Suppress("DEPRECATION")
                MediaRecorder()
            }

            mediaRecorder?.apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioEncodingBitRate(128000)
                setAudioSamplingRate(44100)
                setOutputFile(outputFile.absolutePath)
                prepare()
                start()
            }
            isRecording = true
            isPaused = false
            Log.d(TAG, "Recording started")
        } catch (e: IOException) {
            Log.e(TAG, "MediaRecorder prepare() failed", e)
            stopSelf()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start recording", e)
            stopSelf()
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
                    uploadFile(outputFile)
                }

                updateNotification("Uploading in background...")
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

    private suspend fun uploadFile(file: File) {
        try {
            val account = GoogleSignIn.getLastSignedInAccount(this)
            if (account != null) {
                val credential = GoogleAccountCredential.usingOAuth2(
                    this, listOf(DriveScopes.DRIVE_FILE, DriveScopes.DRIVE)
                )
                credential.selectedAccount = account.account
                val driveService = Drive.Builder(
                    NetHttpTransport(),
                    GsonFactory.getDefaultInstance(),
                    credential
                ).setApplicationName(getString(R.string.app_name)).build()

                val fileId = GoogleDriveUploader.uploadToDrive(
                    this, file, driveService
                )

                if (fileId == null) {
                    Log.w(TAG, "Upload failed for ${file.name}")
                    pendingUploads[file.name] = true
                    updateNotification("Upload failed. File saved locally.")
                    notifyUploadComplete(null)
                } else {
                    Log.i(TAG, "Upload successful! Google Drive File ID: $fileId")
                    pendingUploads[file.name] = true

                    // Send HTTP POST to the AI processing endpoint
                    // Pass the local file reference to allow deletion after successful processing
                    sendToAIProcessing(fileId, file)

                    updateNotification("Upload successful!")
                    notifyUploadComplete(fileId)
                }
            } else {
                Log.w(TAG, "No Google account available for upload")
                pendingUploads[file.name] = true
                updateNotification("Upload failed: No Google account")
                notifyUploadComplete(null)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error during upload process", e)
            pendingUploads[file.name] = true
            updateNotification("Error during upload: ${e.message}")
            notifyUploadComplete(null)
        }

        if (pendingUploads.values.all { it }) {
            stopSelf()
        }
    }

    private fun sendToAIProcessing(fileId: String, localFile: File) {
        try {
            // Show notification that AI analysis has begun
            val notificationBuilder = NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_mic)
                .setContentTitle("AI Analysis")
                .setContentText("Starting AI analysis of your recording...")
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)

            val notificationManager = NotificationManagerCompat.from(this)
            notificationManager.notify(NOTIFICATION_ID_AI_PROCESS, notificationBuilder.build())

            // Keep track of the file being processed
            processingFiles[fileId] = localFile
            isProcessingActive.set(true)

            // Create JSON payload
            val jsonObject = JSONObject()
            jsonObject.put("file_id", fileId)
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

                    // Create a new OkHttpClient with even longer timeout
                    val processingClient = OkHttpClient.Builder()
                        .connectTimeout(180, TimeUnit.SECONDS)
                        .readTimeout(600, TimeUnit.SECONDS)  // 10 minutes timeout for processing
                        .writeTimeout(60, TimeUnit.SECONDS)
                        .build()

                    // Execute request synchronously
                    val response = processingClient.newCall(request).execute()
                    val responseBody = response.body?.string()

                    if (response.isSuccessful && responseBody != null) {
                        val jsonResponse = JSONObject(responseBody)
                        if (jsonResponse.optBoolean("success", false)) {
                            // Success case handling
                            val notionPageId = jsonResponse.optString("notion_page_id", "")
                            val notionPageUrl = jsonResponse.optString("notion_page_url", "")
                            val title = jsonResponse.optString("title", "Untitled Meeting")
                            val summary = jsonResponse.optString("summary", "No summary available")

                            // Extract todos if available
                            val todos = mutableListOf<String>()
                            val todosArray = jsonResponse.optJSONArray("todos")
                            if (todosArray != null) {
                                for (i in 0 until todosArray.length()) {
                                    todos.add(todosArray.getString(i))
                                }
                            }

                            Log.i(TAG, "AI processing completed successfully!")
                            Log.i(TAG, "Title: $title")
                            Log.i(TAG, "Summary: ${summary.take(100)}...")
                            Log.i(TAG, "Notion URL: $notionPageUrl")
                            Log.i(TAG, "Found ${todos.size} todos")

                            // Delete the local file after successful processing
                            try {
                                val fileToDelete = processingFiles.remove(fileId) ?: localFile
                                if (fileToDelete.exists()) {
                                    if (fileToDelete.delete()) {
                                        Log.i(TAG, "Local recording file deleted successfully: ${fileToDelete.absolutePath}")
                                    } else {
                                        Log.w(TAG, "Failed to delete local recording file: ${fileToDelete.absolutePath}")
                                    }
                                }
                            } catch (e: Exception) {
                                Log.e(TAG, "Error while trying to delete local file", e)
                            }

                            // Show success notification
                            try {
                                val notificationIntent = Intent(Intent.ACTION_VIEW, Uri.parse(notionPageUrl))
                                val pendingIntent = PendingIntent.getActivity(
                                    applicationContext,
                                    0,
                                    notificationIntent,
                                    PendingIntent.FLAG_IMMUTABLE
                                )

                                val successNotification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
                                    .setSmallIcon(R.drawable.ic_mic)
                                    .setContentTitle("Meeting Notes: $title")
                                    .setContentText("Tap to view your notes in Notion")
                                    .setStyle(
                                        NotificationCompat.BigTextStyle()
                                            .bigText("Summary: ${summary.take(200)}${if (summary.length > 200) "..." else ""}\n\nTap to open in Notion.")
                                    )
                                    .setPriority(NotificationCompat.PRIORITY_HIGH)
                                    .setContentIntent(pendingIntent)
                                    .setAutoCancel(true)
                                    .build()

                                val notificationManager = ContextCompat.getSystemService(
                                    applicationContext,
                                    NotificationManager::class.java
                                )
                                notificationManager?.notify(NOTIFICATION_ID_AI_PROCESS, successNotification)

                                // Add vibration
                                try {
                                    val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                                        val vibratorManager = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
                                        vibratorManager.defaultVibrator
                                    } else {
                                        @Suppress("DEPRECATION")
                                        getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
                                    }

                                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                        vibrator.vibrate(VibrationEffect.createOneShot(500, VibrationEffect.DEFAULT_AMPLITUDE))
                                    } else {
                                        @Suppress("DEPRECATION")
                                        vibrator.vibrate(500)
                                    }
                                } catch (e: Exception) {
                                    Log.e(TAG, "Error during vibration", e)
                                }
                            } catch (e: Exception) {
                                Log.e(TAG, "Error showing success notification", e)
                            }
                        } else {
                            // Error case in response
                            val errorMessage = jsonResponse.optString("error", "Unknown error occurred")
                            Log.e(TAG, "AI processing API returned error: $errorMessage")
                            showProcessingFailureNotification("Error: $errorMessage")
                        }
                    } else {
                        // HTTP error cases
                        when (response.code) {
                            500 -> {
                                Log.e(TAG, "AI processing server encountered an internal error (500)")
                                showProcessingFailureNotification("Server Error")
                            }
                            else -> {
                                Log.e(TAG, "AI processing request failed with code: ${response.code}")
                                showProcessingFailureNotification("Server error (${response.code})")
                            }
                        }
                    }
                    response.close()
                } catch (e: Exception) {
                    Log.e(TAG, "Error during AI processing", e)
                    showProcessingFailureNotification("Error: ${e.message}")
                } finally {
                    isProcessingActive.set(false)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error sending file for AI processing", e)
            showProcessingFailureNotification("Error: ${e.message}")  // Fixed: replaced updateNotificationForAIProcessingFailure with showProcessingFailureNotification
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

    private fun pauseRecording() {
        if (!isRecording || isPaused) {
            Log.w(TAG, "Cannot pause: Not recording or already paused.")
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            try {
                mediaRecorder?.pause()
                isPaused = true
                Log.d(TAG, "Recording paused")
                updateNotification("Paused")
            } catch (e: IllegalStateException) {
                Log.e(TAG, "Failed to pause MediaRecorder", e)
            }
        } else {
            Log.w(TAG, "Pause/Resume not supported on this Android version (requires API 24+)")
        }
    }

    private fun resumeRecording() {
        if (!isRecording || !isPaused) {
            Log.w(TAG, "Cannot resume: Not recording or not paused.")
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            try {
                mediaRecorder?.resume()
                isPaused = false
                Log.d(TAG, "Recording resumed")
                updateNotification("Recording...")
            } catch (e: IllegalStateException) {
                Log.e(TAG, "Failed to resume MediaRecorder", e)
            }
        } else {
            Log.w(TAG, "Pause/Resume not supported on this Android version (requires API 24+)")
        }
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

    private fun forceStop() {
        Log.d(TAG, "Force stopping service")
        try {
            if (mediaRecorder != null) {
                try {
                    if (isRecording && !isPaused) {
                        mediaRecorder?.stop()
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error stopping recorder during force stop", e)
                }

                mediaRecorder?.reset()
                mediaRecorder?.release()
                mediaRecorder = null
            }

            isRecording = false
            isPaused = false

            releaseWakeLock()
        } catch (e: Exception) {
            Log.e(TAG, "Error during force stop", e)
        } finally {
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

        val stopIntent = Intent(this, RecorderService::class.java).apply {
            action = ACTION_STOP_RECORDING
        }
        val stopPendingIntent = PendingIntent.getService(this, 1, stopIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

        val pauseResumeIntent = Intent(this, RecorderService::class.java).apply {
            action = if (isPaused) ACTION_RESUME_RECORDING else ACTION_PAUSE_RECORDING
        }
        val pauseResumePendingIntent = PendingIntent.getService(this, 2, pauseResumeIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
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
            wakeLock?.release()
            Log.d(TAG, "WakeLock released")
        }
    }
}
