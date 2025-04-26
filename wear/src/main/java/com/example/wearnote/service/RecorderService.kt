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
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File

class RecorderService : Service() {
    private lateinit var recorder: MediaRecorder
    private lateinit var outputFile: File

    override fun onCreate() {
        super.onCreate()
        // 建立前台通知 channel
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel("rec", "Recording", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        }
        setupRecorder()
        startForeground(1, buildNotification(isPaused = false))
        recorder.start()
    }

    private fun setupRecorder() {
        outputFile = File(filesDir, "record_${System.currentTimeMillis()}.mp4")
        recorder = MediaRecorder().apply {
            setAudioSource(MediaRecorder.AudioSource.MIC)
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            setOutputFile(outputFile.absolutePath)
            prepare()
        }
    }

    private fun buildNotification(isPaused: Boolean): Notification {
        val pauseIntent = Intent(this, RecorderService::class.java).apply { action = if (isPaused) "RESUME" else "PAUSE" }
        val stopIntent  = Intent(this, RecorderService::class.java).apply { action = "STOP" }
        return NotificationCompat.Builder(this, "rec")
            .setContentTitle("Recording")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .addAction(
                if (isPaused) android.R.drawable.ic_media_play else android.R.drawable.ic_media_pause,
                if (isPaused) "Resume" else "Pause",
                PendingIntent.getService(this, 0, pauseIntent, PendingIntent.FLAG_IMMUTABLE)
            )
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                "Stop",
                PendingIntent.getService(this, 1, stopIntent, PendingIntent.FLAG_IMMUTABLE)
            ).build()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            "PAUSE" -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) recorder.pause()
                updateNotification(paused = true)
            }
            "RESUME" -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) recorder.resume()
                updateNotification(paused = false)
            }
            "STOP" -> stopRecording()
        }
        return START_STICKY
    }

    private fun updateNotification(paused: Boolean) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(1, buildNotification(paused))
    }

    private fun stopRecording() {
        recorder.stop()
        recorder.release()
        // 上傳或暫存
        CoroutineScope(Dispatchers.IO).launch {
            val fileId = GoogleDriveUploader.upload(this@RecorderService, outputFile)
            if (fileId == null) GoogleDriveUploader.enqueuePending(this@RecorderService, outputFile)
            // TODO: Http POST fileId to server if uploaded
            stopSelf()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
