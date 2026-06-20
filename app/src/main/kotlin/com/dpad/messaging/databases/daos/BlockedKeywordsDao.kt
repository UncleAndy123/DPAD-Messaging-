package com.dpad.messaging.databases.daos

import androidx.room.*
import com.dpad.messaging.models.BlockedKeyword

@Dao
interface BlockedKeywordsDao {

    @Query("SELECT * FROM blocked_keywords ORDER BY keyword ASC")
    suspend fun getAll(): List<BlockedKeyword>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(keyword: BlockedKeyword)

    @Query("DELETE FROM blocked_keywords WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM blocked_keywords")
    suspend fun deleteAll()

    // Legacy convenience: check whether a normalized number exists in blocked keywords
    @Query("SELECT COUNT(*) FROM blocked_keywords WHERE keyword = :kw")
    suspend fun exists(kw: String): Int
}
