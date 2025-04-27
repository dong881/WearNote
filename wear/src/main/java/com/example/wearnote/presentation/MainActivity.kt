/* While this template provides a good starting point for using Wear Compose, you can always
 * take a look at https://github.com/android/wear-os-samples/tree/main/ComposeStarter and
 * https://github.com/android/wear-os-samples/tree/main/ComposeAdvanced to find the most up to date
 * changes to the libraries and their usages.
 */

package com.example.wearnote.presentation

import android.Manifest
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material.Button
import androidx.wear.compose.material.ButtonDefaults
import androidx.wear.compose.material.Icon
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import com.example.wearnote.R
import com.example.wearnote.presentation.theme.WearNoteTheme
import com.example.wearnote.service.RecorderService
import kotlinx.coroutines.delay

// Google Sign-In imports
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.Scope

// Google Drive API imports
import com.google.api.client.http.javanet.NetHttpTransport
import com.google.api.client.json.gson.GsonFactory
import com.google.api.client.googleapis.extensions.android.gms.auth.GoogleAccountCredential
import com.google.api.services.drive.Drive
import com.google.api.services.drive.DriveScopes

class MainActivity : ComponentActivity() {

    companion object {
        private const val RC_SIGN_IN = 9001
    }

    private lateinit var googleSignInClient: GoogleSignInClient
    private var driveService: Drive? = null

    private val permLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { perms ->
        if (perms.values.all { it }) checkGoogleSignIn()
        else finish()
    }

    private val signInLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        handleSignInResult(result.data)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Configure Google Sign-In with Android client ID only
        val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestEmail()
            .requestIdToken(getString(R.string.android_client_id))
            .requestServerAuthCode(getString(R.string.android_client_id))
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
            signInLauncher.launch(signInIntent)
        }
    }

    private fun handleSignInResult(data: Intent?) {
        try {
            val task = GoogleSignIn.getSignedInAccountFromIntent(data)
            val account = task.getResult(ApiException::class.java)
            createDriveService(account)
            checkFirstLaunch()
        } catch (e: ApiException) {
            // Handle sign-in failure
            finish()
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

    private fun checkFirstLaunch() {
        val prefs = getSharedPreferences("wearnote", Context.MODE_PRIVATE)
        val first = prefs.getBoolean("first_launch", true)
        
        if (first) {
            prefs.edit().putBoolean("first_launch", false).apply()
            startRecording()
            // First launch - exit to home screen after starting recording
            finish()
        } else {
            // Subsequent launches - show UI
            startRecording()
            setContent { RecordingControlUI() }
        }
    }

    private fun startRecording() {
        Intent(this, RecorderService::class.java).also {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                startForegroundService(it)
            else startService(it)
        }
    }

    private fun stopRecording() {
        Intent(this, RecorderService::class.java).also { 
            it.action = RecorderService.ACTION_STOP_RECORDING 
            startService(it)
        }
        finish()
    }
    
    private fun togglePauseResumeRecording(isPaused: Boolean) {
        val action = if (isPaused) 
            RecorderService.ACTION_RESUME_RECORDING 
        else 
            RecorderService.ACTION_PAUSE_RECORDING
            
        Intent(this, RecorderService::class.java).also { 
            it.action = action
            startService(it) 
        }
    }

    @Composable
    private fun RecordingControlUI() {
        val isPaused = remember { mutableStateOf(false) }
        val elapsedTime = remember { mutableStateOf(0L) }
        val isRecording = remember { mutableStateOf(true) }
        
        // Timer effect
        LaunchedEffect(key1 = isPaused.value) {
            while(isRecording.value && !isPaused.value) {
                delay(1000)
                elapsedTime.value += 1
            }
        }
        
        val formattedTime = formatTime(elapsedTime.value)
        
        WearNoteTheme {
            Box(
                Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = formattedTime,
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