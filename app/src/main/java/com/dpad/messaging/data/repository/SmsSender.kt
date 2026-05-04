package com.dpad.messaging.data.repository

import android.app.PendingIntent
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Telephony
import android.telephony.SmsManager
import android.os.Build
import com.klinker.android.send_message.Message
import com.klinker.android.send_message.Settings
import com.klinker.android.send_message.Transaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class SmsSender(private val context: Context) {

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
            put(Telephony.Sms.TYPE, Telephony.Sms.MESSAGE_TYPE_OUTBOX) // outbox until sent
        }
        val insertedUri: Uri? = try {
            context.contentResolver.insert(Telephony.Sms.CONTENT_URI, values)
        } catch (_: Exception) { null }

        // Now send over the air
        val parts = manager.divideMessage(body)
        if (parts.size == 1) {
            manager.sendTextMessage(address, null, body, null, null)
        } else {
            manager.sendMultipartTextMessage(address, null, parts, null, null)
        }

        // Move from outbox -> sent
        if (insertedUri != null) {
            try {
                val sentValues = ContentValues().apply {
                    put(Telephony.Sms.TYPE, Telephony.Sms.MESSAGE_TYPE_SENT)
                }
                context.contentResolver.update(insertedUri, sentValues, null, null)
            } catch (_: Exception) {}
        }

        insertedUri
    }

    suspend fun sendMms(addresses: List<String>, body: String, imageBytes: ByteArray? = null, mimeType: String? = null) = withContext(Dispatchers.IO) {
        val settings = Settings().apply {
            useSystemSending = true   // let the OS MMS stack handle APN/connectivity
            group            = addresses.size > 1
            sendLongAsMms    = true
            sendLongAsMmsAfter = 3
        }

        val transaction = Transaction(context, settings)
        val message = Message(body, addresses.toTypedArray())

        if (imageBytes != null && mimeType != null) {
            message.addMedia(imageBytes, mimeType)
        }

        transaction.sendNewMessage(message, Transaction.NO_THREAD_ID)
    }

    @Suppress("DEPRECATION")
    private fun getSmsManager(): SmsManager =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            context.getSystemService(SmsManager::class.java)
        else
            SmsManager.getDefault()
}
