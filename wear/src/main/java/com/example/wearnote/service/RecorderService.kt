package com.example.wearnote.service

import android.app.*
import android.content.Context
import android.content.Intent
import android.media.MediaRecorder
import android.os.*
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
import java.util.concurrent.ConcurrentHashMap

class RecorderService : Service() {
    companion object {
        private const val TAG = "RecorderService"
        private const val NOTIFICATION_ID = 1
        private const val CHANNEL_ID = "RecorderServiceChannel"

        const val ACTION_START_RECORDING = "com.example.wearnote.ACTION_START_RECORDING"
        const val ACTION_STOP_RECORDING = "com.example.wearnote.ACTION_STOP_RECORDING"
        const val ACTION_PAUSE_RECORDING = "com.example.wearnote.ACTION_PAUSE_RECORDING"
        const val ACTION_RESUME_RECORDING = "com.example.wearnote.ACTION_RESUME_RECORDING"
        const val ACTION_DISCARD_RECORDING = "com.example.wearnote.ACTION_DISCARD_RECORDING"
        const val ACTION_FORCE_STOP = "com.example.wearnote.ACTION_FORCE_STOP"
        const val ACTION_CHECK_UPLOAD_STATUS = "com.example.wearnote.ACTION_CHECK_UPLOAD_STATUS"

        private val pendingUploads = ConcurrentHashMap<String, Boolean>()
    }

    private var mediaRecorder: MediaRecorder? = null
    private lateinit var outputFile: File
    private var isRecording = false
    private var isPaused = false
    private var wakeLock: PowerManager.WakeLock? = null

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "WearNote::RecorderWakeLock")
    }

    override fun onDestroy() {
        super.onDestroy()
        releaseWakeLock()
        serviceScope.cancel()
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
