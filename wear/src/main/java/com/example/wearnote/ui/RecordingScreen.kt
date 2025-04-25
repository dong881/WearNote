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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material.*
import com.example.wearnote.R

private const val TAG = "RecordingScreen"

@Composable
fun RecordingScreen(
    modifier: Modifier = Modifier,
    isRecording: Boolean,
    uploadStatus: String,
    errorMessage: String = "",
    debugInfo: String = "",
    onStopRecording: () -> Unit
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colors.background),
        contentAlignment = Alignment.Center
    ) {
        when {
            isRecording -> {
                Log.d(TAG, "Showing recording controls")
                RecordingControls(onStopRecording = onStopRecording)
            }
            uploadStatus == "Upload Success" -> {
                Log.d(TAG, "Showing upload success")
                UploadSuccessScreen()
            }
            uploadStatus == "Upload Failed" -> {
                Log.d(TAG, "Showing upload failed")
                UploadFailedScreen()
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
fun RecordingControls(onStopRecording: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = Modifier.fillMaxWidth()
    ) {
        Button(
            onClick = { 
                Log.d(TAG, "Stop recording button clicked")
                onStopRecording() 
            },
            modifier = Modifier
                .size(60.dp)
                .clip(CircleShape),
            colors = ButtonDefaults.buttonColors(
                backgroundColor = Color(0xFFE57373) // Light red color
            )
        ) {
            Icon(
                painter = painterResource(id = android.R.drawable.ic_media_pause),
                contentDescription = "Stop Recording",
                tint = Color.White
            )
        }
        
        Spacer(modifier = Modifier.height(8.dp))
        
        Text(
            text = "Stop",
            color = Color.White,
            fontSize = 14.sp,
            fontWeight = FontWeight.Normal
        )
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
fun UploadFailedScreen() {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            painter = painterResource(id = android.R.drawable.ic_dialog_alert),
            contentDescription = "Failed",
            tint = Color(0xFFE57373), // Light red
            modifier = Modifier.size(40.dp)
        )
        
        Spacer(modifier = Modifier.height(8.dp))
        
        Text(
            text = "Failed",
            color = Color.White,
            fontSize = 16.sp,
            fontWeight = FontWeight.Medium
        )
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
