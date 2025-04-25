package com.example.wearnote

import android.app.Application
import android.util.Log

class WearNoteApplication : Application() {
    companion object {
        const val TAG = "WearNoteApp"
    }
    
    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Application created")
    }
}
