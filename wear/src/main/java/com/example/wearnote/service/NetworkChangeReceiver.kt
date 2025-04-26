package com.example.wearnote.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import androidx.core.content.getSystemService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class NetworkChangeReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val cm = ctx.getSystemService<ConnectivityManager>() ?: return
        val info = cm.activeNetworkInfo
        if (info != null && info.isConnected) {
            CoroutineScope(Dispatchers.IO).launch {
                GoogleDriveUploader.processPending(ctx)
            }
        }
    }
}
