package com.dpad.messaging.data.repository

import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.provider.ContactsContract
import android.provider.Telephony
import android.util.Log
import java.io.File
import com.dpad.messaging.data.db.ThreadMetadataDao
import com.dpad.messaging.data.model.MsgType
import com.dpad.messaging.data.model.SmsMessage
import com.dpad.messaging.data.model.MmsPart
import com.dpad.messaging.data.model.SmsThread
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class SmsRepository(
    private val context: Context,
    private val metadataDao: ThreadMetadataDao
) {

    private val cr: ContentResolver get() = context.contentResolver

    // ── Threads ──────────────────────────────────────────────────────────────

    /**
     * @param archivedMode  false = inbox (skip archived), true = archive view (only archived)
     */
    suspend fun getThreads(archivedMode: Boolean = false): List<SmsThread> = withContext(Dispatchers.IO) {
        val threads = mutableListOf<SmsThread>()
        val uri = Telephony.Threads.CONTENT_URI.buildUpon()
            .appendQueryParameter("simple", "true").build()
        val projection = arrayOf(
            Telephony.Threads._ID,
            Telephony.Threads.DATE,
            Telephony.Threads.SNIPPET,
            Telephony.Threads.READ,
            Telephony.Threads.MESSAGE_COUNT,
            Telephony.Threads.RECIPIENT_IDS
        )
        val cursor: Cursor? = try {
            cr.query(uri, projection, null, null, "${Telephony.Threads.DATE} DESC")
        } catch (e: Exception) { null }

        // Batch fetch all unread counts in one query (GROUP BY thread_id)
        val unreadByThread = fetchAllUnreadCounts()

        // In-memory contact name cache for this call — avoids N repeated PhoneLookup queries
        val contactCache = mutableMapOf<String, String?>()

        cursor?.use { c ->
            while (c.moveToNext()) {
                val threadId = c.getLong(c.getColumnIndexOrThrow(Telephony.Threads._ID))
                val date = c.getLong(c.getColumnIndexOrThrow(Telephony.Threads.DATE))
                val snippet = c.getString(c.getColumnIndexOrThrow(Telephony.Threads.SNIPPET)) ?: ""
                val recipientIds = c.getString(c.getColumnIndexOrThrow(Telephony.Threads.RECIPIENT_IDS)) ?: ""

                // Resolve address from recipient ids
                val addresses = resolveAddresses(recipientIds)
                val address = addresses.joinToString(", ")
                val baseContactName = if (addresses.size == 1)
                    contactCache.getOrPut(addresses[0]) { resolveContactName(addresses[0]) } ?: addresses[0]
                else
                    addresses.joinToString(", ") { addr ->
                        contactCache.getOrPut(addr) { resolveContactName(addr) } ?: addr
                    }

                val unread = unreadByThread[threadId] ?: 0

                // Get metadata
                val metadata = metadataDao.getMetadataSync(threadId)
                val isPinned = metadata?.isPinned ?: false
                val isArchived = metadata?.isArchived ?: false
                val isMuted = metadata?.isMuted ?: false
                val isBlocked = metadata?.isBlocked ?: false
                val customTitle = metadata?.customTitle

                val finalContactName = customTitle ?: baseContactName

                // Inbox mode: skip archived. Archive mode: skip non-archived.
                if (!archivedMode && isArchived) continue
                if (archivedMode && !isArchived) continue

                threads.add(
                    SmsThread(
                        threadId = threadId,
                        address = address,
                        contactName = finalContactName,
                        snippet = snippet,
                        date = date,
                        unreadCount = unread,
                        isGroup = addresses.size > 1,
                        isPinned = isPinned,
                        isArchived = isArchived,
                        isMuted = isMuted,
                        isBlocked = isBlocked,
                        customTitle = customTitle
                    )
                )
            }
        }

        // Sort: Pinned first, then by date
        threads.sortedWith(compareByDescending<SmsThread> { it.isPinned }.thenByDescending { it.date })
    }

    /** Fetch unread counts for all threads — SMS + MMS combined (BUG 10 fix). */
    private fun fetchAllUnreadCounts(): Map<Long, Int> {
        val map = mutableMapOf<Long, Int>()
        // SMS unread
        countUnreadByThread(Telephony.Sms.CONTENT_URI, Telephony.Sms.THREAD_ID, Telephony.Sms.READ)
            .forEach { (tid, cnt) -> map[tid] = (map[tid] ?: 0) + cnt }
        // MMS unread (BUG 10)
        countUnreadByThread(Telephony.Mms.CONTENT_URI, Telephony.Mms.THREAD_ID, Telephony.Mms.READ)
            .forEach { (tid, cnt) -> map[tid] = (map[tid] ?: 0) + cnt }
        return map
    }

    private fun countUnreadByThread(uri: Uri, threadIdCol: String, readCol: String): Map<Long, Int> {
        val map = mutableMapOf<Long, Int>()
        // R6 fix: COUNT(*) column alias in projection is unreliable across OEM providers.
        // Fetch all unread rows and count in-memory instead.
        val cursor = try {
            cr.query(
                uri,
                arrayOf(threadIdCol),
                "$readCol = 0",
                null,
                null
            )
        } catch (e: Exception) { null }
        cursor?.use { c ->
            val tidIdx = c.getColumnIndex(threadIdCol)
            if (tidIdx < 0) return@use
            while (c.moveToNext()) {
                val tid = c.getLong(tidIdx)
                map[tid] = (map[tid] ?: 0) + 1
            }
        }
        return map
    }

    // ── Messages in thread ────────────────────────────────────────────────────

    /**
     * Load messages for a thread.
     *
     * @param threadId  The thread to load from.
     * @param limit     Max number of most-recent combined messages to return (default 50).
     *                  Pass [Int.MAX_VALUE] to load all.
     */
    suspend fun getMessages(threadId: Long, limit: Int = 50): List<SmsMessage> = withContext(Dispatchers.IO) {
        val messages = mutableListOf<SmsMessage>()

        // SMS — fetch all rows for this thread; we'll trim to `limit` after combining with MMS
        val smsCursor: Cursor? = try {
            cr.query(
                Telephony.Sms.CONTENT_URI,
                arrayOf(Telephony.Sms._ID, Telephony.Sms.ADDRESS, Telephony.Sms.BODY,
                    Telephony.Sms.DATE, Telephony.Sms.TYPE),
                "${Telephony.Sms.THREAD_ID} = ?",
                arrayOf(threadId.toString()),
                "${Telephony.Sms.DATE} DESC"  // DESC so we can early-exit after `limit` rows
            )
        } catch (e: Exception) { null }

        smsCursor?.use { c ->
            while (c.moveToNext()) {
                val id = c.getLong(c.getColumnIndexOrThrow(Telephony.Sms._ID))
                val address = c.getString(c.getColumnIndexOrThrow(Telephony.Sms.ADDRESS)) ?: ""
                val body = c.getString(c.getColumnIndexOrThrow(Telephony.Sms.BODY)) ?: ""
                val date = c.getLong(c.getColumnIndexOrThrow(Telephony.Sms.DATE))
                val type = c.getInt(c.getColumnIndexOrThrow(Telephony.Sms.TYPE))
                val msgType = if (type == Telephony.Sms.MESSAGE_TYPE_SENT) MsgType.SMS_OUT else MsgType.SMS_IN
                val state = if (msgType == com.dpad.messaging.data.model.MsgType.SMS_OUT) com.dpad.messaging.data.model.DeliveryState.SENT else com.dpad.messaging.data.model.DeliveryState.UNKNOWN
                // No MMS parts for SMS messages
                messages.add(SmsMessage(id, threadId, address, body, date, msgType, emptyList(), emptyList(), state))
                // Stop reading SMS once we already have enough candidates
                if (limit != Int.MAX_VALUE && messages.size >= limit * 2) break
            }
        }

        // MMS
        val mmsCursor: Cursor? = try {
            cr.query(
                Telephony.Mms.CONTENT_URI,
                arrayOf(Telephony.Mms._ID, Telephony.Mms.DATE, Telephony.Mms.MESSAGE_BOX),
                "${Telephony.Mms.THREAD_ID} = ?",
                arrayOf(threadId.toString()),
                "${Telephony.Mms.DATE} DESC"
            )
        } catch (e: Exception) { null }

        Log.e("SmsRepo", "getMessages tid=$threadId mmsCursor=${mmsCursor?.count}")
        mmsCursor?.use { c ->
            while (c.moveToNext()) {
                val mmsId = c.getLong(c.getColumnIndexOrThrow(Telephony.Mms._ID))
                val date = c.getLong(c.getColumnIndexOrThrow(Telephony.Mms.DATE)) * 1000L
                val box = c.getInt(c.getColumnIndexOrThrow(Telephony.Mms.MESSAGE_BOX))
                // BUG 5 fix: outbox (4) and failed (5) are also outgoing states
                val msgType = when (box) {
                    Telephony.Mms.MESSAGE_BOX_SENT,
                    Telephony.Mms.MESSAGE_BOX_OUTBOX,
                    Telephony.Mms.MESSAGE_BOX_FAILED -> MsgType.MMS_OUT
                    else -> MsgType.MMS_IN
                }
                val address = getMmsAddress(mmsId)
                val (body, parts) = getMmsParts(mmsId)
                // For backward compatibility with existing UI we still expose part URI strings
                val partUris = parts.map { it.uri }
                Log.e("SmsRepo", "  mmsId=$mmsId box=$box msgType=$msgType body='$body' partUris=$partUris parts=${parts.map { it.id }}")
                // Provider messages should be marked SENT for outgoing types
                val state = if (msgType == com.dpad.messaging.data.model.MsgType.MMS_OUT) com.dpad.messaging.data.model.DeliveryState.SENT else com.dpad.messaging.data.model.DeliveryState.UNKNOWN
                messages.add(SmsMessage(mmsId, threadId, address, body, date, msgType, partUris, parts, state))
            }
        }

        // Sort DESC, take the `limit` most-recent, then re-sort ASC for display
        val sorted = messages.sortedByDescending { it.date }
        val trimmed = if (limit == Int.MAX_VALUE) sorted else sorted.take(limit)
        trimmed.sortedBy { it.date }
    }

    /** Read the thread_id for a given inserted SMS Uri (e.g. content://sms/2). */
    suspend fun threadIdForUri(uri: Uri): Long? = withContext(Dispatchers.IO) {
        try {
            val cursor = cr.query(uri, arrayOf(Telephony.Sms.THREAD_ID), null, null, null)
            cursor?.use { c -> if (c.moveToFirst()) c.getLong(0) else null }
        } catch (_: Exception) { null }
    }

    suspend fun markThreadRead(threadId: Long) = withContext(Dispatchers.IO) {
        val values = ContentValues().apply { put(Telephony.Sms.READ, 1) }
        try {
            // BUG 11 fix: mark both SMS and MMS as read
            cr.update(Telephony.Sms.CONTENT_URI, values,
                "${Telephony.Sms.THREAD_ID} = ? AND ${Telephony.Sms.READ} = 0",
                arrayOf(threadId.toString()))
            cr.update(Telephony.Mms.CONTENT_URI, values,
                "${Telephony.Mms.THREAD_ID} = ? AND ${Telephony.Mms.READ} = 0",
                arrayOf(threadId.toString()))
        } catch (_: Exception) {}
    }

    suspend fun togglePin(threadId: Long, isPinned: Boolean) = withContext(Dispatchers.IO) {
        val current = metadataDao.getMetadataSync(threadId)
        if (current == null) {
            metadataDao.insertOrUpdate(com.dpad.messaging.data.db.ThreadMetadata(threadId = threadId, isPinned = isPinned))
        } else {
            metadataDao.updatePinned(threadId, isPinned)
        }
    }

    suspend fun toggleArchive(threadId: Long, isArchived: Boolean) = withContext(Dispatchers.IO) {
        val current = metadataDao.getMetadataSync(threadId)
        if (current == null) {
            metadataDao.insertOrUpdate(com.dpad.messaging.data.db.ThreadMetadata(threadId = threadId, isArchived = isArchived))
        } else {
            metadataDao.updateArchived(threadId, isArchived)
        }
    }

    suspend fun toggleMute(threadId: Long, isMuted: Boolean) = withContext(Dispatchers.IO) {
        val current = metadataDao.getMetadataSync(threadId)
        if (current == null) {
            metadataDao.insertOrUpdate(com.dpad.messaging.data.db.ThreadMetadata(threadId = threadId, isMuted = isMuted))
        } else {
            metadataDao.updateMuted(threadId, isMuted)
        }
    }

    suspend fun toggleBlock(threadId: Long, isBlocked: Boolean) = withContext(Dispatchers.IO) {
        val current = metadataDao.getMetadataSync(threadId)
        if (current == null) {
            metadataDao.insertOrUpdate(com.dpad.messaging.data.db.ThreadMetadata(threadId = threadId, isBlocked = isBlocked))
        } else {
            metadataDao.updateBlocked(threadId, isBlocked)
        }
    }

    suspend fun deleteThread(threadId: Long) = withContext(Dispatchers.IO) {
        if (threadId <= 0) return@withContext

        try {
            // Use the threads URI to delete an entire conversation. This is the
            // provider-backed way to remove all messages for a thread and is
            // safer than issuing deletes against the global SMS/MMS tables which
            // may behave inconsistently on some devices/providers.
            val threadUri = Uri.withAppendedPath(Telephony.Threads.CONTENT_URI, threadId.toString())
            cr.delete(threadUri, null, null)
        } catch (_: Exception) {}

        // Remove any metadata
        try {
            metadataDao.deleteMetadata(threadId)
        } catch (_: Exception) {}
    }

    suspend fun deleteMessage(msg: com.dpad.messaging.data.model.SmsMessage) = withContext(Dispatchers.IO) {
        try {
            when (msg.type) {
                com.dpad.messaging.data.model.MsgType.SMS_IN, com.dpad.messaging.data.model.MsgType.SMS_OUT ->
                    cr.delete(Telephony.Sms.CONTENT_URI, "${Telephony.Sms._ID} = ?", arrayOf(msg.id.toString()))
                com.dpad.messaging.data.model.MsgType.MMS_IN, com.dpad.messaging.data.model.MsgType.MMS_OUT ->
                    cr.delete(Telephony.Mms.CONTENT_URI, "${Telephony.Mms._ID} = ?", arrayOf(msg.id.toString()))
            }
        } catch (_: Exception) {}
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun resolveAddresses(recipientIds: String): List<String> {
        if (recipientIds.isBlank()) return emptyList()
        return recipientIds.trim().split(" ").mapNotNull { rid ->
            if (rid.isBlank()) return@mapNotNull null
            val cursor = try {
                cr.query(
                    Uri.parse("content://mms-sms/canonical-address/$rid"),
                    null, null, null, null
                )
            } catch (e: Exception) { null }
            cursor?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        }
    }

    private fun resolveContactName(address: String): String? {
        if (address.isBlank()) return null
        val uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI,
            Uri.encode(address))
        val cursor = try {
            cr.query(uri, arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME), null, null, null)
        } catch (e: Exception) { null }
        return cursor?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }

    private fun getMmsAddress(mmsId: Long): String {
        // BUG 6 fix: filter by address type — FROM (0x89) is the sender.
        // Iterating all rows without type check returns the first TO recipient
        // for group MMS, showing the wrong sender.
        val cursor = try {
            cr.query(
                Uri.parse("content://mms/$mmsId/addr"),
                arrayOf("address", "type"), null, null, null
            )
        } catch (e: Exception) { null }
        return cursor?.use { c ->
            var fromAddr = ""
            var firstNonToken = ""
            while (c.moveToNext()) {
                val addr = c.getString(0) ?: continue
                if (addr == "insert-address-token") continue
                if (firstNonToken.isEmpty()) firstNonToken = addr
                if (c.getInt(1) == 0x89) { // PduHeaders.FROM
                    fromAddr = addr
                    break
                }
            }
            fromAddr.ifEmpty { firstNonToken }
        } ?: ""
    }

    /**
     * Read parts for an MMS and return the text snippet plus a list of MmsPart descriptors.
     */
    private fun getMmsParts(mmsId: Long): Pair<String, List<MmsPart>> {
        var text = ""
        val parts = mutableListOf<MmsPart>()
        // Use the official Mms.Part.CONTENT_URI with MSG_ID selection. Request a few
        // useful columns including inline text, possible filename and _data marker.
        val partUri = Telephony.Mms.Part.CONTENT_URI
        Log.e("SmsRepo", "getMmsParts mmsId=$mmsId partUri=$partUri")
        val projection = arrayOf(
            Telephony.Mms.Part._ID,
            Telephony.Mms.Part.CONTENT_TYPE,
            Telephony.Mms.Part.TEXT,
            "name",
            "_data"
        )
        val cursor = try {
            cr.query(
                partUri,
                projection,
                "${Telephony.Mms.Part.MSG_ID} = ?",
                arrayOf(mmsId.toString()),
                null
            )
        } catch (e: Exception) {
            Log.e("SmsRepo", "getMmsParts query failed for mmsId=$mmsId", e)
            null
        }
        Log.e("SmsRepo", "getMmsParts mmsId=$mmsId cursor count=${cursor?.count}")
        cursor?.use { c ->
            while (c.moveToNext()) {
                val partId = c.getLong(c.getColumnIndexOrThrow(Telephony.Mms.Part._ID))
                val ct = c.getString(c.getColumnIndexOrThrow(Telephony.Mms.Part.CONTENT_TYPE)) ?: ""
                val inlineText = try { c.getString(c.getColumnIndexOrThrow(Telephony.Mms.Part.TEXT)) } catch (_: Exception) { null }
                val name = try { c.getString(c.getColumnIndexOrThrow("name")) } catch (_: Exception) { null }
                val dataCol = try { c.getString(c.getColumnIndexOrThrow("_data")) } catch (_: Exception) { null }
                val hasData = !dataCol.isNullOrBlank() || !inlineText.isNullOrBlank()

                Log.e("SmsRepo", "  part id=$partId ct=$ct name=$name hasData=$hasData")

                when {
                    ct == "text/plain" -> {
                        text = if (!inlineText.isNullOrEmpty()) {
                            inlineText
                        } else {
                            // Read from content stream via the part URI
                            val uri = Uri.withAppendedPath(partUri, partId.toString())
                            try {
                                cr.openInputStream(uri)?.use { it.bufferedReader().readText() } ?: ""
                            } catch (e: Exception) {
                                Log.e("SmsRepo", "Failed to read text part stream for partId=$partId", e)
                                ""
                            }
                        }
                    }
                    ct.startsWith("image") || ct.startsWith("video") -> {
                        // If the provider exposed an internal file path in _data, prefer a file:// URI
                        // when the file exists and is readable. This mirrors behavior in other
                        // messaging apps and helps image loaders that can read file URIs directly.
                        var uriStr = Uri.withAppendedPath(partUri, partId.toString()).toString()
                        if (!dataCol.isNullOrBlank()) {
                            try {
                                val f = File(dataCol)
                                if (f.exists() && f.canRead()) {
                                    uriStr = Uri.fromFile(f).toString()
                                    Log.e("SmsRepo", "  part id=$partId using _data file path=$dataCol")
                                } else {
                                    Log.e("SmsRepo", "  part id=$partId _data present but file not readable: $dataCol")
                                }
                            } catch (e: Exception) {
                                Log.e("SmsRepo", "  part id=$partId failed to use _data path $dataCol", e)
                            }
                        }
                        parts.add(MmsPart(partId, uriStr, ct, name, hasData))
                    }
                    // application/smil and other types ignored here (SMIL handled elsewhere)
                }
            }
        }
        Log.e("SmsRepo", "getMmsParts mmsId=$mmsId -> text='$text' parts=${parts.map { it.uri }}")
        return text to parts
    }
}
