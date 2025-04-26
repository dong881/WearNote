package com.example.wearnote.util

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

object NetworkDiagnostics {
    private const val TAG = "NetworkDiagnostics"
    
    fun isNetworkAvailable(context: Context): Boolean {
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
        
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }
    
    suspend fun pingServer(url: String): Boolean = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "Pinging server: $url")
            val connection = URL(url).openConnection() as HttpURLConnection
            connection.connectTimeout = 5000
            connection.readTimeout = 5000
            connection.requestMethod = "HEAD"  // Just check if server is reachable
            
            val responseCode = connection.responseCode
            Log.d(TAG, "Server ping response: $responseCode")
            
            return@withContext responseCode in 200..399
            
        } catch (e: Exception) {
            Log.e(TAG, "Error pinging server: ${e.message}")
            return@withContext false
        }
    }
    
    suspend fun runDiagnostics(context: Context, serverUrl: String): String = withContext(Dispatchers.IO) {
        val results = StringBuilder()
        
        // Check network connectivity
        results.append("Network available: ${isNetworkAvailable(context)}\n")
        
        // Try to ping the server
        val canReachServer = pingServer(serverUrl)
        results.append("Server reachable: $canReachServer\n")
        
        // Log DNS info
        try {
            val domain = URL(serverUrl).host
            val ipAddress = java.net.InetAddress.getByName(domain).hostAddress
            results.append("DNS resolution: $domain -> $ipAddress\n")
        } catch (e: Exception) {
            results.append("DNS resolution failed: ${e.message}\n")
        }
        
        return@withContext results.toString()
    }
}
