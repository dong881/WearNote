package com.example.wearnote.services

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.media.AudioFormat
import android.media.AudioRecord
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class RecordingService : Service() {
    
    companion object {
        private const val TAG = "RecordingService"
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "recording_channel"
        
        // Actions
        const val ACTION_START = "com.example.wearnote.action.START"
        const val ACTION_PAUSE = "com.example.wearnote.action.PAUSE"
        const val ACTION_RESUME = "com.example.wearnote.action.RESUME"
        const val ACTION_STOP = "com.example.wearnote.action.STOP"
        
        // Audio source preferences
        const val PREF_AUDIO_SOURCE = "audio_source"
        const val AUDIO_SOURCE_WATCH = 0
        const val AUDIO_SOURCE_PHONE = 1
        const val AUDIO_SOURCE_BLUETOOTH = 2
        
        // Audio recording constants
        private const val SAMPLE_RATE = 44100
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
    }
    
    private var binder = LocalBinder()
    private var isRecording = false
    private var isPaused = false
    private var audioRecord: AudioRecord? = null
    private var recordingJob: Job? = null
    private var recordingFile: File? = null
    private var recordingStartTime: Long = 0
    private var pauseStartTime: Long = 0
    private var totalPausedTime: Long = 0
    private var wakeLock: PowerManager.WakeLock? = null
    private lateinit var statePrefs: SharedPreferences
    
    override fun onCreate() {
        super.onCreate()
        statePrefs = getSharedPreferences("recording_state", Context.MODE_PRIVATE)
        createNotificationChannel()
    }
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startRecording()
            ACTION_PAUSE -> pauseRecording()
            ACTION_RESUME -> resumeRecording()
            ACTION_STOP -> stopRecording()
        }
        return START_STICKY
    }
    
    override fun onBind(intent: Intent): IBinder {
        return binder
    }
    
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = getString(R.string.app_name)
            val importance = NotificationManager.IMPORTANCE_LOW
            val channel = NotificationChannel(CHANNEL_ID, name, importance).apply {
                description = "Recording notifications"
            }
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }
    
    private fun createNotification(): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent, PendingIntent.FLAG_IMMUTABLE
        )
        
        val stopIntent = Intent(this, RecordingService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this, 1, stopIntent, PendingIntent.FLAG_IMMUTABLE
        )
        
        val pauseResumeIntent = Intent(this, RecordingService::class.java).apply {
            action = if (isPaused) ACTION_RESUME else ACTION_PAUSE
        }
        val pauseResumePendingIntent = PendingIntent.getService(
            this, 2, pauseResumeIntent, PendingIntent.FLAG_IMMUTABLE
        )
        
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Recording Audio")
            .setContentText(if (isPaused) "Paused" else "Recording...")
            .setSmallIcon(R.drawable.ic_notification) // Use an appropriate icon
            .setOngoing(true)
            .setContentIntent(pendingIntent)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)

        // Add actions based on state
        if (isPaused) {
            builder.addAction(R.drawable.ic_notification, "Resume", pauseResumePendingIntent) // Replace with appropriate icon
        } else {
            builder.addAction(R.drawable.ic_notification, "Pause", pauseResumePendingIntent) // Replace with appropriate icon
        }
        // Use R.drawable.ic_media_stop for the stop action icon
        builder.addAction(R.drawable.ic_media_stop, "Stop", stopPendingIntent)

        return builder.build()
    }
    
    private fun acquireWakeLock() {
        if (wakeLock == null) {
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "WearNote:RecordingWakeLock"
            )
            wakeLock?.acquire()
        }
    }
    
    private fun releaseWakeLock() {
        wakeLock?.let {
            if (it.isHeld) it.release()
            wakeLock = null
        }
    }
    
    private fun startRecording() {
        if (isRecording) return
        
        Log.d(TAG, "Starting recording")
        acquireWakeLock()
        
        // Get audio source from preferences
        val prefs = getSharedPreferences("recording_prefs", Context.MODE_PRIVATE)
        val sourceType = prefs.getInt(PREF_AUDIO_SOURCE, AUDIO_SOURCE_WATCH)
        
        // Convert preference to actual audio source constant
        val audioSource = when (sourceType) {
            AUDIO_SOURCE_PHONE -> MediaRecorder.AudioSource.MIC // Handle via Wearable APIs
            AUDIO_SOURCE_BLUETOOTH -> MediaRecorder.AudioSource.MIC // Need Bluetooth check
            else -> MediaRecorder.AudioSource.MIC // Default to watch microphone
        }
        
        try {
            // Initialize recorder
            val bufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
            audioRecord = AudioRecord(
                audioSource,
                SAMPLE_RATE,
                CHANNEL_CONFIG,
                AUDIO_FORMAT,
                bufferSize * 2
            )
            
            // Create file for recording
            recordingFile = createAudioFile()
            
            recordingStartTime = System.currentTimeMillis()
            totalPausedTime = 0
            isRecording = true
            isPaused = false
            
            updateRecordingState(true, false)
            
            // Start foreground service with notification
            startForeground(NOTIFICATION_ID, createNotification())
            
            // Start recording in a coroutine
            recordingJob = CoroutineScope(Dispatchers.IO).launch {
                val buffer = ByteArray(bufferSize)
                audioRecord?.startRecording()
                
                try {
                    val outputStream = FileOutputStream(recordingFile)
                    
                    // Write WAV header
                    writeWavHeader(outputStream, SAMPLE_RATE, false)
                    
                    while (isRecording) {
                        if (!isPaused) {
                            val read = audioRecord?.read(buffer, 0, buffer.size) ?: 0
                            if (read > 0) {
                                outputStream.write(buffer, 0, read)
                            }
                        } else {
                            delay(100) // Small delay when paused
                        }
                    }
                    
                    // Update WAV header with final file size
                    outputStream.close()
                    updateWavHeader(recordingFile!!)
                    
                    // Start upload if we've completed the recording
                    if (!isPaused) {
                        startUploadService(recordingFile!!)
                    }
                    
                } catch (e: IOException) {
                    Log.e(TAG, "Error writing audio data", e)
                } finally {
                    audioRecord?.stop()
                    audioRecord?.release()
                    audioRecord = null
                }
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start recording", e)
            releaseWakeLock()
            stopSelf()
        }
    }
    
    private fun pauseRecording() {
        if (isRecording && !isPaused) {
            isPaused = true
            pauseStartTime = System.currentTimeMillis()
            updateRecordingState(true, true)
            startForeground(NOTIFICATION_ID, createNotification())
            Log.d(TAG, "Recording paused")
        }
    }
    
    private fun resumeRecording() {
        if (isRecording && isPaused) {
            // Calculate and add to total paused time
            if (pauseStartTime > 0) {
                totalPausedTime += System.currentTimeMillis() - pauseStartTime
                pauseStartTime = 0
            }
            
            isPaused = false
            updateRecordingState(true, false)
            startForeground(NOTIFICATION_ID, createNotification())
            Log.d(TAG, "Recording resumed")
        }
    }
    
    private fun stopRecording() {
        if (!isRecording) return
        
        Log.d(TAG, "Stopping recording")
        isRecording = false
        recordingJob?.cancel()
        
        // Reset recording state
        updateRecordingState(false, false)
        
        // We'll let the coroutine finish cleanup and upload
        releaseWakeLock()
        stopForeground(true)
        stopSelf()
    }
    
    private fun updateRecordingState(isRecording: Boolean, isPaused: Boolean) {
        statePrefs.edit()
            .putBoolean("is_recording", isRecording)
            .putBoolean("is_paused", isPaused)
            .putLong("start_time", recordingStartTime)
            .apply()
    }
    
    private fun startUploadService(file: File) {
        val intent = Intent(this, DriveUploadService::class.java).apply {
            putExtra(DriveUploadService.EXTRA_FILE_PATH, file.absolutePath)
        }
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }
    
    private fun createAudioFile(): File {
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val fileName = "WearNote_$timestamp.wav"
        val directory = getExternalFilesDir(Environment.DIRECTORY_MUSIC) ?: filesDir
        return File(directory, fileName)
    }
    
    private fun writeWavHeader(outputStream: FileOutputStream, sampleRate: Int, isStereo: Boolean) {
        val channels = if (isStereo) 2 else 1
        val byteRate = sampleRate * channels * 2 // 16 bits (2 bytes) per sample
        
        val headerSize = 44
        val header = ByteArray(headerSize)
        
        // RIFF header
        header[0] = 'R'.code.toByte()
        header[1] = 'I'.code.toByte()
        header[2] = 'F'.code.toByte()
        header[3] = 'F'.code.toByte()
        
        // File size (will be updated later)
        header[4] = 0
        header[5] = 0
        header[6] = 0
        header[7] = 0
        
        // WAVE header
        header[8] = 'W'.code.toByte()
        header[9] = 'A'.code.toByte()
        header[10] = 'V'.code.toByte()
        header[11] = 'E'.code.toByte()
        
        // fmt chunk
        header[12] = 'f'.code.toByte()
        header[13] = 'm'.code.toByte()
        header[14] = 't'.code.toByte()
        header[15] = ' '.code.toByte()
        
        // Size of fmt chunk
        header[16] = 16
        header[17] = 0
        header[18] = 0
        header[19] = 0
        
        // Audio format (1 = PCM)
        header[20] = 1
        header[21] = 0
        
        // Number of channels
        header[22] = channels.toByte()
        header[23] = 0
        
        // Sample rate
        header[24] = (sampleRate and 0xff).toByte()
        header[25] = (sampleRate shr 8 and 0xff).toByte()
        header[26] = (sampleRate shr 16 and 0xff).toByte()
        header[27] = (sampleRate shr 24 and 0xff).toByte()
        
        // Byte rate
        header[28] = (byteRate and 0xff).toByte()
        header[29] = (byteRate shr 8 and 0xff).toByte()
        header[30] = (byteRate shr 16 and 0xff).toByte()
        header[31] = (byteRate shr 24 and 0xff).toByte()
        
        // Block align
        header[32] = (channels * 2).toByte()
        header[33] = 0
        
        // Bits per sample
        header[34] = 16
        header[35] = 0
        
        // data chunk
        header[36] = 'd'.code.toByte()
        header[37] = 'a'.code.toByte()
        header[38] = 't'.code.toByte()
        header[39] = 'a'.code.toByte()
        
        // Data size (will be updated later)
        header[40] = 0
        header[41] = 0
        header[42] = 0
        header[43] = 0
        
        outputStream.write(header, 0, headerSize)
    }
    
    private fun updateWavHeader(wavFile: File) {
        try {
            val fileSize = wavFile.length().toInt()
            val dataSize = fileSize - 44
            
            RandomAccessFile(wavFile, "rw").use { randomAccessFile ->
                // Update file size (minus 8 bytes for RIFF header)
                randomAccessFile.seek(4)
                writeInt(randomAccessFile, fileSize - 8)
                
                // Update data chunk size
                randomAccessFile.seek(40)
                writeInt(randomAccessFile, dataSize)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error updating WAV header", e)
        }
    }
    
    private fun writeInt(output: RandomAccessFile, value: Int) {
        output.write(value and 0xff)
        output.write(value shr 8 and 0xff)
        output.write(value shr 16 and 0xff)
        output.write(value shr 24 and 0xff)
    }
    
    fun getElapsedTime(): Long {
        if (!isRecording) return 0L
        
        val currentTime = if (isPaused) pauseStartTime else System.currentTimeMillis()
        return (currentTime - recordingStartTime) - totalPausedTime
    }
    
    inner class LocalBinder : Binder() {
        fun getService(): RecordingService = this@RecordingService
    }
    
    override fun onDestroy() {
        stopRecording()
        super.onDestroy()
    }
}