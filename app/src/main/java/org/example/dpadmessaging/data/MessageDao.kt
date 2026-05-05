package org.example.dpadmessaging.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

@Dao
interface MessageDao {
    @Insert
    suspend fun insert(msg: MessageEntity): Long

    @Query("SELECT * FROM messages WHERE threadId = :threadId ORDER BY timestamp DESC")
    suspend fun getForThread(threadId: Long): List<MessageEntity>

    @Query("SELECT * FROM messages ORDER BY timestamp DESC")
    suspend fun getAll(): List<MessageEntity>
}
