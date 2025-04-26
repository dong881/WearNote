package com.example.wearnote

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.wear.compose.material.Button
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class RecordingsActivity : ComponentActivity() {

    companion object {
        private const val TAG = "RecordingsActivity"
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Get the last recording info
        val prefs = getSharedPreferences("WearNotePrefs", Context.MODE_PRIVATE)
        val lastPath = prefs.getString("last_recording_path", null)
        val lastSize = prefs.getLong("last_recording_size", 0)
        val lastTime = prefs.getLong("last_recording_time", 0)
        
        // Format date for display
        val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        val dateString = if (lastTime > 0) dateFormat.format(Date(lastTime)) else "Unknown"
        
        setContent {
            MaterialTheme {
                RecordingsScreen(
                    lastRecordingPath = lastPath,
                    lastRecordingSize = lastSize,
                    lastRecordingDate = dateString,
                    onOpenFile = { path -> openAudioFile(path) }
                )
            }
        }
    }
    
    private fun openAudioFile(path: String?) {
        if (path == null) {
            Log.e(TAG, "Cannot open null file path")
            return
        }
        
        try {
            val file = File(path)
            if (!file.exists()) {
                Log.e(TAG, "File does not exist: $path")
                return
            }
            
            // Create a content URI using FileProvider
            val contentUri = FileProvider.getUriForFile(
                this,
                "com.example.wearnote.fileprovider",
                file
            )
            
            // Create an intent to view the audio file
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(contentUri, "audio/*")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            
            // Check if there's an app that can handle this intent
            if (intent.resolveActivity(packageManager) != null) {
                startActivity(intent)
            } else {
                Log.e(TAG, "No app found to handle audio playback")
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Error opening audio file", e)
        }
    }
}

@Composable
fun RecordingsScreen(
    lastRecordingPath: String?,
    lastRecordingSize: Long,
    lastRecordingDate: String,
    onOpenFile: (String?) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "Last Recording",
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold
        )
        
        Spacer(modifier = Modifier.height(8.dp))
        
        if (lastRecordingPath != null) {
            val fileName = File(lastRecordingPath).name
            val fileSizeKb = lastRecordingSize / 1024
            
            Text(
                text = fileName,
                fontSize = 14.sp
            )
            
            Text(
                text = "$fileSizeKb KB",
                fontSize = 12.sp
            )
            
            Text(
                text = lastRecordingDate,
                fontSize = 12.sp
            )
            
            Spacer(modifier = Modifier.height(16.dp))
            
            Button(onClick = { onOpenFile(lastRecordingPath) }) {
                Text("Play Recording")
            }
        } else {
            Text(
                text = "No recordings found",
                fontSize = 14.sp
            )
        }
    }
}
