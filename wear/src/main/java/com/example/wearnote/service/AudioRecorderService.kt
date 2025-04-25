package com.example.wearnote.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
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
    
    private val driveApiClient by lazy { DriveApiClient(applicationContext) }
    private val externalServerClient by lazy { ExternalServerClient() }
    private val serviceScope = CoroutineScope(Dispatchers.IO)

    inner class LocalBinder : Binder() {
        fun getService(): AudioRecorderService = this@AudioRecorderService
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onBind(intent: Intent?): IBinder {
        return binder
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, createNotification())
        
        if (_recordingState.value == RecordingState.IDLE) {
            startRecording()
        }
        
        return START_STICKY
    }

    fun startRecording() {
        if (_recordingState.value == RecordingState.RECORDING) return
        
        recordingJob = serviceScope.launch {
            try {
                outputFile = createOutputFile()
                
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
                }
                
                _recordingState.value = RecordingState.RECORDING
                
            } catch (e: Exception) {
                Log.e(TAG, "Error starting recording", e)
                _recordingState.value = RecordingState.IDLE
            }
        }
    }

    fun stopRecording() {
        if (_recordingState.value != RecordingState.RECORDING) return
        
        serviceScope.launch {
            try {
                recorder?.apply {
                    stop()
                    release()
                }
                recorder = null
                
                // Upload the recorded file
                _recordingState.value = RecordingState.UPLOADING
                
                outputFile?.let {
                    try {
                        val fileId = driveApiClient.uploadFileToDrive(it)
                        if (fileId != null) {
                            // Send file ID to external server
                            val result = externalServerClient.sendFileIdToServer(fileId)
                            if (result) {
                                _recordingState.value = RecordingState.UPLOAD_SUCCESS
                            } else {
                                _recordingState.value = RecordingState.UPLOAD_FAILED
                            }
                        } else {
                            _recordingState.value = RecordingState.UPLOAD_FAILED
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error uploading file", e)
                        _recordingState.value = RecordingState.UPLOAD_FAILED
                    }
                }
                
            } catch (e: Exception) {
                Log.e(TAG, "Error stopping recording", e)
                _recordingState.value = RecordingState.UPLOAD_FAILED
            }
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
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    override fun onDestroy() {
        super.onDestroy()
        recordingJob?.cancel()
        
        if (_recordingState.value == RecordingState.RECORDING) {
            recorder?.apply {
                try {
                    stop()
                } catch (e: Exception) {
                    Log.e(TAG, "Error stopping recorder", e)
                }
                release()
            }
            recorder = null
        }
    }
}
