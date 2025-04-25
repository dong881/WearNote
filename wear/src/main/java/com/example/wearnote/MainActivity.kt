package com.example.wearnote

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.lifecycleScope
import androidx.wear.compose.material.MaterialTheme
import com.example.wearnote.service.AudioRecorderService
import com.example.wearnote.ui.RecordingScreen
import com.example.wearnote.ui.theme.WearNoteTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    
    private var service: AudioRecorderService? = null
    private var bound = false
    
    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val localBinder = binder as AudioRecorderService.LocalBinder
            service = localBinder.getService()
            bound = true
        }
        
        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
            bound = false
        }
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        setContent {
            WearNoteTheme {
                val permissionsLauncher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.RequestMultiplePermissions(),
                    onResult = { permissions ->
                        val allPermissionsGranted = permissions.entries.all { it.value }
                        if (allPermissionsGranted) {
                            startRecordingIfNotRunning()
                        } else {
                            Log.e("WearNote", "Permissions not granted")
                        }
                    }
                )
                
                DisposableEffect(Unit) {
                    permissionsLauncher.launch(arrayOf(
                        Manifest.permission.RECORD_AUDIO,
                        Manifest.permission.INTERNET
                    ))
                    
                    onDispose {}
                }
                
                MainContent()
            }
        }
    }
    
    @Composable
    fun MainContent() {
        var isRecording by remember { mutableStateOf(false) }
        var uploadStatus by remember { mutableStateOf("") }
        
        if (bound && service != null) {
            val recordingState by service!!.recordingState.collectAsState()
            isRecording = recordingState == AudioRecorderService.RecordingState.RECORDING
            uploadStatus = when (recordingState) {
                AudioRecorderService.RecordingState.UPLOADING -> "Uploading..."
                AudioRecorderService.RecordingState.UPLOAD_SUCCESS -> "Upload Success"
                AudioRecorderService.RecordingState.UPLOAD_FAILED -> "Upload Failed"
                else -> ""
            }
        }

        MaterialTheme {
            RecordingScreen(
                modifier = Modifier.fillMaxSize(),
                isRecording = isRecording,
                uploadStatus = uploadStatus,
                onStopRecording = {
                    service?.stopRecording()
                }
            )
        }
    }

    override fun onStart() {
        super.onStart()
        val intent = Intent(this, AudioRecorderService::class.java)
        bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
    }

    override fun onStop() {
        super.onStop()
        if (bound) {
            unbindService(serviceConnection)
            bound = false
        }
    }
    
    private fun startRecordingIfNotRunning() {
        val intent = Intent(this, AudioRecorderService::class.java)
        startForegroundService(intent)
        lifecycleScope.launch {
            // Give the service a moment to start up and bind
            kotlinx.coroutines.delay(500)
            if (bound && service?.isRecording() == false) {
                service?.startRecording()
            }
        }
    }
}
