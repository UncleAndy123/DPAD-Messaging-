package com.dpad.messaging.receivers

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import com.dpad.messaging.events.RefreshConversations
import com.dpad.messaging.events.RefreshMessages
import com.dpad.messaging.helpers.SmsSender
import org.greenrobot.eventbus.EventBus

/**
 * Receives the PendingIntent result fired by SmsManager after the radio
 * accepts (or rejects) an outgoing SMS.
 *
 * Updates the Telephony CP row from TYPE_OUTBOX to TYPE_SENT or TYPE_FAILED,
 * then triggers a UI refresh so the thread shows the correct bubble style.
 */
class SmsStatusSentReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val msgId   = intent.getLongExtra(SmsSender.EXTRA_MESSAGE_ID, -1L)
        val threadId = intent.getLongExtra(SmsSender.EXTRA_THREAD_ID, -1L)
        val success = resultCode == Activity.RESULT_OK

        SmsSender.updateMessageType(
            context,
            msgId,
            if (success) Telephony.Sms.MESSAGE_TYPE_SENT else Telephony.Sms.MESSAGE_TYPE_FAILED
        )

        EventBus.getDefault().post(RefreshConversations())
        if (threadId != -1L) EventBus.getDefault().post(RefreshMessages(threadId))
    }
}
