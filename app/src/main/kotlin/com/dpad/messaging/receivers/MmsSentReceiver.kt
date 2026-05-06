package com.dpad.messaging.receivers

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.util.Log
import android.net.Uri
import com.dpad.messaging.events.RefreshConversations
import com.dpad.messaging.events.RefreshMessages
import com.dpad.messaging.helpers.MmsSender
import org.greenrobot.eventbus.EventBus

/**
 * Receives the result PendingIntent fired by SmsManager.sendMultimediaMessage().
 *
 * On success (resultCode = RESULT_OK = -1): advances the MMS row from outbox (4) → sent (2).
 * On failure: leaves the row in outbox so the UI can show it as pending/failed.
 * Either way, EventBus events are posted so the UI refreshes.
 */
class MmsSentReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val mmsId    = intent.getLongExtra(MmsSender.EXTRA_MMS_ID, -1L)
        val threadId = intent.getLongExtra(MmsSender.EXTRA_THREAD_ID, -1L)
        val hasImage = intent.getBooleanExtra("extra_has_image", false)
        val librarySender = intent.getBooleanExtra("extra_library_sender", false)
        val contentUri = intent.getStringExtra("content_uri")
        val extrasSummary = intent.extras?.keySet()?.sorted()?.joinToString() ?: "<none>"

        // RESULT_OK = -1, so resultCode=-1 means SUCCESS
        val isSuccess = resultCode == Activity.RESULT_OK
        Log.d(
            "DPAD_MSG",
            "MmsSentReceiver.onReceive() mmsId=$mmsId threadId=$threadId hasImage=$hasImage librarySender=$librarySender resultCode=$resultCode isSuccess=$isSuccess extras=$extrasSummary"
        )

        if (mmsId > 0) {
            val newBox = if (isSuccess) {
                MmsSender.MSG_BOX_SENT
            } else {
                // Leave in MSG_BOX_OUTBOX — appears as pending/unsent to the UI
                MmsSender.MSG_BOX_OUTBOX
            }
            Log.d("DPAD_MSG", "MmsSentReceiver: updating mmsId=$mmsId to msg_box=$newBox (${if (isSuccess) "SUCCESS" else "FAILED"})")
            MmsSender.updateMmsStatus(context, mmsId, newBox)
        } else if (!contentUri.isNullOrBlank()) {
            val newBox = if (isSuccess) {
                MmsSender.MSG_BOX_SENT
            } else {
                MmsSender.MSG_BOX_OUTBOX
            }
            try {
                context.contentResolver.update(
                    Uri.parse(contentUri),
                    ContentValues().apply { put("msg_box", newBox) },
                    null,
                    null
                )
                Log.d("DPAD_MSG", "MmsSentReceiver: updated content_uri=$contentUri to msg_box=$newBox")
            } catch (e: Exception) {
                Log.w("DPAD_MSG", "MmsSentReceiver: failed to update content_uri=$contentUri", e)
            }
        } else if (librarySender) {
            Log.d("DPAD_MSG", "MmsSentReceiver: mmslib handled provider persistence for threadId=$threadId")
        }

        EventBus.getDefault().post(RefreshConversations())
        if (threadId > 0) EventBus.getDefault().post(RefreshMessages(threadId))
    }
}
