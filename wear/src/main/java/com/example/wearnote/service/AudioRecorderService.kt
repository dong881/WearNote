package com.example.wearnote.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.MediaRecorder
import android.os.Binder
import android.os.Build
import android.os.Environment
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.wearnote.MainActivity
import com.example.wearnote.R
import com.example.wearnote.network.DriveApiClient
import com.example.wearnote.network.ExternalServerClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class AudioRecorderService : Service() {

    companion object {
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "recording_channel"
        private const val TAG = "AudioRecorderService"
    }

    enum class RecordingState {
        IDLE,
        RECORDING,
        PAUSED,
        UPLOADING,
        UPLOAD_SUCCESS,
        UPLOAD_FAILED
    }

    private val _recordingState = MutableStateFlow(RecordingState.IDLE)
    val recordingState: StateFlow<RecordingState> = _recordingState

    private val binder = LocalBinder()
    private var recorder: MediaRecorder? = null
    private var outputFile: File? = null
    private var recordingJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    
    private val driveApiClient by lazy { DriveApiClient(applicationContext) }
    private val externalServerClient by lazy { ExternalServerClient() }
    private val serviceScope = CoroutineScope(Dispatchers.IO)

    inner class LocalBinder : Binder() {
        fun getService(): AudioRecorderService = this@AudioRecorderService
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Service created")
        createNotificationChannel()
        acquireWakeLock()
    }
    
    private fun acquireWakeLock() {
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "WearNote::RecordingWakeLock"
        ).apply {
            acquire(10*60*1000L) // 10 minutes
            Log.d(TAG, "Wake lock acquired")
        }
    }
    
    private fun releaseWakeLock() {
        wakeLock?.let {
            if (it.isHeld) {
                it.release()
                Log.d(TAG, "Wake lock released")
            }
        }
        wakeLock = null
    }

    override fun onBind(intent: Intent?): IBinder {
        Log.d(TAG, "Service bound")
        return binder
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "Service onStartCommand called")
        
        startForeground(NOTIFICATION_ID, createNotification())
        Log.d(TAG, "Service started in foreground")
        
        if (_recordingState.value == RecordingState.IDLE) {
            Log.d(TAG, "Starting recording from onStartCommand")
            startRecording()
        } else {
            Log.d(TAG, "Already in state: ${_recordingState.value}")
        }
        
        return START_STICKY
    }

    fun startRecording() {
        if (_recordingState.value == RecordingState.RECORDING) {
            Log.d(TAG, "Already recording, ignoring start request")
            return
        }
        
        recordingJob = serviceScope.launch {
            try {
                Log.d(TAG, "Starting actual recording...")
                outputFile = createOutputFile()
                Log.d(TAG, "Created output file for recording: ${outputFile?.absolutePath}")
                
                recorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    MediaRecorder(applicationContext)
                } else {
                    @Suppress("DEPRECATION")
                    MediaRecorder()
                }
                
                recorder?.apply {
                    setAudioSource(MediaRecorder.AudioSource.MIC)
                    setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                    setOutputFile(outputFile?.absolutePath)
                    setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                    setAudioSamplingRate(44100)
                    setAudioEncodingBitRate(96000)
                    prepare()
                    start()
                    
                    Log.d(TAG, "MediaRecorder actually started recording")
                    _recordingState.value = RecordingState.RECORDING
                }
                
            } catch (e: Exception) {
                Log.e(TAG, "Error starting recording", e)
                cleanupRecorder()
                _recordingState.value = RecordingState.IDLE
            }
        }
    }

    fun pauseRecording() {
        if (_recordingState.value != RecordingState.RECORDING) {
            Log.d(TAG, "Not recording, ignoring pause request")
            return
        }
        
        serviceScope.launch {
            try {
                Log.d(TAG, "Pausing recording...")
                
                try {
                    recorder?.apply {
                        pause()
                        Log.d(TAG, "Recorder paused")
                    }
                    _recordingState.value = RecordingState.PAUSED
                } catch (e: Exception) {
                    Log.e(TAG, "Error pausing recorder", e)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error in pauseRecording", e)
            }
        }
    }

    fun resumeRecording() {
        if (_recordingState.value != RecordingState.PAUSED) {
            Log.d(TAG, "Not paused, ignoring resume request")
            return
        }
        
        serviceScope.launch {
            try {
                Log.d(TAG, "Resuming recording...")
                
                try {
                    recorder?.apply {
                        resume()
                        Log.d(TAG, "Recorder resumed")
                    }
                    _recordingState.value = RecordingState.RECORDING
                } catch (e: Exception) {
                    Log.e(TAG, "Error resuming recorder", e)
                    try {
                        recorder?.release()
                        recorder = null
                        startRecording()
                    } catch (e2: Exception) {
                        Log.e(TAG, "Error restarting recorder after resume failure", e2)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error in resumeRecording", e)
            }
        }
    }

    fun stopRecording() {
        if (_recordingState.value != RecordingState.RECORDING) {
            Log.d(TAG, "Not recording, ignoring stop request")
            return
        }
        
        serviceScope.launch {
            try {
                Log.d(TAG, "Stopping recording...")
                
                try {
                    recorder?.apply {
                        stop()
                        release()
                        Log.d(TAG, "Recorder stopped and released")
                    }
                    recorder = null
                } catch (e: Exception) {
                    Log.e(TAG, "Error stopping recorder", e)
                }
                
                outputFile?.let { file ->
                    if (file.exists() && file.length() > 0) {
                        Log.d(TAG, "Recording file saved: ${file.absolutePath} (${file.length()} bytes)")
                        uploadRecording(file)
                    } else {
                        Log.e(TAG, "Recording file is missing or empty")
                        _recordingState.value = RecordingState.UPLOAD_FAILED
                    }
                } ?: run {
                    Log.e(TAG, "Output file is null")
                    _recordingState.value = RecordingState.UPLOAD_FAILED
                }
                
            } catch (e: Exception) {
                Log.e(TAG, "Error in stopRecording", e)
                _recordingState.value = RecordingState.UPLOAD_FAILED
            }
        }
    }
    
    private fun uploadRecording(file: File) {
        serviceScope.launch {
            try {
                Log.d(TAG, "Starting upload process")
                _recordingState.value = RecordingState.UPLOADING
                
                // Save file information in shared preferences
                saveFileInfo(file)
                
                // Verify file exists and has content
                if (!file.exists() || file.length() == 0L) {
                    Log.e(TAG, "Recording file doesn't exist or is empty: ${file.absolutePath}")
                    _recordingState.value = RecordingState.UPLOAD_FAILED
                    return@launch
                }
                
                Log.d(TAG, "Recording file saved: ${file.absolutePath} (${file.length()} bytes)")
                
                // Always create local backup first
                val backupDir = File(applicationContext.getExternalFilesDir(null), "WearNoteBackups")
                if (!backupDir.exists()) backupDir.mkdirs()
                
                val backupFile = File(backupDir, file.name)
                
                try {
                    file.inputStream().use { input ->
                        FileOutputStream(backupFile).use { output ->
                            input.copyTo(output)
                        }
                    }
                    Log.d(TAG, "Created backup at: ${backupFile.absolutePath}")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to create backup: ${e.message}")
                }
                
                // Try Drive upload using OAuth user authentication
                val driveApiClient = DriveApiClient(applicationContext)
                val fileId = driveApiClient.uploadFileToDrive(file)
                
                if (fileId != null) {
                    Log.d(TAG, "File successfully uploaded to Drive with OAuth, ID: $fileId")
                    
                    // Try to send to external server
                    try {
                        val serverResult = externalServerClient.sendFileIdToServer(fileId)
                        
                        if (serverResult) {
                            Log.d(TAG, "File ID successfully sent to server")
                        } else {
                            Log.e(TAG, "Failed to send file ID to server")
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error sending to server", e)
                    }
                    
                    _recordingState.value = RecordingState.UPLOAD_SUCCESS
                    
                } else {
                    // Check if authentication is needed
                    if (driveApiClient.needsDrivePermission()) {
                        Log.d(TAG, "Drive upload needs user authentication")
                        
                        // Save pending upload info
                        val prefs = applicationContext.getSharedPreferences("WearNotePrefs", Context.MODE_PRIVATE)
                        prefs.edit().apply {
                            putString("pending_upload_file", file.absolutePath)
                            putLong("pending_upload_time", System.currentTimeMillis())
                            apply()
                        }
                        
                        // Show upload failed status to trigger sign-in button
                        _recordingState.value = RecordingState.UPLOAD_FAILED
                    } else {
                        Log.e(TAG, "Drive upload failed for unknown reason")
                        _recordingState.value = RecordingState.UPLOAD_FAILED
                    }
                }
                
                // Keep success message visible for a moment
                delay(2000)
                stopSelf()
                
            } catch (e: Exception) {
                Log.e(TAG, "Error during upload process", e)
                _recordingState.value = RecordingState.UPLOAD_FAILED
            }
        }
    }

    /**
     * Resumes uploading a previously recorded file
     */
    fun resumeUpload(file: File) {
        serviceScope.launch {
            try {
                Log.d(TAG, "Resuming upload for file: ${file.absolutePath}")
                _recordingState.value = RecordingState.UPLOADING
                
                if (!file.exists() || file.length() == 0L) {
                    Log.e(TAG, "Cannot resume upload - file doesn't exist or is empty")
                    _recordingState.value = RecordingState.UPLOAD_FAILED
                    return@launch
                }
                
                // Try to upload with fresh authentication
                val driveApiClient = DriveApiClient(applicationContext)
                val fileId = driveApiClient.uploadFileToDrive(file)
                
                if (fileId != null) {
                    Log.d(TAG, "File uploaded to Drive after authentication, ID: $fileId")
                    
                    try {
                        val serverResult = externalServerClient.sendFileIdToServer(fileId)
                        if (serverResult) {
                            Log.d(TAG, "File ID sent to server after authentication")
                        } else {
                            Log.e(TAG, "Failed to send file ID to server after authentication")
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error sending to server after authentication", e)
                    }
                    
                    _recordingState.value = RecordingState.UPLOAD_SUCCESS
                } else {
                    Log.e(TAG, "Failed to upload to Drive after authentication")
                    _recordingState.value = RecordingState.UPLOAD_FAILED
                }
                
            } catch (e: Exception) {
                Log.e(TAG, "Error resuming upload", e)
                _recordingState.value = RecordingState.UPLOAD_FAILED
            }
        }
    }

    private fun createLocalBackupCopy(sourceFile: File): File? {
        try {
            // Create directories for various backup locations
            val backupLocations = listOf(
                File(applicationContext.getExternalFilesDir(null), "WearNoteBackups"),
                File(applicationContext.getExternalFilesDir(Environment.DIRECTORY_MUSIC), "WearNote")
            )
            
            backupLocations.forEach { dir -> 
                if (!dir.exists()) dir.mkdirs()
            }
            
            // Create backup files in each location
            val backupFiles = backupLocations.map { dir -> 
                File(dir, sourceFile.name)
            }
            
            // Copy the file to each backup location
            backupFiles.forEach { backupFile -> 
                sourceFile.inputStream().use { input -> 
                    FileOutputStream(backupFile).use { output -> 
                        input.copyTo(output)
                    }
                }
                Log.d(TAG, "Created backup at: ${backupFile.absolutePath}")
            }
            
            return backupFiles.firstOrNull()
        } catch (e: Exception) {
            Log.e(TAG, "Error creating backup file", e)
            return null
        }
    }

    private fun saveFileInfo(file: File) {
        try {
            val prefs = applicationContext.getSharedPreferences("WearNotePrefs", Context.MODE_PRIVATE)
            prefs.edit().apply {
                putString("last_recording_path", file.absolutePath)
                putLong("last_recording_size", file.length())
                putLong("last_recording_time", System.currentTimeMillis())
                apply()
            }
            Log.d(TAG, "Saved recording info to shared preferences")
        } catch (e: Exception) {
            Log.e(TAG, "Error saving recording info", e)
        }
    }

    private fun cleanupRecorder() {
        try {
            recorder?.apply {
                release()
            }
            recorder = null
        } catch (e: Exception) {
            Log.e(TAG, "Error cleaning up recorder", e)
        }
    }

    fun isRecording(): Boolean {
        return _recordingState.value == RecordingState.RECORDING
    }

    private fun createOutputFile(): File {
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val fileName = "wearnote_$timestamp.m4a"
        
        val dir = applicationContext.getExternalFilesDir(null)
        dir?.mkdirs()
        
        val file = File(dir, fileName)
        Log.d(TAG, "Created output file at: ${file.absolutePath}")
        
        return file
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.recording_notification_channel),
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = getString(R.string.recording_notification_channel_description)
            }
            
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            notificationIntent,
            PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.recording_notification_title))
            .setContentText(getString(R.string.recording_notification_text))
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "Service being destroyed")
        
        recordingJob?.cancel()
        
        if (_recordingState.value == RecordingState.RECORDING) {
            Log.d(TAG, "Recording was in progress, cleaning up")
            cleanupRecorder()
        }
        
        releaseWakeLock()
    }
}
