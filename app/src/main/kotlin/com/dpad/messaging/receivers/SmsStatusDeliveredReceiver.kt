package com.dpad.messaging.receivers

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Telephony
import android.telephony.SmsMessage
import android.util.Log
import com.dpad.messaging.events.RefreshMessages
import com.dpad.messaging.helpers.SmsSender
import com.dpad.messaging.models.Message
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.greenrobot.eventbus.EventBus

/**
 * Receives the delivery-report PendingIntent after the recipient's handset
 * acknowledges receipt of an outgoing SMS.
 *
 * Handles both GSM and CDMA status codes to ensure consistent delivery indicators
 * across different carriers. Maps carrier-specific result codes to standardized
 * SMS status values (STATUS_COMPLETE, STATUS_FAILED, STATUS_NONE).
 *
 * Updates the Telephony CP row's status column so the "Delivered" indicator
 * can be shown in the thread.
 *
 * CDMA Status codes (used by US carriers):
 *   0 = No error (success)
 *   1 = Mobile blocked / Network error
 *   2 = Mobile does not exist
 *   3 = Mobile busy
 *   4 = Mobile no answer
 *   5 = Mobile refused
 *   6 = Mobile not reachable
 *   21 = Network error
 *   255 = Unknown error
 *
 * GSM Status codes:
 *   0 = Delivered (success)
 *   Non-zero = Various failure reasons
 */
class SmsStatusDeliveredReceiver : BroadcastReceiver() {

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

                val status = parseDeliveryStatusFromPdu(intent) ?: mapDeliveryStatus(receiverResultCode)
                Log.d(
                    "DPAD_MSG",
                    "SmsStatusDeliveredReceiver.onReceive() msgId=$msgId threadId=$threadId resultCode=$receiverResultCode mappedStatus=$status"
                )

                if (msgId > 0) {
                    SmsSender.updateMessageStatus(context, msgId, status)
                    if (status == Message.STATUS_COMPLETE) {
                        context.contentResolver.update(
                            Telephony.Sms.CONTENT_URI,
                            ContentValues().apply { put("date_sent", System.currentTimeMillis()) },
                            "_id = ?",
                            arrayOf(msgId.toString())
                        )
                    }
                }

                if (threadId > 0) EventBus.getDefault().post(RefreshMessages(threadId))
            } finally {
                pendingResult.finish()
            }
        }
    }

    /**
     * Maps carrier-specific delivery status codes to standard Android status constants.
     *
     * Handles three cases:
     *  1. Activity.RESULT_OK (-1) = GSM/universal success indicator
     *  2. CDMA success (0) = No error
     *  3. CDMA/GSM errors (other values) = Delivery failed
     *
     * @param resultCode The result code from the delivery intent broadcast
     * @return STATUS_COMPLETE (0) if delivered, STATUS_FAILED (64) if failed, STATUS_NONE (-1) if unknown
     */
    private fun mapDeliveryStatus(resultCode: Int): Int {
        return when {
            // GSM / Universal success marker
            resultCode == Activity.RESULT_OK -> {
                Log.d("DPAD_MSG", "SmsStatusDeliveredReceiver: RESULT_OK (-1) = delivered")
                Message.STATUS_COMPLETE
            }
            // CDMA success: no error (0)
            resultCode == 0 -> {
                Log.d("DPAD_MSG", "SmsStatusDeliveredReceiver: CDMA code 0 = delivered")
                Message.STATUS_COMPLETE
            }
            // CDMA error codes: 1, 2, 3, 4, 5, 6, 21, 255, or other non-zero values
            resultCode > 0 -> {
                Log.w("DPAD_MSG", "SmsStatusDeliveredReceiver: CDMA/GSM error code=$resultCode = failed")
                Message.STATUS_FAILED
            }
            // Unknown result code (e.g., negative values other than RESULT_OK)
            else -> {
                Log.w("DPAD_MSG", "SmsStatusDeliveredReceiver: unknown resultCode=$resultCode = no status change")
                Message.STATUS_NONE
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun parseDeliveryStatusFromPdu(intent: Intent): Int? {
        val pdu = intent.extras?.get("pdu") as? ByteArray ?: return null
        val format = intent.getStringExtra("format")
        val sms = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                SmsMessage.createFromPdu(pdu, format)
            } else {
                SmsMessage.createFromPdu(pdu)
            }
        }.getOrNull() ?: return null

        val rawStatus = sms.status
        if (format == "3gpp2") {
            val errorClass = rawStatus shr 24 and 0x03
            val statusCode = rawStatus shr 16 and 0x3f
            return when (errorClass) {
                0 -> if (statusCode == 0x02) Message.STATUS_COMPLETE else Message.STATUS_PENDING
                2 -> Message.STATUS_PENDING
                3 -> Message.STATUS_FAILED
                else -> Message.STATUS_PENDING
            }
        }

        return when {
            rawStatus == 0 -> Message.STATUS_COMPLETE
            rawStatus > 0 -> Message.STATUS_FAILED
            else -> Message.STATUS_NONE
        }
    }
}
