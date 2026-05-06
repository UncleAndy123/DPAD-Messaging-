package com.dpad.messaging.receivers

import android.content.BroadcastReceiver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import com.dpad.messaging.helpers.MmsDownloader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Receives WAP_PUSH_DELIVER for incoming MMS messages.
 *
 * On many budget/MTK ROMs the system does NOT auto-download MMS content, and
 * [android.telephony.SmsManager.downloadMultimediaMessage] consistently returns
 * MMS_ERROR_IO_ERROR (code 5), so we bypass it entirely.
 *
 * Flow:
 *  1. Extract the content-location URL from the M-Notification-Ind WAP PDU.
 *  2. Pre-insert a placeholder row into content://mms so the thread appears
 *     immediately in the UI.
 *  3. Kick off [MmsDownloader.download] on a background coroutine (via goAsync),
 *     which requests the MMS cellular network, fetches the PDU via HTTP GET,
 *     parses it, and stores the result in the content provider.
 */
class MmsReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action   = intent.action ?: "(null)"
        val mimeType = intent.type   ?: "(null)"
        val pduData  = intent.getByteArrayExtra("data")
        val subId    = intent.getIntExtra("subscription", -1)

        Log.d(TAG, "onReceive() action=$action mimeType=$mimeType pduSize=${pduData?.size ?: 0} subId=$subId")

        if (pduData == null) {
            Log.e(TAG, "no PDU data in intent — ignoring")
            return
        }

        // Parse the content-location URL from the M-Notification-Ind PDU.
        val contentLocation = extractContentLocation(pduData)
        Log.d(TAG, "contentLocation=$contentLocation")

        if (contentLocation == null) {
            Log.e(TAG, "could not parse content-location from PDU — cannot download")
            return
        }

        // Pre-insert a placeholder row so the thread appears in the UI while
        // the download is in progress. Must use the same valid field set as
        // MmsSender.insertMmsRow() — in particular 'v' and 'ct_t' are required;
        // 'content_location' is NOT a real pdu column and must NOT be included.
        val cv = ContentValues().apply {
            put("msg_box",   4)     // outbox / pending-download
            put("m_type",    130)   // M-Notification-Ind
            put("v",         18)    // MMS version 1.2
            put("date",      System.currentTimeMillis() / 1000L)
            put("read",      0)
            put("seen",      0)
            put("thread_id", 0)
            put("ct_t",      "application/vnd.wap.multipart.mixed")
        }
        val rowUri: Uri? = try {
            context.contentResolver.insert(Uri.parse("content://mms"), cv)
        } catch (e: Exception) {
            Log.e(TAG, "failed to insert placeholder row", e)
            null
        }
        if (rowUri == null) {
            Log.e(TAG, "contentResolver.insert returned null — aborting")
            return
        }
        val msgId = rowUri.lastPathSegment?.toLongOrNull() ?: -1L
        Log.d(TAG, "pre-inserted placeholder row -> $rowUri (msgId=$msgId)")

        // goAsync() extends the BroadcastReceiver deadline so the coroutine can
        // run the full MMS network request (~30 s) without ANR.
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                MmsDownloader.download(context, contentLocation, subId, msgId)
            } finally {
                pendingResult.finish()
            }
        }
    }

    // ── M-Notification-Ind PDU parsing ───────────────────────────────────────

    /**
     * Extracts the X-Mms-Content-Location URL from an M-Notification-Ind binary PDU.
     *
     * Field code for Content-Location is 0x83. Its value is a null-terminated
     * ASCII string (typically an HTTP URL). We scan for 0x83 followed by bytes
     * that start with "http" — simple and reliable for all carrier implementations.
     */
    private fun extractContentLocation(pdu: ByteArray): String? {
        var i = 0
        while (i < pdu.size) {
            val b = pdu[i].toInt() and 0xFF
            if (b == 0x83 && i + 1 < pdu.size) {
                // Check whether the byte following 0x83 starts a text-string value.
                // Some encoders prefix the string with a 0x00 or a length; handle
                // both: "http..." directly or a quoted-string with 0x22 prefix.
                var start = i + 1
                if (start < pdu.size && (pdu[start].toInt() and 0xFF) == 0x22) start++ // skip leading quote

                if (start + 4 < pdu.size &&
                    pdu[start].toChar().lowercaseChar()     == 'h' &&
                    pdu[start + 1].toChar().lowercaseChar() == 't' &&
                    pdu[start + 2].toChar().lowercaseChar() == 't' &&
                    pdu[start + 3].toChar().lowercaseChar() == 'p'
                ) {
                    val sb = StringBuilder()
                    var j = start
                    while (j < pdu.size && pdu[j] != 0.toByte()) sb.append(pdu[j++].toChar())
                    val url = sb.toString().trim()
                    if (url.isNotEmpty()) return url
                }
            }
            i++
        }
        return null
    }

    companion object {
        private const val TAG = "DPAD_MSG/MmsReceiver"
    }
}
