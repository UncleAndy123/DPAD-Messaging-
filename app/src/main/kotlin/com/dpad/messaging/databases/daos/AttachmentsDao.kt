package com.dpad.messaging.databases.daos

import androidx.room.*
import com.dpad.messaging.models.Attachment

@Dao
interface AttachmentsDao {

    @Query("SELECT * FROM attachments WHERE message_id = :messageId")
    suspend fun getAttachmentsForMessage(messageId: Long): List<Attachment>

    @Query("SELECT * FROM attachments")
    suspend fun getAllAttachments(): List<Attachment>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAttachment(attachment: Attachment)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAttachments(attachments: List<Attachment>)

    @Query("DELETE FROM attachments WHERE message_id = :messageId")
    suspend fun deleteAttachmentsForMessage(messageId: Long)

    @Query("DELETE FROM attachments")
    suspend fun deleteAllAttachments()
}
