package com.example.wearnote

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
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
import com.example.wearnote.auth.KeystoreHelper
import kotlinx.coroutines.delay

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
    }

    private enum class RecordingState {
        IDLE, RECORDING, PAUSED
    }
    
    private var currentRecordingState = RecordingState.IDLE
    private lateinit var googleSignInClient: GoogleSignInClient
    private var driveService: Drive? = null
    
    private val permLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { perms ->
        if (perms.values.all { it }) {
            checkGoogleSignIn()
        } else {
            finish()
        }
    }
    
    private val googleSignInLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        handleSignInResult(result.data)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
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
            
            checkFirstLaunch()
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
            this, listOf(DriveScopes.DRIVE_FILE)
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
            handleFirstLaunch()
            markFirstLaunchDone()
        }
        startRecording()
        setContent { 
            WearNoteTheme {
                RecordingControlUI() 
            }
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
        // First launch actions - could initialize Drive folders, etc.
        Log.d(TAG, "First launch detected")
    }

    private fun startRecording() {
        Log.d(TAG, "Starting recording")
        Intent(this, RecorderService::class.java).also {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                startForegroundService(it)
            else 
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
        finish()
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
    
    @Composable
    fun WearNoteTheme(content: @Composable () -> Unit) {
        MaterialTheme(content = content)
    }

    @Composable
    private fun RecordingControlUI() {
        val isPaused = remember { mutableStateOf(false) }
        val elapsedTime = remember { mutableStateOf(0L) }
        val isRecording = remember { mutableStateOf(currentRecordingState == RecordingState.RECORDING) }
        
        // Timer effect
        LaunchedEffect(key1 = isPaused.value) {
            while(isRecording.value && !isPaused.value) {
                delay(1000)
                elapsedTime.value += 1
            }
        }
        
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = formatTime(elapsedTime.value),
                    color = Color.White,
                    fontSize = 20.sp,
                    textAlign = TextAlign.Center
                )
                
                Spacer(modifier = Modifier.height(12.dp))
                
                // Pause/Resume Button
                Button(
                    onClick = { 
                        isPaused.value = !isPaused.value
                        togglePauseResumeRecording(isPaused.value)
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
                            id = if (isPaused.value)
                                R.drawable.ic_play
                            else
                                R.drawable.ic_pause
                        ),
                        contentDescription = if (isPaused.value) "Resume" else "Pause",
                        modifier = Modifier.size(24.dp)
                    )
                }
                
                Spacer(modifier = Modifier.height(12.dp))
                
                // Stop Button
                Button(
                    onClick = { stopRecording() },
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
