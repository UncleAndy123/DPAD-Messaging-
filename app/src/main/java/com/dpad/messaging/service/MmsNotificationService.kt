package com.dpad.messaging.service

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ContentUris
import android.database.ContentObserver
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.provider.Telephony
import android.telephony.SmsManager
import com.dpad.messaging.util.SmsHelper
import android.util.Log
import androidx.core.app.NotificationCompat
import com.dpad.messaging.MainActivity
import com.dpad.messaging.R
import com.dpad.messaging.data.db.AppDatabase
import com.dpad.messaging.receiver.SmsDeliverReceiver
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Started by MmsPushReceiver on WAP_PUSH_DELIVER.
 *
 * Waits for a new unread MMS inbox row to appear in the Telephony provider
 * (up to WAIT_TIMEOUT_MS), then checks blocked/muted and posts a notification.
 *
 * Using a Service avoids the 10-second broadcast timeout that caused the ANR
 * when this logic lived directly in the BroadcastReceiver.
 */
class MmsNotificationService : Service() {

    companion object {
        private const val TAG = "MmsNotificationService"
        const val EXTRA_AFTER_SECS = "after_secs"
        private const val WAIT_TIMEOUT_MS = 30_000L
    }

    private val job = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.IO + job)

    // HandlerThread for the ContentObserver (needs a looper)
    private val handlerThread = HandlerThread("MmsObserver").also { it.start() }
    private val observerHandler = Handler(handlerThread.looper)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val afterSecs = intent?.getLongExtra(EXTRA_AFTER_SECS, 0L) ?: 0L
        Log.d(TAG, "onStartCommand afterSecs=$afterSecs")

        scope.launch {
            try {
                val info = waitForInboxRow(afterSecs)
                if (info == null) {
                    Log.w(TAG, "Timed out waiting for MMS row")
                } else {
                    handleNewMms(info)
                }
            } catch (e: CancellationException) {
                // Coroutine was cancelled (service stopped by system). Not an error.
                Log.d(TAG, "MmsNotificationService coroutine cancelled")
            } catch (e: Exception) {
                Log.e(TAG, "Error in MmsNotificationService", e)
            } finally {
                stopSelf(startId)
            }
        }

        return START_NOT_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
        handlerThread.quitSafely()
    }

    // ── Wait for row ──────────────────────────────────────────────────────────

    private data class MmsInfo(
        val mmsId: Long,
        val threadId: Long,
        val address: String,
        val snippet: String
    )

    private suspend fun waitForInboxRow(afterSecs: Long): MmsInfo? {
        // Determine the current maximum MMS _id so we can detect newly-inserted rows.
        val currentMaxId = try {
            contentResolver.query(
                Telephony.Mms.CONTENT_URI,
                arrayOf(Telephony.Mms._ID),
                null, null,
                "${Telephony.Mms._ID} DESC"
            )?.use { c -> if (c.moveToFirst()) c.getLong(0) else 0L } ?: 0L
        } catch (e: Exception) {
            Log.w(TAG, "Failed to read current max MMS id", e)
            0L
        }

        // Trigger HTTP download for any notification stubs that are waiting.
        // The default SMS app is responsible for calling downloadMultimediaMessage();
        // the system inserts a notification stub (with ct_l = download URL) but does
        // NOT download automatically — the app must initiate it.
        triggerPendingDownloads()

        // Check immediately — download may already be done (e.g. stock app was previously default).
        queryNewInboxRow(afterSecs, currentMaxId)?.let { return it }

        return withTimeoutOrNull(WAIT_TIMEOUT_MS) {
            suspendCancellableCoroutine { cont ->
                var observer: ContentObserver? = null
                observer = object : ContentObserver(observerHandler) {
                    override fun onChange(selfChange: Boolean) {
                        val info = queryNewInboxRow(afterSecs, currentMaxId)
                        if (info != null) {
                            applicationContext.contentResolver
                                .unregisterContentObserver(this)
                            if (cont.isActive) cont.resume(info) {}
                        }
                    }
                }
                cont.invokeOnCancellation {
                    observer?.let {
                        applicationContext.contentResolver.unregisterContentObserver(it)
                    }
                }
                applicationContext.contentResolver.registerContentObserver(
                    // Watch the full MMS URI tree — download completion notifies content://mms
                    // (not just content://mms/inbox), so a narrow inbox observer misses it.
                    Telephony.Mms.CONTENT_URI, true, observer!!
                )
            }
        }
    }

    /**
     * Query for a newly-inserted inbox row. If maxSeenId is non-zero, require the
     * found row to have _id > maxSeenId so we only return truly new rows.
     */
    private fun queryNewInboxRow(afterSecs: Long, maxSeenId: Long): MmsInfo? {
        val thresholdSecs = (afterSecs - 60).coerceAtLeast(0)
        Log.d(TAG, "queryNewInboxRow afterSecs=$afterSecs thresholdSecs=$thresholdSecs maxSeenId=$maxSeenId")
        val cursor = try {
            contentResolver.query(
                Telephony.Mms.Inbox.CONTENT_URI,
                arrayOf(Telephony.Mms._ID, Telephony.Mms.THREAD_ID, Telephony.Mms.DATE, Telephony.Mms.READ),
                null,
                null,
                "${Telephony.Mms.DATE} DESC"
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to query MMS inbox", e)
            null
        } ?: return null

        Log.d(TAG, "queryNewInboxRow cursor count=${cursor.count}")
        cursor.use { c ->
            if (!c.moveToFirst()) return null
            val id = c.getLong(c.getColumnIndexOrThrow(Telephony.Mms._ID))
            val tid = c.getLong(c.getColumnIndexOrThrow(Telephony.Mms.THREAD_ID))
            val date = c.getLong(c.getColumnIndexOrThrow(Telephony.Mms.DATE))
            val read = c.getInt(c.getColumnIndexOrThrow(Telephony.Mms.READ))
            Log.d(TAG, "top inbox row id=$id tid=$tid date=$date read=$read")
            // Accept if it's newer by id, or if the DATE is recent enough
            if (maxSeenId > 0L) {
                // New row if id increased, or if the top row equals the previous max
                // but has a recent DATE (within the threshold). The latter handles
                // races where the download completed and updated/inserted a row that
                // doesn't have a strictly higher _id than the snapshot.
                if (id > maxSeenId || (id == maxSeenId && date >= thresholdSecs)) {
                    val address = getMmsAddress(id)
                    val snippet = getMmsTextSnippet(id)
                    return MmsInfo(id, tid, address, snippet)
                }
                return null
            } else {
                if (date >= thresholdSecs) {
                    val address = getMmsAddress(id)
                    val snippet = getMmsTextSnippet(id)
                    return MmsInfo(id, tid, address, snippet)
                }
                return null
            }
        }
    }

    // ── Download trigger ──────────────────────────────────────────────────────

    /**
     * Find any MMS notification stubs (rows where content_location / ct_l is set)
     * and call SmsManager.downloadMultimediaMessage() for each one.
     *
     * The system inserts a stub when a WAP push arrives but does NOT download
     * the content automatically — the default SMS app must call this API.
     * Without it, the stub stays in the provider with no body or parts.
     */
    @SuppressLint("MissingPermission")
    private fun triggerPendingDownloads() {
        val cursor = try {
            contentResolver.query(
                Telephony.Mms.CONTENT_URI,
                arrayOf(Telephony.Mms._ID, Telephony.Mms.CONTENT_LOCATION),
                "${Telephony.Mms.CONTENT_LOCATION} IS NOT NULL",
                null,
                "${Telephony.Mms._ID} DESC"
            )
        } catch (e: Exception) {
            Log.e(TAG, "triggerPendingDownloads: query failed", e)
            return
        } ?: return

        cursor.use { c ->
            while (c.moveToNext()) {
                val id = c.getLong(0)
                val location = c.getString(1) ?: continue
                if (location.isBlank()) continue
                Log.e(TAG, "triggerPendingDownloads: stub id=$id url=$location")
                val stubUri = ContentUris.withAppendedId(Telephony.Mms.CONTENT_URI, id)
                try {
                    @Suppress("DEPRECATION")
                    val smsManager = SmsHelper.getSmsManager(applicationContext)
                    val usedSubId = SmsHelper.getDefaultSmsSubId(applicationContext)
                    Log.d(TAG, "triggerPendingDownloads: using subId=$usedSubId manager=$smsManager for stub id=$id")
                    smsManager.downloadMultimediaMessage(
                        applicationContext,
                        location,
                        stubUri,
                        null,  // no config overrides
                        null   // no callback intent — ContentObserver handles completion
                    )
                    Log.d(TAG, "triggerPendingDownloads: download triggered for stub id=$id")
                } catch (e: Exception) {
                    Log.e(TAG, "triggerPendingDownloads: failed for stub id=$id", e)
                }
            }
        }
    }

    // ── Handle new MMS ────────────────────────────────────────────────────────

    private fun handleNewMms(info: MmsInfo) {
        val db = AppDatabase.getDatabase(this)
        val meta = try {
            db.threadMetadataDao().getMetadataBlocking(info.threadId)
        } catch (_: Exception) { null }

        if (meta?.isBlocked == true) {
            Log.d(TAG, "Sender blocked — deleting MMS ${info.mmsId}")
            try {
                contentResolver.delete(
                    Uri.withAppendedPath(Telephony.Mms.CONTENT_URI, info.mmsId.toString()),
                    null, null
                )
            } catch (_: Exception) {}
            return
        }

        if (meta?.isMuted == true) {
            Log.d(TAG, "Thread ${info.threadId} muted — suppressing notification")
            return
        }

        postNotification(info)
    }

    // ── Provider helpers ──────────────────────────────────────────────────────

    private fun getMmsAddress(mmsId: Long): String {
        return try {
            contentResolver.query(
                Uri.parse("content://mms/$mmsId/addr"),
                arrayOf("address"),
                "type = 137", // 0x89 = PduHeaders.FROM
                null, null
            )?.use { c -> if (c.moveToFirst()) c.getString(0) ?: "" else "" } ?: ""
        } catch (_: Exception) { "" }
    }

    private fun getMmsTextSnippet(mmsId: Long): String {
        return try {
            contentResolver.query(
                Uri.parse("content://mms/$mmsId/part"),
                arrayOf("ct", "text"),
                "ct = 'text/plain'",
                null, null
            )?.use { c -> if (c.moveToFirst()) c.getString(1) ?: "" else "" } ?: ""
        } catch (_: Exception) { "" }
    }

    // ── Notification ──────────────────────────────────────────────────────────

    private fun postNotification(info: MmsInfo) {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(
                SmsDeliverReceiver.CHANNEL_ID,
                SmsDeliverReceiver.CHANNEL_NAME,
                NotificationManager.IMPORTANCE_HIGH
            ).apply { description = "Notifications for incoming messages" }
        )

        val tapIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("threadId", info.threadId)
            putExtra("address", info.address)
        }
        val pi = PendingIntent.getActivity(
            this, info.threadId.toInt(), tapIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val title = info.address.ifBlank { "MMS" }
        val text = info.snippet.ifBlank { "\uD83D\uDCF7 Photo" }

        nm.notify(
            info.threadId.toInt(),
            NotificationCompat.Builder(this, SmsDeliverReceiver.CHANNEL_ID)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .setContentIntent(pi)
                .build()
        )
        Log.d(TAG, "Notification posted for thread ${info.threadId}")
    }
}
