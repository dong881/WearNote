package com.example.wearnote.ui

import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material.*
import com.example.wearnote.R

private const val TAG = "RecordingScreen"

@Composable
fun RecordingScreen(
    modifier: Modifier = Modifier,
    isRecording: Boolean,
    isPaused: Boolean,
    uploadStatus: String,
    errorMessage: String = "",
    onStopRecording: () -> Unit,
    onPauseRecording: () -> Unit = {},
    onRequestSignIn: () -> Unit = {} // Add sign-in callback
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colors.background),
        contentAlignment = Alignment.Center
    ) {
        when {
            isRecording && !isPaused -> {
                Log.d(TAG, "Showing recording controls")
                RecordingControls(
                    onStopRecording = onStopRecording,
                    onPauseRecording = onPauseRecording,
                    isPaused = false
                )
            }
            isRecording && isPaused -> {
                Log.d(TAG, "Showing paused controls")
                RecordingControls(
                    onStopRecording = onStopRecording,
                    onPauseRecording = onPauseRecording,
                    isPaused = true
                )
            }
            uploadStatus == "Upload Success" -> {
                Log.d(TAG, "Showing upload success")
                UploadSuccessScreen()
            }
            uploadStatus == "Upload Failed" -> {
                Log.d(TAG, "Showing upload failed")
                UploadFailedScreen(onRequestSignIn = onRequestSignIn)
            }
            uploadStatus == "Uploading..." -> {
                Log.d(TAG, "Showing uploading")
                UploadingScreen()
            }
            errorMessage.isNotEmpty() -> {
                Log.d(TAG, "Showing error: $errorMessage")
                ErrorScreen(message = errorMessage)
            }
            else -> {
                Log.d(TAG, "Showing initializing screen")
                InitializingScreen()
            }
        }
    }
}

@Composable
fun RecordingControls(
    onStopRecording: () -> Unit,
    onPauseRecording: () -> Unit,
    isPaused: Boolean
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(20.dp),
        modifier = Modifier.padding(16.dp)
    ) {
        // Pause/Resume button
        Button(
            onClick = { 
                if (isPaused) {
                    Log.d(TAG, "Resume button clicked")
                } else {
                    Log.d(TAG, "Pause button clicked")
                }
                onPauseRecording() 
            },
            modifier = Modifier
                .size(56.dp)
                .clip(CircleShape),
            colors = ButtonDefaults.buttonColors(
                backgroundColor = if (isPaused) Color(0xFF81C784) else Color(0xFFFFB74D) // Green for resume, amber for pause
            )
        ) {
            Icon(
                painter = painterResource(
                    id = if (isPaused) 
                        R.drawable.ic_media_play 
                    else 
                        android.R.drawable.ic_media_pause
                ),
                contentDescription = if (isPaused) "Resume Recording" else "Pause Recording",
                tint = Color.White
            )
        }
        
        // Stop button
        Button(
            onClick = { 
                Log.d(TAG, "Stop button clicked")
                onStopRecording() 
            },
            modifier = Modifier
                .size(56.dp)
                .clip(CircleShape),
            colors = ButtonDefaults.buttonColors(
                backgroundColor = Color(0xFFE57373) // Light red color
            )
        ) {
            Icon(
                painter = painterResource(id = R.drawable.ic_media_stop),
                contentDescription = "Stop Recording",
                tint = Color.White
            )
        }
    }
}

@Composable
fun UploadSuccessScreen() {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            painter = painterResource(id = android.R.drawable.ic_dialog_info),
            contentDescription = "Success",
            tint = Color(0xFF81C784), // Light green
            modifier = Modifier.size(40.dp)
        )
        
        Spacer(modifier = Modifier.height(8.dp))
        
        Text(
            text = "Saved",
            color = Color.White,
            fontSize = 16.sp,
            fontWeight = FontWeight.Medium
        )
    }
}

@Composable
fun UploadFailedScreen(
    message: String = "",
    onRequestSignIn: () -> Unit = {}
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = Modifier.padding(16.dp)
    ) {
        Icon(
            painter = painterResource(id = android.R.drawable.ic_dialog_alert),
            contentDescription = "Error",
            tint = Color(0xFFFFB74D), // Amber/orange color
            modifier = Modifier.size(36.dp)
        )
        
        Spacer(modifier = Modifier.height(8.dp))
        
        Text(
            text = "Upload Failed",
            color = Color.White,
            fontSize = 16.sp,
            fontWeight = FontWeight.Medium
        )
        
        if (message.isNotEmpty()) {
            Spacer(modifier = Modifier.height(4.dp))
            
            Text(
                text = message,
                color = Color.White.copy(alpha = 0.7f),
                fontSize = 12.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
        
        // Add Google Sign-In button
        Spacer(modifier = Modifier.height(16.dp))
        
        Button(
            onClick = onRequestSignIn,
            colors = ButtonDefaults.buttonColors(
                backgroundColor = Color(0xFF4285F4) // Google Blue
            ),
            modifier = Modifier.padding(top = 8.dp)
        ) {
            Text(
                text = "Sign in to Drive",
                fontSize = 14.sp
            )
        }
    }
}

@Composable
fun UploadingScreen() {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(40.dp),
            indicatorColor = Color(0xFF90CAF9), // Light blue
            strokeWidth = 4.dp
        )
        
        Spacer(modifier = Modifier.height(8.dp))
        
        Text(
            text = "Saving...",
            color = Color.White,
            fontSize = 16.sp,
            fontWeight = FontWeight.Normal
        )
    }
}

@Composable
fun ErrorScreen(message: String) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = Modifier.padding(16.dp)
    ) {
        Icon(
            painter = painterResource(id = android.R.drawable.ic_dialog_alert),
            contentDescription = "Error",
            tint = Color(0xFFFFB74D), // Amber/orange color
            modifier = Modifier.size(36.dp)
        )
        
        Spacer(modifier = Modifier.height(8.dp))
        
        Text(
            text = "Error",
            color = Color.White,
            fontSize = 16.sp,
            fontWeight = FontWeight.Medium
        )
    }
}

@Composable
fun InitializingScreen() {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(32.dp),
            indicatorColor = Color.White,
            strokeWidth = 3.dp
        )
    }
}

// Add a standalone sign-in button component
@Composable
fun GoogleSignInButton(onClick: () -> Unit) {
    Button(
        onClick = onClick,
        colors = ButtonDefaults.buttonColors(
            backgroundColor = Color(0xFF4285F4) // Google blue
        ),
        modifier = Modifier.padding(16.dp)
    ) {
        Text(
            text = "Sign in with Google",
            fontSize = 12.sp,
            color = Color.White
        )
    }
}
