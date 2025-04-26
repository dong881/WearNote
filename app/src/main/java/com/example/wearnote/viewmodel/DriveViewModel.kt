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
    
    // LiveData 屬性
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
    
    // 檢查授權狀態
    fun checkAuthStatus() {
        viewModelScope.launch {
            _isAuthorized.value = authManager.isAuthorized()
        }
    }
    
    // 更新筆記列表
    fun refreshNotesList() {
        viewModelScope.launch {
            val notesList = driveService.listAllNotes()
            _notes.value = notesList ?: emptyList()
        }
    }
    
    // 加載特定筆記內容
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
    
    // 保存筆記
    fun saveNote(title: String, content: String) {
        viewModelScope.launch {
            val success = driveService.saveNote(title, content)
            if (success) {
                _operationStatus.value = OperationStatus.Success("Note saved successfully")
                refreshNotesList()
            } else {
                _operationStatus.value = OperationStatus.Error("Failed to save note")
            }
        }
    }
    
    // 刪除筆記
    fun deleteNote(fileId: String) {
        viewModelScope.launch {
            val success = driveService.deleteNote(fileId)
            if (success) {
                _operationStatus.value = OperationStatus.Success("Note deleted successfully")
                refreshNotesList()
            } else {
                _operationStatus.value = OperationStatus.Error("Failed to delete note")
            }
        }
    }
    
    // 登出
    fun logout() {
        authManager.clearAuthData()
        _isAuthorized.value = false
        _notes.value = emptyList()
    }
}

// 操作狀態密封類
sealed class OperationStatus {
    data class Success(val message: String) : OperationStatus()
    data class Error(val message: String) : OperationStatus()
}
