package com.dpad.messaging.helpers

import android.content.Context
import android.content.ContentValues
import android.content.Intent
import android.net.Uri
import android.provider.Telephony
import android.util.Log
import com.dpad.messaging.receivers.SmsStatusDeliveredReceiver
import com.dpad.messaging.receivers.SmsStatusSentReceiver
import com.klinker.android.send_message.Message as KlinkerMessage
import com.klinker.android.send_message.Settings as KlinkerSettings
import com.klinker.android.send_message.Transaction as KlinkerTransaction

/**
 * Unified send abstraction for SMS + MMS.
 *
 * Phase 1 goal: introduce an adapter layer without changing behavior.
 * Current implementation delegates to existing SmsSender/MmsSender.
 */
interface UnifiedMessageSender {
    suspend fun sendSms(
        context: Context,
        phoneNumber: String,
        body: String,
        threadId: Long,
        subscriptionId: Int = -1
    )

    suspend fun sendMms(
        context: Context,
        recipients: List<String>,
        body: String,
        attachmentUri: Uri?,
        threadId: Long,
        subscriptionId: Int = -1
    )

    suspend fun sendGroupSmsFanout(
        context: Context,
        recipients: List<String>,
        body: String,
        fallbackThreadId: Long,
        subscriptionId: Int = -1
    )
}

/**
 * Legacy adapter preserving existing behavior.
 */
object LegacyUnifiedMessageSender : UnifiedMessageSender {
    override suspend fun sendSms(
        context: Context,
        phoneNumber: String,
        body: String,
        threadId: Long,
        subscriptionId: Int
    ) {
        SmsSender.send(
            context = context,
            phoneNumber = phoneNumber,
            body = body,
            threadId = threadId,
            subscriptionId = subscriptionId
        )
    }

    override suspend fun sendMms(
        context: Context,
        recipients: List<String>,
        body: String,
        attachmentUri: Uri?,
        threadId: Long,
        subscriptionId: Int
    ) {
        MmsSender.send(
            context = context,
            recipients = recipients,
            body = body,
            attachmentUri = attachmentUri,
            threadId = threadId,
            subscriptionId = subscriptionId
        )
    }

    override suspend fun sendGroupSmsFanout(
        context: Context,
        recipients: List<String>,
        body: String,
        fallbackThreadId: Long,
        subscriptionId: Int
    ) {
        for (recipient in recipients) {
            val recipientThreadId = try {
                Telephony.Threads.getOrCreateThreadId(context, recipient)
            } catch (e: Exception) {
                Log.w("DPAD_MSG", "UnifiedSender: failed to resolve threadId for $recipient", e)
                fallbackThreadId
            }

            SmsSender.send(
                context = context,
                phoneNumber = recipient,
                body = body,
                threadId = recipientThreadId,
                subscriptionId = subscriptionId
            )
        }

        if (fallbackThreadId > 0 && recipients.isNotEmpty()) {
            runCatching {
                context.contentResolver.insert(
                    Telephony.Sms.CONTENT_URI,
                    ContentValues().apply {
                        put("address", recipients.joinToString("|"))
                        put("body", body)
                        put("type", Telephony.Sms.MESSAGE_TYPE_SENT)
                        put("thread_id", fallbackThreadId)
                        put("date", System.currentTimeMillis())
                        put("date_sent", System.currentTimeMillis())
                        put("read", 1)
                        put("seen", 1)
                    }
                )
            }.onFailure { e ->
                Log.w("DPAD_MSG", "UnifiedSender: failed to insert group fanout broadcast row", e)
            }
        }
    }
}

/**
 * Phase 2 pilot sender:
 *  - SMS single-recipient is sent via mmslib transaction path.
 *  - MMS and fanout group SMS keep legacy behavior for stability.
 */
object LibraryUnifiedMessageSender : UnifiedMessageSender {
    private const val TAG = "DPAD_MSG"

    override suspend fun sendSms(
        context: Context,
        phoneNumber: String,
        body: String,
        threadId: Long,
        subscriptionId: Int
    ) {
        val settings = KlinkerSettings().apply {
            setUseSystemSending(true)
            setGroup(false)
            setDeliveryReports(Prefs.get().deliveryReports)
            if (subscriptionId >= 0) setSubscriptionId(subscriptionId)
        }

        val transaction = KlinkerTransaction(context, settings)
            .setExplicitBroadcastForSentSms(
                Intent(context, SmsStatusSentReceiver::class.java).apply {
                    putExtra(SmsSender.EXTRA_THREAD_ID, threadId)
                }
            )
            .setExplicitBroadcastForDeliveredSms(
                Intent(context, SmsStatusDeliveredReceiver::class.java).apply {
                    putExtra(SmsSender.EXTRA_THREAD_ID, threadId)
                }
            )
        val message = KlinkerMessage(body, phoneNumber)

        try {
            Log.d(TAG, "LibraryUnifiedMessageSender: SMS via mmslib phone=$phoneNumber threadId=$threadId subId=$subscriptionId")
            transaction.sendNewMessage(message)
        } catch (e: Exception) {
            Log.e(TAG, "LibraryUnifiedMessageSender: SMS library send failed, falling back to legacy", e)
            SmsSender.send(
                context = context,
                phoneNumber = phoneNumber,
                body = body,
                threadId = threadId,
                subscriptionId = subscriptionId
            )
        }
    }

    override suspend fun sendMms(
        context: Context,
        recipients: List<String>,
        body: String,
        attachmentUri: Uri?,
        threadId: Long,
        subscriptionId: Int
    ) {
        // Keep existing MMS path unchanged for phase 2.
        MmsSender.send(
            context = context,
            recipients = recipients,
            body = body,
            attachmentUri = attachmentUri,
            threadId = threadId,
            subscriptionId = subscriptionId
        )
    }

    override suspend fun sendGroupSmsFanout(
        context: Context,
        recipients: List<String>,
        body: String,
        fallbackThreadId: Long,
        subscriptionId: Int
    ) {
        // Keep existing fanout path unchanged for phase 2.
        LegacyUnifiedMessageSender.sendGroupSmsFanout(
            context = context,
            recipients = recipients,
            body = body,
            fallbackThreadId = fallbackThreadId,
            subscriptionId = subscriptionId
        )
    }
}

object MessageSenders {
    val unified: UnifiedMessageSender
        get() = if (Prefs.get().useLibrarySmsSending) {
            LibraryUnifiedMessageSender
        } else {
            LegacyUnifiedMessageSender
        }
}
