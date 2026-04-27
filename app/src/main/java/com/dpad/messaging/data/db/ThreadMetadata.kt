package com.dpad.messaging.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "thread_metadata")
data class ThreadMetadata(
    @PrimaryKey
    val threadId: Long,
    val isPinned: Boolean = false,
    val isArchived: Boolean = false,
    val isMuted: Boolean = false,
    val isBlocked: Boolean = false,
    val customTitle: String? = null
)
