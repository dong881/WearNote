package com.example.wearnote.services

import android.content.Context
import android.content.Intent
import android.util.Log
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService

class DataLayerListenerService : WearableListenerService() {
    
    companion object {
        private const val TAG = "DataLayerService"
        private const val PATH_AUDIO_SOURCE = "/wearnote/audio_source"
        private const val PATH_START_RECORDING = "/wearnote/start_recording"
        private const val PATH_STOP_RECORDING = "/wearnote/stop_recording"
        private const val PATH_PAUSE_RECORDING = "/wearnote/pause_recording"
        private const val PATH_RESUME_RECORDING = "/wearnote/resume_recording"
    }
    
    override fun onDataChanged(dataEvents: DataEventBuffer) {
        dataEvents.forEach { event ->
            if (event.type == DataEvent.TYPE_CHANGED) {
                val uri = event.dataItem.uri
                val path = uri.path ?: return@forEach
                
                when (path) {
                    PATH_AUDIO_SOURCE -> {
                        val dataMap = DataMapItem.fromDataItem(event.dataItem).dataMap
                        val audioBytes = dataMap.getByteArray("audio_data")
                        if (audioBytes != null) {
                            // Use audioBytes safely here
                            // The original code that got the type mismatch error
                        } else {
                            Log.e(TAG, "Received null audio data")
                        }
                        
                        val audioSource = dataMap.getInt("source", RecordingService.AUDIO_SOURCE_WATCH)
                        
                        // Store the selected audio source
                        val prefs = getSharedPreferences("recording_prefs", Context.MODE_PRIVATE)
                        prefs.edit().putInt(RecordingService.PREF_AUDIO_SOURCE, audioSource).apply()
                        
                        Log.d(TAG, "Received audio source preference: $audioSource")
                    }
                }
            }
        }
    }
    
    override fun onMessageReceived(messageEvent: MessageEvent) {
        val path = messageEvent.path
        
        when (path) {
            PATH_START_RECORDING -> {
                Log.d(TAG, "Received start recording message")
                startRecordingService(RecordingService.ACTION_START)
            }
            PATH_STOP_RECORDING -> {
                Log.d(TAG, "Received stop recording message")
                startRecordingService(RecordingService.ACTION_STOP)
            }
            PATH_PAUSE_RECORDING -> {
                Log.d(TAG, "Received pause recording message")
                startRecordingService(RecordingService.ACTION_PAUSE)
            }
            PATH_RESUME_RECORDING -> {
                Log.d(TAG, "Received resume recording message")
                startRecordingService(RecordingService.ACTION_RESUME)
            }
        }
    }
    
    private fun startRecordingService(action: String) {
        val intent = Intent(this, RecordingService::class.java).apply {
            this.action = action
        }
        startForegroundService(intent)
    }
}