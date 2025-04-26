package com.example.wearnote.network

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

class ExternalServerClient {
    
    companion object {
        private const val TAG = "ExternalServerClient"
        private const val SERVER_URL = "http://140.118.123.107:5000/process" // Change to your real server
    }
    
    suspend fun sendFileIdToServer(fileId: String): Boolean = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "Sending file ID to server: $fileId")
            
            val url = URL(SERVER_URL)
            val connection = url.openConnection() as HttpURLConnection
            
            try {
                connection.requestMethod = "POST"
                connection.setRequestProperty("Content-Type", "application/json")
                connection.doOutput = true
                
                // Create JSON payload
                val jsonPayload = JSONObject().apply {
                    put("file_id", fileId)
                }.toString()
                
                // Send the request
                val outputStream = connection.outputStream
                outputStream.write(jsonPayload.toByteArray())
                outputStream.close()
                
                // Check response
                val responseCode = connection.responseCode
                Log.d(TAG, "Server response code: $responseCode")
                
                return@withContext (responseCode >= 200 && responseCode < 300)
                
            } finally {
                connection.disconnect()
            }
            
        } catch (e: IOException) {
            Log.e(TAG, "Error sending file ID to server", e)
            return@withContext false
        }
    }
}
