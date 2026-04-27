package com.dpad.messaging.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Receives incoming SMS so Android registers this app as a valid SMS app.
 * The actual reading of messages is done via the Telephony content provider.
 */
class SmsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // Messages are read from the content provider in SmsRepository.
        // This receiver exists so the system allows us READ_SMS access
        // when the user sets this as the default SMS app.
    }
}
