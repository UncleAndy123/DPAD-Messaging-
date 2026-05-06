package com.dpad.messaging.services

import android.app.Service
import android.content.Intent
import android.net.Uri
import android.os.IBinder
import com.dpad.messaging.helpers.SmsSender
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Handles the RESPOND_VIA_MESSAGE intent — required by the default SMS app role.
 *
 * When the user presses "Reply" from a phone call screen, Android starts this service
 * and passes the recipient (in the Intent data URI, e.g. smsto:+15551234567) and any
 * pre-filled reply text (in EXTRA_TEXT).  If EXTRA_TEXT is non-blank we send immediately
 * via SmsSender; otherwise we do nothing (the user can open the app to compose).
 */
class HeadlessSmsSendService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent != null) {
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    handleRespondViaMessage(intent)
                } finally {
                    stopSelf(startId)
                }
            }
        } else {
            stopSelf(startId)
        }
        return START_NOT_STICKY
    }

    private suspend fun handleRespondViaMessage(intent: Intent) {
        val body = intent.getStringExtra(Intent.EXTRA_TEXT)?.trim()
        if (body.isNullOrBlank()) return

        // Data URI is e.g. "smsto:+15551234567" or "sms:+15551234567"
        val recipientUri: Uri = intent.data ?: return
        val phoneNumber = recipientUri.schemeSpecificPart
            ?.removePrefix("//")    // some ROMs include leading slashes
            ?.trim()
        if (phoneNumber.isNullOrBlank()) return

        // Resolve (or create) a thread ID for this recipient
        val threadId = resolveThreadId(phoneNumber) ?: return

        SmsSender.send(
            context        = this,
            phoneNumber    = phoneNumber,
            body           = body,
            threadId       = threadId,
            subscriptionId = -1
        )
    }

    private fun resolveThreadId(address: String): Long? {
        return try {
            val uri = android.net.Uri.withAppendedPath(
                android.net.Uri.parse("content://mms-sms/threadID"),
                android.net.Uri.encode(address)
            )
            contentResolver.query(uri, arrayOf("_id"), null, null, null)
                ?.use { cursor -> if (cursor.moveToFirst()) cursor.getLong(0) else null }
        } catch (e: Exception) {
            null
        }
    }
}
