package com.example.wearnote.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class NetworkChangeReceiver : BroadcastReceiver() {
    companion object {
        private const val TAG = "NetworkChangeReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == ConnectivityManager.CONNECTIVITY_ACTION) {
            if (isNetworkAvailable(context)) {
                Log.d(TAG, "Network is now available, processing pending uploads")
                // Use goAsync to properly manage the BroadcastReceiver lifecycle
                val pendingResult = goAsync()
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        // Ensure PendingUploadsManager is initialized before processing
                        PendingUploadsManager.initialize(context)
                        PendingUploadsManager.processAllPendingUploads(context)
                    } catch (e: Exception) {
                        Log.e(TAG, "Error processing pending uploads on network change", e)
                    } finally {
                        pendingResult.finish()
                    }
                }
            } else {
                Log.d(TAG, "Network is not available")
            }
        }
    }
    
    private fun isNetworkAvailable(context: Context): Boolean {
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val network = connectivityManager.activeNetwork ?: return false
            val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
            
            return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        } else {
            @Suppress("DEPRECATION")
            val networkInfo = connectivityManager.activeNetworkInfo
            @Suppress("DEPRECATION")
            return networkInfo != null && networkInfo.isConnected
        }
    }
}
