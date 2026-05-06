package com.dpad.messaging.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Fires when a scheduled message alarm expires.
 *
 * Phase 2: retrieve the scheduled message from Room, send via SmsManager.
 */
class ScheduledMessageReceiver : BroadcastReceiver() {
    companion object {
        const val EXTRA_SCHEDULED_MESSAGE_ID = "extra_scheduled_message_id"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val messageId = intent.getLongExtra(EXTRA_SCHEDULED_MESSAGE_ID, -1L)
        if (messageId == -1L) return

        // Phase 2: load message from Room, call SmsManager.sendTextMessage()
    }
}
