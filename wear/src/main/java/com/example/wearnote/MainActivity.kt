package com.example.wearnote

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.wear.compose.material.MaterialTheme
import com.example.wearnote.service.AudioRecorderService
import com.example.wearnote.ui.RecordingScreen
import com.example.wearnote.ui.theme.WearNoteTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    
    companion object {
        private const val TAG = "MainActivity"
    }
    
    private var service: AudioRecorderService? = null
    private var bound = false
    
    // UI state tracking
    private val _uiStateFlow = MutableStateFlow(UIState())
    val uiStateFlow: StateFlow<UIState> = _uiStateFlow
    
    data class UIState(
        val isRecording: Boolean = false,
        val uploadStatus: String = "",
        val errorMessage: String = "",
        val debugInfo: String = "Initializing..."
    )
    
    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val localBinder = binder as? AudioRecorderService.LocalBinder
            if (localBinder != null) {
                service = localBinder.getService()
                bound = true
                Log.d(TAG, "Service connected successfully")
                
                // Start observing service state
                observeServiceState()
            } else {
                Log.e(TAG, "Failed to cast binder")
                _uiStateFlow.value = _uiStateFlow.value.copy(
                    errorMessage = "Service binding failed"
                )
            }
        }
        
        override fun onServiceDisconnected(name: ComponentName?) {
            Log.d(TAG, "Service disconnected")
            service = null
            bound = false
        }
    }
    
    private fun observeServiceState() {
        lifecycleScope.launch {
            try {
                service?.recordingState?.collect { state ->
                    Log.d(TAG, "Service state updated: $state")
                    _uiStateFlow.value = _uiStateFlow.value.copy(
                        isRecording = state == AudioRecorderService.RecordingState.RECORDING,
                        uploadStatus = when (state) {
                            AudioRecorderService.RecordingState.UPLOADING -> "Uploading..."
                            AudioRecorderService.RecordingState.UPLOAD_SUCCESS -> "Upload Success"
                            AudioRecorderService.RecordingState.UPLOAD_FAILED -> "Upload Failed"
                            else -> ""
                        },
                        debugInfo = "Service state: $state"
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error collecting state", e)
                _uiStateFlow.value = _uiStateFlow.value.copy(
                    errorMessage = "Error tracking state: ${e.message}",
                    debugInfo = "Collection error: ${e.javaClass.simpleName}"
                )
            }
        }
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d(TAG, "onCreate called")
        
        setContent {
            WearNoteTheme {
                Box(modifier = Modifier
                    .fillMaxSize()
                    .background(Color.DarkGray)
                ) {
                    AppContent()
                }
            }
        }
        
        // Request permissions first, then start recording service
        checkAndRequestPermissions()
    }
    
    @Composable
    fun AppContent() {
        val state by _uiStateFlow.collectAsState()
        
        // Only log debug info, don't display it on the UI
        DisposableEffect(state) {
            Log.d(TAG, "UI State: isRecording=${state.isRecording}, " +
                   "uploadStatus=${state.uploadStatus}, errorMsg=${state.errorMessage}")
            onDispose {}
        }
        
        RecordingScreen(
            modifier = Modifier.fillMaxSize(),
            isRecording = state.isRecording,
            uploadStatus = state.uploadStatus,
            errorMessage = state.errorMessage,
            onStopRecording = {
                Log.d(TAG, "Stop recording requested")
                service?.stopRecording() ?: run {
                    Log.e(TAG, "Cannot stop - service is null")
                    _uiStateFlow.value = _uiStateFlow.value.copy(
                        errorMessage = "Service unavailable"
                    )
                }
            }
        )
    }

    private fun checkAndRequestPermissions() {
        // Check if we have all required permissions
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != 
                PackageManager.PERMISSION_GRANTED) {
            
            Log.d(TAG, "Requesting RECORD_AUDIO permission")
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.RECORD_AUDIO),
                100
            )
        } else {
            Log.d(TAG, "RECORD_AUDIO permission already granted, starting service")
            startRecordingService()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        
        if (requestCode == 100) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                Log.d(TAG, "RECORD_AUDIO permission granted by user")
                startRecordingService()
            } else {
                Log.e(TAG, "RECORD_AUDIO permission denied")
                _uiStateFlow.value = _uiStateFlow.value.copy(
                    errorMessage = "Recording permission required"
                )
                Toast.makeText(
                    this,
                    "Recording permission is required",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }
    
    private fun startRecordingService() {
        try {
            Log.d(TAG, "Starting and binding to recording service")
            val intent = Intent(this, AudioRecorderService::class.java)
            
            // Start as foreground service to keep it running in background
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
            
            // Bind to the service
            bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
        } catch (e: Exception) {
            Log.e(TAG, "Error starting recording service", e)
        }
    }

    override fun onStart() {
        super.onStart()
        Log.d(TAG, "onStart called")
    }

    override fun onResume() {
        super.onResume()
        Log.d(TAG, "onResume called")
        
        // Update state if we're already bound
        if (bound && service != null) {
            Log.d(TAG, "Service already bound, updating state")
            lifecycleScope.launch {
                _uiStateFlow.value = _uiStateFlow.value.copy(
                    isRecording = service?.isRecording() ?: false,
                    debugInfo = "Resume: recording=${service?.isRecording()}"
                )
            }
        }
    }

    override fun onStop() {
        super.onStop()
        Log.d(TAG, "onStop called")
        if (bound) {
            unbindService(serviceConnection)
            bound = false
        }
    }
    
    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "onDestroy called - service should continue running in background")
        
        // Unbind but don't stop the service
        if (bound) {
            unbindService(serviceConnection)
            bound = false
        }
    }
}
