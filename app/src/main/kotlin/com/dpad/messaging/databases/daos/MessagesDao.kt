package com.dpad.messaging.databases.daos

import androidx.room.*
import com.dpad.messaging.models.Message
import com.dpad.messaging.models.RecycleBinMessage
import kotlinx.coroutines.flow.Flow

@Dao
interface MessagesDao {

    @Query("SELECT * FROM messages WHERE thread_id = :threadId AND id NOT IN (SELECT id FROM recycle_bin_messages) ORDER BY date ASC")
    fun getMessagesForThreadFlow(threadId: Long): Flow<List<Message>>

    @Query("SELECT * FROM messages WHERE thread_id = :threadId AND id NOT IN (SELECT id FROM recycle_bin_messages) ORDER BY date ASC")
    suspend fun getMessagesForThread(threadId: Long): List<Message>

    @Query("SELECT * FROM messages WHERE thread_id = :threadId AND id NOT IN (SELECT id FROM recycle_bin_messages) ORDER BY date DESC LIMIT :limit")
    suspend fun getRecentMessages(threadId: Long, limit: Int = 30): List<Message>

    @Query("SELECT * FROM messages WHERE id = :id")
    suspend fun getMessage(id: Long): Message?

    @Query("SELECT * FROM messages WHERE body LIKE '%' || :query || '%' ORDER BY date DESC LIMIT 50")
    suspend fun searchMessages(query: String): List<Message>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMessage(message: Message)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMessages(messages: List<Message>)

    @Update
    suspend fun updateMessage(message: Message)

    @Query("DELETE FROM messages WHERE id = :id")
    suspend fun deleteMessage(id: Long)

    @Query("DELETE FROM messages WHERE thread_id = :threadId")
    suspend fun deleteMessagesForThread(threadId: Long)

    @Query("UPDATE messages SET read = 1 WHERE thread_id = :threadId")
    suspend fun markThreadRead(threadId: Long)

    // ─── Recycle Bin ───

    @Query("SELECT * FROM recycle_bin_messages")
    suspend fun getRecycleBinMessages(): List<RecycleBinMessage>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRecycleBinMessage(message: RecycleBinMessage)

    @Query("DELETE FROM recycle_bin_messages WHERE id = :id")
    suspend fun removeFromRecycleBin(id: Long)

    @Query("DELETE FROM recycle_bin_messages WHERE deleted_ts < :cutoff")
    suspend fun purgeExpiredRecycleBin(cutoff: Long)

    @Query("DELETE FROM recycle_bin_messages")
    suspend fun emptyRecycleBin()
}
