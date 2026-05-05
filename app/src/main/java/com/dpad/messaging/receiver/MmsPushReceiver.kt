package com.dpad.messaging.receiver

import android.content.BroadcastReceiver
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.telephony.SmsManager
import com.dpad.messaging.util.SmsHelper
import android.util.Log
import com.dpad.messaging.service.MmsNotificationService

/**
 * Receives WAP_PUSH_DELIVER alongside the library's PushReceiver.
 *
 * onReceive must return immediately — we use goAsync() and a short-lived
 * background worker to query the MMS provider for notification stubs and
 * trigger downloads via SmsManager.downloadMultimediaMessage(). This keeps
 * work off the main thread and avoids long-running broadcast handling.
 */
class MmsPushReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "MmsPushReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != "android.provider.Telephony.WAP_PUSH_DELIVER") return
        val mimeType = intent.type ?: ""
        if (!mimeType.contains("mms-message") && !mimeType.contains("wap.mms")) return

        Log.d(TAG, "WAP_PUSH_DELIVER received — starting async download trigger")

        // Keep the broadcast alive while we do a quick background query + trigger.
        val pendingResult = goAsync()

        Thread {
            try {
                val cr = context.contentResolver

                // Stubs may be inserted a few hundred ms after the WAP push; retry a
                // few times with a short sleep so we don't miss the provider row.
                val maxAttempts = 10
                val sleepMs = 200L
                var foundAny = false

                for (attempt in 1..maxAttempts) {
                    val cursor = try {
                        cr.query(
                            Telephony.Mms.CONTENT_URI,
                            arrayOf(Telephony.Mms._ID, Telephony.Mms.CONTENT_LOCATION),
                            "${Telephony.Mms.CONTENT_LOCATION} IS NOT NULL",
                            null,
                            "${Telephony.Mms._ID} DESC"
                        )
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to query MMS provider for stubs (attempt=$attempt)", e)
                        null
                    }

                    var anyThisRound = false
                    cursor?.use { c ->
                        while (c.moveToNext()) {
                            val id = c.getLong(0)
                            val location = c.getString(1) ?: continue
                            if (location.isBlank()) continue
                            anyThisRound = true
                            foundAny = true
                            val stubUri = ContentUris.withAppendedId(Telephony.Mms.CONTENT_URI, id)
                            try {
                                @Suppress("DEPRECATION")
                                val smsManager = SmsHelper.getSmsManager(context)
                                val usedSubId = SmsHelper.getDefaultSmsSubId(context)
                                Log.d(TAG, "download trigger: using subId=$usedSubId manager=$smsManager for stub id=$id (attempt=$attempt)")
                                smsManager.downloadMultimediaMessage(
                                    context,
                                    location,
                                    stubUri,
                                    null,
                                    null
                                )
                                Log.e(TAG, "download triggered for stub id=$id url=$location (attempt=$attempt)")
                            } catch (e: Exception) {
                                Log.e(TAG, "Failed to trigger download for stub id=$id (attempt=$attempt)", e)
                            }
                        }
                    }

                    if (anyThisRound) break
                    try {
                        Thread.sleep(sleepMs)
                    } catch (_: InterruptedException) {
                        break
                    }
                }

                if (!foundAny) {
                    Log.d(TAG, "No MMS notification stubs found after retries; will fallback to service wait")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error in MmsPushReceiver worker", e)
            } finally {
                // Start the service which waits for the fully-downloaded inbox row and posts a notification
                try {
                    val nowSecs = System.currentTimeMillis() / 1000L
                    val serviceIntent = Intent(context, MmsNotificationService::class.java).apply {
                        putExtra(MmsNotificationService.EXTRA_AFTER_SECS, nowSecs)
                    }
                    context.startService(serviceIntent)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to start MmsNotificationService", e)
                }
                pendingResult.finish()
            }
        }.start()
    }
}
