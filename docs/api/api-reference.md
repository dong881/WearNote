# WearNote API Reference

This document describes the key APIs and interfaces provided by WearNote for recording, uploading, and managing audio notes.

## Table of Contents

- [RecorderService API](#recorderservice-api)
- [GoogleDriveUploader API](#googledrive-uploader-api)
- [PendingUploadsManager API](#pendinguploadsmanager-api)
- [NetworkMonitorService API](#networkmonitorservice-api)
- [Data Models](#data-models)
- [Broadcast Actions](#broadcast-actions)

## RecorderService API

The `RecorderService` provides the core recording functionality and is controlled through Intent actions.

### Starting the Service

To start recording:

```kotlin
Intent(context, RecorderService::class.java).also {
    it.action = RecorderService.ACTION_START_RECORDING
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        context.startForegroundService(it)
    } else {
        context.startService(it)
    }
}
```

### Available Actions

| Action | Description |
|--------|-------------|
| `ACTION_START_RECORDING` | Start a new recording |
| `ACTION_STOP_RECORDING` | Stop the current recording and process it |
| `ACTION_PAUSE_RECORDING` | Pause the current recording |
| `ACTION_RESUME_RECORDING` | Resume a paused recording |
| `ACTION_DISCARD_RECORDING` | Discard the current recording without saving |
| `ACTION_CHECK_UPLOAD_STATUS` | Check for any pending uploads |

### Broadcast Notifications

The service broadcasts recording status events that can be received with a BroadcastReceiver:

```kotlin
// Register receiver
val filter = IntentFilter(MainActivity.ACTION_RECORDING_STATUS)
registerReceiver(recordingStatusReceiver, filter)

// Define receiver
private val recordingStatusReceiver = object : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == MainActivity.ACTION_RECORDING_STATUS) {
            val status = intent.getStringExtra(MainActivity.EXTRA_STATUS)
            // Handle status update
        }
    }
}
```

Status values include:
- `STATUS_UPLOAD_COMPLETED`: Upload to Google Drive completed successfully
- `STATUS_UPLOAD_STARTED`: Upload process has started
- `STATUS_RECORDING_DISCARDED`: Recording was discarded
- `STATUS_RECORDING_STARTED`: Recording has started successfully

## GoogleDriveUploader API

The `GoogleDriveUploader` object provides methods for uploading audio files to Google Drive.

### Upload a File to Google Drive

```kotlin
suspend fun upload(context: Context, file: File): String? {
    // Uploads file to Google Drive
    // Returns the file ID if successful, null if failed
}
```

### Check for Pending Authorization

```kotlin
fun hasPermissionRequest(): Boolean {
    // Returns true if there's a pending authorization request
}

fun getPermissionIntent(): Intent? {
    // Returns the intent needed to request authorization, if any
}

fun clearPermissionIntent() {
    // Clears any pending permission request
}
```

### Handle Authorization Result

```kotlin
fun handleAuthResult(resultCode: Int): Boolean {
    // Processes the result of an authorization request
    // Returns true if authorization was granted
}
```

### Process Pending Uploads

```kotlin
suspend fun processPending(context: Context) {
    // Attempts to upload any pending files
}
```

## PendingUploadsManager API

The `PendingUploadsManager` object manages uploads that couldn't be completed immediately.

### Initialize the Manager

```kotlin
fun initialize(context: Context) {
    // Initializes the manager and loads any saved pending uploads
}
```

### Observe Pending Uploads

```kotlin
val pendingUploadsFlow: StateFlow<List<PendingUpload>>
// Provides a flow of pending uploads that can be collected for UI updates
```

### Add a Pending Upload

```kotlin
fun addPendingUpload(pendingUpload: PendingUpload) {
    // Adds a new pending upload to the queue
}
```

### Remove a Pending Upload

```kotlin
fun removePendingUpload(id: String) {
    // Removes a pending upload by ID
}
```

### Process All Pending Uploads

```kotlin
suspend fun processAllPendingUploads(context: Context): Boolean {
    // Attempts to process all pending uploads
    // Returns true if all uploads were successful
}
```

### Scan for Local Files

```kotlin
fun scanLocalMusicDirectory(context: Context): Int {
    // Scans the local directory for audio files not yet in the pending list
    // Returns the number of new files found
}
```

## NetworkMonitorService API

The `NetworkMonitorService` monitors network conditions for optimal upload timing.

### Start Monitoring

```kotlin
fun startMonitoring(context: Context) {
    // Starts monitoring network conditions
}
```

### Stop Monitoring

```kotlin
fun stopMonitoring(context: Context) {
    // Stops monitoring network conditions
}
```

## Data Models

### PendingUpload

Represents a file waiting to be uploaded:

```kotlin
data class PendingUpload(
    val fileName: String,
    val filePath: String,
    val uploadType: UploadType,
    val fileId: String? = null,
    val failureReason: String? = null,
    val id: String = System.currentTimeMillis().toString(),
    val timestamp: Long = System.currentTimeMillis(),
    val displayName: String = fileName
) {
    enum class UploadType {
        DRIVE,
        AI_PROCESSING,
        BOTH
    }
}
```

## Broadcast Actions

WearNote uses several broadcast actions for inter-component communication:

| Action | Description |
|--------|-------------|
| `ACTION_RECORDING_STATUS` | Broadcast when recording status changes |
| `CONNECTIVITY_ACTION` | System broadcast when network connectivity changes |

### Example: Receiving Network Changes

```kotlin
class NetworkChangeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (ConnectivityManager.CONNECTIVITY_ACTION == intent.action) {
            // Check network availability
            if (GoogleDriveUploader.isNetworkAvailable(context)) {
                // Process pending uploads
            }
        }
    }
}
```

## Error Codes and Handling

WearNote provides standardized error handling for common operations:

| Error Code | Description | Resolution |
|------------|-------------|------------|
| `10` | Google Sign-In Developer Error | Verify OAuth client ID and SHA-1 fingerprint |
| `12500` | Play Services Version Too Old | Update Google Play Services |
| `12501` | User Cancelled | User cancelled the sign-in process |
| `7` | Network Error | Check network connectivity |
