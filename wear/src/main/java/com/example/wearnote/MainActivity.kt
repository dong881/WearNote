package com.example.wearnote

import android.Manifest
import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
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
import com.example.wearnote.network.DriveApiClient
import com.example.wearnote.service.AudioRecorderService
import com.example.wearnote.ui.RecordingScreen
import com.example.wearnote.ui.theme.WearNoteTheme
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.Scope
import com.google.android.gms.tasks.Task
import com.google.api.services.drive.DriveScopes
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancelChildren

class MainActivity : ComponentActivity() {
    
    companion object {
        private const val TAG = "MainActivity"
        private const val SIGN_IN_REQUEST_CODE = 1001
    }
    
    private var service: AudioRecorderService? = null
    private var bound = false
    
    // UI state tracking
    private val _uiStateFlow = MutableStateFlow(UIState())
    val uiStateFlow: StateFlow<UIState> = _uiStateFlow
    
    data class UIState(
        val isRecording: Boolean = false,
        val isPaused: Boolean = false,
        val uploadStatus: String = "",
        val errorMessage: String = "",
        val debugInfo: String = "Initializing..."
    )
    
    private lateinit var googleSignInClient: GoogleSignInClient
    private lateinit var driveApiClient: DriveApiClient
    
    private val signInLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            Log.d(TAG, "Google sign-in successful")
            val task = GoogleSignIn.getSignedInAccountFromIntent(result.data)
            handleSignInResult(task)
        } else {
            Log.e(TAG, "Google sign-in failed: ${result.resultCode}")
            _uiStateFlow.value = _uiStateFlow.value.copy(
                errorMessage = "Google sign-in failed"
            )
        }
    }
    
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
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d(TAG, "onCreate called")
        
        // Initialize DriveApiClient
        driveApiClient = DriveApiClient(this)
        
        // Configure Google Sign In
        val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestEmail()
            .requestScopes(Scope(DriveScopes.DRIVE_FILE))
            .build()
        
        googleSignInClient = GoogleSignIn.getClient(this, gso)
        
        setContent {
            WearNoteTheme {
                AppContent()
            }
        }
        
        // Check if the user is already signed in
        val account = GoogleSignIn.getLastSignedInAccount(this)
        if (account != null) {
            Log.d(TAG, "User already signed in: ${account.email}")
        } else {
            Log.d(TAG, "User not signed in, requesting sign-in")
            signIn()
        }
        
        // Check and request permissions
        checkAndRequestPermissions()
    }
    
    private fun signIn() {
        try {
            val signInIntent = googleSignInClient.signInIntent
            signInLauncher.launch(signInIntent)
        } catch (e: Exception) {
            Log.e(TAG, "Error starting sign-in", e)
        }
    }
    
    private fun handleSignInResult(completedTask: Task<GoogleSignInAccount>) {
        try {
            val account = completedTask.getResult(ApiException::class.java)
            Log.d(TAG, "Signed in as: ${account.email}")
            
            // Check if we need to resume an upload
            service?.let { service ->
                if (driveApiClient.isAuthenticationRequested()) {
                    Log.d(TAG, "Resuming upload after authentication")
                    driveApiClient.resetAuthRequest()
                    val pendingFile = driveApiClient.getPendingUploadFile()
                    if (pendingFile != null) {
                        service.resumeUpload(pendingFile)
                    }
                }
            }
            
        } catch (e: ApiException) {
            Log.e(TAG, "Sign-in failed: ${e.statusCode}", e)
            _uiStateFlow.value = _uiStateFlow.value.copy(
                errorMessage = "Google sign-in failed: ${e.statusCode}"
            )
        }
    }
    
    private fun observeServiceState() {
        lifecycleScope.launch {
            try {
                service?.recordingState?.collect { state ->
                    Log.d(TAG, "Service state updated: $state")
                    _uiStateFlow.value = _uiStateFlow.value.copy(
                        isRecording = state == AudioRecorderService.RecordingState.RECORDING || 
                                     state == AudioRecorderService.RecordingState.PAUSED,
                        isPaused = state == AudioRecorderService.RecordingState.PAUSED,
                        uploadStatus = when (state) {
                            AudioRecorderService.RecordingState.UPLOADING -> "Uploading..."
                            AudioRecorderService.RecordingState.UPLOAD_SUCCESS -> "Upload Success"
                            AudioRecorderService.RecordingState.UPLOAD_FAILED -> "Upload Failed"
                            else -> ""
                        }
                    )
                }
            } catch (e: Exception) {
                // Only log the error if it's not a standard cancellation
                if (e !is kotlinx.coroutines.CancellationException) {
                    Log.e(TAG, "Error collecting state", e)
                    _uiStateFlow.value = _uiStateFlow.value.copy(
                        errorMessage = "Error tracking state: ${e.message}"
                    )
                }
            }
        }
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
            Log.d(TAG, "Starting recording service")
            val intent = Intent(this, AudioRecorderService::class.java)
            startForegroundService(intent)
            bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
            _uiStateFlow.value = _uiStateFlow.value.copy(
                debugInfo = "Starting service..."
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error starting service", e)
            _uiStateFlow.value = _uiStateFlow.value.copy(
                errorMessage = "Service start error: ${e.message}"
            )
        }
    }

    @Composable
    fun AppContent() {
        val state by uiStateFlow.collectAsState()
        
        RecordingScreen(
            modifier = Modifier.fillMaxSize(),
            isRecording = state.isRecording,
            isPaused = state.isPaused,
            uploadStatus = state.uploadStatus,
            errorMessage = state.errorMessage,
            onStopRecording = {
                Log.d(TAG, "Stop recording requested")
                service?.stopRecording()
            },
            onPauseRecording = {
                if (state.isPaused) {
                    Log.d(TAG, "Resume recording requested")
                    service?.resumeRecording()
                } else {
                    Log.d(TAG, "Pause recording requested")
                    service?.pauseRecording()
                }
            }
        )
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
                service?.recordingState?.value?.let { state ->
                    _uiStateFlow.value = _uiStateFlow.value.copy(
                        isRecording = state == AudioRecorderService.RecordingState.RECORDING || 
                                     state == AudioRecorderService.RecordingState.PAUSED,
                        isPaused = state == AudioRecorderService.RecordingState.PAUSED
                    )
                }
            }
        }
    }

    override fun onStop() {
        super.onStop()
        Log.d(TAG, "onStop called")
        // Use the correct method to cancel coroutines
        lifecycleScope.coroutineContext.cancelChildren()
        
        if (bound) {
            unbindService(serviceConnection)
            bound = false
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "onDestroy called - service should continue running in background")
    }
}
