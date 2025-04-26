package com.example.wearnote

import android.Manifest
import android.accounts.AccountManager
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
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
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
import com.example.wearnote.util.GoogleApiSetupHelper
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.Scope
import com.google.android.gms.tasks.Task
import com.google.api.services.drive.DriveScopes
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.File

class MainActivity : ComponentActivity() {

    companion object {
        private const val TAG = "MainActivity"
        private const val RC_SIGN_IN = 9001
    }

    private var service: AudioRecorderService? = null
    private var bound = false
    private var googleSignInAttempted = false

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
    private var pendingUploadFile: File? = null

    private val googleSignInLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            Log.d(TAG, "Google Sign-In successful")
            val task = GoogleSignIn.getSignedInAccountFromIntent(result.data)
            handleSignInResult(task)
        } else {
            Log.d(TAG, "Google Sign-In canceled or failed")
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
        val driveApiClient = DriveApiClient(this)

        // Initialize Google Sign-In
        val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestEmail()
            .requestScopes(Scope(DriveScopes.DRIVE_FILE))
            .build()

        googleSignInClient = GoogleSignIn.getClient(this, gso)

        // Check and show API setup instructions if needed
        checkAndShowApiSetupInstructions()

        setContent {
            WearNoteTheme {
                AppContent()
            }
        }

        // Check permission first, worry about Google sign-in later
        checkAndRequestPermissions()
    }

    private fun handleSignInResult(task: Task<GoogleSignInAccount>) {
        try {
            val account = task.getResult(ApiException::class.java)
            Log.d(TAG, "Signed in as: ${account.email}")

            // Check if we need to resume an upload
            checkPendingUploads()
        } catch (e: ApiException) {
            Log.e(TAG, "Sign-in failed: ${e.statusCode}", e)
        }
    }

    private fun requestGoogleSignIn(fileToUpload: File? = null) {
        pendingUploadFile = fileToUpload
        try {
            val signInIntent = googleSignInClient.signInIntent
            googleSignInLauncher.launch(signInIntent)
        } catch (e: Exception) {
            Log.e(TAG, "Error starting Google Sign-In", e)
            Toast.makeText(
                this,
                "Error starting Google Sign-In: ${e.message}",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun checkPendingUploads() {
        val prefs = getSharedPreferences("WearNotePrefs", Context.MODE_PRIVATE)
        val pendingFilePath = prefs.getString("pending_upload_file", null)

        if (!pendingFilePath.isNullOrEmpty()) {
            val file = File(pendingFilePath)
            if (file.exists() && file.length() > 0L) {
                Log.d(TAG, "Found pending upload: $pendingFilePath")

                // Clear from preferences
                prefs.edit().remove("pending_upload_file").apply()

                // Resume upload
                service?.resumeUpload(file)
            }
        }

        // Also check the pendingUploadFile property
        pendingUploadFile?.let { file ->
            if (file.exists() && file.length() > 0L) {
                Log.d(TAG, "Resuming upload from pending file: ${file.absolutePath}")
                service?.resumeUpload(file)
                pendingUploadFile = null
            }
        }
    }

    private fun clearErrorMessage() {
        if (_uiStateFlow.value.errorMessage.contains("Google sign-in")) {
            _uiStateFlow.value = _uiStateFlow.value.copy(
                errorMessage = ""
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
                if (e !is kotlinx.coroutines.CancellationException) {
                    Log.e(TAG, "Error collecting state", e)
                }
            }
        }
    }

    private fun checkAndRequestPermissions() {
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

            // Try Google sign-in only after we've started recording
            // This way the app's core functionality works even if sign-in fails
            requestGoogleSignIn()
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
                requestGoogleSignIn()
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
        } catch (e: Exception) {
            Log.e(TAG, "Error starting service", e)
            _uiStateFlow.value = _uiStateFlow.value.copy(
                errorMessage = "Service start error: ${e.message}"
            )
        }
    }

    private fun checkAndRequestDrivePermission(file: File? = null) {
        val driveClient = DriveApiClient(this)

        if (driveClient.needsDrivePermission()) {
            Log.d(TAG, "Drive permission needed, launching sign-in")
            pendingUploadFile = file
            requestGoogleSignIn()
        } else {
            Log.d(TAG, "Drive permission already granted")
        }
    }

    /**
     * Checks if this is a development build and shows API setup instructions if needed
     */
    private fun checkAndShowApiSetupInstructions() {
        // Instead of using BuildConfig.DEBUG, use a simple flag
        val isDevelopmentBuild = true // Change to false for release builds

        if (isDevelopmentBuild) {
            // Check for google-services.json
            val googleServicesFile = File(applicationContext.applicationInfo.sourceDir)
                .parentFile?.parentFile?.resolve("google-services.json")

            if (googleServicesFile == null || !googleServicesFile.exists()) {
                Log.i(TAG, "google-services.json not found, showing setup instructions")
                // Show instructions if we need to implement this
            }
        }
    }

    @Composable
    fun AppContent() {
        val state by uiStateFlow.collectAsState()

        Box(modifier = Modifier.fillMaxSize()) {
            RecordingScreen(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black),
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
                },
                onRequestSignIn = {
                    Log.d(TAG, "Google Sign-In requested by user")
                    requestGoogleSignIn()
                }
            )
        }
    }

    override fun onStart() {
        super.onStart()
        Log.d(TAG, "onStart called")
    }

    override fun onResume() {
        super.onResume()
        Log.d(TAG, "onResume called")

        // Check if user is signed in
        val account = GoogleSignIn.getLastSignedInAccount(this)
        val hasDriveScope = account != null &&
                GoogleSignIn.hasPermissions(account, Scope(DriveScopes.DRIVE_FILE))

        if (account != null) {
            Log.d(TAG, "User signed in: ${account.email}, has Drive scope: $hasDriveScope")

            // If we have proper permissions, check for pending uploads
            if (hasDriveScope) {
                checkPendingUploads()
            }
        } else {
            Log.d(TAG, "No user signed in")
        }
    }

    override fun onStop() {
        super.onStop()
        Log.d(TAG, "onStop called")
        lifecycleScope.coroutineContext.cancelChildren()

        if (bound) {
            unbindService(serviceConnection)
            bound = false
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "onDestroy called")
    }
}
