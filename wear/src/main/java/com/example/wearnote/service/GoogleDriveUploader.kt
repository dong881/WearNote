package com.example.wearnote.service

import android.content.Context
import java.io.File

object GoogleDriveUploader {
    // 呼叫 Google Drive REST API，上傳成功回傳 fileId
    suspend fun upload(context: Context, file: File): String? {
        // TODO: 實作 OAuth2 取 token & Retrofit/OkHttp 上傳
        return try {
            // val response = driveService.files().create(...).execute()
            // response.id
            "mockFileId"
        } catch (e: Exception) {
            // 上傳失敗 -> 傳回 null
            null
        }
    }

    // 若上傳失敗，將檔案路徑加入暫存清單（可用 Room/SharedPrefs or local folder queue）
    fun enqueuePending(context: Context, file: File) {
        val queueDir = File(context.filesDir, "pending")
        if (!queueDir.exists()) queueDir.mkdir()
        file.copyTo(File(queueDir, file.name), overwrite = true)
    }

    // 扫描 & 上傳所有待處理檔案
    suspend fun processPending(context: Context) {
        val queueDir = File(context.filesDir, "pending")
        queueDir.listFiles()?.forEach { f ->
            val id = upload(context, f)
            if (id != null) f.delete()
        }
    }
}
