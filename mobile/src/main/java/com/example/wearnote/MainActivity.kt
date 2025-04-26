package com.example.wearnote

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.view.View
import android.widget.RadioButton
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.example.wearnote.auth.DriveAuthActivity
import com.example.wearnote.model.AudioSource
import com.example.wearnote.service.AudioRecorderService
import com.example.wearnote.utils.DriveServiceHelper
import com.example.wearnote.utils.PreferenceManager
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.switchmaterial.SwitchMaterial
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {
    
    companion object {
        private const val REQUEST_PERMISSIONS = 100
        private const val REQUEST_IGNORE_BATTERY_OPTIMIZATION = 101
        private val REQUIRED_PERMISSIONS = arrayOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.READ_EXTERNAL_STORAGE,
            Manifest.permission.WRITE_EXTERNAL_STORAGE,
            Manifest.permission.POST_NOTIFICATIONS
        )
    }
    
    // UI elements
    private lateinit var fabRecord: FloatingActionButton
    private lateinit var fabPause: FloatingActionButton
    private lateinit var textStatusValue: android.widget.TextView
    private lateinit var textDurationValue: android.widget.TextView
    private lateinit var radioPhone: RadioButton
    private lateinit var radioWatch: RadioButton
    private lateinit var radioBluetooth: RadioButton
    private lateinit var switchAutoStart: SwitchMaterial
    private lateinit var buttonGoogleDrive: android.widget.Button
    
    // Utils
    private lateinit var preferenceManager: PreferenceManager
    
    // Service binding
    private var audioRecorderService: AudioRecorderService? = null
    private var isServiceBound = false
    private var isRecording = false
    private var isPaused = false
    
    // Duration timer
    private val handler = Handler(Looper.getMainLooper())
    private var recordingStartTime = 0L
    private val durationRunnable = object : Runnable {
        override fun run() {
            if (isRecording && !isPaused) {
                val elapsedMillis = System.currentTimeMillis() - recordingStartTime
                val formatter = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
                formatter.timeZone = java.util.TimeZone.getTimeZone("UTC")
                val formatted = formatter.format(Date(elapsedMillis))
                textDurationValue.text = formatted
                handler.postDelayed(this, 1000)
            }
        }
    }
    
    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as AudioRecorderService.LocalBinder
            audioRecorderService = binder.getService()
            isServiceBound = true
            
            // Update UI based on service state
            updateUIFromService()
        }
        
        override fun onServiceDisconnected(name: ComponentName?) {
            audioRecorderService = null
            isServiceBound = false
        }
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        
        // Initialize preferences
        preferenceManager = PreferenceManager(this)
        
        // Initialize UI components
        initializeUI()
        
        // Set listeners
        setupListeners()
        
        // Check permissions
        checkPermissions()
        
        // Bind to service
        bindAudioRecorderService()
        
        // Check if auto-start enabled and app is launched for the first time
        if (preferenceManager.isAutoStartEnabled() && !preferenceManager.isRecordingActive()) {
            // Start recording automatically if permissions are granted
            if (allPermissionsGranted()) {
                startRecording()
            }
        }
    }
    
    private fun initializeUI() {
        // Find UI elements
        fabRecord = findViewById(R.id.fabRecord)
        fabPause = findViewById(R.id.fabPause)
        textStatusValue = findViewById(R.id.textStatusValue)
        textDurationValue = findViewById(R.id.textDurationValue)
        radioPhone = findViewById(R.id.radioPhone)
        radioWatch = findViewById(R.id.radioWatch)
        radioBluetooth = findViewById(R.id.radioBluetooth)
        switchAutoStart = findViewById(R.id.switchAutoStart)
        buttonGoogleDrive = findViewById(R.id.buttonGoogleDrive)
        
        // Set initial states
        switchAutoStart.isChecked = preferenceManager.isAutoStartEnabled()
        
        // Set the selected audio source
        when (preferenceManager.getAudioSource()) {
            AudioSource.PHONE -> radioPhone.isChecked = true
            AudioSource.WATCH -> radioWatch.isChecked = true
            AudioSource.BLUETOOTH -> radioBluetooth.isChecked = true
        }
        
        // Set initial text for Google Drive button
        updateGoogleDriveButtonText()
    }
    
    private fun setupListeners() {
        fabRecord.setOnClickListener {
            if (isRecording) {
                stopRecording()
            } else {
                startRecording()
            }
        }
        
        fabPause.setOnClickListener {
            if (isPaused) {
                resumeRecording()
            } else {
                pauseRecording()
            }
        }
        
        radioPhone.setOnClickListener {
            preferenceManager.setAudioSource(AudioSource.PHONE)
        }
        
        radioWatch.setOnClickListener {
            preferenceManager.setAudioSource(AudioSource.WATCH)
        }
        
        radioBluetooth.setOnClickListener {
            preferenceManager.setAudioSource(AudioSource.BLUETOOTH)
        }
        
        switchAutoStart.setOnCheckedChangeListener { _, isChecked ->
            preferenceManager.setAutoStartEnabled(isChecked)
        }
        
        buttonGoogleDrive.setOnClickListener {
            if (DriveServiceHelper.isSignedIn(this)) {
                // Sign out logic
                lifecycleScope.launch {
                    withContext(Dispatchers.IO) {
                        try {
                            val googleSignInClient = com.google.android.gms.auth.api.signin.GoogleSignIn.getClient(
                                this@MainActivity,
                                com.google.android.gms.auth.api.signin.GoogleSignInOptions.DEFAULT_SIGN_IN
                            )
                            googleSignInClient.signOut().await()
                            preferenceManager.setDriveCredentialsSetup(false)
                            
                            withContext(Dispatchers.Main) {
                                updateGoogleDriveButtonText()
                                showMessage("Signed out from Google Drive")
                            }
                        } catch (e: Exception) {
                            withContext(Dispatchers.Main) {
                                showMessage("Error signing out: ${e.message}")
                            }
                        }
                    }
                }
            } else {
                // Sign in logic
                val intent = Intent(this, DriveAuthActivity::class.java)
                startActivity(intent)
            }
        }
    }
    
    private fun showMessage(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }
    
    private fun updateGoogleDriveButtonText() {
        val isSignedIn = DriveServiceHelper.isSignedIn(this)
        buttonGoogleDrive.text = if (isSignedIn) "Sign Out from Google Drive" else "Configure Google Drive"
    }
    
    private fun bindAudioRecorderService() {
        Intent(this, AudioRecorderService::class.java).also { intent ->
            bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
        }
    }
    
    private fun updateUIFromService() {
        audioRecorderService?.let { service ->
            // Update UI based on service state
            isRecording = service.isRecording()
            isPaused = service.isPaused()
            
            if (isRecording) {
                // Update UI for recording state
                fabRecord.setImageResource(android.R.drawable.ic_media_pause)
                fabPause.visibility = View.VISIBLE
                
                if (isPaused) {
                    textStatusValue.text = "Paused"
                    textStatusValue.setTextColor(getColor(R.color.status_paused))
                    fabPause.setImageResource(android.R.drawable.ic_media_play)
                } else {
                    textStatusValue.text = "Recording"
                    textStatusValue.setTextColor(getColor(R.color.status_recording))
                    fabPause.setImageResource(android.R.drawable.ic_media_pause)
                    
                    // Start timer
                    recordingStartTime = System.currentTimeMillis() - service.getRecordingDuration()
                    handler.post(durationRunnable)
                }
            } else {
                // Update UI for stopped state
                fabRecord.setImageResource(R.drawable.ic_record)
                fabPause.visibility = View.GONE
                textStatusValue.text = "Not Recording"
                textStatusValue.setTextColor(getColor(R.color.status_not_recording))
                textDurationValue.text = "00:00:00"
            }
        }
    }
    
    private fun startRecording() {
        if (allPermissionsGranted()) {
            if (!isIgnoringBatteryOptimizations()) {
                requestBatteryOptimizationExemption()
                return
            }
            
            // Check if Google Drive is set up
            if (!preferenceManager.isDriveCredentialsSetup()) {
                showGoogleDriveSetupDialog()
                return
            }
            
            val intent = Intent(this, AudioRecorderService::class.java)
            intent.action = AudioRecorderService.ACTION_START_RECORDING
            startForegroundService(intent)
            
            // Update UI
            isRecording = true
            isPaused = false
            fabRecord.setImageResource(android.R.drawable.ic_media_pause)
            fabPause.visibility = View.VISIBLE
            textStatusValue.text = "Recording"
            textStatusValue.setTextColor(getColor(R.color.status_recording))
            
            // Start timer
            recordingStartTime = System.currentTimeMillis()
            handler.post(durationRunnable)
            
            // Update preferences
            preferenceManager.setRecordingActive(true)
            
            if (preferenceManager.isAutoStartEnabled()) {
                // If auto-start is enabled, close the app after starting recording
                finish()
            }
        } else {
            requestPermissions()
        }
    }
    
    private fun stopRecording() {
        if (isServiceBound && isRecording) {
            val intent = Intent(this, AudioRecorderService::class.java)
            intent.action = AudioRecorderService.ACTION_STOP_RECORDING
            startForegroundService(intent)
            
            // Update UI
            isRecording = false
            isPaused = false
            fabRecord.setImageResource(R.drawable.ic_record)
            fabPause.visibility = View.GONE
            textStatusValue.text = "Not Recording"
            textStatusValue.setTextColor(getColor(R.color.status_not_recording))
            textDurationValue.text = "00:00:00"
            handler.removeCallbacks(durationRunnable)
            
            // Update preferences
            preferenceManager.setRecordingActive(false)
            preferenceManager.setCurrentRecordingPath(null)
        }
    }
    
    private fun pauseRecording() {
        if (isServiceBound && isRecording && !isPaused) {
            val intent = Intent(this, AudioRecorderService::class.java)
            intent.action = AudioRecorderService.ACTION_PAUSE_RECORDING
            startForegroundService(intent)
            
            // Update UI
            isPaused = true
            textStatusValue.text = "Paused"
            textStatusValue.setTextColor(getColor(R.color.status_paused))
            fabPause.setImageResource(android.R.drawable.ic_media_play)
            handler.removeCallbacks(durationRunnable)
        }
    }
    
    private fun resumeRecording() {
        if (isServiceBound && isRecording && isPaused) {
            val intent = Intent(this, AudioRecorderService::class.java)
            intent.action = AudioRecorderService.ACTION_RESUME_RECORDING
            startForegroundService(intent)
            
            // Update UI
            isPaused = false
            textStatusValue.text = "Recording"
            textStatusValue.setTextColor(getColor(R.color.status_recording))
            fabPause.setImageResource(android.R.drawable.ic_media_pause)
            
            // Resume timer (adjust start time based on elapsed time)
            val elapsed = recordingStartTime - System.currentTimeMillis()
            recordingStartTime = System.currentTimeMillis() - elapsed
            handler.post(durationRunnable)
        }
    }
    
    private fun checkPermissions() {
        if (!allPermissionsGranted()) {
            requestPermissions()
        } else if (!isIgnoringBatteryOptimizations()) {
            requestBatteryOptimizationExemption()
        }
    }
    
    private fun allPermissionsGranted(): Boolean {
        return REQUIRED_PERMISSIONS.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
    }
    
    private fun requestPermissions() {
        ActivityCompat.requestPermissions(this, REQUIRED_PERMISSIONS, REQUEST_PERMISSIONS)
    }
    
    private fun isIgnoringBatteryOptimizations(): Boolean {
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        return powerManager.isIgnoringBatteryOptimizations(packageName)
    }
    
    private fun requestBatteryOptimizationExemption() {
        val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
            data = Uri.parse("package:$packageName")
        }
        startActivityForResult(intent, REQUEST_IGNORE_BATTERY_OPTIMIZATION)
    }
    
    private fun showGoogleDriveSetupDialog() {
        val snackbar = Snackbar.make(
            findViewById(android.R.id.content),
            "Google Drive setup required for uploads",
            Snackbar.LENGTH_LONG
        )
        snackbar.setAction("Setup") {
            val intent = Intent(this, DriveAuthActivity::class.java)
            startActivity(intent)
        }
        snackbar.show()
    }
    
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        
        if (requestCode == REQUEST_PERMISSIONS) {
            if (allPermissionsGranted()) {
                if (!isIgnoringBatteryOptimizations()) {
                    requestBatteryOptimizationExemption()
                } else if (preferenceManager.isAutoStartEnabled()) {
                    startRecording()
                }
            } else {
                showMessage("Permissions are required for recording audio")
            }
        }
    }
    
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        
        if (requestCode == REQUEST_IGNORE_BATTERY_OPTIMIZATION) {
            if (isIgnoringBatteryOptimizations()) {
                if (preferenceManager.isAutoStartEnabled()) {
                    startRecording()
                }
            } else {
                showMessage("Battery optimization exemption is recommended for reliable background recording")
            }
        }
    }
    
    override fun onResume() {
        super.onResume()
        
        // Update UI
        updateUIFromService()
        updateGoogleDriveButtonText()
        
        // Check if we need to restart the timer
        if (isRecording && !isPaused) {
            handler.post(durationRunnable)
        }
    }
    
    override fun onDestroy() {
        super.onDestroy()
        
        if (isServiceBound) {
            unbindService(serviceConnection)
            isServiceBound = false
        }
        
        handler.removeCallbacks(durationRunnable)
    }
    
    // Extension function for awaiting the result of a Task
    private suspend fun <T> com.google.android.gms.tasks.Task<T>.await(): T {
        return kotlinx.coroutines.tasks.await()
    }
}