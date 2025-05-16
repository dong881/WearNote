# Google Drive Integration

This document explains how WearNote integrates with Google Drive for cloud storage of audio recordings.

## Overview

WearNote uses Google Drive as the cloud storage solution for audio recordings. This integration enables:

- Secure storage of audio files in the user's Google Drive account
- Automatic organization in a dedicated folder
- Seamless authentication using Google Sign-In
- Background uploads with retry capability
- Access to recordings from any device with Google Drive

## Setup Instructions

### 1. Create a Google Cloud Project

1. Go to the [Google Cloud Console](https://console.cloud.google.com/)
2. Create a new project or select an existing one
3. Make note of your project ID

### 2. Enable Required APIs

1. Go to "APIs & Services" > "Library"
2. Search for and enable the following APIs:
   - Google Drive API
   - Google Sign-In API

### 3. Configure OAuth Consent Screen

1. Go to "APIs & Services" > "OAuth consent screen"
2. Select "External" user type (or "Internal" if using Google Workspace)
3. Fill in the required application information:
   - App name: "WearNote"
   - User support email: your email
   - Developer contact information: your email
4. Add the following scopes:
   - `https://www.googleapis.com/auth/drive.file`
   - `email`
   - `profile`
5. Add test users if using External type
6. Complete the registration process

### 4. Create OAuth Credentials

1. Go to "APIs & Services" > "Credentials"
2. Click "Create Credentials" > "OAuth client ID"
3. Select "Android" as the application type
4. Fill in the required information:
   - Name: "WearNote Android"
   - Package name: `com.example.wearnote`
   - SHA-1 certificate fingerprint: Your app's SHA-1 fingerprint

#### Obtaining SHA-1 Fingerprint

For debug builds, run:
```bash
keytool -list -v -keystore ~/.android/debug.keystore -alias androiddebugkey -storepass android -keypass android
```

For release builds, use your signing keystore:
```bash
keytool -list -v -keystore your_keystore.jks -alias your_alias
```

### 5. Configure Your Application

1. Copy your Android client ID from the credentials page
2. Add it to your `strings.xml` file:

```xml
<resources>
    <!-- Single client ID for all Google Sign-In operations -->
    <string name="android_client_id">YOUR_CLIENT_ID_HERE.apps.googleusercontent.com</string>
</resources>
```

## Authentication Flow

WearNote uses the Google Sign-In API for authentication with the following flow:

1. **Initial Authentication**:
   - App requests permissions from user via GoogleSignInClient
   - User grants permissions
   - App receives authentication tokens

2. **Token Management**:
   - Access tokens are stored securely
   - Refresh tokens are used to obtain new access tokens when needed
   - Tokens are automatically refreshed when expired

3. **Authorization Handling**:
   - When additional permissions are needed, the app shows the authorization screen
   - UserRecoverableAuthIOException is handled to request permissions

## Drive API Usage

### Folder Structure

WearNote creates a dedicated folder in the user's Google Drive called "WearNote_Recordings" to store all recordings.

```kotlin
private const val FOLDER_NAME = "WearNote_Recordings"
```

### File Upload Process

The upload process follows these steps:

1. **Check/Create Folder**:
   - Check if the WearNote folder exists
   - If not, create the folder

```kotlin
// Check if folder exists
val folderListResult = drive.files().list()
    .setQ("name='$FOLDER_NAME' and mimeType='application/vnd.google-apps.folder' and trashed=false")
    .setSpaces("drive")
    .execute()

// Create folder if it doesn't exist
if (folderListResult.files.isEmpty()) {
    val folderMetadata = DriveFile().apply {
        name = FOLDER_NAME
        mimeType = "application/vnd.google-apps.folder"
    }
    
    drive.files().create(folderMetadata)
        .setFields("id")
        .execute()
}
```

2. **Prepare File Metadata**:
   - Create metadata for the file including name and parent folder

```kotlin
val metadata = DriveFile().apply {
    name = file.name
    parents = listOf(folderId)
}
```

3. **Upload File Content**:
   - For small files (<5MB), upload directly
   - For larger files, use chunked uploading

```kotlin
// For small files
val mediaContent = FileContent("audio/mpeg", file)
drive.files().create(metadata, mediaContent)
    .setFields("id, name")
    .execute()

// For large files (chunked upload)
val mediaContent = InputStreamContent("audio/mpeg", BufferedInputStream(FileInputStream(file)))
mediaContent.length = file.length()

val request = drive.files().create(metadata, mediaContent)
request.mediaHttpUploader.apply {
    isDirectUploadEnabled = false
    chunkSize = CHUNK_SIZE
}
```

4. **Set Permissions** (optional):
   - Set file permissions as needed

```kotlin
val permission = Permission()
    .setRole("reader")
    .setType("anyone")
    
drive.permissions().create(fileId, permission)
    .execute()
```

### Error Handling

The Drive API integration includes robust error handling:

1. **Network Errors**:
   - Files are queued for later upload
   - Network changes trigger retry attempts

2. **Authorization Errors**:
   - UserRecoverableAuthIOException is caught
   - Permission request is displayed to user

```kotlin
try {
    // Drive API operation
} catch (e: UserRecoverableAuthIOException) {
    // Store the permission intent for later handling
    permissionIntent = e.intent
    // Broadcast need for permission
    val permissionBroadcast = Intent("com.example.wearnote.NEED_PERMISSION")
    context.sendBroadcast(permissionBroadcast)
    return null
}
```

3. **Rate Limiting**:
   - Exponential backoff for retries

```kotlin
// Retry with exponential backoff
var retryCount = 0
while (retryCount < MAX_RETRIES) {
    try {
        // Drive API operation
        break
    } catch (e: IOException) {
        retryCount++
        if (retryCount >= MAX_RETRIES) throw e
        delay(RETRY_DELAY_MS * (1 shl (retryCount - 1)))
    }
}
```

## Best Practices

1. **Minimize API Calls**:
   - Cache folder IDs to reduce API requests
   - Batch operations when possible

2. **Handle Token Expiration**:
   - Refresh tokens before they expire
   - Handle refresh failures gracefully

3. **Optimize Uploads**:
   - Use chunked uploads for large files
   - Monitor network conditions before uploading

4. **Security**:
   - Request only necessary scopes
   - Don't store access tokens in plain text
   - Use https for all network communication

## Troubleshooting

### Common Issues and Solutions

1. **Authentication Failures** (Error Code 10):
   - Verify the client ID is correctly added to strings.xml
   - Ensure SHA-1 fingerprint in Google Cloud Console matches your app signing key
   - Check logs for specific error messages
   
2. **File Upload Failures**:
   - Check network connectivity
   - Verify Drive API is enabled in Google Cloud Console
   - Check user's storage quota in Google Drive
   - Examine logcat for specific error messages

3. **Permission Denied**:
   - Verify OAuth scopes include `https://www.googleapis.com/auth/drive.file`
   - Check that app has been authorized by the user
   - User may have revoked permissions, prompt for re-authorization

### Debugging Tools

1. **Google API Explorer**:
   - Test Drive API calls directly in the [API Explorer](https://developers.google.com/apis-explorer/#p/drive/v3/)
   
2. **OAuth Playground**:
   - Test OAuth flows in the [OAuth 2.0 Playground](https://developers.google.com/oauthplayground/)

3. **Google Cloud Console**:
   - Monitor API usage and errors in the Google Cloud Console
   - Check API quotas and limits
