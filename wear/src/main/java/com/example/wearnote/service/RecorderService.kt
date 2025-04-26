package com.example.wearnote.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.media.MediaRecorder
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

class RecorderService : Service() {
    private lateinit var recorder: MediaRecorder
    private lateinit var outputFile: File
    private var isRecording = false
    private var recordingStartTime: Long = 0
    private var isStoppingRequested = false
    
    companion object {
        const val ACTION_PAUSE_RECORDING = "PAUSE"
        const val ACTION_RESUME_RECORDING = "RESUME"
        const val ACTION_STOP_RECORDING = "STOP"
        private const val TAG = "RecorderService"
        private const val MIN_RECORDING_DURATION_MS = 1000 // Minimum 1 second recording to be valid
    }

    override fun onCreate() {
        super.onCreate()
        // 建立前台通知 channel
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel("rec", "Recording", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        }
        
        if (setupRecorder()) {
            startForeground(1, buildNotification(isPaused = false))
            try {
                recorder.start()
                isRecording = true
                recordingStartTime = SystemClock.elapsedRealtime()
                Log.d(TAG, "Recording started successfully")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start recording", e)
                stopSelf()
            }
        } else {
            Log.e(TAG, "Failed to setup recorder")
            stopSelf()
        }
    }

    private fun setupRecorder(): Boolean {
        try {
            outputFile = File(filesDir, "record_${System.currentTimeMillis()}.mp4")
            
            // Make sure parent directory exists
            outputFile.parentFile?.mkdirs()
            
            recorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(this)
            } else {
                MediaRecorder()
            }
            
            recorder.apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioEncodingBitRate(128000)
                setAudioSamplingRate(44100)
                setOutputFile(outputFile.absolutePath)
                try {
                    prepare()
                    return true
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to prepare recorder", e)
                    return false
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error setting up recorder", e)
            return false
        }
    }

    private fun buildNotification(isPaused: Boolean): Notification {
        val pauseResumeIntent = Intent(this, RecorderService::class.java).apply { 
            action = if (isPaused) ACTION_RESUME_RECORDING else ACTION_PAUSE_RECORDING 
        }
        val stopIntent = Intent(this, RecorderService::class.java).apply { 
            action = ACTION_STOP_RECORDING 
        }
        
        val actionIcon = if (isPaused) 
            android.R.drawable.ic_media_play 
        else 
            android.R.drawable.ic_media_pause
            
        val actionText = if (isPaused) "Resume" else "Pause"
        
        return NotificationCompat.Builder(this, "rec")
            .setContentTitle("Recording" + if (isPaused) " (Paused)" else "")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .addAction(
                actionIcon,
                actionText,
                PendingIntent.getService(this, 0, pauseResumeIntent, PendingIntent.FLAG_IMMUTABLE)
            )
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                "Stop",
                PendingIntent.getService(this, 1, stopIntent, PendingIntent.FLAG_IMMUTABLE)
            ).build()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PAUSE_RECORDING -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && isRecording) {
                    try {
                        recorder.pause()
                        isRecording = false
                        updateNotification(paused = true)
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to pause recording", e)
                    }
                }
            }
            ACTION_RESUME_RECORDING -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    try {
                        recorder.resume()
                        isRecording = true
                        updateNotification(paused = false)
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to resume recording", e)
                    }
                }
            }
            ACTION_STOP_RECORDING -> {
                if (!isStoppingRequested) {
                    isStoppingRequested = true
                    val recordingDuration = SystemClock.elapsedRealtime() - recordingStartTime
                    Log.d(TAG, "Stop requested. Current duration: $recordingDuration ms")
                    
                    if (recordingDuration < MIN_RECORDING_DURATION_MS) {
                        Log.d(TAG, "Recording too short, delaying stop to ensure minimum recording time")
                        CoroutineScope(Dispatchers.Main).launch {
                            val delayTime = MIN_RECORDING_DURATION_MS - recordingDuration
                            if (delayTime > 0) {
                                delay(delayTime)
                            }
                            stopRecording()
                        }
                    } else {
                        stopRecording()
                    }
                }
            }
        }
        return START_STICKY
    }

    private fun updateNotification(paused: Boolean) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(1, buildNotification(paused))
    }

    private fun stopRecording() {
        if (::recorder.isInitialized) {
            val recordingDuration = SystemClock.elapsedRealtime() - recordingStartTime
            Log.d(TAG, "Recording duration at stop: $recordingDuration ms")
            
            try {
                if (isRecording) {
                    Log.d(TAG, "Attempting to stop recording")
                    recorder.stop()
                    Log.d(TAG, "Recording stopped successfully")
                } else {
                    Log.w(TAG, "Skipped stop() - recording not active")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error stopping recorder", e)
                // Continue with cleanup even if stop failed
            } finally {
                isRecording = false
                try {
                    recorder.release()
                    Log.d(TAG, "Recorder released")
                } catch (e: Exception) {
                    Log.e(TAG, "Error releasing recorder", e)
                }
                
                // Only upload if the file exists and has content and recording was long enough
                if (::outputFile.isInitialized && outputFile.exists() && 
                    outputFile.length() > 0 && recordingDuration >= MIN_RECORDING_DURATION_MS) {
                    Log.d(TAG, "File exists with size: ${outputFile.length()} bytes, uploading")
                    // 上傳或暫存
                    CoroutineScope(Dispatchers.IO).launch {
                        val fileId = GoogleDriveUploader.upload(this@RecorderService, outputFile)
                        if (fileId == null) {
                            Log.d(TAG, "Upload failed, enqueuing for later")
                            GoogleDriveUploader.enqueuePending(this@RecorderService, outputFile)
                        } else {
                            Log.d(TAG, "Upload successful: $fileId")
                        }
                        // TODO: Http POST fileId to server if uploaded
                        stopSelf()
                    }
                } else {
                    // No valid recording to upload, just stop the service
                    Log.w(TAG, "Invalid or empty recording file, deleting file")
                    if (::outputFile.isInitialized && outputFile.exists()) {
                        try {
                            outputFile.delete()
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to delete empty recording file", e)
                        }
                    }
                    stopSelf()
                }
            }
        } else {
            Log.w(TAG, "Recorder not initialized, stopping service")
            stopSelf()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null
    
    override fun onDestroy() {
        Log.d(TAG, "onDestroy called")
        if (::recorder.isInitialized && isRecording) {
            try {
                val recordingDuration = SystemClock.elapsedRealtime() - recordingStartTime
                if (recordingDuration >= MIN_RECORDING_DURATION_MS) {
                    recorder.stop()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error stopping recorder in onDestroy", e)
            }
            try {
                recorder.release()
            } catch (e: Exception) {
                Log.e(TAG, "Error releasing recorder in onDestroy", e)
            }
        }
        super.onDestroy()
    }
}
