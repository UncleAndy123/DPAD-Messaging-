package com.dpad.messaging.ui.conversations

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.dpad.messaging.data.db.AppDatabase
import com.dpad.messaging.data.model.SmsThread
import com.dpad.messaging.data.repository.SmsRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class ConversationsViewModel(application: Application) : AndroidViewModel(application) {

    private val db = AppDatabase.getDatabase(application)
    private val repository = SmsRepository(application, db.threadMetadataDao())

    private val _threads = MutableStateFlow<List<SmsThread>>(emptyList())
    val threads: StateFlow<List<SmsThread>> = _threads

    private val _isLoading = MutableStateFlow(true)
    val isLoading: StateFlow<Boolean> = _isLoading

    init {
        loadThreads()
    }

    fun loadThreads() {
        viewModelScope.launch {
            _isLoading.value = true
            val smsThreads = repository.getThreads()
            _threads.value = smsThreads
            _isLoading.value = false
        }
    }

    fun togglePin(threadId: Long) {
        val thread = _threads.value.find { it.threadId == threadId } ?: return
        viewModelScope.launch {
            repository.togglePin(threadId, !thread.isPinned)
            loadThreads()
        }
    }

    fun toggleArchive(threadId: Long) {
        val thread = _threads.value.find { it.threadId == threadId } ?: return
        viewModelScope.launch {
            repository.toggleArchive(threadId, !thread.isArchived)
            loadThreads()
        }
    }

    fun toggleMute(threadId: Long) {
        val thread = _threads.value.find { it.threadId == threadId } ?: return
        viewModelScope.launch {
            repository.toggleMute(threadId, !thread.isMuted)
            loadThreads()
        }
    }

    fun toggleBlock(threadId: Long) {
        val thread = _threads.value.find { it.threadId == threadId } ?: return
        viewModelScope.launch {
            repository.toggleBlock(threadId, !thread.isBlocked)
            loadThreads()
        }
    }
}
