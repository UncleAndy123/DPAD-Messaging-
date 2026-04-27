package com.dpad.messaging.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface ThreadMetadataDao {
    @Query("SELECT * FROM thread_metadata")
    fun getAllMetadata(): Flow<List<ThreadMetadata>>

    @Query("SELECT * FROM thread_metadata WHERE threadId = :threadId")
    suspend fun getMetadataSync(threadId: Long): ThreadMetadata?

    @Query("SELECT * FROM thread_metadata WHERE threadId = :threadId")
    fun getMetadata(threadId: Long): Flow<ThreadMetadata?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdate(metadata: ThreadMetadata)

    @Query("UPDATE thread_metadata SET isPinned = :isPinned WHERE threadId = :threadId")
    suspend fun updatePinned(threadId: Long, isPinned: Boolean)

    @Query("UPDATE thread_metadata SET isArchived = :isArchived WHERE threadId = :threadId")
    suspend fun updateArchived(threadId: Long, isArchived: Boolean)

    @Query("UPDATE thread_metadata SET isMuted = :isMuted WHERE threadId = :threadId")
    suspend fun updateMuted(threadId: Long, isMuted: Boolean)

    @Query("UPDATE thread_metadata SET isBlocked = :isBlocked WHERE threadId = :threadId")
    suspend fun updateBlocked(threadId: Long, isBlocked: Boolean)

    @Query("DELETE FROM thread_metadata WHERE threadId = :threadId")
    suspend fun deleteMetadata(threadId: Long)
}
