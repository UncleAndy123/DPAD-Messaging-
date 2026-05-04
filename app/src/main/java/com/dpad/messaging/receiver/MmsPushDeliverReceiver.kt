package com.dpad.messaging.receiver

import android.content.Context
import android.content.Intent
import com.klinker.android.send_message.MmsReceivedReceiver

/**
 * Klinker's MmsReceivedReceiver is useful but some OEM MMS deliveries can cause
 * unexpected exceptions (e.g. null file paths). Protect the app by catching
 * exceptions at the receiver boundary so a malformed incoming push doesn't crash
 * the process.
 */
class MmsPushDeliverReceiver : MmsReceivedReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        try {
            // Delegate to Klinker but protect against any throwable it might raise
            super.onReceive(context, intent)
        } catch (t: Throwable) {
            // Swallow and log: receiver should never crash the app.
            t.printStackTrace()
        }
    }
}
