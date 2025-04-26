package com.example.wearnote

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.wear.compose.material.Button
import androidx.wear.compose.material.ButtonDefaults
import androidx.wear.compose.material.Icon
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import com.example.wearnote.auth.GoogleSignInHandler
import com.example.wearnote.services.RecordingService
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.tasks.Task
import kotlinx.coroutines.delay
import java.util.concurrent.TimeUnit

class MainActivity : ComponentActivity() {
    
    private var recordingState by mutableStateOf(RecordingState.INITIALIZING)
    private var elapsedTime by mutableStateOf(0L)
    private var recordingStartTime by mutableStateOf(0L)
    private var showSettings by mutableStateOf(false)
    private var showSignInPrompt by mutableStateOf(false)
    private var selectedAudioSource by mutableStateOf(RecordingService.AUDIO_SOURCE_WATCH)
    private lateinit var preferences: SharedPreferences
    private lateinit var googleSignInHandler: GoogleSignInHandler
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        
        preferences = getSharedPreferences("recording_prefs", MODE_PRIVATE)
        selectedAudioSource = preferences.getInt(RecordingService.PREF_AUDIO_SOURCE, RecordingService.AUDIO_SOURCE_WATCH)
        googleSignInHandler = GoogleSignInHandler(this)
        
        setContent {
            WearNoteTheme {
                val permissionsToRequest = arrayOf(
                    Manifest.permission.RECORD_AUDIO,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE,
                    Manifest.permission.READ_EXTERNAL_STORAGE,
                    Manifest.permission.INTERNET,
                    Manifest.permission.ACCESS_NETWORK_STATE
                )
                
                var permissionsGranted by remember { mutableStateOf(false) }
                
                val permissionLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestMultiplePermissions()
                ) { permissions -> 
                    permissionsGranted = permissions.values.all { it }
                    if (permissionsGranted) {
                        requestIgnoreBatteryOptimization()
                        checkGoogleSignIn()
                    }
                }
                
                val googleSignInLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.StartActivityForResult()
                ) { result -> 
                    handleSignInResult(result.resultCode, result.data)
                }
                
                LaunchedEffect(true) {
                    val allGranted = permissionsToRequest.all {
                        ContextCompat.checkSelfPermission(this@MainActivity, it) == 
                            PackageManager.PERMISSION_GRANTED
                    }
                    
                    if (allGranted) {
                        permissionsGranted = true
                        requestIgnoreBatteryOptimization()
                        checkGoogleSignIn()
                    } else {
                        permissionLauncher.launch(permissionsToRequest)
                    }
                }
                
                // Update elapsed time while recording
                LaunchedEffect(recordingState) {
                    if (recordingState == RecordingState.RECORDING || recordingState == RecordingState.PAUSED) {
                        while (true) {
                            if (recordingState == RecordingState.RECORDING) {
                                elapsedTime = System.currentTimeMillis() - recordingStartTime
                            }
                            delay(1000)
                        }
                    }
                }
                
                Box(modifier = Modifier.fillMaxSize()) {
                    RecorderScreen(
                        recordingState = recordingState,
                        elapsedTime = elapsedTime,
                        onPauseClick = { togglePause() },
                        onStopClick = { stopRecording() },
                        onSettingsClick = { showSettings = true }
                    )
                    
                    if (showSettings) {
                        SettingsDialog(
                            selectedSource = selectedAudioSource,
                            onSourceSelected = { 
                                selectedAudioSource = it
                                preferences.edit().putInt(RecordingService.PREF_AUDIO_SOURCE, it).apply()
                            },
                            onDismiss = { showSettings = false }
                        )
                    }
                    
                    if (showSignInPrompt) {
                        GoogleSignInDialog(
                            onDismiss = { showSignInPrompt = false },
                            onSignInClick = { 
                                showSignInPrompt = false
                                googleSignInHandler.signIn(googleSignInLauncher)
                            }
                        )
                    }
                }
            }
        }
    }
    
    private fun checkGoogleSignIn() {
        if (!googleSignInHandler.isSignedInWithScope()) {
            showSignInPrompt = true
        } else {
            // If already signed in, proceed to check recording state
            checkRecordingState()
        }
    }
    
    private fun handleSignInResult(resultCode: Int, data: Intent?) {
        if (resultCode == RESULT_OK && data != null) {
            val task: Task<GoogleSignInAccount> = GoogleSignIn.getSignedInAccountFromIntent(data)
            try {
                val account = task.getResult(ApiException::class.java)
                // Google Sign-In was successful, proceed to check recording state
                checkRecordingState()
            } catch (e: ApiException) {
                // Sign-In failed, but we'll proceed with recording anyway
                // The upload will fail but the recording will still be stored locally
                checkRecordingState()
            }
        } else {
            // User cancelled sign-in, proceed with recording anyway
            checkRecordingState()
        }
    }
    
    private fun checkRecordingState() {
        val prefs = getSharedPreferences("recording_state", MODE_PRIVATE)
        val isRecording = prefs.getBoolean("is_recording", false)
        val isPaused = prefs.getBoolean("is_paused", false)
        
        if (isRecording) {
            recordingState = if (isPaused) RecordingState.PAUSED else RecordingState.RECORDING
            // Try to get the start time from preferences, otherwise use current time
            recordingStartTime = prefs.getLong("start_time", System.currentTimeMillis())
        } else {
            // If not already recording, start automatically
            startRecording()
        }
    }
    
    private fun requestIgnoreBatteryOptimization() {
        val packageName = packageName
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (!pm.isIgnoringBatteryOptimizations(packageName)) {
            val intent = Intent().apply {
                action = Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS
                data = Uri.parse("package:$packageName")
            }
            startActivity(intent)
        }
    }
    
    private fun startRecording() {
        val intent = Intent(this, RecordingService::class.java).apply {
            action = RecordingService.ACTION_START
        }
        startForegroundService(intent)
        recordingStartTime = System.currentTimeMillis()
        recordingState = RecordingState.RECORDING
        
        // Store the start time for future reference
        getSharedPreferences("recording_state", MODE_PRIVATE).edit()
            .putLong("start_time", recordingStartTime)
            .apply()
    }
    
    private fun togglePause() {
        val action = if (recordingState == RecordingState.RECORDING) {
            recordingState = RecordingState.PAUSED
            RecordingService.ACTION_PAUSE
        } else {
            recordingState = RecordingState.RECORDING
            RecordingService.ACTION_RESUME
        }
        
        val intent = Intent(this, RecordingService::class.java).apply {
            this.action = action
        }
        startForegroundService(intent)
    }
    
    private fun stopRecording() {
        val intent = Intent(this, RecordingService::class.java).apply {
            action = RecordingService.ACTION_STOP
        }
        startForegroundService(intent)
        recordingState = RecordingState.STOPPED
        finish() // Close the app after stopping recording
    }
    
    override fun onDestroy() {
        // Don't stop recording when the activity is destroyed
        super.onDestroy()
    }
    
    enum class RecordingState {
        INITIALIZING, RECORDING, PAUSED, STOPPED
    }
}

@Composable
fun SimpleDialog(
    showDialog: Boolean,
    onDismissRequest: () -> Unit,
    content: @Composable () -> Unit
) {
    if (showDialog) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.7f))
                .clickable(onClick = onDismissRequest),
            contentAlignment = Alignment.Center
        ) {
            // Prevent clicks inside the dialog from dismissing it
            Box(
                modifier = Modifier
                    .clip(MaterialTheme.shapes.medium)
                    .background(MaterialTheme.colors.surface)
                    .clickable(enabled = false) {}
                    .padding(16.dp)
            ) {
                content()
            }
        }
    }
}

@Composable
fun GoogleSignInDialog(
    onDismiss: () -> Unit,
    onSignInClick: () -> Unit
) {
    SimpleDialog(
        showDialog = true,
        onDismissRequest = onDismiss
    ) {
        Column(
            modifier = Modifier.padding(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "Google Account",
                style = MaterialTheme.typography.title2,
                textAlign = TextAlign.Center
            )
            
            Spacer(modifier = Modifier.height(8.dp))
            
            Text(
                text = "Sign in with your Google account to enable automatic uploads to Google Drive.",
                textAlign = TextAlign.Center
            )
            
            Spacer(modifier = Modifier.height(16.dp))
            
            Button(
                onClick = onSignInClick,
                colors = ButtonDefaults.buttonColors(
                    backgroundColor = MaterialTheme.colors.primary
                )
            ) {
                Text("Sign In")
            }
            
            Spacer(modifier = Modifier.height(8.dp))
            
            Button(
                onClick = onDismiss,
                colors = ButtonDefaults.buttonColors(
                    backgroundColor = MaterialTheme.colors.surface
                )
            ) {
                Text("Later")
            }
        }
    }
}

@Composable
fun RecorderScreen(
    recordingState: MainActivity.RecordingState,
    elapsedTime: Long,
    onPauseClick: () -> Unit,
    onStopClick: () -> Unit,
    onSettingsClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Format elapsed time
        val formattedTime = remember(elapsedTime) {
            val minutes = TimeUnit.MILLISECONDS.toMinutes(elapsedTime)
            val seconds = TimeUnit.MILLISECONDS.toSeconds(elapsedTime) % 60
            String.format("%02d:%02d", minutes, seconds)
        }
        
        Text(
            text = when (recordingState) {
                MainActivity.RecordingState.INITIALIZING -> "Initializing..."
                MainActivity.RecordingState.RECORDING -> "Recording"
                MainActivity.RecordingState.PAUSED -> "Paused"
                MainActivity.RecordingState.STOPPED -> "Saving..."
            },
            style = MaterialTheme.typography.title2,
            textAlign = TextAlign.Center
        )
        
        Spacer(modifier = Modifier.height(8.dp))
        
        // Only show time while recording or paused
        if (recordingState == MainActivity.RecordingState.RECORDING || recordingState == MainActivity.RecordingState.PAUSED) {
            Text(
                text = formattedTime,
                style = MaterialTheme.typography.title3,
                textAlign = TextAlign.Center
            )
            
            Spacer(modifier = Modifier.height(24.dp))
            
            // Pulsating recording indicator
            if (recordingState == MainActivity.RecordingState.RECORDING) {
                val infiniteTransition = rememberInfiniteTransition()
                val animatedSize by infiniteTransition.animateFloat(
                    initialValue = 0.8f,
                    targetValue = 1.2f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(1000, easing = LinearEasing),
                        repeatMode = RepeatMode.Reverse
                    )
                )
                
                Box(
                    modifier = Modifier
                        .size(20.dp * animatedSize)
                        .background(Color.Red.copy(alpha = 0.7f), CircleShape)
                )
            } else {
                // Placeholder when paused
                Box(
                    modifier = Modifier
                        .size(20.dp)
                        .background(Color.Gray.copy(alpha = 0.5f), CircleShape)
                )
            }
            
            Spacer(modifier = Modifier.height(24.dp))
        }
        
        // Control buttons - shown in a row
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            // Settings button
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colors.surface.copy(alpha = 0.8f))
                    .clickable { onSettingsClick() },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Settings,
                    contentDescription = "Settings",
                    tint = MaterialTheme.colors.primary,
                    modifier = Modifier.size(24.dp)
                )
            }
            
            // Pause/Resume button
            if (recordingState != MainActivity.RecordingState.INITIALIZING) {
                Box(
                    modifier = Modifier
                        .size(56.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colors.primary)
                        .clickable { onPauseClick() },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = if (recordingState == MainActivity.RecordingState.RECORDING) 
                            Icons.Default.Pause else Icons.Default.Mic,
                        contentDescription = if (recordingState == MainActivity.RecordingState.RECORDING) 
                            "Pause Recording" else "Resume Recording",
                        tint = MaterialTheme.colors.onPrimary,
                        modifier = Modifier.size(28.dp)
                    )
                }
            }
            
            // Stop button
            if (recordingState != MainActivity.RecordingState.INITIALIZING) {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(Color.Red.copy(alpha = 0.8f))
                        .clickable { onStopClick() },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Stop,
                        contentDescription = "Stop Recording",
                        tint = Color.White,
                        modifier = Modifier.size(24.dp)
                    )
                }
            }
        }
    }
}

@Composable
fun SettingsDialog(
    selectedSource: Int,
    onSourceSelected: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    SimpleDialog(
        showDialog = true,
        onDismissRequest = onDismiss
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "Audio Source",
                style = MaterialTheme.typography.title2,
                textAlign = TextAlign.Center
            )
            
            Spacer(modifier = Modifier.height(16.dp))
            
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                AudioSourceOption(
                    text = "Watch Microphone",
                    selected = selectedSource == RecordingService.AUDIO_SOURCE_WATCH,
                    onClick = { onSourceSelected(RecordingService.AUDIO_SOURCE_WATCH) }
                )
                
                AudioSourceOption(
                    text = "Phone Microphone",
                    selected = selectedSource == RecordingService.AUDIO_SOURCE_PHONE,
                    onClick = { onSourceSelected(RecordingService.AUDIO_SOURCE_PHONE) }
                )
                
                AudioSourceOption(
                    text = "Bluetooth Device",
                    selected = selectedSource == RecordingService.AUDIO_SOURCE_BLUETOOTH,
                    onClick = { onSourceSelected(RecordingService.AUDIO_SOURCE_BLUETOOTH) }
                )
            }
            
            Spacer(modifier = Modifier.height(16.dp))
            
            Button(
                onClick = onDismiss
            ) {
                Text("Close")
            }
        }
    }
}

@Composable
fun AudioSourceOption(
    text: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    // Get the color from the theme outside the Canvas lambda
    val primaryColor = MaterialTheme.colors.primary
    val onSurfaceColor = MaterialTheme.colors.onSurface // Get onSurface color too if needed later

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(CircleShape)
            .background(if (selected) primaryColor.copy(alpha = 0.2f) else Color.Transparent) // Use primaryColor here
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp, horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Selection indicator
        Canvas(modifier = Modifier.size(16.dp)) {
            // The lambda here is a DrawScope, not @Composable. Use the pre-fetched color.
            if (selected) {
                // Filled circle for selected
                drawCircle(
                    color = primaryColor, // Use the variable passed from the @Composable scope
                    radius = size.minDimension / 2
                )
            } else {
                // Outline for unselected
                drawCircle(
                    color = primaryColor, // Use the variable passed from the @Composable scope
                    radius = size.minDimension / 2,
                    style = Stroke(width = 2.dp.toPx())
                )
            }
        }

        Spacer(modifier = Modifier.size(8.dp))

        Text(
            text = text,
            style = MaterialTheme.typography.body2,
            color = if (selected) primaryColor else onSurfaceColor // Use the variables
        )
    }
}

@Composable
fun WearNoteTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colors = MaterialTheme.colors,
        typography = MaterialTheme.typography,
        shapes = MaterialTheme.shapes,
        content = content
    )
}
