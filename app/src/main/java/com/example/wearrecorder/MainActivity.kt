package com.example.wearnote

import android.Manifest
import android.app.*
import android.content.Context
import android.content.Intent
import android.media.MediaRecorder
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.util.Log
import android.widget.Button
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import com.google.api.client.googleapis.extensions.android.gms.auth.GoogleAccountCredential
import com.google.api.client.googleapis.json.GoogleJsonResponseException
import com.google.api.client.http.javanet.NetHttpTransport
import com.google.api.client.json.gson.GsonFactory
import com.google.api.services.drive.Drive
import com.google.api.services.drive.DriveScopes
import com.google.android.gms.auth.api.signin.GoogleSignIn
import kotlinx.coroutines.*
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.*
import java.io.File
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import com.example.wearnote.model.PendingUpload
import com.example.wearnote.service.GoogleDriveUploader
import com.example.wearnote.service.PendingUploadsManager
import com.example.wearnote.service.RecorderService

class MainActivity : ComponentActivity() {

    private var isRecording = false
    private var recorder: MediaRecorder? = null
    private var outputFile: String = ""

    // 新增：以 ActivityResultContracts 處理多重權限
    private val permLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        if (result.values.all { it }) {
            if (!isRecording) {
                startRecording()
                moveTaskToBack(true)
            } else {
                showStopUI()
            }
        } else {
            Toast.makeText(this, "Permissions required", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 先請求權限，並在回調中啟動錄音或顯示 UI
        permLauncher.launch(arrayOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.FOREGROUND_SERVICE,
            Manifest.permission.INTERNET
        ))
    }

    private fun showStopUI() {
        val button = Button(this).apply {
            text = "Stop Recording"
            setOnClickListener {
                stopRecording()
            }
        }
        setContentView(button)
    }

    private fun startRecording() {
        isRecording = true
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val fileName = "REC_$timestamp.3gp"
        val storageDir = getExternalFilesDir(Environment.DIRECTORY_MUSIC)
        outputFile = "${storageDir?.absolutePath}/$fileName"

        recorder = MediaRecorder().apply {
            setAudioSource(MediaRecorder.AudioSource.MIC)
            setOutputFormat(MediaRecorder.OutputFormat.THREE_GPP)
            setAudioEncoder(MediaRecorder.AudioEncoder.AMR_NB)
            setOutputFile(outputFile)
            prepare()
            start()
        }

        startForegroundService()
    }

    private fun stopRecording() {
        isRecording = false
        recorder?.apply {
            stop()
            release()
        }
        recorder = null
        
        Toast.makeText(this, "Saved: $outputFile", Toast.LENGTH_SHORT).show()
        
        // Make sure we send the stop action and wait for the service to confirm
        Intent(this, RecorderService::class.java).also { 
            it.action = RecorderService.ACTION_STOP_RECORDING
            startService(it)
        }
    }

    private fun startForegroundService() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceIntent = Intent(this, RecorderService::class.java)
            startForegroundService(serviceIntent)
        } else {
            startService(Intent(this, RecorderService::class.java))
        }
    }
}

class RecorderService : Service() {
    override fun onBind(intent: Intent?) = null

    @RequiresApi(Build.VERSION_CODES.O)
    override fun onCreate() {
        super.onCreate()
        val channelId = "RecorderServiceChannel"
        val channel = NotificationChannel(
            channelId,
            "Recording Service",
            NotificationManager.IMPORTANCE_LOW
        )
        getSystemService(NotificationManager::class.java)
            .createNotificationChannel(channel)

        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("Recording...")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .build()

        startForeground(1, notification)
    }

    companion object {
        const val ACTION_START_AI_PROCESSING = "com.example.wearnote.ACTION_START_AI_PROCESSING"
        const val ACTION_UPLOAD_AND_PROCESS = "com.example.wearnote.ACTION_UPLOAD_AND_PROCESS"
        const val ACTION_STOP_RECORDING = "com.example.wearnote.ACTION_STOP_RECORDING"
    }
}