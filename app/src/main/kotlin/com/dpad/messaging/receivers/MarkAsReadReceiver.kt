package com.dpad.messaging.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.dpad.messaging.App
import com.dpad.messaging.events.RefreshConversations
import com.dpad.messaging.extensions.markThreadAsReadInTelephony
import com.dpad.messaging.helpers.NotificationHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.greenrobot.eventbus.EventBus

/**
 * Handles the "Mark as Read" notification action.
 *
 * Marks the thread read in both Room and the Telephony CP, cancels the
 * notification, and fires a refresh event.
 */
class MarkAsReadReceiver : BroadcastReceiver() {

    companion object {
        const val EXTRA_THREAD_ID       = "extra_thread_id"
        const val EXTRA_NOTIFICATION_ID = "extra_notification_id"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val threadId = intent.getLongExtra(EXTRA_THREAD_ID, -1L)
        val notifId  = intent.getIntExtra(EXTRA_NOTIFICATION_ID, -1)
        if (threadId == -1L) return

        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                App.get().database.messagesDao().markThreadRead(threadId)
                App.get().database.conversationsDao().markAsRead(threadId)
                context.markThreadAsReadInTelephony(threadId)
                EventBus.getDefault().post(RefreshConversations())
            } finally {
                if (notifId != -1) NotificationHelper.cancelNotification(context, notifId)
                pendingResult.finish()
            }
        }
    }
}
