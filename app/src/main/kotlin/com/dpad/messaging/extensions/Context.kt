package com.dpad.messaging.extensions

import android.annotation.SuppressLint
import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.Telephony
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import com.dpad.messaging.helpers.ContactHelper
import com.dpad.messaging.helpers.MmsHelper
import com.dpad.messaging.models.Conversation
import com.dpad.messaging.models.Message

// ─────────────────────────────────────────────────────────────────────────────
// Telephony ContentProvider read helpers
// All functions are synchronous — call from a background coroutine.
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Returns the set of own phone numbers (digits only, no +/- formatting) for all
 * active SIM subscriptions.  Used to filter the device's own number out of MMS
 * participant lists.  Requires READ_PHONE_STATE permission; returns empty set if
 * permission is missing or no number is available.
 */
@SuppressLint("MissingPermission")
fun Context.getOwnPhoneNumbers(): Set<String> {
    val numbers = mutableSetOf<String>()
    try {
        val subMgr = getSystemService(SubscriptionManager::class.java)
        val subs   = subMgr?.activeSubscriptionInfoList ?: emptyList()
        if (subs.isNotEmpty()) {
            val tm = getSystemService(TelephonyManager::class.java)
            for (sub in subs) {
                val num = tm?.createForSubscriptionId(sub.subscriptionId)
                    ?.line1Number?.filter { it.isDigit() }
                if (!num.isNullOrBlank()) numbers.add(num)
                // Also store last-10-digit form for matching canonical addresses
                if (num != null && num.length > 10) numbers.add(num.takeLast(10))
            }
        } else {
            // Single-SIM fallback
            @Suppress("DEPRECATION")
            val num = (getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager)
                ?.line1Number?.filter { it.isDigit() }
            if (!num.isNullOrBlank()) {
                numbers.add(num)
                if (num.length > 10) numbers.add(num.takeLast(10))
            }
        }
    } catch (_: Exception) {}
    return numbers
}

/**
 * Reads all non-archived conversations from the system Telephony provider,
 * resolves contact names, and returns them sorted pinned-first then date-desc.
 *
 * @param pinnedThreadIds Set of thread IDs that the user has pinned.
 */
fun Context.getConversationsFromTelephony(
    contactHelper: ContactHelper,
    pinnedThreadIds: Set<Long> = emptySet()
): List<Conversation> {
    val uri = Uri.parse("content://mms-sms/conversations?simple=true")
    val projection = arrayOf(
        Telephony.Threads._ID,
        Telephony.Threads.RECIPIENT_IDS,
        Telephony.Threads.SNIPPET,
        Telephony.Threads.DATE,
        Telephony.Threads.READ,
        Telephony.Threads.MESSAGE_COUNT
    )

    val conversations = mutableListOf<Conversation>()
    val ownNumbers = getOwnPhoneNumbers()

    try {
        contentResolver.query(uri, projection, null, null, "${Telephony.Threads.DATE} DESC")
            ?.use { cursor ->
                val idxId = cursor.getColumnIndex(Telephony.Threads._ID)
                val idxRecipients = cursor.getColumnIndex(Telephony.Threads.RECIPIENT_IDS)
                val idxSnippet = cursor.getColumnIndex(Telephony.Threads.SNIPPET)
                val idxDate = cursor.getColumnIndex(Telephony.Threads.DATE)
                val idxRead = cursor.getColumnIndex(Telephony.Threads.READ)

                while (cursor.moveToNext()) {
                    val threadId = cursor.getLong(idxId)
                    val recipientIds = cursor.getString(idxRecipients) ?: continue
                    val snippet = cursor.getString(idxSnippet) ?: ""
                    val date = cursor.getLong(idxDate)
                    val read = cursor.getInt(idxRead) == 1

                    // Filter out own numbers so group participant lists and titles
                    // don't include the device's own phone number.
                    val allNumbers = resolveRecipientIds(recipientIds)
                    val phoneNumbers = allNumbers.filter { num ->
                        val digits = num.filter { it.isDigit() }
                        digits !in ownNumbers && digits.takeLast(10) !in ownNumbers
                    }.ifEmpty { allNumbers }  // fallback: keep all if filter removes everything
                    if (phoneNumbers.isEmpty()) continue

                    val isGroup = phoneNumbers.size > 1
                    val primaryPhone = phoneNumbers.first()
                    val contactInfo = contactHelper.resolve(primaryPhone)
                    val title = when {
                        isGroup -> phoneNumbers.joinToString(", ") {
                            contactHelper.getDisplayName(it)
                        }
                        contactInfo != null -> contactInfo.displayName
                        else -> primaryPhone
                    }

                    conversations.add(
                        Conversation(
                            threadId = threadId,
                            phoneNumber = primaryPhone,
                            title = title,
                            photoUri = contactInfo?.photoUri ?: "",
                            snippet = snippet,
                            date = date,
                            read = read,
                            isGroupConversation = isGroup,
                            pinned = threadId in pinnedThreadIds,
                            participants = phoneNumbers.joinToString(",")
                        )
                    )
                }
            }
    } catch (e: Exception) {
        e.printStackTrace()
    }

    return conversations.sortedWith(
        compareByDescending<Conversation> { it.pinned }.thenByDescending { it.date }
    )
}

/**
 * Resolves a space-separated string of canonical address IDs to phone numbers.
 * e.g. "3 7" → ["+15551234567", "+15559876543"]
 */
private fun Context.resolveRecipientIds(recipientIds: String): List<String> {
    return recipientIds.trim().split(" ").mapNotNull { idStr ->
        val id = idStr.trim().toLongOrNull() ?: return@mapNotNull null
        val uri = Uri.parse("content://mms-sms/canonical-address/$id")
        try {
            contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
        } catch (e: Exception) {
            null
        }
    }.filter { it.isNotBlank() }
}

/**
 * Reads all SMS messages for a given thread from the Telephony provider,
 * sorted oldest-first (for display in a chat thread).
 */
fun Context.getMessagesForThread(
    threadId: Long,
    contactHelper: ContactHelper,
    limit: Int = 200
): List<Message> {
    val messages = mutableListOf<Message>()

    // ── SMS ──────────────────────────────────────────────────────────────────
    val smsUri = Telephony.Sms.CONTENT_URI
    val smsProjection = arrayOf(
        Telephony.Sms._ID,
        Telephony.Sms.BODY,
        Telephony.Sms.DATE,
        Telephony.Sms.DATE_SENT,
        Telephony.Sms.TYPE,
        Telephony.Sms.READ,
        Telephony.Sms.ADDRESS,
        Telephony.Sms.STATUS,
        Telephony.Sms.SUBSCRIPTION_ID
    )

    try {
        contentResolver.query(
            smsUri,
            smsProjection,
            "${Telephony.Sms.THREAD_ID} = ?",
            arrayOf(threadId.toString()),
            "${Telephony.Sms.DATE} DESC LIMIT $limit"
        )?.use { cursor ->
            val idxId = cursor.getColumnIndex(Telephony.Sms._ID)
            val idxBody = cursor.getColumnIndex(Telephony.Sms.BODY)
            val idxDate = cursor.getColumnIndex(Telephony.Sms.DATE)
            val idxDateSent = cursor.getColumnIndex(Telephony.Sms.DATE_SENT)
            val idxType = cursor.getColumnIndex(Telephony.Sms.TYPE)
            val idxRead = cursor.getColumnIndex(Telephony.Sms.READ)
            val idxAddress = cursor.getColumnIndex(Telephony.Sms.ADDRESS)
            val idxStatus = cursor.getColumnIndex(Telephony.Sms.STATUS)
            val idxSubId = cursor.getColumnIndex(Telephony.Sms.SUBSCRIPTION_ID)

            while (cursor.moveToNext()) {
                val address = cursor.getString(idxAddress) ?: ""
                val type = cursor.getInt(idxType)
                val contactInfo = if (type == Message.TYPE_INBOX)
                    contactHelper.resolve(address) else null

                messages.add(
                    Message(
                        id = cursor.getLong(idxId),
                        threadId = threadId,
                        body = cursor.getString(idxBody) ?: "",
                        type = type,
                        date = cursor.getLong(idxDate),
                        dateSent = cursor.getLong(idxDateSent),
                        read = cursor.getInt(idxRead) == 1,
                        address = address,
                        senderName = contactInfo?.displayName ?: address,
                        senderPhotoUri = contactInfo?.photoUri ?: "",
                        isMms = false,
                        status = cursor.getInt(idxStatus),
                        subscriptionId = cursor.getInt(idxSubId)
                    )
                )
            }
        }
    } catch (e: Exception) {
        e.printStackTrace()
    }

    // ── MMS ──────────────────────────────────────────────────────────────────
    val mmsUri = Uri.parse("content://mms")
    val mmsProjection = arrayOf("_id", "date", "msg_box", "read", "sub")
    try {
        contentResolver.query(
            mmsUri,
            mmsProjection,
            "thread_id = ?",
            arrayOf(threadId.toString()),
            "date DESC LIMIT $limit"
        )?.use { cursor ->
            val idxId     = cursor.getColumnIndexOrThrow("_id")
            val idxDate   = cursor.getColumnIndexOrThrow("date")
            val idxMsgBox = cursor.getColumnIndexOrThrow("msg_box")
            val idxRead   = cursor.getColumnIndexOrThrow("read")
            val idxSub    = cursor.getColumnIndexOrThrow("sub")

            while (cursor.moveToNext()) {
                val id      = cursor.getLong(idxId)
                val date    = cursor.getLong(idxDate) * 1000L   // MMS date is in seconds
                val msgBox  = cursor.getInt(idxMsgBox)
                val read    = cursor.getInt(idxRead) == 1
                val subject = cursor.getString(idxSub) ?: ""

                // MMS msg_box: 1=inbox, 2=sent, 4=outbox, 5=failed
                val type = when (msgBox) {
                    1    -> Message.TYPE_INBOX
                    2    -> Message.TYPE_SENT
                    4    -> Message.TYPE_OUTBOX
                    5    -> Message.TYPE_FAILED
                    else -> Message.TYPE_INBOX
                }

                // Derive sender address from MMS addr table
                val address     = getMmsAddress(id, msgBox)
                val contactInfo = if (type == Message.TYPE_INBOX)
                    contactHelper.resolve(address) else null

                // Real text body from text/plain part; fallback to subject or "MMS"
                val body = MmsHelper.getMmsDisplayBody(this, id, subject)

                // Store first image part URI in attachmentsJson for ThreadAdapter to load
                val attachmentsJson = MmsHelper.getMmsImagePartUri(this, id) ?: "[]"

                messages.add(
                    Message(
                        id             = id,
                        threadId       = threadId,
                        body           = body,
                        type           = type,
                        date           = date,
                        read           = read,
                        address        = address,
                        senderName     = contactInfo?.displayName ?: address,
                        senderPhotoUri = contactInfo?.photoUri ?: "",
                        isMms          = true,
                        attachmentsJson = attachmentsJson,
                        status         = Message.STATUS_NONE
                    )
                )
            }
        }
    } catch (e: Exception) {
        e.printStackTrace()
    }

    // Sort all (SMS + MMS) by date ascending for chat display
    return messages.sortedBy { it.date }
}

/** Get the FROM or TO address for an MMS message. */
private fun Context.getMmsAddress(msgId: Long, msgBox: Int): String {
    val addrUri = Uri.parse("content://mms/$msgId/addr")
    val addrType = if (msgBox == 1) "137" else "151"   // PduHeaders.FROM=137, TO=151
    try {
        contentResolver.query(
            addrUri,
            arrayOf("address"),
            "type = ?",
            arrayOf(addrType),
            null
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                val addr = cursor.getString(0)
                if (!addr.isNullOrBlank() && addr != "insert-address-token") {
                    return addr
                }
            }
        }
    } catch (e: Exception) {
        // ignore
    }
    return ""
}

/** Mark an entire thread as read in the system telephony provider. */
fun Context.markThreadAsReadInTelephony(threadId: Long) {
    try {
        val values = android.content.ContentValues().apply {
            put(Telephony.Sms.READ, 1)
        }
        contentResolver.update(
            Telephony.Sms.CONTENT_URI,
            values,
            "${Telephony.Sms.THREAD_ID} = ? AND ${Telephony.Sms.READ} = 0",
            arrayOf(threadId.toString())
        )
    } catch (e: Exception) {
        e.printStackTrace()
    }
}

/** Delete all messages in a thread from the system telephony provider. */
fun Context.deleteThreadInTelephony(threadId: Long) {
    try {
        val uri = Uri.parse("content://mms-sms/conversations/$threadId")
        contentResolver.delete(uri, null, null)
    } catch (e: Exception) {
        e.printStackTrace()
    }
}

/**
 * Fetches a single SMS message by its Telephony CP row ID.
 * Returns null if the message no longer exists (e.g. already hard-deleted).
 * Must be called from a background thread.
 */
fun Context.getSmsMessageById(id: Long, contactHelper: ContactHelper): Message? {
    val uri = ContentUris.withAppendedId(Telephony.Sms.CONTENT_URI, id)
    val projection = arrayOf(
        Telephony.Sms._ID,
        Telephony.Sms.THREAD_ID,
        Telephony.Sms.BODY,
        Telephony.Sms.DATE,
        Telephony.Sms.DATE_SENT,
        Telephony.Sms.TYPE,
        Telephony.Sms.READ,
        Telephony.Sms.ADDRESS,
        Telephony.Sms.STATUS,
        Telephony.Sms.SUBSCRIPTION_ID
    )
    return try {
        contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
            if (!cursor.moveToFirst()) return@use null
            val address = cursor.getString(cursor.getColumnIndexOrThrow(Telephony.Sms.ADDRESS)) ?: ""
            val type    = cursor.getInt(cursor.getColumnIndexOrThrow(Telephony.Sms.TYPE))
            val contactInfo = if (type == Message.TYPE_INBOX) contactHelper.resolve(address) else null
            Message(
                id             = cursor.getLong(cursor.getColumnIndexOrThrow(Telephony.Sms._ID)),
                threadId       = cursor.getLong(cursor.getColumnIndexOrThrow(Telephony.Sms.THREAD_ID)),
                body           = cursor.getString(cursor.getColumnIndexOrThrow(Telephony.Sms.BODY)) ?: "",
                type           = type,
                date           = cursor.getLong(cursor.getColumnIndexOrThrow(Telephony.Sms.DATE)),
                dateSent       = cursor.getLong(cursor.getColumnIndexOrThrow(Telephony.Sms.DATE_SENT)),
                read           = cursor.getInt(cursor.getColumnIndexOrThrow(Telephony.Sms.READ)) == 1,
                address        = address,
                senderName     = contactInfo?.displayName ?: address,
                senderPhotoUri = contactInfo?.photoUri ?: "",
                isMms          = false,
                status         = cursor.getInt(cursor.getColumnIndexOrThrow(Telephony.Sms.STATUS)),
                subscriptionId = cursor.getInt(cursor.getColumnIndexOrThrow(Telephony.Sms.SUBSCRIPTION_ID))
            )
        }
    } catch (e: Exception) {
        e.printStackTrace()
        null
    }
}
