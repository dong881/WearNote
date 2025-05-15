package com.example.wearnote.application

import android.app.Application
import android.util.Log
import com.example.wearnote.service.PendingUploadsManager
import com.example.wearnote.service.NetworkMonitorService

class WearNoteApplication : Application() {
    
    override fun onCreate() {
        super.onCreate()
        
        // Initialize PendingUploadsManager
        PendingUploadsManager.initialize(this)
        
        // Start network monitoring service
        NetworkMonitorService.startMonitoring(this)
        
        Log.d("WearNoteApp", "Application initialized")
    }
}
