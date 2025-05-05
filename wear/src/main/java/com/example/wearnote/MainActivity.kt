package com.example.wearnote

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
// Add this import for RECEIVER_NOT_EXPORTED constant
import android.content.Context.RECEIVER_NOT_EXPORTED
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.wear.compose.material.*
import com.example.wearnote.service.RecorderService
import com.example.wearnote.auth.ConfigHelper
import com.example.wearnote.auth.KeystoreHelper
import com.example.wearnote.service.PendingUploadsManager
import com.example.wearnote.ui.PendingUploadsScreen
import com.example.wearnote.service.GoogleDriveUploader  // Fixed: Changed from upload to service package
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// Google Sign-In imports
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.CommonStatusCodes
import com.google.android.gms.common.api.Scope

// Google Drive API imports
import com.google.api.client.http.javanet.NetHttpTransport
import com.google.api.client.json.gson.GsonFactory
import com.google.api.client.googleapis.extensions.android.gms.auth.GoogleAccountCredential
import com.google.api.services.drive.Drive
import com.google.api.services.drive.DriveScopes

class MainActivity : ComponentActivity() {
    companion object {
        private const val TAG = "MainActivity"
        private const val PREFS_NAME = "WearNotePrefs"
        private const val KEY_FIRST_LAUNCH_DONE = "firstLaunchDone"
        
        // Constants for broadcast communication
        const val ACTION_RECORDING_STATUS = "com.example.wearnote.ACTION_RECORDING_STATUS"
        const val EXTRA_STATUS = "recording_status"
        const val STATUS_UPLOAD_COMPLETED = "upload_completed"
        const val EXTRA_FILE_ID = "file_id"
        
        // Add new constants
        const val STATUS_UPLOAD_STARTED = "upload_started"
        const val STATUS_RECORDING_DISCARDED = "recording_discarded"
        const val STATUS_RECORDING_STARTED = "recording_started"  // New status for recording started confirmation

        // Add this constant for Google Drive permission handling
        private const val REQUEST_AUTHORIZATION = 1001
    }

    private enum class RecordingState {
        IDLE, RECORDING, PAUSED
    }
    
    private var currentRecordingState = RecordingState.IDLE
    private lateinit var googleSignInClient: GoogleSignInClient
    private var driveService: Drive? = null
    
    // Shared mutable state for composables to access
    private val isPaused = mutableStateOf(false)
    private val elapsedTime = mutableStateOf(0L)
    private val isRecording = mutableStateOf(false)
    private val isUploading = mutableStateOf(false)
    private val fileId = mutableStateOf<String?>(null)
    private val isRecordingStartConfirmed = mutableStateOf(false)
    private val isAutoStartInProgress = mutableStateOf(false)
    private val userInteracted = mutableStateOf(false)
    
    // Improved broadcast receiver with more logging
    private val recordingStatusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            Log.d(TAG, "***** BROADCAST RECEIVED in MainActivity *****")
            Log.d(TAG, "Action: ${intent.action}")
            Log.d(TAG, "Extras: ${intent.extras?.keySet()?.joinToString()}")
            
            if (intent.action == ACTION_RECORDING_STATUS) {
                val status = intent.getStringExtra(EXTRA_STATUS)
                Log.d(TAG, "Status from broadcast: $status")
                
                when (status) {
                    STATUS_UPLOAD_COMPLETED -> {
                        val id = intent.getStringExtra(EXTRA_FILE_ID)
                        Log.d(TAG, "Upload completed with file ID: $id")
                        
                        // Update state
                        fileId.value = id
                        isUploading.value = false
                        isRecording.value = false
                        isPaused.value = false
                        elapsedTime.value = 0
                        currentRecordingState = RecordingState.IDLE
                        
                        // Show success message and force UI update on main thread
                        runOnUiThread {
                            Toast.makeText(context, "Upload successful!", Toast.LENGTH_SHORT).show()
                            showInitialUI(autoStartRecording = false)  // Don't auto-start after upload
                            Log.d(TAG, "UI updated to initial state after upload")
                        }
                    }
                    
                    STATUS_RECORDING_DISCARDED -> {
                        Log.d(TAG, "Recording discarded")
                        // Reset states
                        isUploading.value = false
                        isRecording.value = false
                        isPaused.value = false
                        elapsedTime.value = 0
                        
                        // Show initial UI and reset state
                        showInitialUI(autoStartRecording = false)  // Don't auto-start after discard
                        
                        // Show a message about discarding
                        Toast.makeText(context, "Recording discarded", Toast.LENGTH_SHORT).show()
                    }
                    
                    STATUS_RECORDING_STARTED -> {
                        Log.d(TAG, "Recording started confirmation received")
                        isRecordingStartConfirmed.value = true
                        
                        // If auto-start is in progress, now we can safely return to home
                        if (isAutoStartInProgress.value) {
                            Log.d(TAG, "Auto-start confirmed, returning to home")
                            moveTaskToBack(true)
                            isAutoStartInProgress.value = false
                        }
                    }
                }
            }
        }
    }
    
    // Add this as a class member
    private val permissionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            Log.d(TAG, "Received permission request broadcast")
            checkPendingPermissionRequests()
        }
    }
    
    private val permLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { perms ->
        onPermissionsGranted(perms.values.all { it })
    }
    
    private val googleSignInLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        handleSignInResult(result.data)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        Log.d(TAG, "Registering broadcast receiver")
        // Register broadcast receiver with proper flags
        val filter = IntentFilter(ACTION_RECORDING_STATUS)
        
        // Fix security exception for Android 13 (API 33) and higher
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(recordingStatusReceiver, filter, RECEIVER_NOT_EXPORTED)
            Log.d(TAG, "Registered receiver with RECEIVER_NOT_EXPORTED flag")
        } else {
            // For older Android versions
            registerReceiver(recordingStatusReceiver, filter)
            Log.d(TAG, "Registered receiver without flags")
        }
        
        // Register for permission broadcasts
        val permissionFilter = IntentFilter("com.example.wearnote.NEED_PERMISSION")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(permissionReceiver, permissionFilter, RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(permissionReceiver, permissionFilter)
        }
        
        // Check for ongoing uploads when app starts
        checkPendingUploads()
        
        // Configure Google Sign-In with Drive API scopes
        val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestEmail()
            .requestScopes(Scope(DriveScopes.DRIVE_FILE), Scope(DriveScopes.DRIVE))
            .build()
        googleSignInClient = GoogleSignIn.getClient(this, gso)
        
        // Request permissions
        permLauncher.launch(arrayOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.FOREGROUND_SERVICE,
            Manifest.permission.INTERNET
         ))
        
        // Check for pending permission requests at startup
        checkPendingPermissionRequests()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
            autoStartRecordingAndGoHome()
        }
    }

    private fun onPermissionsGranted(areGranted: Boolean) {
        if (areGranted) {
            // Check Google Sign-In and proceed with auto-recording
            val account = GoogleSignIn.getLastSignedInAccount(this)
            if (account != null) {
                createDriveService(account)
                autoStartRecordingAndGoHome()
            } else {
                val signInIntent = googleSignInClient.signInIntent
                googleSignInLauncher.launch(signInIntent)
            }
        } else {
            Toast.makeText(this, getString(R.string.permission_denied), Toast.LENGTH_LONG).show()
            finish()
        }
    }

    private fun autoStartRecordingAndGoHome() {
        // Check if already recording
        if (currentRecordingState != RecordingState.RECORDING && currentRecordingState != RecordingState.PAUSED) {
            Log.d(TAG, "Auto-starting recording and waiting for confirmation")
            
            // Reset interaction flag and confirmation flags
            userInteracted.value = false
            isRecordingStartConfirmed.value = false
            isAutoStartInProgress.value = true
            
            // Start recording
            startRecording()
            
            // Set a longer timeout to wait for confirmation or timeout
            Handler(Looper.getMainLooper()).postDelayed({
                if (!isRecordingStartConfirmed.value && isAutoStartInProgress.value) {
                    // Only return to home if user hasn't interacted
                    if (!userInteracted.value) {
                        Log.d(TAG, "No user interaction detected, returning to home")
                        moveTaskToBack(true)
                    } else {
                        Log.d(TAG, "User interaction detected, staying in app")
                    }
                    isAutoStartInProgress.value = false
                }
            }, 3000) // 3-second timeout
        } else {
            Log.d(TAG, "Already recording, showing recording UI")
            // Already recording, just show the recording UI
            setContent { 
                WearNoteTheme {
                    RecordingControlUI() 
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            unregisterReceiver(recordingStatusReceiver)
        } catch (e: IllegalArgumentException) {
            // Receiver might not be registered
        }
        // Don't forget to unregister the receiver
        try {
            unregisterReceiver(permissionReceiver)
        } catch (e: Exception) {
            // Receiver might not be registered
        }
    }
    
    private fun checkGoogleSignIn() {
        val account = GoogleSignIn.getLastSignedInAccount(this)
        if (account != null) {
            createDriveService(account)
            checkFirstLaunch()
        } else {
            val signInIntent = googleSignInClient.signInIntent
            googleSignInLauncher.launch(signInIntent)
        }
    }

    private fun handleSignInResult(data: Intent?) {
        try {
            val task = GoogleSignIn.getSignedInAccountFromIntent(data)
            val account = task.getResult(ApiException::class.java)
            
            // Create Drive service after successful authentication
            createDriveService(account)
            Log.d(TAG, "Signed in as: ${account.email}")
            Log.d(TAG, "Drive API access granted")
            
            // Auto-start recording and go home after successful sign-in
            autoStartRecordingAndGoHome()
        } catch (e: ApiException) {
            Log.e(TAG, "Google Sign-In Failed: ${e.statusCode} - ${e.message}")
            when (e.statusCode) {
                CommonStatusCodes.DEVELOPER_ERROR -> {
                    Log.e(TAG, "開發者錯誤 (10) - 表示 CLIENT_ID 或 SHA-1 指紋配置錯誤")
                    // 輸出診斷資訊
                    ConfigHelper.checkDeveloperConfiguration(this)
                    KeystoreHelper.printKeystoreDebugInfo(this)
                    KeystoreHelper.logShaFixInstructions()
                    showErrorMessage("登入失敗: 開發者配置錯誤，請檢查 Logcat 獲取詳細資訊")
                }
                CommonStatusCodes.NETWORK_ERROR -> {
                    Log.e(TAG, "網絡錯誤 - 請檢查網絡連接")
                    showErrorMessage("登入失敗: 網絡連接問題")
                }
                CommonStatusCodes.CANCELED -> {
                    Log.d(TAG, "使用者取消登入")
                    showErrorMessage("登入已取消")
                }
                else -> {
                    Log.e(TAG, "其他錯誤: ${e.statusCode}")
                    showErrorMessage("登入失敗: 代碼 ${e.statusCode}")
                }
            }
            finish() // Exit on authentication failure
        }
    }
    
    private fun createDriveService(account: GoogleSignInAccount) {
        val credential = GoogleAccountCredential.usingOAuth2(
            this, listOf(DriveScopes.DRIVE_FILE, DriveScopes.DRIVE)
        )
        credential.selectedAccount = account.account
        driveService = Drive.Builder(
            NetHttpTransport(),
            GsonFactory.getDefaultInstance(),
            credential
        ).setApplicationName(getString(R.string.app_name)).build()
    }

    private fun showErrorMessage(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }
    
    private fun checkFirstLaunch() {
        if (!isFirstLaunchDone()) {
            // Only for first launch
            Log.d(TAG, "First launch detected")
            handleFirstLaunch()
            markFirstLaunchDone()
            
            // Start recording only on first launch
            startRecording()
        } else {
            // For subsequent launches, just show the initial UI
            Log.d(TAG, "Subsequent launch detected, showing normal UI")
            showInitialUI(autoStartRecording = false)
        }
    }
    
    // Make sure our first launch detection is reliable
    private fun isFirstLaunchDone(): Boolean {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getBoolean(KEY_FIRST_LAUNCH_DONE, false)
    }
    
    private fun markFirstLaunchDone() {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putBoolean(KEY_FIRST_LAUNCH_DONE, true).apply() // Use apply() for asynchronous write
        Log.d(TAG, "First launch flag has been set")
    }

    private fun handleFirstLaunch() {
        // First launch actions - could initialize Drive folders, etc.
        Log.d(TAG, "First launch detected")
    }

    private fun startRecording() {
        Log.d(TAG, "Starting recording")
        Intent(this, RecorderService::class.java).also {
            it.action = RecorderService.ACTION_START_RECORDING
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(it) // Always use startForegroundService for API 26+
            } else {
                startService(it)
            }
        }
        currentRecordingState = RecordingState.RECORDING
        isRecording.value = true
        isPaused.value = false
        elapsedTime.value = 0
        
        // Update UI to recording state
        setContent { 
            WearNoteTheme {
                RecordingControlUI() 
            }
        }
    }

    private fun stopRecording() {
        Log.d(TAG, "Stopping recording")
        
        // Reset state immediately
        isRecording.value = false  
        isPaused.value = false
        currentRecordingState = RecordingState.IDLE
        
        // Show initial UI immediately instead of uploading UI
        showInitialUI(autoStartRecording = false)
        
        // Show a small notification that upload is in progress
        Toast.makeText(this, "Uploading in background...", Toast.LENGTH_SHORT).show()
        
        // Make sure we send the stop action and wait for the service to confirm
        Intent(this, RecorderService::class.java).also { 
            it.action = RecorderService.ACTION_STOP_RECORDING
            startService(it)
        }
    }
    
    // Add a new function to discard recording
    private fun discardRecording() {
        Log.d(TAG, "Discarding recording")
        isRecording.value = false  // Stop timer immediately
        
        // Send discard action to service
        Intent(this, RecorderService::class.java).also { 
            it.action = RecorderService.ACTION_DISCARD_RECORDING
            startService(it)
        }
        currentRecordingState = RecordingState.IDLE
        
        // Show initial UI immediately after discarding
        showInitialUI(autoStartRecording = false)
    }
    
    private fun togglePauseResumeRecording(isPaused: Boolean) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            Log.d(TAG, if (isPaused) "Resuming recording" else "Pausing recording")
            val action = if (isPaused) 
                RecorderService.ACTION_RESUME_RECORDING 
            else
                RecorderService.ACTION_PAUSE_RECORDING
                
            Intent(this, RecorderService::class.java).also {
                it.action = action
                startService(it)
            }
            currentRecordingState = if (isPaused) RecordingState.RECORDING else RecordingState.PAUSED
        }
    }
    
    private fun showInitialUI(autoStartRecording: Boolean = false, forceRefresh: Boolean = false) {
        // Reset recording state
        isRecording.value = false
        isPaused.value = false
        elapsedTime.value = 0
        currentRecordingState = RecordingState.IDLE
        
        // Add this to ensure PendingUploadsManager is properly initialized
        if (forceRefresh) {
            PendingUploadsManager.initialize(this)
        }
        
        if (autoStartRecording) {
            // Reset the interaction flag
            userInteracted.value = false
            
            // Start recording automatically and return to home
            Handler(Looper.getMainLooper()).postDelayed({
                startRecording()
                
                // Return to home screen after a short delay, but only if no interaction
                Handler(Looper.getMainLooper()).postDelayed({
                    if (!userInteracted.value) {
                        moveTaskToBack(true)
                    } else {
                        Log.d(TAG, "User interaction detected, staying in app")
                    }
                }, 1000)
            }, 500)
        }
        
        // Show initial UI
        setContent { 
            WearNoteTheme {
                ScalingLazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    item {
                        Text(
                            text = getString(R.string.app_name),
                            style = MaterialTheme.typography.title1,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    
                    item {
                        Spacer(modifier = Modifier.height(16.dp))
                        Button(
                            onClick = { startRecording() },
                            modifier = Modifier
                                .fillMaxWidth(0.7f)
                                .padding(vertical = 4.dp),
                            colors = ButtonDefaults.buttonColors(
                                backgroundColor = MaterialTheme.colors.primary
                            )
                        ) {
                            Icon(
                                painter = painterResource(id = R.drawable.ic_mic),
                                contentDescription = "Start Recording",
                                modifier = Modifier.size(24.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                        }
                    }
                    
                    // Add Pending Uploads button 
                    item {
                        val pendingUploadsCount = remember {
                            mutableStateOf(0)
                        }
                        
                        // Force an immediate update on composition
                        LaunchedEffect(forceRefresh) {
                            pendingUploadsCount.value = PendingUploadsManager.getPendingUploadCount()
                        }
                        
                        // Observe changes in pending uploads
                        LaunchedEffect(Unit) {
                            PendingUploadsManager.pendingUploadsFlow.collect {
                                pendingUploadsCount.value = it.size
                            }
                        }
                        
                        if (pendingUploadsCount.value > 0) {
                            Spacer(modifier = Modifier.height(8.dp))
                            Button(
                                onClick = { showPendingUploadsScreen() },
                                modifier = Modifier
                                    .fillMaxWidth(0.7f)
                                    .padding(vertical = 4.dp),
                                colors = ButtonDefaults.buttonColors(
                                    backgroundColor = Color.DarkGray
                                )
                            ) {
                                Text("Pending (${pendingUploadsCount.value})")
                            }
                        }
                    }
                }
            }
        }
    }

    // Fix the function that shows the pending uploads screen
    private fun showPendingUploadsScreen() {
        setContent {
            WearNoteTheme {
                PendingUploadsScreen(
                    onBackClick = { 
                        // Update to pass the correct parameter
                        showInitialUI(autoStartRecording = false)
                    }
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        
        if (currentRecordingState == RecordingState.IDLE) {
            Log.d(TAG, "App resumed while idle, checking if we should show initial UI")
            // Don't auto-start recording on resume, just show appropriate UI
            showInitialUI(autoStartRecording = false)
        } else {
            Log.d(TAG, "App resumed while recording/paused, showing appropriate UI")
            // If already recording, just make sure the UI matches the state
            if (currentRecordingState == RecordingState.RECORDING || currentRecordingState == RecordingState.PAUSED) {
                setContent { 
                    WearNoteTheme {
                        RecordingControlUI() 
                    }
                }
            }
        }

        // Check for pending permission requests when the activity resumes
        checkPendingPermissionRequests()
    }

    // Add this method to check and handle pending permission requests
    private fun checkPendingPermissionRequests() {
        if (GoogleDriveUploader.hasPermissionRequest()) {
            val permissionIntent = GoogleDriveUploader.getPermissionIntent()
            permissionIntent?.let {
                try {
                    // Start the permission request activity
                    startActivityForResult(it, GoogleDriveUploader.REQUEST_AUTHORIZATION)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to start permission request: ${e.message}")
                    Toast.makeText(this, "Failed to request Google Drive permissions", Toast.LENGTH_SHORT).show()
                    GoogleDriveUploader.clearPermissionIntent()
                }
            }
        }
    }

    // Handle permission result
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        
        if (requestCode == GoogleDriveUploader.REQUEST_AUTHORIZATION) {
            if (GoogleDriveUploader.handleAuthResult(resultCode)) {
                // Permission granted, try to process pending uploads
                Toast.makeText(this, "Google Drive permissions granted", Toast.LENGTH_SHORT).show()
                lifecycleScope.launch {
                    GoogleDriveUploader.processPending(this@MainActivity)
                }
            } else {
                // Permission denied
                Toast.makeText(this, "Google Drive permissions denied", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // Check if there are any pending uploads when app starts
    private fun checkPendingUploads() {
        Intent(this, RecorderService::class.java).also {
            it.action = RecorderService.ACTION_CHECK_UPLOAD_STATUS
            startService(it)
        }
    }
    
    override fun onPause() {
        super.onPause()
        // Don't stop the service or recording when app is paused
        Log.d(TAG, "MainActivity paused, allowing background recording to continue")
    }

    override fun onStop() {
        super.onStop()
        // Don't stop the service or recording when app is stopped
        Log.d(TAG, "MainActivity stopped, allowing background recording to continue")
    }
    
    @Composable
    fun WearNoteTheme(content: @Composable () -> Unit) {
        MaterialTheme(content = content)
    }

    @Composable
    private fun RecordingControlUI() {
        val isPausedState = remember { isPaused }
        val elapsedTimeState = remember { elapsedTime }
        val isRecordingState = remember { isRecording }
        
        // Timer effect
        LaunchedEffect(key1 = isPausedState.value, key2 = isRecordingState.value) {
            while(isRecordingState.value && !isPausedState.value) {
                delay(1000)
                elapsedTimeState.value += 1
            }
        }
        
        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectTapGestures { 
                        userInteracted.value = true
                    }
                },
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = formatTime(elapsedTimeState.value),
                    color = Color.White,
                    fontSize = 20.sp,
                    textAlign = TextAlign.Center
                )
                
                Spacer(modifier = Modifier.height(12.dp))
                
                // Pause/Resume Button
                Button(
                    onClick = {
                        userInteracted.value = true  // Set flag when button is pressed
                        togglePauseResumeRecording(isPausedState.value)
                        isPausedState.value = !isPausedState.value
                    },
                    modifier = Modifier
                        .size(60.dp)
                        .padding(4.dp),
                    shape = CircleShape,
                    colors = ButtonDefaults.buttonColors(
                        backgroundColor = MaterialTheme.colors.primary,
                        contentColor = Color.White
                    )
                ) {
                    Icon(
                        painter = painterResource(
                            id = if (isPausedState.value)
                                R.drawable.ic_play
                            else
                                R.drawable.ic_pause
                        ),
                        contentDescription = if (isPausedState.value) "Resume" else "Pause",
                        modifier = Modifier.size(24.dp)
                    )
                }
                
                Spacer(modifier = Modifier.height(12.dp))
                
                // Action buttons row - Stop and Discard
                Row(
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Stop Button
                    Button(
                        onClick = { 
                            userInteracted.value = true  // Set flag when button is pressed
                            stopRecording() 
                        },
                        modifier = Modifier.size(60.dp),
                        shape = CircleShape,
                        colors = ButtonDefaults.buttonColors(
                            backgroundColor = Color.Red,
                            contentColor = Color.White
                        )
                    ) {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_stop),
                            contentDescription = "Stop Recording",
                            modifier = Modifier.size(24.dp)
                        )
                    }
                    
                    // New Discard Button
                    Button(
                        onClick = { 
                            userInteracted.value = true  // Set flag when button is pressed
                            discardRecording() 
                        },
                        modifier = Modifier.size(60.dp),
                        shape = CircleShape,
                        colors = ButtonDefaults.buttonColors(
                            backgroundColor = Color.Gray,
                            contentColor = Color.White
                        )
                    ) {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_delete),
                            contentDescription = "Discard Recording",
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }
            }
        }
    }
    
    @Composable
    private fun UploadingUI() {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "Uploading...",
                    color = Color.White,
                    fontSize = 18.sp,
                    textAlign = TextAlign.Center
                )
                
                Spacer(modifier = Modifier.height(16.dp))
                
                CircularProgressIndicator(
                    modifier = Modifier.size(48.dp),
                    indicatorColor = MaterialTheme.colors.primary,
                    strokeWidth = 4.dp
                )
            }
        }
    }
    
    @Composable
    private fun UploadCompletedUI(fileId: String) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "Upload Complete!",
                    color = Color.White,
                    fontSize = 20.sp,
                    textAlign = TextAlign.Center
                )
                
                Spacer(modifier = Modifier.height(8.dp))
                
                Text(
                    text = "ID: ${fileId.takeLast(8)}...",
                    color = Color.White,
                    fontSize = 14.sp,
                    textAlign = TextAlign.Center
                )
                
                Spacer(modifier = Modifier.height(16.dp))
                
                Icon(
                    painter = painterResource(id = R.drawable.ic_check),
                    contentDescription = "Success",
                    modifier = Modifier.size(48.dp),
                    tint = Color.Green
                )
            }
        }
    }
    
    private fun formatTime(seconds: Long): String {
        val hours = seconds / 3600
        val minutes = (seconds % 3600) / 60
        val secs = seconds % 60
        
        return if (hours > 0) {
            String.format("%d:%02d:%02d", hours, minutes, secs)
        } else {
            String.format("%02d:%02d", minutes, secs)
        }
    }
}
