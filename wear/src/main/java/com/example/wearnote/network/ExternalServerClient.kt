package com.example.wearnote.network

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException

class ExternalServerClient {
    
    companion object {
        private const val TAG = "ExternalServerClient"
        private const val SERVER_URL = "http://localhost:5000/process"
    }
    
    private val client = OkHttpClient()
    
    suspend fun sendFileIdToServer(fileId: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val json = JSONObject().apply {
                put("file_id", fileId)
            }
            
            val mediaType = "application/json; charset=utf-8".toMediaType()
            val body = json.toString().toRequestBody(mediaType)
            
            val request = Request.Builder()
                .url(SERVER_URL)
                .post(body)
                .build()
                
            client.newCall(request).execute().use { response ->
                val success = response.isSuccessful
                if (!success) {
                    Log.e(TAG, "Server error: ${response.code}")
                }
                return@withContext success
            }
        } catch (e: IOException) {
            Log.e(TAG, "Error sending file ID to server", e)
            return@withContext false
        }
    }
}
