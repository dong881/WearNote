package com.example.wearnote.network

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import javax.net.ssl.HttpsURLConnection

class ExternalServerClient {
    
    companion object {
        private const val TAG = "ExternalServerClient"
        private const val SERVER_URL = "http://140.118.123.107:5000/process"
        private const val SERVER_URL_HTTPS = "https://140.118.123.107:5000/process"
        private const val SERVER_HEALTH_URL = "http://140.118.123.107:5000/health"
    }
    
    suspend fun checkServerHealth(): Boolean = withContext(Dispatchers.IO) {
        try {
            val url = URL(SERVER_HEALTH_URL)
            val connection = url.openConnection() as HttpURLConnection
            connection.connectTimeout = 5000
            connection.readTimeout = 5000
            connection.requestMethod = "GET"
            
            val responseCode = connection.responseCode
            Log.d(TAG, "Server health check response code: $responseCode")
            
            if (responseCode == 200) {
                val response = connection.inputStream.bufferedReader().use { it.readText() }
                Log.d(TAG, "Health check response: $response")
                return@withContext true
            } else {
                Log.e(TAG, "Health check failed with response code: $responseCode")
                return@withContext false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Health check failed with exception", e)
            return@withContext false
        }
    }
    
    suspend fun sendFileIdToServer(fileId: String): Boolean = withContext(Dispatchers.IO) {
        // First check if the server is healthy
        val isHealthy = checkServerHealth()
        Log.d(TAG, "Server health check result: $isHealthy")
        
        if (!isHealthy) {
            Log.e(TAG, "Server is not healthy, cannot proceed with upload")
            return@withContext false
        }
        
        try {
            Log.d(TAG, "Sending real file ID to server: $fileId")
            
            // Try HTTPS first, then fallback to HTTP if it fails
            val result = try {
                sendRequest(SERVER_URL_HTTPS, fileId)
            } catch (e: Exception) {
                Log.d(TAG, "HTTPS request failed, trying HTTP: ${e.message}")
                sendRequest(SERVER_URL, fileId)
            }
            
            return@withContext result
            
        } catch (e: IOException) {
            Log.e(TAG, "Error sending file ID to server", e)
            
            // Log detailed error information
            when {
                e.message?.contains("Cleartext HTTP traffic") == true -> {
                    Log.e(TAG, "Cleartext HTTP traffic not permitted. Add network security config.")
                }
                e.message?.contains("Unable to resolve host") == true -> {
                    Log.e(TAG, "Network error: Unable to resolve host. Check internet connection and server address.")
                }
                e.message?.contains("Connection refused") == true -> {
                    Log.e(TAG, "Server refused connection. Verify server is running and accepting connections.")
                }
                else -> {
                    Log.e(TAG, "Unknown network error: ${e.message}")
                }
            }
            
            return@withContext false
        }
    }
    
    private fun sendRequest(urlString: String, fileId: String): Boolean {
        val url = URL(urlString)
        val connection = if (urlString.startsWith("https")) {
            url.openConnection() as HttpsURLConnection
        } else {
            url.openConnection() as HttpURLConnection
        }
        
        connection.connectTimeout = 10000 // 10 seconds timeout
        connection.readTimeout = 10000
        
        try {
            connection.requestMethod = "POST"
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("User-Agent", "WearNote/1.0")
            connection.doOutput = true
            
            // Create JSON payload
            val jsonPayload = JSONObject().apply {
                put("file_id", fileId)
                // Add additional information that might be useful for server debugging
                put("timestamp", System.currentTimeMillis())
                put("client_info", "WearNote Android App")
            }.toString()
            
            Log.d(TAG, "Sending data: $jsonPayload")
            
            // Send the request
            val outputStream = connection.outputStream
            outputStream.write(jsonPayload.toByteArray())
            outputStream.flush()
            outputStream.close()
            
            // Check response
            val responseCode = connection.responseCode
            Log.d(TAG, "Server response code: $responseCode")
            
            // Read response body if available
            var responseBody = ""
            if (responseCode == HttpURLConnection.HTTP_OK) {
                val inputStream = connection.inputStream
                responseBody = inputStream.bufferedReader().use { it.readText() }
                Log.d(TAG, "Server response: $responseBody")
            }
            
            return (responseCode >= 200 && responseCode < 300)
            
        } catch (e: Exception) {
            Log.e(TAG, "Error during request to $urlString: ${e.message}")
            throw e
        } finally {
            connection.disconnect()
        }
    }
}
