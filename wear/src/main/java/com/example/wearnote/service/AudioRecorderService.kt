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
        PAUSED,  // Added PAUSED state
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
        
        // This ensures the service continues running if it's killed
        return START_STICKY
    }

    fun startRecording() {
        if (_recordingState.value == RecordingState.RECORDING) {
            Log.d(TAG, "Already recording, ignoring start request")
            return
        }
        
        recordingJob = serviceScope.launch {
            try {
                Log.d(TAG, "Starting recording...")
                outputFile = createOutputFile()
                Log.d(TAG, "Output file path: ${outputFile?.absolutePath}")
                
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
                    
                    Log.d(TAG, "MediaRecorder started successfully")
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
                    // Continue recording if pause fails
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
                    // If resume fails, try to restart recording
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
                Log.d(TAG, "Starting real upload process")
                _recordingState.value = RecordingState.UPLOADING
                
                // Try to authenticate and upload to Google Drive
                val fileId = driveApiClient.uploadFileToDrive(file)
                
                if (fileId != null) {
                    Log.d(TAG, "File successfully uploaded to Drive, ID: $fileId")
                    
                    // Send the file ID to the external server
                    val serverResult = externalServerClient.sendFileIdToServer(fileId)
                    
                    if (serverResult) {
                        Log.d(TAG, "File ID successfully sent to server")
                        _recordingState.value = RecordingState.UPLOAD_SUCCESS
                        
                        // Keep success message visible for a moment then stop
                        delay(2000)
                        stopSelf()
                    } else {
                        Log.e(TAG, "Failed to send file ID to server")
                        _recordingState.value = RecordingState.UPLOAD_FAILED
                    }
                } else {
                    Log.e(TAG, "Failed to upload to Google Drive")
                    _recordingState.value = RecordingState.UPLOAD_FAILED
                }
                
            } catch (e: Exception) {
                Log.e(TAG, "Error during upload process", e)
                _recordingState.value = RecordingState.UPLOAD_FAILED
            }
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
        return File(dir, fileName)
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
            .setOngoing(true)  // Cannot be dismissed by the user
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
