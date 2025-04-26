package com.example.wearnote.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.util.Log
import com.example.wearnote.service.GoogleDriveUploader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class NetworkChangeReceiver : BroadcastReceiver() {

    private val TAG = "NetworkChangeReceiver"
    private val scope = CoroutineScope(Dispatchers.IO)

    override fun onReceive(context: Context, intent: Intent) {
        if (ConnectivityManager.CONNECTIVITY_ACTION == intent.action) {
            if (GoogleDriveUploader.isNetworkAvailable(context)) {
                Log.d(TAG, "Network available, attempting to process pending uploads.")
                // Launch a coroutine to process pending uploads off the main thread
                scope.launch {
                    GoogleDriveUploader.processPending(context)
                }
            } else {
                Log.d(TAG, "Network lost.")
            }
        }
    }
}
