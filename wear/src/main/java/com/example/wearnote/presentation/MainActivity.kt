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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.wear.compose.material.Button
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.Text as WearText
import com.example.wearnote.presentation.theme.WearNoteTheme
import com.example.wearnote.service.RecorderService

class MainActivity : ComponentActivity() {

    private val permLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { perms ->
        if (perms.values.all { it }) checkFirstLaunch()
        else finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        permLauncher.launch(arrayOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.FOREGROUND_SERVICE,
            Manifest.permission.INTERNET
        ))
    }

    private fun checkFirstLaunch() {
        val prefs = getSharedPreferences("wearnote", Context.MODE_PRIVATE)
        val first = prefs.getBoolean("first_launch", true)
        if (first) {
            prefs.edit().putBoolean("first_launch", false).apply()
            startRecording()
            finish()
        } else {
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
        stopService(Intent(this, RecorderService::class.java))
        finish()
    }

    @Composable
    private fun RecordingControlUI() {
        val isPaused = remember { mutableStateOf(false) }
        WearNoteTheme {
            Box(
                Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Button(onClick = {
                    val action = if (isPaused.value) "RESUME" else "PAUSE"
                    Intent(this@MainActivity, RecorderService::class.java).also { it.action = action }.apply { startService(this) }
                    isPaused.value = !isPaused.value
                }) {
                    WearText(if (isPaused.value) "Resume" else "Pause")
                }
                Button(onClick = {
                    Intent(this@MainActivity, RecorderService::class.java).also { it.action = "STOP" }.apply { startService(this) }
                    stopRecording()
                }) {
                    WearText("Stop")
                }
            }
        }
    }
}