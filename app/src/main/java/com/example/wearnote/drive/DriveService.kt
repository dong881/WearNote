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
    
    // 創建並配置 Drive 服務
    private fun getDriveService(): Drive? {
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
    
    // 獲取應用文件夾 ID
    suspend fun getOrCreateAppFolder(): String? {
        return withContext(Dispatchers.IO) {
            try {
                val drive = getDriveService() ?: return@withContext null
                
                // 查詢應用文件夾
                val folderListResult = drive.files().list()
                    .setQ("name='${DriveConstants.NOTES_DIRECTORY}' and mimeType='application/vnd.google-apps.folder' and trashed=false")
                    .setSpaces("drive")
                    .execute()
                    
                // 返回現有文件夾或創建新文件夾
                if (folderListResult.files.isNotEmpty()) {
                    folderListResult.files[0].id
                } else {
                    // 創建新文件夾
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
                Log.e("DriveService", "Error accessing app folder", e)
                null
            }
        }
    }
    
    // 保存筆記到 Drive
    suspend fun saveNote(title: String, content: String): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                val drive = getDriveService() ?: return@withContext false
                val folderId = getOrCreateAppFolder() ?: return@withContext false
                
                // 查找是否已有同名文件
                val existingFileId = findNoteByTitle(title)
                
                // 準備文件元數據
                val fileMetadata = com.google.api.services.drive.model.File().apply {
                    name = "$title.txt"
                    if (folderId.isNotEmpty()) {
                        parents = listOf(folderId)
                    }
                }
                
                // 準備文件內容
                val contentStream = ByteArrayContent.fromString("text/plain", content)
                
                if (existingFileId != null) {
                    // 更新現有文件
                    drive.files().update(existingFileId, fileMetadata, contentStream).execute()
                } else {
                    // 創建新文件
                    drive.files().create(fileMetadata, contentStream)
                        .setFields("id, name")
                        .execute()
                }
                
                true
            } catch (e: Exception) {
                Log.e("DriveService", "Error saving note", e)
                false
            }
        }
    }
    
    // 根據標題查找筆記
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
                Log.e("DriveService", "Error finding note", e)
                null
            }
        }
    }
    
    // 讀取筆記內容
    suspend fun readNote(fileId: String): String? {
        return withContext(Dispatchers.IO) {
            try {
                val drive = getDriveService() ?: return@withContext null
                
                val outputStream = ByteArrayOutputStream()
                drive.files().get(fileId)
                    .executeMediaAndDownloadTo(outputStream)
                
                outputStream.toString("UTF-8")
            } catch (e: Exception) {
                Log.e("DriveService", "Error reading note", e)
                null
            }
        }
    }
    
    // 獲取所有筆記列表
    suspend fun listAllNotes(): List<NoteMetadata>? {
        return withContext(Dispatchers.IO) {
            try {
                val drive = getDriveService() ?: return@withContext null
                val folderId = getOrCreateAppFolder() ?: return@withContext null
                
                val query = "'$folderId' in parents and mimeType='text/plain' and trashed=false"
                val fileList = drive.files().list()
                    .setQ(query)
                    .setSpaces("drive")
                    .setFields("files(id, name, createdTime, modifiedTime)")
                    .execute()
                
                fileList.files.map { file ->
                    // 移除 .txt 後綴以獲取真實標題
                    val title = file.name.removeSuffix(".txt")
                    NoteMetadata(
                        id = file.id,
                        title = title,
                        createdTime = file.createdTime?.value ?: 0,
                        modifiedTime = file.modifiedTime?.value ?: 0
                    )
                }
            } catch (e: Exception) {
                Log.e("DriveService", "Error listing notes", e)
                null
            }
        }
    }
    
    // 刪除筆記
    suspend fun deleteNote(fileId: String): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                val drive = getDriveService() ?: return@withContext false
                drive.files().delete(fileId).execute()
                true
            } catch (e: Exception) {
                Log.e("DriveService", "Error deleting note", e)
                false
            }
        }
    }
    
    // 用於受保護 API 的請求處理器
    private inner class HttpExecutorWithToken(private val accessToken: String) : 
            com.google.api.client.http.HttpRequestInitializer {
        
        @Throws(IOException::class)
        override fun initialize(request: com.google.api.client.http.HttpRequest) {
            request.headers.authorization = "Bearer $accessToken"
        }
    }
}

// 筆記元數據數據類
data class NoteMetadata(
    val id: String,
    val title: String,
    val createdTime: Long,
    val modifiedTime: Long
)
