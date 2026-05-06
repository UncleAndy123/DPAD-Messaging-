package com.dpad.messaging.receivers

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.dpad.messaging.events.RefreshMessages
import com.dpad.messaging.helpers.SmsSender
import com.dpad.messaging.models.Message
import org.greenrobot.eventbus.EventBus

/**
 * Receives the delivery-report PendingIntent after the recipient's handset
 * acknowledges receipt of an outgoing SMS.
 *
 * Updates the Telephony CP row's status column to STATUS_COMPLETE so the
 * "Delivered" indicator can be shown in the thread.
 */
class SmsStatusDeliveredReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val msgId    = intent.getLongExtra(SmsSender.EXTRA_MESSAGE_ID, -1L)
        val threadId = intent.getLongExtra(SmsSender.EXTRA_THREAD_ID, -1L)

        if (resultCode == Activity.RESULT_OK) {
            SmsSender.updateMessageStatus(context, msgId, Message.STATUS_COMPLETE)
        }

        if (threadId != -1L) EventBus.getDefault().post(RefreshMessages(threadId))
    }
}
