package com.dpad.messaging.helpers

import android.content.ContentValues
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.Uri
import android.os.Build
import android.provider.Telephony
import android.util.Log
import java.net.InetSocketAddress
import java.net.Proxy
import java.io.IOException
import com.dpad.messaging.App
import com.dpad.messaging.BuildConfig
import com.dpad.messaging.events.RefreshConversations
import com.dpad.messaging.events.RefreshMessages
import com.dpad.messaging.extensions.getOwnPhoneNumbers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import org.greenrobot.eventbus.EventBus
import java.net.HttpURLConnection
import java.net.URL
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Downloads an MMS M-Retrieve-Conf PDU over the carrier's MMS APN,
 * parses it, and stores the result into the system Telephony MMS content provider.
 *
 * This bypasses [android.telephony.SmsManager.downloadMultimediaMessage] entirely
 * because several MTK/budget ROMs return MMS_ERROR_IO_ERROR (code 5) for every
 * download attempt even when the app is the default SMS handler.
 *
 * Flow:
 *   1. Request NET_CAPABILITY_MMS cellular network via ConnectivityManager.
 *   2. HTTP GET the content-location URL over that specific network.
 *   3. Parse the M-Retrieve-Conf PDU with [MmsPduParser].
 *   4. Update the pre-inserted placeholder row in content://mms.
 *   5. Insert text/plain part, image parts, and FROM/TO addr rows.
 *   6. Resolve thread_id, post EventBus events, show notification.
 *
 * Must be called from a coroutine on a background dispatcher (e.g. Dispatchers.IO).
 */
object MmsDownloader {

    private const val TAG = "DPAD_MSG"
    private const val NETWORK_TIMEOUT_MS  = 30_000L
    private const val HTTP_CONNECT_TIMEOUT = 30_000   // ms
    private const val HTTP_READ_TIMEOUT    = 30_000   // ms

    // PduHeaders constants
    private const val ADDR_TYPE_FROM = 137
    private const val ADDR_TYPE_TO   = 151

    private inline fun d(message: () -> String) {
        if (BuildConfig.DEBUG) Log.d(TAG, message())
    }

    private inline fun w(message: () -> String) {
        if (BuildConfig.DEBUG) Log.w(TAG, message())
    }

    private inline fun w(message: () -> String, t: Throwable) {
        if (BuildConfig.DEBUG) Log.w(TAG, message(), t)
    }

    private inline fun e(message: () -> String, t: Throwable? = null) {
        if (BuildConfig.DEBUG) {
            if (t != null) Log.e(TAG, message(), t) else Log.e(TAG, message())
        }
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Download and store the MMS identified by [contentLocation].
     * [msgId] is the _id of the placeholder row pre-inserted by [MmsReceiver].
     * On any failure the placeholder row is deleted and RefreshConversations is posted.
     */
    suspend fun download(
        context: Context,
        contentLocation: String,
        @Suppress("UNUSED_PARAMETER") subId: Int,
        msgId: Long
    ) {
        d { "MmsDownloader.download() url=$contentLocation msgId=$msgId" }

        try {
            // 1. Acquire MMS network
            val network = acquireMmsNetwork(context)
                ?: throw Exception("Could not acquire MMS cellular network within ${NETWORK_TIMEOUT_MS}ms")
            d { "MmsDownloader: acquired MMS network $network" }

            // 2. Download PDU
            val pduBytes = downloadPdu(context, network, contentLocation)
            d { "MmsDownloader: PDU size=${pduBytes.size}" }

            // 3. Parse PDU
            val parsed = MmsPduParser.parse(pduBytes)
                ?: throw Exception("MmsPduParser returned null — malformed PDU?")
            d { "MmsDownloader: from='${parsed.from}' subject='${parsed.subject}' textLen=${parsed.textBody.length} images=${parsed.imageParts.size}" }

            // 4–6. Store and notify
            storeMms(context, msgId, parsed)

        } catch (e: Exception) {
            e({ "MmsDownloader.download() failed - deleting placeholder msgId=$msgId" }, e)
            deletePlaceholder(context, msgId)
            EventBus.getDefault().post(RefreshConversations())
        }
    }

    // ── Network acquisition ───────────────────────────────────────────────────

    /**
     * Requests a cellular network with NET_CAPABILITY_MMS and suspends until
     * it becomes available or [NETWORK_TIMEOUT_MS] elapses.
     * Returns null on timeout; throws on unavailable.
     */
    private suspend fun acquireMmsNetwork(context: Context): Network? {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

        return withTimeoutOrNull(NETWORK_TIMEOUT_MS) {
            suspendCancellableCoroutine { cont ->
                val request = NetworkRequest.Builder()
                    .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_MMS)
                    .build()

                val callback = object : ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: Network) {
                        d { "MmsDownloader: onAvailable $network" }
                        if (cont.isActive) cont.resume(network)
                    }
                    override fun onUnavailable() {
                        w { "MmsDownloader: onUnavailable" }
                        if (cont.isActive) cont.resumeWithException(
                            Exception("MMS network unavailable — carrier may not support MMS APN")
                        )
                    }
                }

                cont.invokeOnCancellation {
                    try { cm.unregisterNetworkCallback(callback) } catch (_: Exception) {}
                }

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    cm.requestNetwork(request, callback, NETWORK_TIMEOUT_MS.toInt())
                } else {
                    @Suppress("DEPRECATION")
                    cm.requestNetwork(request, callback)
                }
            }
        }
    }

    // ── PDU download ──────────────────────────────────────────────────────────

    private fun downloadPdu(context: Context, network: Network?, urlString: String): ByteArray {
        // Best-effort: read APN MMS proxy from Telephony.Carriers (may fail on some ROMs)
        var proxyHost: String? = null
        var proxyPort: Int? = null
        try {
            val projection = arrayOf(
                Telephony.Carriers.MMSPROXY,
                Telephony.Carriers.MMSPORT,
                Telephony.Carriers.MMSC,
                Telephony.Carriers.TYPE
            )
            val cursor = context.contentResolver.query(Telephony.Carriers.CONTENT_URI, projection, null, null, null)
            cursor?.use {
                while (it.moveToNext()) {
                    val p = it.getString(0)
                    val portStr = it.getString(1)
                    val mmsc = it.getString(2)
                    val type = it.getString(3)
                    if (!p.isNullOrEmpty()) {
                        proxyHost = p
                        proxyPort = try { portStr?.toInt() ?: -1 } catch (e: NumberFormatException) { -1 }
                        break
                    }
                    // Otherwise continue scanning for a reasonable row
                    if (!mmsc.isNullOrEmpty() && proxyHost == null) {
                        // Keep looking — prefer explicit proxy if present
                    }
                }
            }
        } catch (e: SecurityException) {
            w({ "No permission to read APN settings for proxy detection" }, e)
        } catch (e: Exception) {
            w({ "Failed to read APN settings for proxy detection" }, e)
        }

        if (proxyHost.isNullOrBlank()) {
            val prefHost = Prefs.get().mmsProxyHost.takeIf { it.isNotBlank() }
            val prefPort = Prefs.get().mmsProxyPort.takeIf { it > 0 }
            if (!prefHost.isNullOrBlank()) {
                proxyHost = prefHost
                proxyPort = prefPort ?: 80
                d { "MmsDownloader: using configured MMS proxy $proxyHost:$proxyPort" }
            }
        }

        var connection: HttpURLConnection? = null
        var bound = false
        try {
            // If we have a proxy, prefer using it and bind the process to the MMS network
            if (!proxyHost.isNullOrEmpty()) {
                // Temporarily bind process to the acquired network so that socket traffic uses MMS APN
                if (network != null) {
                    try {
                        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                            cm.bindProcessToNetwork(network)
                        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                            ConnectivityManager.setProcessDefaultNetwork(network)
                        }
                        bound = true
                    } catch (e: Exception) {
                        w({ "Failed to bind process to MMS network" }, e)
                    }
                }

                val url = URL(urlString)
                val p = Proxy(Proxy.Type.HTTP, InetSocketAddress(proxyHost, proxyPort ?: 0))
                connection = url.openConnection(p) as HttpURLConnection
            } else {
                // No proxy discovered — use Network.openConnection when possible
                val url = URL(urlString)
                connection = if (network != null) {
                    network.openConnection(url) as HttpURLConnection
                } else {
                    url.openConnection() as HttpURLConnection
                }
            }

            connection.requestMethod  = "GET"
            connection.connectTimeout = HTTP_CONNECT_TIMEOUT
            connection.readTimeout    = HTTP_READ_TIMEOUT
            connection.instanceFollowRedirects = true
            connection.setRequestProperty("User-Agent", "Android-Mms/2.0")
            connection.setRequestProperty("x-wap-profile", "http://www.google.com/oha/rdf/ua-profile-20080331.xml")
            connection.setRequestProperty("Accept", "*/*, application/vnd.wap.mms-message, application/vnd.wap.sic")
            connection.setRequestProperty("Accept-Encoding", "gzip")
            connection.setRequestProperty("Accept-Language", "en-US")
            connection.setRequestProperty("Cache-Control", "no-cache")
            connection.connect()

            val code = connection.responseCode
            d { "MmsDownloader: HTTP $code from $urlString" }
            if (code != HttpURLConnection.HTTP_OK) {
                throw Exception("MMSC returned HTTP $code for $urlString")
            }
            return connection.inputStream.readBytes()
        } finally {
            try {
                connection?.disconnect()
            } catch (_: Exception) {}
            if (bound) {
                try {
                    val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        cm.bindProcessToNetwork(null)
                    } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                        ConnectivityManager.setProcessDefaultNetwork(null)
                    }
                } catch (_: Exception) {}
            }
        }
    }

    // ── MMS content provider storage ─────────────────────────────────────────

    private suspend fun storeMms(context: Context, msgId: Long, parsed: MmsPduParser.ParsedMms) {
        val cr   = context.contentResolver
        val from = parsed.from

        // Build the full participant set: sender + all TO/CC recipients.
        // For a 1:1 MMS this is just {from}; for group MMS it includes all addresses.
        // We also strip the own-device "insert-address-token" placeholder and own numbers
        // from the TO list so we don't accidentally create a new thread.
        val ownNumbers = context.getOwnPhoneNumbers()
        val allParticipants: Set<String> = buildSet {
            if (from.isNotBlank()) add(from)
            parsed.toAddresses
                .filter { addr ->
                    if (addr.isBlank() || addr == "insert-address-token") return@filter false
                    val digits = addr.filter { it.isDigit() }
                    digits !in ownNumbers && digits.takeLast(10) !in ownNumbers
                }
                .forEach { add(it) }
        }
        d { "MmsDownloader.storeMms() msgId=$msgId participants=$allParticipants" }

        // Resolve (or create) the thread ID for this conversation.
        val threadId: Long = if (allParticipants.isNotEmpty()) {
            try {
                if (allParticipants.size == 1) {
                    Telephony.Threads.getOrCreateThreadId(context, allParticipants.first())
                } else {
                    Telephony.Threads.getOrCreateThreadId(context, allParticipants)
                }
            } catch (e: Exception) {
                w({ "MmsDownloader: getOrCreateThreadId failed for $allParticipants" }, e)
                0L
            }
        } else 0L

        d { "MmsDownloader.storeMms() threadId=$threadId" }

        // ── Update the placeholder pdu row ────────────────────────────────────
        val pduCv = ContentValues().apply {
            put("msg_box",   1)       // Telephony.Mms.MESSAGE_BOX_INBOX
            put("m_type",    132)     // M-Retrieve-Conf
            put("read",      0)
            put("seen",      0)
            put("thread_id", threadId)
            if (parsed.subject.isNotBlank()) put("sub", parsed.subject)
        }
        try {
            val updated = cr.update(Uri.parse("content://mms/$msgId"), pduCv, null, null)
            d { "MmsDownloader: updated pdu row msgId=$msgId rows=$updated" }
        } catch (e: Exception) {
            e({ "MmsDownloader: failed to update pdu row" }, e)
        }

        // ── Insert text/plain part ─────────────────────────────────────────────
        if (parsed.textBody.isNotBlank()) {
            val textCv = ContentValues().apply {
                put("mid",   msgId)
                put("ct",    "text/plain")
                put("chset", 106)          // UTF-8
                put("text",  parsed.textBody)
            }
            try {
                cr.insert(Uri.parse("content://mms/$msgId/part"), textCv)
                d { "MmsDownloader: inserted text part for msgId=$msgId" }
            } catch (e: Exception) {
                e({ "MmsDownloader: failed to insert text part" }, e)
            }
        }

        // ── Insert image parts ────────────────────────────────────────────────
        for ((index, imgPart) in parsed.imageParts.withIndex()) {
            val imgCv = ContentValues().apply {
                put("mid", msgId)
                put("ct",  imgPart.mimeType)
            }
            try {
                val partUri = cr.insert(Uri.parse("content://mms/$msgId/part"), imgCv)
                if (partUri != null) {
                    cr.openOutputStream(partUri)?.use { it.write(imgPart.data) }
                    d { "MmsDownloader: inserted image[$index] ${imgPart.mimeType} ${imgPart.data.size}B -> $partUri" }
                } else {
                    w { "MmsDownloader: insert returned null for image part[$index]" }
                }
            } catch (e: Exception) {
                e({ "MmsDownloader: failed to insert image part[$index]" }, e)
            }
        }

        // ── Insert FROM addr row ──────────────────────────────────────────────
        if (from.isNotBlank()) {
            val fromCv = ContentValues().apply {
                put("address", from)
                put("type",    ADDR_TYPE_FROM)
                put("charset", 106)
            }
            try {
                cr.insert(Uri.parse("content://mms/$msgId/addr"), fromCv)
            } catch (e: Exception) {
                e({ "MmsDownloader: failed to insert FROM addr" }, e)
            }
        }

        // ── Insert TO addr rows ───────────────────────────────────────────────
        // For a group MMS, insert a row for each recipient address.
        // For a 1:1 MMS (no TO addresses parsed), insert the generic placeholder
        // so the telephony provider still has a TO row for the message.
        val toAddresses = parsed.toAddresses.filter { it.isNotBlank() && it != "insert-address-token" }
        if (toAddresses.isNotEmpty()) {
            for (toAddr in toAddresses) {
                val toCv = ContentValues().apply {
                    put("address", toAddr)
                    put("type",    ADDR_TYPE_TO)
                    put("charset", 106)
                }
                try {
                    cr.insert(Uri.parse("content://mms/$msgId/addr"), toCv)
                } catch (e: Exception) {
                    w({ "MmsDownloader: failed to insert TO addr '$toAddr'" }, e)
                }
            }
        } else {
            // No explicit TO addresses parsed — use placeholder so the row exists.
            val toCv = ContentValues().apply {
                put("address", "insert-address-token")
                put("type",    ADDR_TYPE_TO)
                put("charset", 106)
            }
            try {
                cr.insert(Uri.parse("content://mms/$msgId/addr"), toCv)
            } catch (e: Exception) {
                w({ "MmsDownloader: failed to insert TO addr placeholder" }, e)
            }
        }

        // ── Block-list check, notification, EventBus ──────────────────────────
        val body    = MmsHelper.getMmsDisplayBody(context, msgId, parsed.subject)
        val keywords = App.get().database.blockedKeywordsDao().getAll()
        val blockedNumbers = App.get().database.blockedNumbersDao().getAll()
        val normalizedFromDigits = from.filter { it.isDigit() }

        val isBlockedByNumber = blockedNumbers.any { bn ->
            val ndigits = bn.number.filter { it.isDigit() }
            bn.number == from || ndigits == normalizedFromDigits
        }

        val isBlockedByKeyword = keywords.any { kw ->
            body.contains(kw.keyword, ignoreCase = true)
        }

        val isBlocked = isBlockedByNumber || isBlockedByKeyword

        if (!isBlocked && from.isNotBlank()) {
            val senderName = App.get().contactHelper.getDisplayName(from)
            NotificationHelper.showIncomingNotification(context, threadId, senderName, from, body)
        }

        EventBus.getDefault().post(RefreshConversations())
        if (threadId > 0L) {
            EventBus.getDefault().post(RefreshMessages(threadId))
        }

        d { "MmsDownloader.storeMms() done msgId=$msgId threadId=$threadId blocked=$isBlocked" }
    }

    // ── Cleanup ───────────────────────────────────────────────────────────────

    private fun deletePlaceholder(context: Context, msgId: Long) {
        if (msgId <= 0L) return
        try {
            context.contentResolver.delete(Uri.parse("content://mms/$msgId"), null, null)
            d { "MmsDownloader: deleted placeholder msgId=$msgId" }
        } catch (e: Exception) {
            w({ "MmsDownloader: could not delete placeholder msgId=$msgId" }, e)
        }
    }
}
