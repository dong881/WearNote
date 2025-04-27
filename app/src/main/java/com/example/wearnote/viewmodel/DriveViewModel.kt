package com.example.wearnote.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.example.wearnote.auth.AuthManager
import com.example.wearnote.drive.DriveService
import com.example.wearnote.drive.NoteMetadata
import kotlinx.coroutines.launch

class DriveViewModel(application: Application) : AndroidViewModel(application) {
    private val authManager = AuthManager(application.applicationContext)
    private val driveService = DriveService(application.applicationContext)
    
    // LiveData properties
    private val _isAuthorized = MutableLiveData<Boolean>()
    val isAuthorized: LiveData<Boolean> = _isAuthorized
    
    private val _notes = MutableLiveData<List<NoteMetadata>>()
    val notes: LiveData<List<NoteMetadata>> = _notes
    
    private val _currentNote = MutableLiveData<String>()
    val currentNote: LiveData<String> = _currentNote
    
    private val _operationStatus = MutableLiveData<OperationStatus>()
    val operationStatus: LiveData<OperationStatus> = _operationStatus
    
    init {
        checkAuthStatus()
    }
    
    // Check authorization status
    fun checkAuthStatus() {
        viewModelScope.launch {
            _isAuthorized.value = authManager.isAuthorized()
        }
    }
    
    // Refresh notes list
    fun refreshNotesList() {
        viewModelScope.launch {
            val notesList = driveService.listAllNotes()
            _notes.value = notesList ?: emptyList()
        }
    }
    
    // Load specific note content
    fun loadNote(fileId: String) {
        viewModelScope.launch {
            val content = driveService.readNote(fileId)
            if (content != null) {
                _currentNote.value = content
                _operationStatus.value = OperationStatus.Success("Note loaded successfully")
            } else {
                _operationStatus.value = OperationStatus.Error("Failed to load note")
            }
        }
    }
    
    // Save note
    fun saveNote(title: String, content: String) {
        viewModelScope.launch {
            val success = driveService.saveNote(title, content)
            if (success) {
                refreshNotesList()
                _operationStatus.value = OperationStatus.Success("Note saved successfully")
            } else {
                _operationStatus.value = OperationStatus.Error("Failed to save note")
            }
        }
    }
    
    // Delete note
    fun deleteNote(fileId: String) {
        viewModelScope.launch {
            val success = driveService.deleteNote(fileId)
            if (success) {
                refreshNotesList()
                _operationStatus.value = OperationStatus.Success("Note deleted successfully")
            } else {
                _operationStatus.value = OperationStatus.Error("Failed to delete note")
            }
        }
    }
    
    // Sign out
    fun signOut() {
        authManager.signOut()
        _isAuthorized.value = false
    }
}

// Operation status sealed class
sealed class OperationStatus {
    data class Success(val message: String) : OperationStatus()
    data class Error(val message: String) : OperationStatus()
    object Loading : OperationStatus()
}
