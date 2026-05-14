package com.dpad.messaging.receivers

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.util.Log
import com.dpad.messaging.events.RefreshConversations
import com.dpad.messaging.events.RefreshMessages
import com.dpad.messaging.helpers.SmsSender
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.greenrobot.eventbus.EventBus

/**
 * Receives the PendingIntent result fired by SmsManager after the radio
 * accepts (or rejects) an outgoing SMS.
 *
 * Handles both GSM and CDMA status codes to ensure consistent sent status
 * across different carriers and network conditions.
 *
 * Updates the Telephony CP row from TYPE_OUTBOX to TYPE_SENT or TYPE_FAILED,
 * then triggers a UI refresh so the thread shows the correct bubble style.
 *
 * Result codes:
 *  - Activity.RESULT_OK (-1) = SMS sent successfully
 *  - SmsManager.RESULT_ERROR_GENERIC_FAILURE (1) = Generic error
 *  - SmsManager.RESULT_ERROR_RADIO_OFF (2) = Radio is off
 *  - SmsManager.RESULT_ERROR_NULL_PDU (3) = PDU construction failed
 *  - SmsManager.RESULT_ERROR_NO_SERVICE (4) = No service available
 */
class SmsStatusSentReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val receiverResultCode = resultCode
        val pendingResult = goAsync()

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val msgId = SmsSender.resolveMessageId(intent)
                val threadId = SmsSender.resolveThreadId(
                    context = context,
                    msgId = msgId,
                    fallbackThreadId = intent.getLongExtra(SmsSender.EXTRA_THREAD_ID, -1L)
                )
                val success = receiverResultCode == Activity.RESULT_OK

                Log.d(
                    "DPAD_MSG",
                    "SmsStatusSentReceiver.onReceive() msgId=$msgId threadId=$threadId resultCode=$receiverResultCode success=$success"
                )

                val messageType = if (success) {
                    Log.d("DPAD_MSG", "SmsStatusSentReceiver: SMS sent successfully")
                    Telephony.Sms.MESSAGE_TYPE_SENT
                } else {
                    Log.w("DPAD_MSG", "SmsStatusSentReceiver: SMS send failed with code=$receiverResultCode")
                    Telephony.Sms.MESSAGE_TYPE_FAILED
                }

                SmsSender.updateMessageType(context, msgId, messageType)

                EventBus.getDefault().post(RefreshConversations())
                if (threadId > 0) EventBus.getDefault().post(RefreshMessages(threadId))
            } finally {
                pendingResult.finish()
            }
        }
    }
}
