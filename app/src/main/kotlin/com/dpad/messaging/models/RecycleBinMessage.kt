package com.dpad.messaging.models

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

@Serializable
@Entity(tableName = "recycle_bin_messages")
data class RecycleBinMessage(
    /** Original Telephony message ID — used as PK so duplicate moves are idempotent. */
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: Long,

    @ColumnInfo(name = "address")
    val address: String = "",

    @ColumnInfo(name = "sender_name")
    val senderName: String = "",

    @ColumnInfo(name = "body")
    val body: String = "",

    @ColumnInfo(name = "date")
    val date: Long = 0L,

    @ColumnInfo(name = "deleted_ts")
    val deletedTs: Long = System.currentTimeMillis()
)
