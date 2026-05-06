package com.dpad.messaging.databases.daos

import androidx.room.*
import com.dpad.messaging.models.Draft

@Dao
interface DraftsDao {

    @Query("SELECT * FROM drafts WHERE thread_id = :threadId")
    suspend fun getDraft(threadId: Long): Draft?

    @Query("SELECT * FROM drafts")
    suspend fun getAllDrafts(): List<Draft>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertDraft(draft: Draft)

    @Query("DELETE FROM drafts WHERE thread_id = :threadId")
    suspend fun deleteDraft(threadId: Long)

    @Query("DELETE FROM drafts")
    suspend fun deleteAllDrafts()
}
