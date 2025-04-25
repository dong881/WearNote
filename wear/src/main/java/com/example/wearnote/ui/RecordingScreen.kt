package com.example.wearnote.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material.CircularProgressIndicator
import androidx.wear.compose.material.Icon
import androidx.wear.compose.material.Text

@Composable
fun RecordingScreen(
    modifier: Modifier = Modifier,
    isRecording: Boolean,
    uploadStatus: String,
    onStopRecording: () -> Unit
) {
    Column(
        modifier = modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        if (isRecording) {
            RecordingIndicator()
            
            Box(
                modifier = Modifier
                    .padding(top = 20.dp)
                    .size(80.dp)
                    .clip(CircleShape)
                    .background(Color.LightGray)
                    .border(2.dp, Color.Red, CircleShape)
                    .clickable { onStopRecording() },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    painter = painterResource(id = android.R.drawable.ic_media_pause),
                    contentDescription = "Stop Recording",
                    tint = Color.Red
                )
            }
            
            Text(
                text = "Stop Recording",
                modifier = Modifier.padding(top = 8.dp),
                textAlign = TextAlign.Center
            )
        } else if (uploadStatus.isNotEmpty()) {
            when (uploadStatus) {
                "Uploading..." -> {
                    CircularProgressIndicator(
                        modifier = Modifier.size(50.dp),
                        indicatorColor = Color.White
                    )
                    Text(
                        text = uploadStatus,
                        modifier = Modifier.padding(top = 12.dp)
                    )
                }
                "Upload Success" -> {
                    Icon(
                        painter = painterResource(id = android.R.drawable.ic_dialog_info),
                        contentDescription = "Upload Success",
                        tint = Color.Green,
                        modifier = Modifier.size(50.dp)
                    )
                    Text(
                        text = uploadStatus,
                        modifier = Modifier.padding(top = 12.dp)
                    )
                }
                "Upload Failed" -> {
                    Icon(
                        painter = painterResource(id = android.R.drawable.ic_dialog_alert),
                        contentDescription = "Upload Failed",
                        tint = Color.Red,
                        modifier = Modifier.size(50.dp)
                    )
                    Text(
                        text = uploadStatus,
                        modifier = Modifier.padding(top = 12.dp)
                    )
                }
            }
        }
    }
}

@Composable
fun RecordingIndicator() {
    val infiniteTransition = rememberInfiniteTransition(label = "recording_animation")
    val alpha by infiniteTransition.animateFloat(
        initialValue = 0.2f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000),
            repeatMode = RepeatMode.Reverse
        ), label = "alpha_animation"
    )

    Box(
        modifier = Modifier
            .size(12.dp)
            .clip(CircleShape)
            .background(Color.Red.copy(alpha = alpha))
    )
    
    Text(
        text = "Recording...",
        modifier = Modifier.padding(top = 8.dp)
    )
}
