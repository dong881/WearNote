# WearNote Technical Architecture

This document outlines the technical architecture of WearNote, describing the main components and how they interact.

## Overview

WearNote is built using a service-oriented architecture with several key components working together to provide a seamless recording and cloud synchronization experience.

![Architecture Diagram](../images/develop.png)

## Architecture Diagram

```mermaid
graph TB
    subgraph User Interface
        MainActivity -- controls --> RecordingUI
        MainActivity -- navigates to --> PendingUploadsScreen
    end
    
    subgraph Services
        RecorderService -- records --> AudioFiles
        NetworkMonitorService -- monitors --> NetworkState
        GoogleDriveUploader -- uploads --> GoogleDrive
    end
    
    subgraph Data Management
        PendingUploadsManager -- tracks --> PendingUploads
    end
    
    MainActivity -- starts/stops --> RecorderService
    RecorderService -- sends files to --> GoogleDriveUploader
    RecorderService -- processes with --> AIProcessing
    
    GoogleDriveUploader -- reports status to --> MainActivity
    GoogleDriveUploader -- adds failed uploads to --> PendingUploadsManager
    
    NetworkMonitorService -- triggers --> GoogleDriveUploader
    PendingUploadsManager -- displays on --> PendingUploadsScreen
    PendingUploadsScreen -- retries --> GoogleDriveUploader
    
    subgraph External Services
        GoogleDrive[Google Drive API]
        AIProcessing[AI Processing API]
    end
```

## Core Components

### MainActivity

The `MainActivity` is the entry point of the application and handles:
- User interface for recording controls
- Google Sign-In process
- Navigation between different screens
- Auto-recording functionality

```kotlin
class MainActivity : ComponentActivity {
    // Handles recording state, Google authentication, and UI interactions
    // Coordinates between services and UI
}
```

### RecorderService

`RecorderService` is a foreground service that handles:
- Audio recording from the device microphone
- Maintaining recording state even when the app is in background
- Managing the recording lifecycle (start, pause, resume, stop)
- Sending recorded files for upload

```kotlin
class RecorderService : Service() {
    // Constants for intent actions
    companion object {
        const val ACTION_START_RECORDING = "com.example.wearnote.ACTION_START_RECORDING"
        const val ACTION_STOP_RECORDING = "com.example.wearnote.ACTION_STOP_RECORDING"
        const val ACTION_PAUSE_RECORDING = "com.example.wearnote.ACTION_PAUSE_RECORDING"
        const val ACTION_RESUME_RECORDING = "com.example.wearnote.ACTION_RESUME_RECORDING"
        const val ACTION_DISCARD_RECORDING = "com.example.wearnote.ACTION_DISCARD_RECORDING"
    }
    
    // Recording implementation...
}
```

### GoogleDriveUploader

This component is responsible for:
- Authenticating with Google Drive API
- Uploading audio files to Google Drive
- Creating appropriate folder structure
- Managing file permissions
- Handling upload failures and retries

```kotlin
object GoogleDriveUploader {
    // Uploads files to Google Drive
    // Handles authentication and permission requests
    suspend fun upload(context: Context, file: File): String? { ... }
}
```

### PendingUploadsManager

Manages uploads that couldn't be completed immediately:
- Tracks files waiting to be uploaded
- Persists state across app restarts
- Provides UI for viewing pending uploads
- Enables manual retry of uploads

```kotlin
object PendingUploadsManager {
    // Tracks and manages pending uploads
    // Provides flow for observing changes
    val pendingUploadsFlow: StateFlow<List<PendingUpload>>
}
```

### NetworkMonitorService

Monitors network conditions for optimal upload timing:
- Detects network type (WiFi, cellular)
- Monitors connectivity changes
- Triggers uploads when conditions are favorable
- Optimizes for battery consumption

```kotlin
class NetworkMonitorService : Service() {
    // Monitors network conditions
    // Helps determine when to upload files
}
```

## Data Flow

### Recording Flow

```mermaid
sequenceDiagram
    actor User
    participant MainActivity
    participant RecorderService
    participant Storage
    
    User->>MainActivity: Opens app
    alt First launch or manual start
        MainActivity->>RecorderService: Start recording
        RecorderService->>Storage: Prepare file
        RecorderService->>RecorderService: Begin capturing audio
        MainActivity->>User: Show recording UI
    else Auto-start
        MainActivity->>MainActivity: Start countdown
        Note over MainActivity: Countdown timer (3s)
        MainActivity->>RecorderService: Start recording
        MainActivity->>MainActivity: Return to watch face
        RecorderService->>Storage: Prepare file
        RecorderService->>RecorderService: Begin capturing audio
    end
    
    alt User interaction during recording
        User->>MainActivity: Pause recording
        MainActivity->>RecorderService: Pause
        RecorderService->>RecorderService: Suspend audio capture
        
        User->>MainActivity: Resume recording
        MainActivity->>RecorderService: Resume
        RecorderService->>RecorderService: Continue audio capture
    end
    
    User->>MainActivity: Stop recording
    MainActivity->>RecorderService: Stop recording
    RecorderService->>Storage: Finalize audio file
    RecorderService->>MainActivity: Notify completion
```

### Upload Flow

```mermaid
sequenceDiagram
    participant RecorderService
    participant GoogleDriveUploader
    participant NetworkMonitor
    participant PendingUploadsManager
    participant GoogleDrive
    participant AIService
    
    RecorderService->>GoogleDriveUploader: Upload recording
    
    alt Network available
        GoogleDriveUploader->>GoogleDrive: Upload file
        GoogleDrive-->>GoogleDriveUploader: Return file ID
        GoogleDriveUploader->>AIService: Process recording
        AIService-->>GoogleDriveUploader: Return processing results
        GoogleDriveUploader->>RecorderService: Notify success
    else Network unavailable
        GoogleDriveUploader->>PendingUploadsManager: Add to pending uploads
        PendingUploadsManager->>PendingUploadsManager: Store for later retry
    end
    
    NetworkMonitor->>NetworkMonitor: Detect network available
    NetworkMonitor->>PendingUploadsManager: Process pending uploads
    PendingUploadsManager->>GoogleDriveUploader: Retry uploads
    
    GoogleDriveUploader->>GoogleDrive: Upload file
    GoogleDrive-->>GoogleDriveUploader: Return file ID
    GoogleDriveUploader->>PendingUploadsManager: Remove from pending
```

## Authentication Flow

```mermaid
sequenceDiagram
    actor User
    participant MainActivity
    participant GoogleSignIn
    participant GoogleAuth
    participant DriveAPI
    
    User->>MainActivity: First launch
    MainActivity->>GoogleSignIn: Request sign-in
    GoogleSignIn->>GoogleAuth: Open authentication
    GoogleAuth->>User: Display permission screen
    User->>GoogleAuth: Grant permissions
    GoogleAuth->>GoogleSignIn: Return tokens
    GoogleSignIn->>MainActivity: Authentication result
    MainActivity->>DriveAPI: Configure with tokens
    
    Note over MainActivity,DriveAPI: Subsequent launches use cached tokens
```

## Pending Uploads Flow

```mermaid
flowchart TD
    A[Recording completed] --> B{Network available?}
    B -->|Yes| C[Upload to Google Drive]
    B -->|No| D[Add to pending uploads]
    
    C -->|Success| E[Send to AI Processing]
    C -->|Failure| D
    
    E -->|Success| F[Show success notification]
    E -->|Failure| G[Add to pending AI processing]
    
    H[Network becomes available] --> I[Check pending uploads]
    I --> J[Process pending uploads]
    
    K[User opens pending list] --> L[Show pending uploads screen]
    L --> M[User selects item]
    M --> N[Retry upload]
    N -->|Success| O[Remove from pending]
    N -->|Failure| P[Keep in pending]
```

## Error Handling

WearNote implements robust error handling:
- Network failures trigger retry mechanisms
- Authentication errors prompt re-authentication
- File system errors are logged and reported
- Service failures are handled gracefully

## Performance Considerations

The app is optimized for the constrained resources of wearable devices:
- Efficient battery usage during recording
- Optimized network operations
- Background work scheduling
- Minimal memory footprint
