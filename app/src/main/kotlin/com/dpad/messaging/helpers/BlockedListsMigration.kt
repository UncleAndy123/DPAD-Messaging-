package com.dpad.messaging.helpers

import android.util.Log
import com.dpad.messaging.App
import com.dpad.messaging.models.BlockedKeyword
import com.dpad.messaging.models.BlockedNumber

/**
 * One-time migration helper to split numeric entries that were historically
 * stored in blocked_keywords into the new blocked_numbers table.
 *
 * Runs on startup; safe to call repeatedly (idempotent).
 */
object BlockedListsMigration {
    private const val TAG = "DPAD_MSG/BlockedListsMigration"

    suspend fun migrate() {
        try {
            val db = App.get().database
            val keywords: List<BlockedKeyword> = db.blockedKeywordsDao().getAll()
            if (keywords.isEmpty()) return

            // Identify entries that look like phone numbers (contain digits, not long words)
            val numericKeywords = keywords.filter { kw ->
                val digits = kw.keyword.filter { it.isDigit() }
                digits.length >= 3 && digits.length >= (kw.keyword.length / 2)
            }
            if (numericKeywords.isEmpty()) return

            Log.d(TAG, "migrating ${numericKeywords.size} numeric blocked keywords to blocked_numbers")

            for (kw in numericKeywords) {
                try {
                    // Insert into blocked_numbers (REPLACE strategy in DAO) and remove from keywords
                    db.blockedNumbersDao().insert(BlockedNumber(number = kw.keyword))
                    db.blockedKeywordsDao().delete(kw.id)
                } catch (e: Exception) {
                    Log.w(TAG, "failed to migrate kw='${kw.keyword}' id=${kw.id}", e)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "blocked lists migration failed", e)
        }
    }
}
