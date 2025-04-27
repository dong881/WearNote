package com.example.wearnote.drive

import android.content.Context
import android.util.Log
import com.example.wearnote.auth.AuthManager
import com.google.api.client.googleapis.extensions.android.gms.auth.GoogleAccountCredential
import com.google.api.client.http.ByteArrayContent
import com.google.api.client.http.javanet.NetHttpTransport
import com.google.api.client.json.gson.GsonFactory
import com.google.api.services.drive.Drive
import com.google.api.services.drive.DriveScopes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.Collections

class DriveService(private val context: Context) {
    private val authManager = AuthManager(context)
    private val transport = NetHttpTransport()
    private val jsonFactory = GsonFactory.getDefaultInstance()
    private val TAG = "DriveService"
    
    // Get configured Drive service instance
    private suspend fun getDriveService(): Drive? {
        val accessToken = authManager.getAccessToken() ?: return null
        
        val credential = GoogleAccountCredential.usingOAuth2(
            context, Collections.singleton(DriveScopes.DRIVE_FILE)
        ).apply {
            setBackOff(null)
        }
        
        return Drive.Builder(transport, jsonFactory, HttpExecutorWithToken(accessToken))
            .setApplicationName("WearNote")
            .build()
    }
    
    // Get or create app folder
    suspend fun getOrCreateAppFolder(): String? {
        return withContext(Dispatchers.IO) {
            try {
                val drive = getDriveService() ?: return@withContext null
                
                // Query for app folder
                val folderListResult = drive.files().list()
                    .setQ("name='${DriveConstants.NOTES_DIRECTORY}' and mimeType='application/vnd.google-apps.folder' and trashed=false")
                    .setSpaces("drive")
                    .execute()
                    
                // Return existing folder or create new one
                if (folderListResult.files.isNotEmpty()) {
                    folderListResult.files[0].id
                } else {
                    // Create new folder
                    val folderMetadata = com.google.api.services.drive.model.File().apply {
                        name = DriveConstants.NOTES_DIRECTORY
                        mimeType = "application/vnd.google-apps.folder"
                    }
                    
                    val folder = drive.files().create(folderMetadata)
                        .setFields("id")
                        .execute()
                        
                    folder.id
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error accessing app folder", e)
                null
            }
        }
    }
    
    // Save note to Drive
    suspend fun saveNote(title: String, content: String): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                val drive = getDriveService() ?: return@withContext false
                val folderId = getOrCreateAppFolder() ?: return@withContext false
                
                // Check for existing file with same name
                val existingFileId = findNoteByTitle(title)
                
                // Prepare file metadata
                val fileMetadata = com.google.api.services.drive.model.File().apply {
                    name = "$title.txt"
                    if (folderId.isNotEmpty()) {
                        parents = listOf(folderId)
                    }
                }
                
                // Prepare file content
                val contentStream = ByteArrayContent.fromString("text/plain", content)
                
                if (existingFileId != null) {
                    // Update existing file
                    drive.files().update(existingFileId, fileMetadata, contentStream).execute()
                } else {
                    // Create new file
                    drive.files().create(fileMetadata, contentStream)
                        .setFields("id, name")
                        .execute()
                }
                
                true
            } catch (e: Exception) {
                Log.e(TAG, "Error saving note", e)
                false
            }
        }
    }
    
    // Find note by title
    suspend fun findNoteByTitle(title: String): String? {
        return withContext(Dispatchers.IO) {
            try {
                val drive = getDriveService() ?: return@withContext null
                val folderId = getOrCreateAppFolder() ?: return@withContext null
                
                val query = "'$folderId' in parents and name='$title.txt' and trashed=false"
                val fileList = drive.files().list()
                    .setQ(query)
                    .setSpaces("drive")
                    .setFields("files(id, name)")
                    .execute()
                
                if (fileList.files.isNotEmpty()) {
                    fileList.files[0].id
                } else {
                    null
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error finding note", e)
                null
            }
        }
    }
    
    // Read note content
    suspend fun readNote(fileId: String): String? {
        return withContext(Dispatchers.IO) {
            try {
                val drive = getDriveService() ?: return@withContext null
                
                val outputStream = ByteArrayOutputStream()
                drive.files().get(fileId)
                    .executeMediaAndDownloadTo(outputStream)
                
                outputStream.toString("UTF-8")
            } catch (e: Exception) {
                Log.e(TAG, "Error reading note", e)
                null
            }
        }
    }
    
    // List all notes
    suspend fun listAllNotes(): List<NoteMetadata>? {
        return withContext(Dispatchers.IO) {
            try {
                val drive = getDriveService() ?: return@withContext null
                val folderId = getOrCreateAppFolder() ?: return@withContext null
                
                val query = "'$folderId' in parents and mimeType='text/plain' and trashed=false"
                val result = drive.files().list()
                    .setQ(query)
                    .setSpaces("drive")
                    .setFields("files(id, name, createdTime, modifiedTime)")
                    .execute()
                    
                result.files.map { file ->
                    val title = file.name.removeSuffix(".txt")
                    NoteMetadata(
                        id = file.id,
                        title = title,
                        createdTime = file.createdTime?.value ?: 0,
                        modifiedTime = file.modifiedTime?.value ?: 0
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error listing notes", e)
                null
            }
        }
    }
    
    // Delete note
    suspend fun deleteNote(fileId: String): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                val drive = getDriveService() ?: return@withContext false
                drive.files().delete(fileId).execute()
                true
            } catch (e: Exception) {
                Log.e(TAG, "Error deleting note", e)
                false
            }
        }
    }
    
    // For protected API request handling
    private inner class HttpExecutorWithToken(private val accessToken: String) : 
        com.google.api.client.http.HttpRequestInitializer {
        
        @Throws(IOException::class)
        override fun initialize(request: com.google.api.client.http.HttpRequest) {
            request.headers.authorization = "Bearer $accessToken"
        }
    }
}

// Note metadata data class
data class NoteMetadata(
    val id: String,
    val title: String,
    val createdTime: Long,
    val modifiedTime: Long
)
