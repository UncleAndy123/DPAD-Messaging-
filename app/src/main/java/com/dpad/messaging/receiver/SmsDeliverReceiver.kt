package com.dpad.messaging.receiver

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import androidx.core.app.NotificationCompat
import com.dpad.messaging.MainActivity
import com.dpad.messaging.R
import com.dpad.messaging.data.db.AppDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SmsDeliverReceiver : BroadcastReceiver() {

    companion object {
        const val CHANNEL_ID = "sms_incoming"
        const val CHANNEL_NAME = "Incoming Messages"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_DELIVER_ACTION) return

        val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent)
        if (messages.isNullOrEmpty()) return

        val address = messages[0].displayOriginatingAddress ?: return
        val body = messages.joinToString("") { it.messageBody ?: "" }
        val date = messages[0].timestampMillis

        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val db = AppDatabase.getDatabase(context)

                // Resolve thread ID for this address
                val threadId = resolveThreadId(context, address)

                // Check metadata for blocked / muted
                val meta = if (threadId != null) db.threadMetadataDao().getMetadataSync(threadId) else null
                val isBlocked = meta?.isBlocked == true
                val isMuted = meta?.isMuted == true

                if (isBlocked) {
                    // Drop the message entirely — don't write to inbox, don't notify
                    return@launch
                }

                // Write to SMS inbox
                val values = ContentValues().apply {
                    put(Telephony.Sms.ADDRESS, address)
                    put(Telephony.Sms.BODY, body)
                    put(Telephony.Sms.DATE, date)
                    put(Telephony.Sms.READ, 0)
                    put(Telephony.Sms.TYPE, Telephony.Sms.MESSAGE_TYPE_INBOX)
                }
                context.contentResolver.insert(Telephony.Sms.Inbox.CONTENT_URI, values)

                // Post notification unless muted
                if (!isMuted) {
                    withContext(Dispatchers.Main) {
                        postNotification(context, address, body, threadId)
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                pendingResult.finish()
            }
        }
    }

    private fun resolveThreadId(context: Context, address: String): Long? {
        return try {
            val cursor = context.contentResolver.query(
                Telephony.Sms.CONTENT_URI,
                arrayOf(Telephony.Sms.THREAD_ID),
                "${Telephony.Sms.ADDRESS} = ?",
                arrayOf(address),
                "${Telephony.Sms.DATE} DESC"
            )
            cursor?.use { c ->
                if (c.moveToFirst()) c.getLong(0) else null
            }
        } catch (_: Exception) { null }
    }

    private fun postNotification(context: Context, address: String, body: String, threadId: Long?) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        // Ensure channel exists (safe to call repeatedly)
        val channel = NotificationChannel(CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_HIGH).apply {
            description = "Notifications for incoming SMS messages"
        }
        nm.createNotificationChannel(channel)

        val tapIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            if (threadId != null) {
                putExtra("threadId", threadId)
                putExtra("address", address)
            }
        }
        val pi = PendingIntent.getActivity(
            context, threadId?.toInt() ?: address.hashCode(),
            tapIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notif = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(address)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pi)
            .build()

        nm.notify(threadId?.toInt() ?: address.hashCode(), notif)
    }
}
