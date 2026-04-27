package com.dpad.messaging.data.model

data class SmsThread(
    val threadId: Long,
    val address: String,
    val contactName: String,
    val snippet: String,
    val date: Long,
    val unreadCount: Int,
    val isGroup: Boolean,
    val isPinned: Boolean = false,
    val isArchived: Boolean = false,
    val isMuted: Boolean = false,
    val isBlocked: Boolean = false,
    val customTitle: String? = null
)

