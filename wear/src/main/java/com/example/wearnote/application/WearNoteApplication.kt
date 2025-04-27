package com.example.wearnote.application

import android.app.Application
import android.util.Log
import com.example.wearnote.service.PendingUploadsManager

class WearNoteApplication : Application() {
    
    override fun onCreate() {
        super.onCreate()
        
        // Initialize PendingUploadsManager
        PendingUploadsManager.initialize(this)
        
        Log.d("WearNoteApp", "Application initialized")
    }
}
