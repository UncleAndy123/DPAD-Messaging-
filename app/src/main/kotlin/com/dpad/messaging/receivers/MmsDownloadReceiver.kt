package com.dpad.messaging.receivers

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Telephony
import android.util.Log
import com.dpad.messaging.App
import com.dpad.messaging.events.RefreshConversations
import com.dpad.messaging.events.RefreshMessages
import com.dpad.messaging.helpers.MmsHelper
import com.dpad.messaging.helpers.NotificationHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.greenrobot.eventbus.EventBus

/**
 * Receives the result PendingIntent from SmsManager.downloadMultimediaMessage().
 *
 * On RESULT_OK the MMS content has been written to content://mms by the system.
 * We query for the new row, resolve the sender, show a notification, and fire
 * EventBus refresh events so ThreadActivity and MainActivity update.
 */
class MmsDownloadReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION       = "com.dpad.messaging.MMS_DOWNLOADED"
        const val EXTRA_MMS_ID = "extra_mms_id"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val result = resultCode
        val msgId  = intent.getLongExtra(EXTRA_MMS_ID, -1L)
        Log.d("DPAD_MSG", "MmsDownloadReceiver.onReceive() resultCode=$result msgId=$msgId")

        if (result != Activity.RESULT_OK) {
            Log.w("DPAD_MSG", "MmsDownloadReceiver: download failed with resultCode=$result")
            // Delete the placeholder row so it doesn't linger as a ghost entry.
            if (msgId > 0L) {
                try {
                    context.contentResolver.delete(Uri.parse("content://mms/$msgId"), null, null)
                    Log.d("DPAD_MSG", "MmsDownloadReceiver: deleted placeholder row msgId=$msgId")
                } catch (e: Exception) {
                    Log.w("DPAD_MSG", "MmsDownloadReceiver: could not delete placeholder row", e)
                }
            }
            EventBus.getDefault().post(RefreshConversations())
            return
        }

        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                processDownloadedMms(context, msgId)
            } finally {
                pendingResult.finish()
            }
        }
    }

    private suspend fun processDownloadedMms(context: Context, knownMsgId: Long) {
        // Give the system a moment to finish writing parts to the provider.
        delay(500)

        val mmsUri = Uri.parse("content://mms")
        val proj   = arrayOf("_id", "thread_id", "date", "sub", "m_type", "msg_box")

        // ── Diagnostic dump of all recent rows ───────────────────────────────
        val cutoffSecs = System.currentTimeMillis() / 1000L - 120L
        try {
            context.contentResolver.query(
                mmsUri, proj, "date > ?", arrayOf(cutoffSecs.toString()), "date DESC"
            )?.use { cursor ->
                Log.d("DPAD_MSG", "MmsDownloadReceiver: ${cursor.count} recent MMS row(s) in provider:")
                while (cursor.moveToNext()) {
                    val id    = cursor.getLong(cursor.getColumnIndexOrThrow("_id"))
                    val tid   = cursor.getLong(cursor.getColumnIndexOrThrow("thread_id"))
                    val mType = cursor.getInt(cursor.getColumnIndexOrThrow("m_type"))
                    val box   = cursor.getInt(cursor.getColumnIndexOrThrow("msg_box"))
                    val label = when (mType) {
                        130  -> "M-Notification-Ind"
                        132  -> "M-Retrieve-Conf(DOWNLOADED)"
                        else -> "type=$mType"
                    }
                    Log.d("DPAD_MSG", "  _id=$id thread_id=$tid msg_box=$box m_type=$label")
                }
            }
        } catch (e: Exception) {
            Log.e("DPAD_MSG", "MmsDownloadReceiver: diagnostic query failed", e)
        }

        // ── Query the specific row we pre-inserted (primary path) ────────────
        var msgId    = -1L
        var threadId = -1L
        var subject  = ""

        if (knownMsgId > 0L) {
            try {
                context.contentResolver.query(
                    Uri.parse("content://mms/$knownMsgId"),
                    proj, null, null, null
                )?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        msgId    = cursor.getLong(cursor.getColumnIndexOrThrow("_id"))
                        threadId = cursor.getLong(cursor.getColumnIndexOrThrow("thread_id"))
                        subject  = cursor.getString(cursor.getColumnIndexOrThrow("sub")) ?: ""
                        val mType = cursor.getInt(cursor.getColumnIndexOrThrow("m_type"))
                        val box   = cursor.getInt(cursor.getColumnIndexOrThrow("msg_box"))
                        Log.d("DPAD_MSG", "MmsDownloadReceiver: direct query msgId=$msgId mType=$mType msg_box=$box threadId=$threadId")

                        // Some MTK ROMs leave msg_box at 4 (outbox) after download; fix it.
                        if (box != 1) {
                            try {
                                val cv = ContentValues().apply { put("msg_box", 1) }
                                context.contentResolver.update(
                                    Uri.parse("content://mms/$msgId"), cv, null, null
                                )
                                Log.d("DPAD_MSG", "MmsDownloadReceiver: corrected msg_box $box -> 1")
                            } catch (e: Exception) {
                                Log.w("DPAD_MSG", "MmsDownloadReceiver: could not correct msg_box", e)
                            }
                        } else {
                            Log.d("DPAD_MSG", "MmsDownloadReceiver: msg_box already 1 — no update needed")
                        }
                    } else {
                        Log.w("DPAD_MSG", "MmsDownloadReceiver: direct query for msgId=$knownMsgId returned no rows")
                    }
                }
            } catch (e: Exception) {
                Log.e("DPAD_MSG", "MmsDownloadReceiver: direct row query failed", e)
            }
        }

        // ── Fallback: broad date-based scan ──────────────────────────────────
        if (msgId < 0L) {
            Log.w("DPAD_MSG", "MmsDownloadReceiver: direct query missed — falling back to date scan")
            try {
                context.contentResolver.query(
                    mmsUri, proj,
                    "(m_type = 132 OR m_type = 130) AND date > ?",
                    arrayOf(cutoffSecs.toString()),
                    "date DESC"
                )?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        msgId    = cursor.getLong(cursor.getColumnIndexOrThrow("_id"))
                        threadId = cursor.getLong(cursor.getColumnIndexOrThrow("thread_id"))
                        subject  = cursor.getString(cursor.getColumnIndexOrThrow("sub")) ?: ""
                        Log.d("DPAD_MSG", "MmsDownloadReceiver: fallback found msgId=$msgId threadId=$threadId")
                    }
                }
            } catch (e: Exception) {
                Log.e("DPAD_MSG", "MmsDownloadReceiver: fallback query failed", e)
            }
        }

        if (msgId < 0L) {
            Log.w("DPAD_MSG", "MmsDownloadReceiver: still no MMS row — posting RefreshConversations only")
            EventBus.getDefault().post(RefreshConversations())
            return
        }

        // Log parts for image-loading diagnostics
        try {
            context.contentResolver.query(
                Uri.parse("content://mms/$msgId/part"),
                arrayOf("_id", "ct", "cl"), null, null, null
            )?.use { c ->
                Log.d("DPAD_MSG", "MmsDownloadReceiver: parts for msgId=$msgId count=${c.count}")
                while (c.moveToNext()) {
                    Log.d("DPAD_MSG", "  part _id=${c.getLong(0)} ct='${c.getString(1)}' cl='${c.getString(2)}'")
                }
            }
        } catch (e: Exception) {
            Log.e("DPAD_MSG", "MmsDownloadReceiver: parts query failed", e)
        }

        val address = getMmsFromAddress(context, msgId)
        val body    = MmsHelper.getMmsDisplayBody(context, msgId, subject)
        Log.d("DPAD_MSG", "MmsDownloadReceiver: address='$address' body='${body.take(40)}'")

        // Correct thread_id if the ROM assigned it wrongly (same normalization as SmsReceiver)
        val resolvedThreadId = resolveThreadId(context, address, threadId)
        if (resolvedThreadId != threadId && resolvedThreadId > 0L) {
            try {
                val cv = ContentValues().apply { put(Telephony.Mms.THREAD_ID, resolvedThreadId) }
                context.contentResolver.update(
                    Uri.withAppendedPath(mmsUri, msgId.toString()), cv, null, null
                )
                Log.d("DPAD_MSG", "MmsDownloadReceiver: corrected thread_id $threadId -> $resolvedThreadId")
                threadId = resolvedThreadId
            } catch (e: Exception) {
                Log.w("DPAD_MSG", "MmsDownloadReceiver: could not correct thread_id", e)
            }
        }

        val keywords  = App.get().database.blockedKeywordsDao().getAll()
        val isBlocked = keywords.any { kw ->
            body.contains(kw.keyword, ignoreCase = true) || address == kw.keyword
        }

        if (!isBlocked && address.isNotBlank()) {
            val senderName = App.get().contactHelper.getDisplayName(address)
            NotificationHelper.showIncomingNotification(context, threadId, senderName, address, body)
        }

        EventBus.getDefault().post(RefreshConversations())
        if (threadId > 0L) {
            EventBus.getDefault().post(RefreshMessages(threadId))
        }
    }

    /** Reads the FROM address for an MMS message (PduHeaders.FROM = type 137). */
    private fun getMmsFromAddress(context: Context, msgId: Long): String {
        val addrUri = Uri.parse("content://mms/$msgId/addr")
        try {
            context.contentResolver.query(
                addrUri, arrayOf("address"), "type = ?", arrayOf("137"), null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val addr = cursor.getString(0)
                    if (!addr.isNullOrBlank() && addr != "insert-address-token") return addr
                }
            }
        } catch (_: Exception) {}
        return ""
    }

    /**
     * Attempts to find the correct thread_id for the given sender address.
     * Falls back to scanning the canonical-address table (same logic as SmsReceiver).
     */
    private fun resolveThreadId(context: Context, address: String, fallback: Long): Long {
        if (address.isBlank()) return fallback

        val digitsOnly = address.filter { it.isDigit() }
        val candidates = buildList {
            add(address)
            if (digitsOnly != address) add(digitsOnly)
            if (digitsOnly.startsWith("1") && digitsOnly.length > 10) add(digitsOnly.removePrefix("1"))
        }

        // Fast path: threadID URI
        for (cand in candidates) {
            try {
                val uri = Uri.withAppendedPath(Uri.parse("content://mms-sms/threadID"), Uri.encode(cand))
                context.contentResolver.query(uri, arrayOf("_id"), null, null, null)
                    ?.use { c -> if (c.moveToFirst()) return c.getLong(0) }
            } catch (_: Exception) {}
        }

        // Fallback: scan canonical-address table
        val normalizedSet = candidates.map { it.filter { ch -> ch.isDigit() } }.toSet()
        try {
            val convUri = Uri.parse("content://mms-sms/conversations?simple=true")
            context.contentResolver.query(
                convUri, arrayOf(Telephony.Threads._ID, Telephony.Threads.RECIPIENT_IDS),
                null, null, null
            )?.use { cursor ->
                while (cursor.moveToNext()) {
                    val tid = cursor.getLong(0)
                    val recipientIds = cursor.getString(1) ?: continue
                    recipientIds.trim().split(" ").mapNotNull { it.trim().toLongOrNull() }.forEach { cid ->
                        try {
                            context.contentResolver.query(
                                Uri.parse("content://mms-sms/canonical-address/$cid"),
                                arrayOf("address"), null, null, null
                            )?.use { c2 ->
                                if (c2.moveToFirst()) {
                                    val stored = c2.getString(0) ?: return@use
                                    if (stored.filter { it.isDigit() } in normalizedSet) return tid
                                }
                            }
                        } catch (_: Exception) {}
                    }
                }
            }
        } catch (_: Exception) {}

        return fallback
    }
}
