package com.dpad.messaging.ui.chat

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.dpad.messaging.data.db.AppDatabase
import com.dpad.messaging.data.model.SmsMessage
import com.dpad.messaging.data.repository.SmsSender
import com.dpad.messaging.data.repository.SmsRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class ChatViewModel(app: Application) : AndroidViewModel(app) {

    private val db = AppDatabase.getDatabase(app)
    private val repo = SmsRepository(app, db.threadMetadataDao())
    private val sender = SmsSender(app)

    private val _messages = MutableStateFlow<List<SmsMessage>>(emptyList())
    val messages: StateFlow<List<SmsMessage>> = _messages

    private val _isSending = MutableStateFlow(false)
    val isSending: StateFlow<Boolean> = _isSending

    private var threadId: Long = -1
    private var address: String = ""

    fun init(threadId: Long, address: String) {
        this.threadId = threadId
        this.address = address
        load()
        viewModelScope.launch { repo.markThreadRead(threadId) }
    }

    fun load() {
        viewModelScope.launch {
            _messages.value = repo.getMessages(threadId)
        }
    }

    fun send(text: String, imageUri: Uri? = null) {
        if ((text.isBlank() && imageUri == null) || address.isBlank()) return
        viewModelScope.launch {
            _isSending.value = true
            try {
                val addresses = address.split(",").map { it.trim() }.filter { it.isNotBlank() }
                
                if (imageUri != null || addresses.size > 1) {
                    var imageBytes: ByteArray? = null
                    var mimeType: String? = null
                    
                    if (imageUri != null) {
                        val cr = getApplication<Application>().contentResolver
                        mimeType = cr.getType(imageUri) ?: "image/jpeg"
                        cr.openInputStream(imageUri)?.use { stream ->
                            imageBytes = stream.readBytes()
                        }
                    }
                    
                    sender.sendMms(addresses, text, imageBytes, mimeType)
                } else {
                    sender.sendSms(addresses.first(), text)
                }
                
                // Reload after short delay to pick up the sent message
                kotlinx.coroutines.delay(600)
                _messages.value = repo.getMessages(threadId)
            } catch (_: Exception) {
            } finally {
                _isSending.value = false
            }
        }
    }
}
