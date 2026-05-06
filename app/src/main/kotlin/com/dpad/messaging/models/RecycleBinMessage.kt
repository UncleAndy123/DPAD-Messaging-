package com.dpad.messaging.models

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "recycle_bin_messages")
data class RecycleBinMessage(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: Long,

    @ColumnInfo(name = "deleted_ts")
    val deletedTs: Long = System.currentTimeMillis()
)
