package com.dpad.messaging.databases.daos

import androidx.room.*
import com.dpad.messaging.models.Conversation
import kotlinx.coroutines.flow.Flow

@Dao
interface ConversationsDao {

    @Query("SELECT * FROM conversations WHERE archived = 0 ORDER BY pinned DESC, date DESC")
    fun getConversationsFlow(): Flow<List<Conversation>>

    @Query("SELECT * FROM conversations WHERE archived = 0 ORDER BY pinned DESC, date DESC")
    suspend fun getConversations(): List<Conversation>

    @Query("SELECT * FROM conversations WHERE archived = 1 ORDER BY date DESC")
    suspend fun getArchivedConversations(): List<Conversation>

    @Query("SELECT * FROM conversations WHERE thread_id = :threadId")
    suspend fun getConversation(threadId: Long): Conversation?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertConversation(conversation: Conversation)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertConversations(conversations: List<Conversation>)

    @Update
    suspend fun updateConversation(conversation: Conversation)

    @Query("DELETE FROM conversations WHERE thread_id = :threadId")
    suspend fun deleteConversation(threadId: Long)

    @Query("DELETE FROM conversations")
    suspend fun deleteAllConversations()

    @Query("UPDATE conversations SET read = 1, unread_count = 0 WHERE thread_id = :threadId")
    suspend fun markAsRead(threadId: Long)

    @Query("UPDATE conversations SET archived = :archived WHERE thread_id = :threadId")
    suspend fun setArchived(threadId: Long, archived: Boolean)

    @Query("UPDATE conversations SET pinned = :pinned WHERE thread_id = :threadId")
    suspend fun setPinned(threadId: Long, pinned: Boolean)

    @Query("UPDATE conversations SET title = :title, uses_custom_title = 1 WHERE thread_id = :threadId")
    suspend fun setCustomTitle(threadId: Long, title: String)

    @Query("SELECT COUNT(*) FROM conversations WHERE archived = 0 AND read = 0")
    fun getUnreadCount(): Flow<Int>
}
