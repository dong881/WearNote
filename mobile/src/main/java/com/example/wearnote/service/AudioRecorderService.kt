package com.example.wearnote.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
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
import com.example.wearnote.model.AudioSource
import com.example.wearnote.utils.AudioFileManager
import com.example.wearnote.utils.PreferenceManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class AudioRecorderService : Service() {
    
    companion object {
        private const val TAG = "AudioRecorderService"
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "audio_recorder_channel"
        
        const val ACTION_START_RECORDING = "com.example.wearnote.action.START_RECORDING"
        const val ACTION_PAUSE_RECORDING = "com.example.wearnote.action.PAUSE_RECORDING"
        const val ACTION_RESUME_RECORDING = "com.example.wearnote.action.RESUME_RECORDING"
        const val ACTION_STOP_RECORDING = "com.example.wearnote.action.STOP_RECORDING"
        
        // Audio recording constants
        private const val SAMPLE_RATE = 44100
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
    }
    
    // Binder for activity communication
    private val binder = LocalBinder()
    
    // Recording state
    private var isRecording = false
    private var isPaused = false
    private var audioRecord: AudioRecord? = null
    private var recordingJob: Job? = null
    private var recordingFile: File? = null
    private var recordingStartTime: Long = 0
    private var pausedDuration: Long = 0 // Total time spent in paused state
    private var pauseStartTime: Long = 0 // When pause was initiated
    
    // Wake lock to keep CPU active during recording
    private var wakeLock: PowerManager.WakeLock? = null
    
    // File management
    private lateinit var audioFileManager: AudioFileManager
    private lateinit var preferenceManager: PreferenceManager
    
    override fun onCreate() {
        super.onCreate()
        audioFileManager = AudioFileManager(applicationContext)
        preferenceManager = PreferenceManager(applicationContext)
        
        // Create notification channel for foreground service
        createNotificationChannel()
    }
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_RECORDING -> startRecording()
            ACTION_PAUSE_RECORDING -> pauseRecording()
            ACTION_RESUME_RECORDING -> resumeRecording()
            ACTION_STOP_RECORDING -> stopRecording()
        }
        
        return START_STICKY
    }
    
    override fun onBind(intent: Intent): IBinder {
        return binder
    }
    
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = "Audio Recorder"
            val descriptionText = "Records audio in the background"
            val importance = NotificationManager.IMPORTANCE_LOW
            val channel = NotificationChannel(CHANNEL_ID, name, importance).apply {
                description = descriptionText
            }
            
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }
    
    private fun createNotification(status: String): Notification {
        val mainActivityIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, mainActivityIntent, 
            PendingIntent.FLAG_IMMUTABLE
        )
        
        val stopIntent = Intent(this, AudioRecorderService::class.java).apply {
            action = ACTION_STOP_RECORDING
        }
        
        val stopPendingIntent = PendingIntent.getService(
            this, 1, stopIntent, 
            PendingIntent.FLAG_IMMUTABLE
        )
        
        val pauseResumeIntent = Intent(this, AudioRecorderService::class.java).apply {
            action = if (isPaused) ACTION_RESUME_RECORDING else ACTION_PAUSE_RECORDING
        }
        
        val pauseResumePendingIntent = PendingIntent.getService(
            this, 2, pauseResumeIntent, 
            PendingIntent.FLAG_IMMUTABLE
        )
        
        val pauseResumeAction = NotificationCompat.Action.Builder(
            android.R.drawable.ic_media_pause,
            if (isPaused) "Resume" else "Pause",
            pauseResumePendingIntent
        ).build()
        
        val stopAction = NotificationCompat.Action.Builder(
            android.R.drawable.ic_media_pause,
            "Stop",
            stopPendingIntent
        ).build()
        
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("WearNote Recording")
            .setContentText("Status: $status")
            .setSmallIcon(R.drawable.ic_mic)
            .setContentIntent(pendingIntent)
            .addAction(pauseResumeAction)
            .addAction(stopAction)
            .setOngoing(true)
            .build()
    }
    
    private fun acquireWakeLock() {
        if (wakeLock == null) {
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "WearNote:AudioRecorderWakeLock"
            )
            wakeLock?.acquire()
        }
    }
    
    private fun releaseWakeLock() {
        wakeLock?.let {
            if (it.isHeld) {
                it.release()
            }
            wakeLock = null
        }
    }
    
    fun startRecording() {
        if (isRecording) return
        
        Log.d(TAG, "Starting recording")
        acquireWakeLock()
        
        val audioSource = when (preferenceManager.getAudioSource()) {
            AudioSource.PHONE -> MediaRecorder.AudioSource.MIC
            AudioSource.WATCH -> MediaRecorder.AudioSource.MIC // Will be handled differently between devices
            AudioSource.BLUETOOTH -> MediaRecorder.AudioSource.MIC // Will need to check bluetooth connectivity
        }
        
        try {
            // Initialize the recorder
            val bufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
            audioRecord = AudioRecord(
                audioSource,
                SAMPLE_RATE,
                CHANNEL_CONFIG,
                AUDIO_FORMAT,
                bufferSize
            )
            
            // Create file for recording
            recordingFile = audioFileManager.createAudioFile()
            
            recordingStartTime = System.currentTimeMillis()
            pausedDuration = 0
            isRecording = true
            isPaused = false
            
            // Store the current recording path in preferences
            preferenceManager.setCurrentRecordingPath(recordingFile?.absolutePath)
            
            // Start foreground service with notification
            startForeground(NOTIFICATION_ID, createNotification("Recording"))
            
            // Start recording in a coroutine
            recordingJob = CoroutineScope(Dispatchers.IO).launch {
                val buffer = ByteArray(bufferSize)
                audioRecord?.startRecording()
                
                try {
                    val outputStream = FileOutputStream(recordingFile)
                    // Write WAV header
                    writeWavHeader(outputStream, SAMPLE_RATE, CHANNEL_CONFIG == AudioFormat.CHANNEL_IN_STEREO)
                    
                    while (isRecording) {
                        if (!isPaused) {
                            val read = audioRecord?.read(buffer, 0, buffer.size) ?: 0
                            if (read > 0) {
                                outputStream.write(buffer, 0, read)
                            }
                        } else {
                            delay(100) // Small delay when paused to reduce CPU load
                        }
                    }
                    
                    // Update WAV header with final file size
                    updateWavHeader(recordingFile!!)
                    outputStream.close()
                    
                    if (!isPaused) {
                        // Upload file if recording completed normally (not paused)
                        uploadRecordingIfPossible(recordingFile!!)
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
            stopSelf()
        }
    }
    
    fun pauseRecording() {
        if (isRecording && !isPaused) {
            isPaused = true
            pauseStartTime = System.currentTimeMillis()
            startForeground(NOTIFICATION_ID, createNotification("Paused"))
            Log.d(TAG, "Recording paused")
        }
    }
    
    fun resumeRecording() {
        if (isRecording && isPaused) {
            // Update the paused duration total
            pausedDuration += (System.currentTimeMillis() - pauseStartTime)
            isPaused = false
            startForeground(NOTIFICATION_ID, createNotification("Recording"))
            Log.d(TAG, "Recording resumed")
        }
    }
    
    fun stopRecording() {
        if (!isRecording) return
        
        Log.d(TAG, "Stopping recording")
        isRecording = false
        recordingJob?.cancel()
        
        // Release resources
        releaseWakeLock()
        stopForeground(true)
        stopSelf()
        
        // Clear the recording active state and path
        preferenceManager.setRecordingActive(false)
        preferenceManager.setCurrentRecordingPath(null)
        
        // If recording was not paused, upload the file
        if (!isPaused && recordingFile != null && recordingFile!!.exists()) {
            uploadRecordingIfPossible(recordingFile!!)
        }
    }
    
    private fun uploadRecordingIfPossible(file: File) {
        Log.d(TAG, "Attempting to upload recording: ${file.absolutePath}")
        // Launch a coroutine to handle the upload in the background
        CoroutineScope(Dispatchers.IO).launch {
            val isNetworkAvailable = audioFileManager.isNetworkAvailable()
            
            if (isNetworkAvailable) {
                Log.d(TAG, "Network available, uploading file")
                try {
                    val fileId = audioFileManager.uploadFileToDrive(file)
                    
                    if (fileId != null) {
                        Log.d(TAG, "File uploaded successfully, triggering server: $fileId")
                        audioFileManager.triggerServerProcess(fileId)
                    } else {
                        Log.e(TAG, "Upload failed, storing for later retry")
                        audioFileManager.addFileToUploadQueue(file)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error during upload", e)
                    audioFileManager.addFileToUploadQueue(file)
                }
            } else {
                Log.d(TAG, "No network available, storing for later upload")
                audioFileManager.addFileToUploadQueue(file)
            }
        }
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
            
            wavFile.randomAccess {
                // Update file size (minus 8 bytes for RIFF header)
                seek(4)
                writeInt(fileSize - 8)
                
                // Update data chunk size
                seek(40)
                writeInt(dataSize)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error updating WAV header", e)
        }
    }
    
    // Extension function for random access to file
    private inline fun <T> File.randomAccess(block: java.io.RandomAccessFile.() -> T): T {
        val randomAccessFile = java.io.RandomAccessFile(this, "rw")
        return try {
            randomAccessFile.block()
        } finally {
            randomAccessFile.close()
        }
    }
    
    // Extension function to write Int in little endian format
    private fun java.io.RandomAccessFile.writeInt(value: Int) {
        writeByte(value and 0xFF)
        writeByte(value shr 8 and 0xFF)
        writeByte(value shr 16 and 0xFF)
        writeByte(value shr 24 and 0xFF)
    }
    
    // Public methods for binding components to access service state
    fun isRecording(): Boolean = isRecording
    
    fun isPaused(): Boolean = isPaused
    
    fun getRecordingDuration(): Long {
        if (!isRecording) return 0
        
        val currentTime = System.currentTimeMillis()
        val elapsedTime = if (isPaused) {
            (pauseStartTime - recordingStartTime) - pausedDuration
        } else {
            (currentTime - recordingStartTime) - pausedDuration
        }
        
        return elapsedTime
    }
    
    fun getRecordingFilePath(): String? = recordingFile?.absolutePath
    
    inner class LocalBinder : Binder() {
        fun getService(): AudioRecorderService = this@AudioRecorderService
    }
    
    override fun onDestroy() {
        stopRecording()
        super.onDestroy()
    }
}