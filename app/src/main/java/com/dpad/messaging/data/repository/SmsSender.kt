package com.dpad.messaging.data.repository

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.Telephony
import android.telephony.SmsManager
import android.telephony.SubscriptionManager
import android.util.Log
import com.dpad.messaging.util.SmsHelper
import com.klinker.android.send_message.Message
import com.klinker.android.send_message.Settings
import com.klinker.android.send_message.Transaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class SmsSender(private val context: Context) {

    companion object {
        private const val TAG = "SmsSender"
    }

    /**
     * Sends an SMS and writes it to the Telephony sent-items provider (required when we
     * are the default SMS app — the framework does NOT do this automatically).
     * Returns the Uri of the inserted provider row, which carries the real thread_id.
     */
    suspend fun sendSms(address: String, body: String): Uri? = withContext(Dispatchers.IO) {
        val manager = getSmsManager()

        // Write to provider FIRST so the thread_id exists before SmsManager queues the radio send.
        val values = ContentValues().apply {
            put(Telephony.Sms.ADDRESS, address)
            put(Telephony.Sms.BODY, body)
            put(Telephony.Sms.DATE, System.currentTimeMillis())
            put(Telephony.Sms.READ, 1)
            put(Telephony.Sms.TYPE, Telephony.Sms.MESSAGE_TYPE_OUTBOX)
        }
        val insertedUri: Uri? = try {
            context.contentResolver.insert(Telephony.Sms.CONTENT_URI, values)
        } catch (_: Exception) { null }

        // Send over the air
        val parts = manager.divideMessage(body)
        if (parts.size == 1) {
            manager.sendTextMessage(address, null, body, null, null)
        } else {
            manager.sendMultipartTextMessage(address, null, parts, null, null)
        }

        // Move from outbox → sent
        if (insertedUri != null) {
            try {
                context.contentResolver.update(
                    insertedUri,
                    ContentValues().apply { put(Telephony.Sms.TYPE, Telephony.Sms.MESSAGE_TYPE_SENT) },
                    null, null
                )
            } catch (_: Exception) {}
        }

        insertedUri
    }

    /**
     * Sends an MMS via Fossify mmslib's Transaction with useSystemSending = true.
     *
     * Fossify mmslib's sendNewMessage(Message) no longer accepts a threadId parameter
     * (unlike Klinker 4.0.0). The library resolves the thread from the recipient addresses.
     */
    suspend fun sendMms(
        addresses: List<String>,
        body: String,
        threadId: Long = Transaction.NO_THREAD_ID,
        imageBytes: ByteArray? = null,
        mimeType: String? = null
    ) = withContext(Dispatchers.IO) {
        val subId = getDefaultSmsSubId()

        val settings = Settings().apply {
            useSystemSending   = true
            group              = addresses.size > 1
            sendLongAsMms      = true
            sendLongAsMmsAfter = 1
            if (subId != SubscriptionManager.INVALID_SUBSCRIPTION_ID) {
                subscriptionId = subId
            }
        }

        val message = Message(body, addresses.toTypedArray())

        if (imageBytes != null && mimeType != null) {
            message.addMedia(imageBytes, mimeType)
        }

        Log.d(TAG, "sendMms addresses=${addresses.joinToString()} subId=$subId threadId=$threadId imageBytes=${imageBytes?.size ?: 0} mimeType=$mimeType")
        try {
            Transaction(context, settings).sendNewMessage(message)
            Log.d(TAG, "sendMms succeeded for subId=$subId")
        } catch (e: Exception) {
            Log.e(TAG, "sendMms failed for subId=$subId", e)
            throw e
        }
    }

    @Suppress("DEPRECATION")
    private fun getSmsManager(): SmsManager {
        return SmsHelper.getSmsManager(context)
    }

    @Suppress("MissingPermission")
    private fun getDefaultSmsSubId(): Int {
        return try {
            SubscriptionManager.getDefaultSmsSubscriptionId()
                .takeIf { it != SubscriptionManager.INVALID_SUBSCRIPTION_ID }
                ?: SubscriptionManager.getDefaultSubscriptionId()
        } catch (_: Exception) {
            SubscriptionManager.INVALID_SUBSCRIPTION_ID
        }
    }
}
