package com.dpad.messaging.receivers

import android.content.BroadcastReceiver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Telephony
import android.telephony.SmsMessage
import android.util.Log
import com.dpad.messaging.App
import com.dpad.messaging.BuildConfig
import com.dpad.messaging.events.RefreshConversations
import com.dpad.messaging.events.RefreshMessages
import com.dpad.messaging.helpers.SmsWhitelistManager
import com.dpad.messaging.helpers.AppCoroutineScopes
import com.dpad.messaging.helpers.NotificationHelper
import kotlinx.coroutines.launch
import org.greenrobot.eventbus.EventBus
import androidx.core.net.toUri

/**
 * Receives SMS_DELIVER when the app is the default SMS handler.
 *
 * Responsibilities:
 *  1. Parse all PDUs and concatenate multi-part messages (on-thread, before return).
 *  2. [IO coroutine] Resolve / create the thread ID via the Telephony CP.
 *  3. [IO coroutine] Insert the assembled message into the system Telephony Sms CP.
 *  4. [IO coroutine] Skip notification if any blocked keyword matches the body.
 *  5. [IO coroutine] Show a heads-up notification with Reply + Mark-as-Read actions.
 *  6. [IO coroutine] Fire EventBus events so the conversation list and thread refresh.
 *
 * Uses goAsync() so the coroutine can outlive the BroadcastReceiver's normal 10 s window.
 */
class SmsReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        // ── Parse PDUs synchronously before onReceive() returns ───────────────
        val bundle = intent.extras ?: return



        // Bundle.get("pdus") historically returned an Array of ByteArray. On newer
        // API levels the type can vary, so normalize safely to a List<ByteArray>.
        val rawPdus = bundle.get("pdus")
        val pdusList: List<ByteArray> = when (rawPdus) {
            is Array<*> -> rawPdus.mapNotNull { it as? ByteArray }
            is java.util.ArrayList<*> -> rawPdus.mapNotNull { it as? ByteArray }
            else -> emptyList()
        }
        if (pdusList.isEmpty()) return

        val format = bundle.getString("format") ?: "3gpp"
        val smsMessages = pdusList.mapNotNull { pdu ->
            try { SmsMessage.createFromPdu(pdu, format) } catch (_: Exception) { null }
        }
        if (smsMessages.isEmpty()) return

        val address = smsMessages.first().displayOriginatingAddress ?: return
        val body = smsMessages.joinToString("") { it.displayMessageBody ?: "" }
        val timestamp = smsMessages.first().timestampMillis

        // ── Hand off to IO coroutine; keep the receiver alive via PendingResult ─
        val pendingResult = goAsync()
        AppCoroutineScopes.io.launch {
            try {
                processMessage(context, address, body, timestamp)
            } finally {
                pendingResult.finish()
            }
        }
    }

    private suspend fun processMessage(
    context: Context,
    address: String,
    body: String,
    timestamp: Long
) {
    if (BuildConfig.DEBUG) Log.d("DPAD_MSG", "SmsReceiver.processMessage() address=$address body='${body.take(40)}'")

    // ── MDM hard-filter — must run first, before any thread/insert side effects ──
    val filterResult = SmsWhitelistManager.check(context, address)
    if (!filterResult.allowed) {
        Log.i("DPAD_MSG", "SmsReceiver: dropped message from $address — ${filterResult.reason}")
        return  // no thread created, no insert, no notification
    }
    // ───────────────────────────────────────────────────────────────────────────

    // Resolve (or create) the thread ID for this sender address.
    val threadId: Long = resolveThreadId(context, address)
        ?: try {
            val newId = Telephony.Threads.getOrCreateThreadId(context, address)
            if (BuildConfig.DEBUG) Log.d("DPAD_MSG", "SmsReceiver: getOrCreateThreadId for '$address' -> $newId")
            newId
        } catch (e: Exception) {
            if (BuildConfig.DEBUG) Log.e("DPAD_MSG", "SmsReceiver: getOrCreateThreadId failed for '$address'", e)
            return
        }
    if (BuildConfig.DEBUG) Log.d("DPAD_MSG", "SmsReceiver.resolveThreadId() -> threadId=$threadId")

    // Insert into Telephony Sms CP so every SMS reader app can see it.
    val cv = ContentValues().apply {
        put(Telephony.Sms.ADDRESS, address)
        put(Telephony.Sms.BODY, body)
        put(Telephony.Sms.DATE, timestamp)
        put(Telephony.Sms.DATE_SENT, timestamp)
        put(Telephony.Sms.TYPE, Telephony.Sms.MESSAGE_TYPE_INBOX)
        put(Telephony.Sms.THREAD_ID, threadId)
        put(Telephony.Sms.READ, 0)
        put(Telephony.Sms.SEEN, 0)
    }
    try {
        val insertedUri = context.contentResolver.insert(Telephony.Sms.CONTENT_URI, cv)
        if (BuildConfig.DEBUG) Log.d("DPAD_MSG", "SmsReceiver: inserted SMS row -> $insertedUri")
    } catch (e: Exception) {
        if (BuildConfig.DEBUG) Log.e("DPAD_MSG", "SmsReceiver: insert failed", e)
        e.printStackTrace()
    }

    // ── Local soft-filter (upstream blocklist + keywords) — suppress notification only ──
    val keywords = App.get().database.blockedKeywordsDao().getAll()
    val blockedNumbers = App.get().database.blockedNumbersDao().getAll()
    val normalizedAddrDigits = address.filter { it.isDigit() }

    val isBlockedByNumber = blockedNumbers.any { bn ->
        val ndigits = bn.number.filter { it.isDigit() }
        bn.number == address || ndigits == normalizedAddrDigits
    }
    val isBlockedByKeyword = keywords.any { kw ->
        body.contains(kw.keyword, ignoreCase = true)
    }
    val isBlocked = isBlockedByNumber || isBlockedByKeyword

    if (!isBlocked) {
        val senderName = App.get().contactHelper.getDisplayName(address)
        NotificationHelper.showIncomingNotification(context, threadId, senderName, address, body)
    }

    if (BuildConfig.DEBUG) Log.d("DPAD_MSG", "SmsReceiver: posting EventBus RefreshMessages(threadId=$threadId)")
    EventBus.getDefault().post(RefreshConversations())
    EventBus.getDefault().post(RefreshMessages(threadId))
}


    private fun resolveThreadId(context: Context, address: String): Long? {
        // Try several normalized forms of the address because different carriers/ROMs
        // store canonical addresses in different formats (e.g. no leading +, no country
        // code). Try in this order: raw address, digits-only, digits-without-leading-1.
        val candidates = mutableListOf<String>()
        candidates.add(address)
        val digitsOnly = address.filter { it.isDigit() }
        if (digitsOnly.isNotEmpty() && digitsOnly != address) candidates.add(digitsOnly)
        if (digitsOnly.startsWith("1") && digitsOnly.length > 10) {
            val drop1 = digitsOnly.removePrefix("1")
            if (drop1.isNotEmpty()) candidates.add(drop1)
        }

        // First, try the fast threadID URI for each candidate.
        for (cand in candidates) {
            try {
                if (BuildConfig.DEBUG) Log.d("DPAD_MSG", "SmsReceiver.resolveThreadId(): querying threadID for candidate='$cand'")
                val uri = Uri.withAppendedPath(
                    "content://mms-sms/threadID".toUri(),
                    Uri.encode(cand)
                )
                context.contentResolver.query(uri, arrayOf("_id"), null, null, null)
                    ?.use { cursor -> if (cursor.moveToFirst()) return cursor.getLong(0) }
            } catch (e: Exception) {
                // try next candidate
            }
        }

        // Fallback: scan conversations' recipient canonical-address IDs and compare
        // their stored addresses against our normalized candidates. This is more
        // expensive but reliable across ROMs that don't support the threadID URI.
        try {
            if (BuildConfig.DEBUG) Log.d("DPAD_MSG", "SmsReceiver.resolveThreadId(): fallback scanning conversations for address=$address")
            val convUri = "content://mms-sms/conversations?simple=true".toUri()
            val proj = arrayOf(Telephony.Threads._ID, Telephony.Threads.RECIPIENT_IDS)
            val normalizedCandidates = candidates.map { it.filter { ch -> ch.isDigit() } }.toSet()

            context.contentResolver.query(convUri, proj, null, null, null)?.use { cursor ->
                val idxId = cursor.getColumnIndex(Telephony.Threads._ID)
                val idxRecipients = cursor.getColumnIndex(Telephony.Threads.RECIPIENT_IDS)
                while (cursor.moveToNext()) {
                    val threadId = cursor.getLong(idxId)
                    val recipientIds = cursor.getString(idxRecipients) ?: continue
                    val ids = recipientIds.trim().split(" ").mapNotNull { it.trim().toLongOrNull() }
                    for (cid in ids) {
                        try {
                            val addrUri = "content://mms-sms/canonical-address/$cid".toUri()
                            val c2 = context.contentResolver.query(addrUri, arrayOf("address"), null, null, null)
                            c2?.use {
                                if (it.moveToFirst()) {
                                    val storedAddr = it.getString(0)
                                    if (!storedAddr.isNullOrBlank()) {
                                        val storedDigits = storedAddr.filter { ch -> ch.isDigit() }
                                        if (storedDigits in normalizedCandidates) {
                                            if (BuildConfig.DEBUG) Log.d("DPAD_MSG", "SmsReceiver.resolveThreadId(): matched threadId=$threadId via canonical-address id=$cid addr=$storedAddr")
                                            return threadId
                                        }
                                    }
                                }
                            }
                        } catch (e: Exception) {
                            // ignore and continue
                        }
                    }
                }
            }
        } catch (e: Exception) {
            // ignore fallback failures
        }

        return null
    }
}
