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
    private var checkRecordingJob: Job? = null
    
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
        
        // Start a job to periodically log recorder status
        startRecorderStatusChecking()
    }
    
    private fun startRecorderStatusChecking() {
        checkRecordingJob = serviceScope.launch {
            while (true) {
                val recorderState = if (recorder != null) "active" else "null"
                val currentState = _recordingState.value
                Log.d(TAG, "Recorder status: $recorderState, state: $currentState")
                delay(5000) // Check every 5 seconds
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder {
        Log.d(TAG, "Service bound")
        return binder
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "Service onStartCommand called")
        
        try {
            val notification = createNotification()
            startForeground(NOTIFICATION_ID, notification)
            Log.d(TAG, "Service started in foreground")
            
            if (_recordingState.value == RecordingState.IDLE) {
                Log.d(TAG, "Starting recording from onStartCommand")
                startRecording()
            } else {
                Log.d(TAG, "Recording already in progress, state: ${_recordingState.value}")
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Error in onStartCommand", e)
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
                Log.d(TAG, "Starting recording...")
                outputFile = createOutputFile()
                Log.d(TAG, "Output file created at: ${outputFile?.absolutePath}")
                
                recorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    MediaRecorder(applicationContext)
                } else {
                    @Suppress("DEPRECATION")
                    MediaRecorder()
                }
                
                try {
                    recorder?.apply {
                        setAudioSource(MediaRecorder.AudioSource.MIC)
                        Log.d(TAG, "Audio source set")
                        
                        setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                        Log.d(TAG, "Output format set")
                        
                        setOutputFile(outputFile?.absolutePath)
                        Log.d(TAG, "Output file path set")
                        
                        setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                        Log.d(TAG, "Audio encoder set")
                        
                        setAudioSamplingRate(44100)
                        Log.d(TAG, "Sampling rate set")
                        
                        setAudioEncodingBitRate(96000)
                        Log.d(TAG, "Encoding bit rate set")
                        
                        Log.d(TAG, "Preparing recorder...")
                        prepare()
                        Log.d(TAG, "Recorder prepared")
                        
                        Log.d(TAG, "Starting recorder...")
                        start()
                        Log.d(TAG, "Recorder started successfully")
                        
                        _recordingState.value = RecordingState.RECORDING
                        Log.d(TAG, "State changed to RECORDING")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error configuring MediaRecorder", e)
                    _recordingState.value = RecordingState.IDLE
                    throw e // Re-throw to be handled by the outer try-catch
                }
                
            } catch (e: Exception) {
                Log.e(TAG, "Error in startRecording", e)
                cleanupRecorder()
                _recordingState.value = RecordingState.IDLE
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
                        Log.d(TAG, "Recorder stopped")
                        release()
                        Log.d(TAG, "Recorder released")
                    }
                    recorder = null
                } catch (e: Exception) {
                    Log.e(TAG, "Error stopping recorder", e)
                }
                
                // Handle the recording file
                outputFile?.let { file ->
                    if (file.exists() && file.length() > 0) {
                        Log.d(TAG, "Recording saved: ${file.absolutePath}, size: ${file.length()} bytes")
                        uploadRecording(file)
                    } else {
                        Log.e(TAG, "Recording file is missing or empty: ${file.absolutePath}")
                        _recordingState.value = RecordingState.UPLOAD_FAILED
                    }
                } ?: run {
                    Log.e(TAG, "Output file was null")
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
                
                // For testing, simulate upload
                Log.d(TAG, "Simulating upload for testing...")
                delay(3000) // Simulate 3s upload time
                
                // In a real app, this would call the Drive API
                // val fileId = driveApiClient.uploadFileToDrive(file)
                val fileId = "test_file_id_${System.currentTimeMillis()}"
                
                if (fileId != null) {
                    Log.d(TAG, "Upload successful, file ID: $fileId")
                    _recordingState.value = RecordingState.UPLOAD_SUCCESS
                    
                    // In a real app, this would call your server
                    // val result = externalServerClient.sendFileIdToServer(fileId)
                } else {
                    Log.e(TAG, "Upload failed - no file ID returned")
                    _recordingState.value = RecordingState.UPLOAD_FAILED
                }
                
            } catch (e: Exception) {
                Log.e(TAG, "Error uploading recording", e)
                _recordingState.value = RecordingState.UPLOAD_FAILED
            }
        }
    }
    
    private fun cleanupRecorder() {
        try {
            recorder?.apply {
                release()
                Log.d(TAG, "Recorder released during cleanup")
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
        return File(dir, fileName).apply {
            parentFile?.mkdirs()
            Log.d(TAG, "Created output file: $absolutePath")
        }
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
            Log.d(TAG, "Notification channel created")
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
            .build()
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "Service onDestroy called")
        
        checkRecordingJob?.cancel()
        recordingJob?.cancel()
        
        if (_recordingState.value == RecordingState.RECORDING) {
            cleanupRecorder()
        }
    }
}
