package com.dpad.messaging.databases.daos

import androidx.room.*
import com.dpad.messaging.models.BlockedNumber

@Dao
interface BlockedNumbersDao {

    @Query("SELECT * FROM blocked_numbers ORDER BY number ASC")
    suspend fun getAll(): List<BlockedNumber>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(number: BlockedNumber)

    @Query("DELETE FROM blocked_numbers WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM blocked_numbers")
    suspend fun deleteAll()
}
