package com.dpad.messaging.data.repository

import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.provider.ContactsContract
import android.provider.Telephony
import com.dpad.messaging.data.db.ThreadMetadataDao
import com.dpad.messaging.data.model.MsgType
import com.dpad.messaging.data.model.SmsMessage
import com.dpad.messaging.data.model.SmsThread
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class SmsRepository(
    private val context: Context,
    private val metadataDao: ThreadMetadataDao
) {

    private val cr: ContentResolver get() = context.contentResolver

    // ── Threads ──────────────────────────────────────────────────────────────

    suspend fun getThreads(includeArchived: Boolean = false): List<SmsThread> = withContext(Dispatchers.IO) {
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
                    resolveContactName(addresses[0]) ?: addresses[0]
                else
                    addresses.mapNotNull { resolveContactName(it) ?: it }.joinToString(", ")

                // Count unread
                val unread = countUnread(threadId)

                // Get metadata
                val metadata = metadataDao.getMetadataSync(threadId)
                val isPinned = metadata?.isPinned ?: false
                val isArchived = metadata?.isArchived ?: false
                val isMuted = metadata?.isMuted ?: false
                val isBlocked = metadata?.isBlocked ?: false
                val customTitle = metadata?.customTitle

                val finalContactName = customTitle ?: baseContactName

                // Skip archived threads unless caller requested them explicitly
                if (!includeArchived && isArchived) continue

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

    // ── Messages in thread ────────────────────────────────────────────────────

    suspend fun getMessages(threadId: Long): List<SmsMessage> = withContext(Dispatchers.IO) {
        val messages = mutableListOf<SmsMessage>()

        // SMS
        val smsCursor: Cursor? = try {
            cr.query(
                Telephony.Sms.CONTENT_URI,
                arrayOf(Telephony.Sms._ID, Telephony.Sms.ADDRESS, Telephony.Sms.BODY,
                    Telephony.Sms.DATE, Telephony.Sms.TYPE),
                "${Telephony.Sms.THREAD_ID} = ?",
                arrayOf(threadId.toString()),
                "${Telephony.Sms.DATE} ASC"
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
                messages.add(SmsMessage(id, threadId, address, body, date, msgType, emptyList(), state))
            }
        }

        // MMS
        val mmsCursor: Cursor? = try {
            cr.query(
                Telephony.Mms.CONTENT_URI,
                arrayOf(Telephony.Mms._ID, Telephony.Mms.DATE, Telephony.Mms.MESSAGE_BOX),
                "${Telephony.Mms.THREAD_ID} = ?",
                arrayOf(threadId.toString()),
                "${Telephony.Mms.DATE} ASC"
            )
        } catch (e: Exception) { null }

        mmsCursor?.use { c ->
            while (c.moveToNext()) {
                val mmsId = c.getLong(c.getColumnIndexOrThrow(Telephony.Mms._ID))
                val date = c.getLong(c.getColumnIndexOrThrow(Telephony.Mms.DATE)) * 1000L
                val box = c.getInt(c.getColumnIndexOrThrow(Telephony.Mms.MESSAGE_BOX))
                val msgType = if (box == Telephony.Mms.MESSAGE_BOX_SENT) MsgType.MMS_OUT else MsgType.MMS_IN
                val address = getMmsAddress(mmsId)
                val (body, partUris) = getMmsParts(mmsId)
                // Provider messages should be marked SENT for outgoing types
                val state = if (msgType == com.dpad.messaging.data.model.MsgType.MMS_OUT) com.dpad.messaging.data.model.DeliveryState.SENT else com.dpad.messaging.data.model.DeliveryState.UNKNOWN
                messages.add(SmsMessage(mmsId, threadId, address, body, date, msgType, partUris, state))
            }
        }

        messages.sortedBy { it.date }
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
            cr.update(Telephony.Sms.CONTENT_URI, values,
                "${Telephony.Sms.THREAD_ID} = ? AND ${Telephony.Sms.READ} = 0",
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
        try {
            // Delete SMS rows
            cr.delete(Telephony.Sms.CONTENT_URI, "${Telephony.Sms.THREAD_ID} = ?", arrayOf(threadId.toString()))
        } catch (_: Exception) {}
        try {
            // Delete MMS rows (parts and headers)
            cr.delete(Telephony.Mms.CONTENT_URI, "${Telephony.Mms.THREAD_ID} = ?", arrayOf(threadId.toString()))
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

    private fun countUnread(threadId: Long): Int {
        val cursor = try {
            cr.query(
                Telephony.Sms.CONTENT_URI,
                arrayOf("COUNT(*)"),
                "${Telephony.Sms.THREAD_ID} = ? AND ${Telephony.Sms.READ} = 0",
                arrayOf(threadId.toString()), null
            )
        } catch (e: Exception) { null }
        return cursor?.use { c -> if (c.moveToFirst()) c.getInt(0) else 0 } ?: 0
    }

    private fun getMmsAddress(mmsId: Long): String {
        val cursor = try {
            cr.query(
                Uri.parse("content://mms/$mmsId/addr"),
                arrayOf("address", "type"), null, null, null
            )
        } catch (e: Exception) { null }
        return cursor?.use { c ->
            var result = ""
            while (c.moveToNext()) {
                val addr = c.getString(0) ?: continue
                if (addr != "insert-address-token") { result = addr; break }
            }
            result
        } ?: ""
    }

    private fun getMmsParts(mmsId: Long): Pair<String, List<String>> {
        var text = ""
        val uris = mutableListOf<String>()
        val cursor = try {
            cr.query(
                Uri.parse("content://mms/part"),
                arrayOf("_id", "ct", "_data", "text"),
                "mid = ?", arrayOf(mmsId.toString()), null
            )
        } catch (e: Exception) { null }
        cursor?.use { c ->
            while (c.moveToNext()) {
                val partId = c.getLong(0)
                val ct = c.getString(1) ?: ""
                when {
                    ct == "text/plain" -> text = c.getString(3) ?: ""
                    ct.startsWith("image") || ct.startsWith("video") ->
                        uris.add("content://mms/part/$partId")
                }
            }
        }
        return text to uris
    }
}
