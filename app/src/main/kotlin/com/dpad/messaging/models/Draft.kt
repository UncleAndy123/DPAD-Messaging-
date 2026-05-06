package com.dpad.messaging.models

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "drafts")
data class Draft(
    @PrimaryKey
    @ColumnInfo(name = "thread_id")
    val threadId: Long,

    @ColumnInfo(name = "body")
    val body: String,

    @ColumnInfo(name = "date")
    val date: Long = System.currentTimeMillis()
)
