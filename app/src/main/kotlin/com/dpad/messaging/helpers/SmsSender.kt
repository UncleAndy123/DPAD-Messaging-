package com.dpad.messaging.helpers

import android.app.PendingIntent
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Telephony
import android.telephony.SmsManager
import com.dpad.messaging.receivers.SmsStatusDeliveredReceiver
import com.dpad.messaging.receivers.SmsStatusSentReceiver

/**
 * Wraps SmsManager.sendTextMessage() with Telephony CP bookkeeping.
 *
 * Flow:
 *  1. Insert a TYPE_OUTBOX row into Telephony Sms CP (message appears immediately in UI).
 *  2. Fire SmsManager; attach PendingIntents pointing to SmsStatusSentReceiver /
 *     SmsStatusDeliveredReceiver, carrying the Telephony row ID so the receivers
 *     can update its type/status when the radio reports back.
 *
 * Must be called from a background thread — performs ContentProvider I/O.
 */
object SmsSender {

    const val ACTION_SMS_SENT = "com.dpad.messaging.SMS_SENT"
    const val ACTION_SMS_DELIVERED = "com.dpad.messaging.SMS_DELIVERED"
    const val EXTRA_MESSAGE_ID = "extra_message_id"
    const val EXTRA_THREAD_ID = "extra_thread_id"

    fun send(
        context: Context,
        phoneNumber: String,
        body: String,
        threadId: Long,
        subscriptionId: Int = -1
    ) {
        // 1. Insert TYPE_OUTBOX so the message appears in the thread immediately.
        val cv = ContentValues().apply {
            put("address", phoneNumber)
            put("body", body)
            put("type", Telephony.Sms.MESSAGE_TYPE_OUTBOX)
            put("thread_id", threadId)
            put("date", System.currentTimeMillis())
            put("date_sent", 0L)
            put("read", 1)
            put("seen", 1)
        }
        val msgUri = try {
            context.contentResolver.insert(Telephony.Sms.CONTENT_URI, cv)
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
        val msgId = msgUri?.lastPathSegment?.toLongOrNull() ?: -1L

        // 2. Build PendingIntents.  Use a unique request code per message to
        //    avoid overwriting intents from concurrent sends.
        val reqCode = if (msgId > 0) msgId.toInt()
                      else (System.currentTimeMillis() and 0x7FFFFFFF).toInt()

        val sentPI = PendingIntent.getBroadcast(
            context,
            reqCode,
            Intent(ACTION_SMS_SENT, null, context, SmsStatusSentReceiver::class.java).apply {
                putExtra(EXTRA_MESSAGE_ID, msgId)
                putExtra(EXTRA_THREAD_ID, threadId)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val deliveredPI: PendingIntent? = if (Prefs.get().deliveryReports) {
            PendingIntent.getBroadcast(
                context,
                reqCode + 1,
                Intent(ACTION_SMS_DELIVERED, null, context, SmsStatusDeliveredReceiver::class.java).apply {
                    putExtra(EXTRA_MESSAGE_ID, msgId)
                    putExtra(EXTRA_THREAD_ID, threadId)
                },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        } else null

        // 3. Send.
        val smsManager = getSmsManager(context, subscriptionId)
        val parts = try { smsManager.divideMessage(body) } catch (e: Exception) {
            arrayListOf(body)
        }

        try {
            if (parts.size == 1) {
                smsManager.sendTextMessage(phoneNumber, null, body, sentPI, deliveredPI)
            } else {
                val sentIntents = ArrayList<PendingIntent>(parts.size).also { list ->
                    repeat(parts.size) { list.add(sentPI) }
                }
                // Pass null list when delivery reports are disabled.
                val deliveredIntents: ArrayList<PendingIntent>? = if (deliveredPI != null) {
                    ArrayList<PendingIntent>(parts.size).also { list ->
                        repeat(parts.size) { list.add(deliveredPI) }
                    }
                } else null
                smsManager.sendMultipartTextMessage(
                    phoneNumber, null, parts, sentIntents, deliveredIntents
                )
            }
        } catch (e: Exception) {
            e.printStackTrace()
            // Immediately mark FAILED so the UI reflects reality.
            if (msgId > 0) updateMessageType(context, msgId, Telephony.Sms.MESSAGE_TYPE_FAILED)
        }
    }

    // ── Telephony CP helpers ──────────────────────────────────────────────────

    fun updateMessageType(context: Context, msgId: Long, type: Int) {
        if (msgId <= 0) return
        try {
            context.contentResolver.update(
                ContentUris.withAppendedId(Telephony.Sms.CONTENT_URI, msgId),
                ContentValues().apply { put("type", type) },
                null, null
            )
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun updateMessageStatus(context: Context, msgId: Long, status: Int) {
        if (msgId <= 0) return
        try {
            context.contentResolver.update(
                ContentUris.withAppendedId(Telephony.Sms.CONTENT_URI, msgId),
                ContentValues().apply { put("status", status) },
                null, null
            )
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    // ── SmsManager selection ──────────────────────────────────────────────────

    @Suppress("DEPRECATION")
    private fun getSmsManager(context: Context, subscriptionId: Int): SmsManager {
        // subscriptionId always wins — check it first before falling back to the default.
        // (The previous ordering incorrectly applied the system-default branch on API S+
        //  even when a specific SIM was requested.)
        return when {
            subscriptionId != -1 && Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1 ->
                SmsManager.getSmsManagerForSubscriptionId(subscriptionId)
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
                context.getSystemService(SmsManager::class.java) ?: SmsManager.getDefault()
            else ->
                SmsManager.getDefault()
        }
    }
}
