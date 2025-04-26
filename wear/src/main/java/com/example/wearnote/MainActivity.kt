package com.example.wearnote

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.wear.compose.material.*
import com.example.wearnote.service.RecorderService
import com.example.wearnote.auth.ConfigHelper
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.CommonStatusCodes
import kotlinx.coroutines.delay

class MainActivity : ComponentActivity() {
    companion object {
        private const val TAG = "MainActivity"
        private const val PREFS_NAME = "WearNotePrefs"
        private const val KEY_FIRST_LAUNCH_DONE = "firstLaunchDone"
    }

    private enum class RecordingState {
        IDLE, RECORDING, PAUSED
    }
    
    private var currentRecordingState = RecordingState.IDLE
    private lateinit var googleSignInClient: GoogleSignInClient
    
    private val permLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { perms ->
        if (perms.values.all { it }) {
            checkFirstLaunch()
        } else {
            finish()
        }
    }
    
    private val googleSignInLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        handleSignInResult(result)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Configure Google Sign-In
        val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestEmail()
            .build()
        googleSignInClient = GoogleSignIn.getClient(this, gso)
        
        // Request permissions
        permLauncher.launch(arrayOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.FOREGROUND_SERVICE,
            Manifest.permission.INTERNET
        ))
        
        setContent {
            WearApp()
        }
    }
    
    @Composable
    fun WearApp() {
        MaterialTheme {
            val account = GoogleSignIn.getLastSignedInAccount(LocalContext.current)
            val isSignedIn = account != null
            val isRecording = remember { mutableStateOf<Boolean>(currentRecordingState == RecordingState.RECORDING) }
            val isPaused = remember { mutableStateOf<Boolean>(currentRecordingState == RecordingState.PAUSED) }
            
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                when (currentRecordingState) {
                    RecordingState.IDLE -> IdleScreen(isSignedIn)
                    RecordingState.RECORDING, RecordingState.PAUSED -> RecordingScreen(isPaused.value)
                }
            }
        }
    }
    
    @Composable
    private fun IdleScreen(isSignedIn: Boolean) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = if (isSignedIn) stringResource(R.string.status_idle) 
                       else stringResource(R.string.sign_in_required),
                color = MaterialTheme.colors.primary,
                textAlign = TextAlign.Center,
                fontSize = 16.sp,
                modifier = Modifier.padding(bottom = 16.dp)
            )
            
            // Sign In Button (if needed)
            if (!isSignedIn) {
                Button(
                    onClick = { signIn() },
                    modifier = Modifier.padding(bottom = 8.dp),
                    colors = ButtonDefaults.buttonColors(
                        backgroundColor = MaterialTheme.colors.primary
                    )
                ) {
                    Text(text = "Sign In")
                }
            }
            
            // Start Recording Button
            Button(
                onClick = { startRecording() },
                enabled = isSignedIn && ContextCompat.checkSelfPermission(
                    LocalContext.current, Manifest.permission.RECORD_AUDIO
                ) == PackageManager.PERMISSION_GRANTED,
                modifier = Modifier.size(64.dp),
                shape = CircleShape,
                colors = ButtonDefaults.buttonColors(
                    backgroundColor = MaterialTheme.colors.primary,
                    contentColor = Color.White
                )
            ) {
                Icon(
                    painter = painterResource(id = R.drawable.ic_mic),
                    contentDescription = "Start Recording",
                    modifier = Modifier.size(32.dp)
                )
            }
        }
    }
    
    @Composable
    private fun RecordingScreen(isPaused: Boolean) {
        val elapsedSeconds = remember { mutableStateOf<Long>(0L) }
        
        LaunchedEffect(isPaused) {
            while (!isPaused) {
                delay(1000)
                elapsedSeconds.value += 1
            }
        }
        
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = if (isPaused) stringResource(R.string.status_paused) 
                       else stringResource(R.string.status_recording),
                color = MaterialTheme.colors.primary,
                fontSize = 16.sp,
                modifier = Modifier.padding(bottom = 8.dp)
            )
            
            Text(
                text = formatTime(elapsedSeconds.value),
                color = Color.White,
                fontSize = 20.sp,
                modifier = Modifier.padding(bottom = 16.dp)
            )
            
            Row(
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Stop Button
                Button(
                    onClick = { stopRecording() },
                    modifier = Modifier.size(54.dp),
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
                
                // Pause/Resume Button
                Button(
                    onClick = { togglePauseResumeRecording(isPaused) },
                    enabled = Build.VERSION.SDK_INT >= Build.VERSION_CODES.N,
                    modifier = Modifier.size(54.dp),
                    shape = CircleShape,
                    colors = ButtonDefaults.buttonColors(
                        backgroundColor = MaterialTheme.colors.primary,
                        contentColor = Color.White
                    )
                ) {
                    Icon(
                        painter = painterResource(
                            id = if (isPaused) R.drawable.ic_play else R.drawable.ic_pause
                        ),
                        contentDescription = if (isPaused) "Resume" else "Pause",
                        modifier = Modifier.size(24.dp)
                    )
                }
            }
        }
    }

    private fun startRecording() {
        Log.d(TAG, "Starting recording")
        Intent(this, RecorderService::class.java).also {
            it.action = RecorderService.ACTION_START_RECORDING
            startService(it)
        }
        currentRecordingState = RecordingState.RECORDING
    }
    
    private fun stopRecording() {
        Log.d(TAG, "Stopping recording")
        Intent(this, RecorderService::class.java).also {
            it.action = RecorderService.ACTION_STOP_RECORDING
            startService(it)
        }
        currentRecordingState = RecordingState.IDLE
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
    
    private fun signIn() {
        Log.d(TAG, "Initiating Google Sign-In flow.")
        val signInIntent = googleSignInClient.signInIntent
        googleSignInLauncher.launch(signInIntent)
    }
    
    private fun handleSignInResult(result: androidx.activity.result.ActivityResult) {
        try {
            val task = GoogleSignIn.getSignedInAccountFromIntent(result.data)
            val account = task.getResult(ApiException::class.java)
            Log.d(TAG, "Signed in as: ${account.email}")
            // Refresh UI
            setContent { WearApp() }
        } catch (e: ApiException) {
            Log.e(TAG, "Google Sign-In Failed: ${e.statusCode} - ${e.message}")
            when (e.statusCode) {
                CommonStatusCodes.DEVELOPER_ERROR -> {
                    Log.e(TAG, "開發者錯誤 (10) - 表示 CLIENT_ID 或 SHA-1 指紋配置錯誤")
                    // 輸出診斷資訊
                    ConfigHelper.checkDeveloperConfiguration(this)
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
        }
    }

    private fun showErrorMessage(message: String) {
        // 在 Wear OS 上顯示錯誤訊息
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }
    
    private fun checkFirstLaunch() {
        if (!isFirstLaunchDone()) {
            handleFirstLaunch()
            markFirstLaunchDone()
        }
    }
    
    private fun isFirstLaunchDone(): Boolean {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getBoolean(KEY_FIRST_LAUNCH_DONE, false)
    }
    
    private fun markFirstLaunchDone() {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putBoolean(KEY_FIRST_LAUNCH_DONE, true).apply()
    }
    
    private fun handleFirstLaunch() {
        // First launch actions
        Log.d(TAG, "First launch detected")
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
