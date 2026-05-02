package com.dpad.messaging.ui.chat

import android.app.Application
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.dpad.messaging.data.db.AppDatabase
import com.dpad.messaging.data.model.DeliveryState
import com.dpad.messaging.data.model.MsgType
import com.dpad.messaging.data.model.SmsMessage
import com.dpad.messaging.data.repository.SmsSender
import com.dpad.messaging.data.repository.SmsRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
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
    private var messagesObserver: ContentObserver? = null
    private var reloadJob: Job? = null

    fun init(threadId: Long, address: String) {
        Log.d("ChatVM", "init threadId=$threadId address=$address")
        this.threadId = threadId
        this.address = address
        load()
        if (threadId > 0) viewModelScope.launch { repo.markThreadRead(threadId) }
        registerObservers()
    }

    private fun scheduleReload() {
        reloadJob?.cancel()
        reloadJob = viewModelScope.launch {
            delay(150)
            val tid = threadId
            Log.d("ChatVM", "scheduleReload tid=$tid currentMessages=${_messages.value.size}")
            if (tid <= 0) return@launch
            val fetched = repo.getMessages(tid)
            Log.d("ChatVM", "scheduleReload fetched=${fetched.size} for tid=$tid")
            // Keep any optimistic SENDING bubbles not yet confirmed in provider
            val now = System.currentTimeMillis()
            val windowMs = 10_000L
            val stillPending = _messages.value.filter { opt ->
                opt.state == DeliveryState.SENDING &&
                fetched.none { p -> p.type == opt.type && p.body == opt.body && kotlin.math.abs(p.date - now) <= windowMs }
            }
            _messages.value = if (stillPending.isEmpty()) fetched else fetched + stillPending
        }
    }

    private fun registerObservers() {
        messagesObserver?.let {
            try { getApplication<Application>().contentResolver.unregisterContentObserver(it) } catch (_: Exception) {}
        }
        val handler = Handler(Looper.getMainLooper())
        messagesObserver = object : ContentObserver(handler) {
            override fun onChange(selfChange: Boolean, uri: Uri?) = scheduleReload()
            override fun onChange(selfChange: Boolean) = scheduleReload()
        }
        val cr = getApplication<Application>().contentResolver
        try { cr.registerContentObserver(Uri.parse("content://mms-sms/"), true, messagesObserver!!) } catch (_: Exception) {}
    }

    fun load() {
        viewModelScope.launch {
            val tid = threadId
            Log.d("ChatVM", "load() tid=$tid")
            if (tid > 0) {
                val msgs = repo.getMessages(tid)
                Log.d("ChatVM", "load() got ${msgs.size} messages")
                _messages.value = msgs
            }
        }
    }

    fun send(text: String, imageUri: Uri? = null) {
        if ((text.isBlank() && imageUri == null) || address.isBlank()) return
        viewModelScope.launch {
            _isSending.value = true
            try {
                val addresses = address.split(",").map { it.trim() }.filter { it.isNotBlank() }
                val isMms = imageUri != null || addresses.size > 1
                val outType = if (isMms) MsgType.MMS_OUT else MsgType.SMS_OUT
                val partUris = imageUri?.let { listOf(it.toString()) } ?: emptyList()
                val sentAt = System.currentTimeMillis()

                // Show optimistic bubble immediately
                val optimistic = SmsMessage(-sentAt, threadId, address, text, sentAt, outType, partUris, DeliveryState.SENDING)
                _messages.value = _messages.value + optimistic

                if (isMms) {
                    var imageBytes: ByteArray? = null
                    var mimeType: String? = null
                    if (imageUri != null) {
                        val cr = getApplication<Application>().contentResolver
                        mimeType = cr.getType(imageUri) ?: "image/jpeg"
                        cr.openInputStream(imageUri)?.use { stream -> imageBytes = stream.readBytes() }
                    }
                    sender.sendMms(addresses, text, imageBytes, mimeType)
                    // MMS: ContentObserver will fire when provider updates; scheduleReload handles it
                } else {
                    // sendSms writes to the provider and returns the inserted Uri
                    val insertedUri = sender.sendSms(addresses.first(), text)
                    Log.d("ChatVM", "sendSms insertedUri=$insertedUri")

                    if (insertedUri != null) {
                        // Read thread_id from the inserted row — no polling needed
                        val resolvedThreadId = repo.threadIdForUri(insertedUri)
                        Log.d("ChatVM", "resolvedThreadId=$resolvedThreadId (was $threadId)")
                        if (resolvedThreadId != null && resolvedThreadId > 0) {
                            threadId = resolvedThreadId
                        }
                        // Fetch fresh list from provider which now includes the sent message
                        val tid = threadId
                        if (tid > 0) {
                            val fetched = repo.getMessages(tid)
                            Log.d("ChatVM", "post-send fetched=${fetched.size}")
                            _messages.value = fetched
                        }
                    }
                    // If insert failed for some reason, ContentObserver will still reload eventually
                }
            } catch (e: Exception) {
                Log.e("ChatVM", "send error", e)
                // Remove optimistic bubble on failure
                _messages.value = _messages.value.filter { it.state != DeliveryState.SENDING }
            } finally {
                _isSending.value = false
            }
        }
    }

    fun deleteMessage(message: SmsMessage) {
        viewModelScope.launch {
            repo.deleteMessage(message)
            val tid = threadId
            if (tid > 0) _messages.value = repo.getMessages(tid)
        }
    }

    override fun onCleared() {
        super.onCleared()
        messagesObserver?.let {
            try { getApplication<Application>().contentResolver.unregisterContentObserver(it) } catch (_: Exception) {}
        }
        messagesObserver = null
        reloadJob?.cancel()
    }
}
