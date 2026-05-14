package com.dpad.messaging.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.RemoteInput
import com.dpad.messaging.events.RefreshConversations
import com.dpad.messaging.events.RefreshMessages
import com.dpad.messaging.helpers.MessageSenders
import com.dpad.messaging.helpers.NotificationHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.greenrobot.eventbus.EventBus

/**
 * Handles the "Reply" notification action (inline RemoteInput reply).
 *
 * Extracts the reply text, sends it via SmsSender, cancels the notification,
 * and fires refresh events so the thread updates.
 */
class DirectReplyReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val results = RemoteInput.getResultsFromIntent(intent) ?: return
        val replyText = results.getCharSequence(NotificationHelper.REPLY_KEY)
            ?.toString()?.trim() ?: return
        if (replyText.isBlank()) return

        val threadId    = intent.getLongExtra(MarkAsReadReceiver.EXTRA_THREAD_ID, -1L)
        val notifId     = intent.getIntExtra(MarkAsReadReceiver.EXTRA_NOTIFICATION_ID, -1)
        val phoneNumber = intent.getStringExtra(NotificationHelper.EXTRA_PHONE_NUMBER) ?: return

        if (threadId == -1L) return

        // goAsync() keeps the receiver alive while the coroutine runs.
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                MessageSenders.unified.sendSms(
                    context = context,
                    phoneNumber = phoneNumber,
                    body = replyText,
                    threadId = threadId
                )
                EventBus.getDefault().post(RefreshMessages(threadId))
                EventBus.getDefault().post(RefreshConversations())
            } finally {
                if (notifId != -1) NotificationHelper.cancelNotification(context, notifId)
                pendingResult.finish()
            }
        }
    }
}
