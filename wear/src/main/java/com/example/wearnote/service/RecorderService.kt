package com.example.wearnote.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.MediaRecorder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
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
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.*

class RecorderService : Service() {

    private val TAG = "RecorderService"
    private var mediaRecorder: MediaRecorder? = null
    private lateinit var outputFile: File
    private var wakeLock: PowerManager.WakeLock? = null
    private val serviceJob = Job()
    private val serviceScope = CoroutineScope(Dispatchers.IO + serviceJob)

    private var isRecording = false
    private var isPaused = false // Track pause state

    companion object {
        const val ACTION_START_RECORDING = "com.example.wearnote.ACTION_START_RECORDING"
        const val ACTION_STOP_RECORDING = "com.example.wearnote.ACTION_STOP_RECORDING"
        const val ACTION_PAUSE_RECORDING = "com.example.wearnote.ACTION_PAUSE_RECORDING" // New action
        const val ACTION_RESUME_RECORDING = "com.example.wearnote.ACTION_RESUME_RECORDING" // New action
        const val ACTION_DISCARD_RECORDING = "com.example.wearnote.ACTION_DISCARD_RECORDING" // Add new action
        private const val NOTIFICATION_ID = 1
        private const val CHANNEL_ID = "RecorderServiceChannel"
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Service onCreate")
        createNotificationChannel()
        // Acquire WakeLock
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "WearNote::RecorderWakeLock")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "onStartCommand received: ${intent?.action}")
        when (intent?.action) {
            ACTION_START_RECORDING -> startRecording()
            ACTION_STOP_RECORDING -> stopRecordingAndUpload()
            ACTION_PAUSE_RECORDING -> pauseRecording()
            ACTION_RESUME_RECORDING -> resumeRecording()
            ACTION_DISCARD_RECORDING -> discardRecording() // Add new case
            else -> Log.w(TAG, "Unknown action received or null intent")
        }
        // If the service is killed, restart it with the last intent
        return START_REDELIVER_INTENT
    }

    private fun startRecording() {
        if (isRecording) {
            Log.w(TAG, "Recording already in progress")
            return
        }

        Log.d(TAG, "Starting recording...")
        startForeground(NOTIFICATION_ID, createNotification("Recording..."))
        wakeLock?.acquire(10*60*1000L /*10 minutes timeout*/) // Acquire wakelock

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
                // Change output format to MPEG_4 for .m4a
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                // Change audio encoder to AAC for better quality
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                // Set audio quality and bitrate parameters
                setAudioEncodingBitRate(128000)  // 128kbps
                setAudioSamplingRate(44100)      // 44.1kHz
                setOutputFile(outputFile.absolutePath)
                prepare()
                start()
            }
            isRecording = true
            isPaused = false // Reset pause state
            Log.d(TAG, "Recording started")
        } catch (e: IOException) {
            Log.e(TAG, "MediaRecorder prepare() failed", e)
            stopSelf()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start recording", e)
            stopSelf()
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
                updateNotification("Paused") // Update notification text
                // TODO: Update UI state
            } catch (e: IllegalStateException) {
                Log.e(TAG, "Failed to pause MediaRecorder", e)
            }
        } else {
            Log.w(TAG, "Pause/Resume not supported on this Android version (requires API 24+)")
            // Optionally notify the user via notification or UI update
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
                updateNotification("Recording...") // Update notification text
                // TODO: Update UI state
            } catch (e: IllegalStateException) {
                Log.e(TAG, "Failed to resume MediaRecorder", e)
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
            // Ensure MediaRecorder is stopped properly
            mediaRecorder?.stop()
            mediaRecorder?.release()
            mediaRecorder = null
            isRecording = false
            isPaused = false
            Log.d(TAG, "Recording stopped.")

            // Update notification to show uploading status
            updateNotification("Uploading recording...")

            // Check if the file is valid (exists and has size > 0)
            if (::outputFile.isInitialized && outputFile.exists() && outputFile.length() > 100) {
                Log.d(TAG, "Recording saved: ${outputFile.absolutePath}, size: ${outputFile.length()} bytes. Starting upload process.")
                
                serviceScope.launch {
                    try {
                        // Get Drive service using last signed in account
                        val account = GoogleSignIn.getLastSignedInAccount(this@RecorderService)
                        if (account != null) {
                            val credential = GoogleAccountCredential.usingOAuth2(
                                this@RecorderService, 
                                listOf(DriveScopes.DRIVE_FILE, DriveScopes.DRIVE)
                            )
                            credential.selectedAccount = account.account
                            val driveService = Drive.Builder(
                                NetHttpTransport(),
                                GsonFactory.getDefaultInstance(),
                                credential
                            ).setApplicationName(getString(R.string.app_name)).build()
                            
                            // Upload to Google Drive
                            val fileId = GoogleDriveUploader.uploadToDrive(
                                this@RecorderService, 
                                outputFile, 
                                driveService
                            )
                            
                            if (fileId == null) {
                                Log.w(TAG, "Upload failed or deferred (network/auth issue). File kept locally.")
                                updateNotification("Upload failed. File saved locally.")
                                notifyUploadComplete(null)
                            } else {
                                Log.i(TAG, "Upload successful! Google Drive File ID: $fileId")
                                updateNotification("Upload successful! File ID: $fileId")
                                
                                // Notify UI immediately of upload completion
                                notifyUploadComplete(fileId)
                            }
                        } else {
                            Log.w(TAG, "No Google account available for upload")
                            updateNotification("Upload failed: No Google account")
                            notifyUploadComplete(null)
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error during upload process", e)
                        updateNotification("Error during upload: ${e.message}")
                        notifyUploadComplete(null)
                    } finally {
                        // Stop the service after upload attempt (success or failure)
                        stopSelf()
                    }
                }
            } else {
                // Handle invalid file case
                Log.w(TAG, "Invalid or empty recording file: ${outputFile.absolutePath}")
                updateNotification("Invalid recording. No file saved.")
                if (::outputFile.isInitialized && outputFile.exists()) {
                    outputFile.delete()
                }
                notifyUploadComplete(null)
                stopSelf()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to stop MediaRecorder", e)
            if (mediaRecorder != null) {
                try {
                    mediaRecorder?.reset()
                    mediaRecorder?.release()
                } catch (e2: Exception) {
                    Log.e(TAG, "Error releasing MediaRecorder", e2)
                }
                mediaRecorder = null
            }
            isRecording = false
            isPaused = false
            if (::outputFile.isInitialized && outputFile.exists()) {
                outputFile.delete() // Delete potentially corrupt file
            }
            notifyUploadComplete(null)
            stopSelf()
        } finally {
            releaseWakeLock()
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
            // Release MediaRecorder resources
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
            
            // Delete the file if it exists
            if (::outputFile.isInitialized && outputFile.exists()) {
                if (outputFile.delete()) {
                    Log.d(TAG, "Deleted recording file: ${outputFile.absolutePath}")
                } else {
                    Log.e(TAG, "Failed to delete recording file: ${outputFile.absolutePath}")
                }
            }
            
            // Notify UI that recording was discarded
            notifyRecordingDiscarded()
            
        } catch (e: Exception) {
            Log.e(TAG, "Error discarding recording", e)
            notifyRecordingDiscarded() // Ensure we still notify even on error
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

    private fun notifyUploadComplete(fileId: String?) {
        val intent = Intent(MainActivity.ACTION_RECORDING_STATUS).apply {
            putExtra(MainActivity.EXTRA_STATUS, MainActivity.STATUS_UPLOAD_COMPLETED)
            putExtra(MainActivity.EXTRA_FILE_ID, fileId ?: "")
        }
        
        // Log before sending broadcast to confirm it's being sent
        Log.d(TAG, "Sending upload completion broadcast with fileId: ${fileId ?: "null"}")
        
        // Add flags to ensure the broadcast can be received
        intent.addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
        sendBroadcast(intent)
    }

    private fun createOutputFile(): File {
        val mediaDir = File(getExternalFilesDir(null), "Music")
        if (!mediaDir.exists()) {
            mediaDir.mkdirs()
        }
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        // Change extension from .3gp to .m4a
        return File(mediaDir, "REC_${timestamp}.m4a")
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                CHANNEL_ID,
                "Recording Service Channel",
                NotificationManager.IMPORTANCE_LOW // Use LOW to minimize interruption
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

        // Add Stop Action Button to Notification
        val stopIntent = Intent(this, RecorderService::class.java).apply {
            action = ACTION_STOP_RECORDING
        }
        val stopPendingIntent = PendingIntent.getService(this, 1, stopIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

        // Add Pause/Resume Action Button (Conditional based on state)
        val pauseResumeIntent = Intent(this, RecorderService::class.java).apply {
             action = if (isPaused) ACTION_RESUME_RECORDING else ACTION_PAUSE_RECORDING
        }
        val pauseResumePendingIntent = PendingIntent.getService(this, 2, pauseResumeIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val pauseResumeActionText = if (isPaused) "Resume" else "Pause"
        val pauseResumeIcon = if (isPaused) R.drawable.ic_play else R.drawable.ic_pause // Replace with actual icons

        val notificationBuilder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("WearNote Recording")
            .setContentText(contentText)
            .setSmallIcon(R.drawable.ic_mic) // Replace with your app's icon
            .setContentIntent(pendingIntent)
            .setOngoing(true) // Make it non-dismissable while recording/paused
            .setSilent(true) // Avoid sound on update
            .addAction(R.drawable.ic_stop, "Stop", stopPendingIntent) // Replace with actual icon

        // Only add Pause/Resume action if supported
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
             notificationBuilder.addAction(pauseResumeIcon, pauseResumeActionText, pauseResumePendingIntent)
        }

        return notificationBuilder.build()
    }

    // Update existing notification text
    private fun updateNotification(contentText: String) {
        val notification = createNotification(contentText) // Rebuild notification with new text/actions
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

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "Service onDestroy")
        // Ensure recorder is released if service is destroyed unexpectedly
        if (mediaRecorder != null) {
            try {
                if (isRecording) mediaRecorder?.stop()
            } catch (e: Exception) { Log.e(TAG, "Error stopping recorder on destroy", e)}
            mediaRecorder?.reset()
            mediaRecorder?.release()
            mediaRecorder = null
        }
        releaseWakeLock()
        serviceJob.cancel() // Cancel coroutines
        Log.d(TAG, "Recorder resources released, job cancelled.")
    }
}
