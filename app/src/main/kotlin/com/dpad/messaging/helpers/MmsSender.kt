package com.dpad.messaging.helpers

import android.app.PendingIntent
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.Telephony
import android.util.Log
import androidx.core.content.FileProvider
import com.klinker.android.send_message.Message as KlinkerMessage
import com.klinker.android.send_message.Settings as KlinkerSettings
import com.klinker.android.send_message.Transaction as KlinkerTransaction
import com.dpad.messaging.extensions.getOwnPhoneNumbers
import com.dpad.messaging.receivers.MmsSentReceiver
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

/**
 * Sends an MMS message and inserts it into the system MMS content provider so it
 * appears in the message thread immediately.
 *
 * Flow:
 *  1. (Optional) Compress the image to ≤ 800 px wide at 85% JPEG quality.
 *  2. Build a WAP binary M-Send-Req PDU via MmsPduComposer.
 *  3. Write the PDU to a cache file; obtain a FileProvider URI.
 *  4. Insert a placeholder row into content://mms (msg_box = 4 = outbox).
 *  5. Insert part rows into content://mms/{id}/part.
 *  6. Insert addr rows into content://mms/{id}/addr.
 *  7. System MmsService picks up the message from outbox and sends it.
 *
 * Must be called from a background thread — performs ContentProvider and file I/O.
 */
object MmsSender {

    private const val TAG = "DPAD_MSG"

    const val ACTION_MMS_SENT = "com.dpad.messaging.MMS_SENT"
    const val EXTRA_MMS_ID    = "extra_mms_id"
    const val EXTRA_THREAD_ID = "extra_thread_id"

    // MMS msg_box values (mirrors Telephony.Mms.MESSAGE_BOX_*)
    const val MSG_BOX_SENT   = 2
    const val MSG_BOX_OUTBOX = 4

    // MMS m_type = M-Send-Req
    private const val M_TYPE_SEND_REQ = 128

    // MMS Version 1.2 as stored in the provider (decimal 18)
    private const val MMS_VERSION_12 = 18

    // Address type constants used by content://mms/{id}/addr
    private const val ADDR_TYPE_TO   = 151
    private const val ADDR_TYPE_FROM = 137

    private const val MAX_IMAGE_WIDTH = 800
    private const val JPEG_QUALITY    = 85

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Sends an MMS with the given text body and optional image attachment.
     *
     * @param context        Application context.
     * @param recipients     Recipient phone number(s). Multiple numbers = group MMS.
     * @param body           Text body (may be blank when image-only).
     * @param imageUri       Content URI of an image to attach, or null for text-only MMS.
     * @param threadId       Telephony thread ID.
     * @param subscriptionId SIM subscription ID (-1 = system default).
     */
    fun send(
        context: Context,
        recipients: List<String>,
        body: String,
        imageUri: Uri?,
        threadId: Long,
        subscriptionId: Int = -1
    ) {
        if (recipients.isEmpty()) return

        // Strip own phone number(s) from the recipient list — the device's own
        // number appears in group thread participant lists but must not be a TO address.
        val ownNumbers = context.getOwnPhoneNumbers()
        val filteredRecipients = recipients.filter { num ->
            val digits = num.filter { it.isDigit() }
            digits !in ownNumbers && digits.takeLast(10) !in ownNumbers
        }.ifEmpty { recipients }  // fallback: send to all if filter removes everyone
        Log.d(TAG, "MmsSender.send() recipients=$recipients filteredRecipients=$filteredRecipients body='${body.take(20)}' image=${imageUri != null} threadId=$threadId")

        val isTextOnlyGroup = imageUri == null && filteredRecipients.size > 1 && body.isNotBlank()
        if (isTextOnlyGroup) {
            sendTextOnlyGroupMms(context, filteredRecipients, body, subscriptionId)
            return
        }

        // Resolve thread ID using Telephony.Threads to ensure proper group thread creation
        val resolvedThreadId = if (filteredRecipients.isNotEmpty()) {
            try {
                val resolved = if (filteredRecipients.size == 1) {
                    Log.d(TAG, "MmsSender: single recipient, calling getOrCreateThreadId with ${filteredRecipients.first()}")
                    Telephony.Threads.getOrCreateThreadId(context, filteredRecipients.first())
                } else {
                    Log.d(TAG, "MmsSender: group recipients, calling getOrCreateThreadId with ${filteredRecipients}")
                    Telephony.Threads.getOrCreateThreadId(context, filteredRecipients.toSet())
                }
                Log.d(TAG, "MmsSender: resolved threadId=$resolved from recipients (passed threadId was $threadId)")
                resolved
            } catch (e: Exception) {
                Log.w(TAG, "MmsSender: getOrCreateThreadId failed, using passed threadId=$threadId", e)
                threadId
            }
        } else {
            Log.d(TAG, "MmsSender: no filtered recipients, using passed threadId=$threadId")
            threadId
        }

        // 1. Build part list
        val parts = mutableListOf<MmsPduComposer.Part>()

        if (body.isNotBlank()) {
            parts.add(
                MmsPduComposer.Part(
                    contentType = "text/plain",
                    contentId   = "text_0",
                    data        = body.toByteArray(Charsets.UTF_8)
                )
            )
        }

        var imageBytes: ByteArray? = null
        val imageMime = "image/jpeg"  // always JPEG after compression
        if (imageUri != null) {
            val compressed = compressImage(context, imageUri)
            if (compressed != null) {
                imageBytes = compressed
                parts.add(
                    MmsPduComposer.Part(
                        contentType = imageMime,
                        contentId   = "image_0",
                        data        = compressed
                    )
                )
            }
        }

        if (parts.isEmpty()) return  // nothing to send

        // 2. Compose PDU for sending
        val txnId    = UUID.randomUUID().toString().take(8)
        val hasImage = imageBytes != null
        val allParts = if (hasImage) {
            val smil = buildSmil(
                hasText  = body.isNotBlank(),
                hasImage = true
            )
            mutableListOf(
                MmsPduComposer.Part(
                    contentType     = "application/smil",
                    contentId       = "smil",
                    contentLocation = "smil.xml",
                    data            = smil.toByteArray(Charsets.UTF_8)
                )
            ) + parts
        } else {
            parts
        }
        val pduBytes = MmsPduComposer.compose(filteredRecipients, txnId, allParts, hasImage)
        Log.d(TAG, "MmsSender: PDU size=${pduBytes.size} hasImage=$hasImage threadId=$resolvedThreadId")

        // Write PDU to cache file for sendMultimediaMessage
        val pduFile = writePduToCache(context, pduBytes)
        val pduUri = if (pduFile != null) {
            FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                pduFile
            )
        } else null

        // 3. Insert MMS row into provider FIRST (outbox state - system MmsService will pick it up)
        val mmsId = insertMmsRow(context, resolvedThreadId, body, subscriptionId, hasImage)
        Log.d(TAG, "MmsSender: inserted MMS row mmsId=$mmsId subId=$subscriptionId")

        // 4. Insert parts BEFORE the system expects them (matching system app flow)
        if (mmsId > 0) {
            if (body.isNotBlank()) {
                insertTextPart(context, mmsId, body)
            }
            if (imageBytes != null) {
                insertImagePart(context, mmsId, imageMime, imageBytes)
            }

            // 5. Insert address rows (FROM first, then TO per recipient)
            // System app inserts FROM address first, then TO addresses
            insertAddr(context, mmsId, "insert-address-token", ADDR_TYPE_FROM)
            for (recipient in filteredRecipients) {
                insertAddr(context, mmsId, recipient, ADDR_TYPE_TO)
            }
            Log.d(TAG, "MmsSender: inserted parts and addresses for mmsId=$mmsId")
        }

        // 6. Send via SmsManager - system MmsService uses this to send
        // The system app inserts to provider AND calls sendMultimediaMessage
        try {
            val requestCode = if (mmsId > 0) {
                mmsId.toInt()
            } else {
                (System.currentTimeMillis() and 0x7FFFFFFF).toInt()
            }
            val sentIntent = PendingIntent.getBroadcast(
                context,
                requestCode,
                Intent(ACTION_MMS_SENT, null, context, MmsSentReceiver::class.java).apply {
                    putExtra(EXTRA_MMS_ID, mmsId)
                    putExtra(EXTRA_THREAD_ID, resolvedThreadId)
                    putExtra("extra_has_image", hasImage)
                },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val smsManager = when {
                subscriptionId >= 0 && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP_MR1 ->
                    android.telephony.SmsManager.getSmsManagerForSubscriptionId(subscriptionId)
                android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S ->
                    context.getSystemService(android.telephony.SmsManager::class.java) ?: android.telephony.SmsManager.getDefault()
                else ->
                    android.telephony.SmsManager.getDefault()
            }
            if (pduUri != null) {
                // Grant URI permissions to system MMS packages
                val mmsPackages = listOf("com.android.mms", "com.android.messaging", "com.android.phone", "com.android.providers.telephony")
                for (pkg in mmsPackages) {
                    try {
                        context.grantUriPermission(pkg, pduUri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to grant permission to $pkg")
                    }
                }
                smsManager.sendMultimediaMessage(context, pduUri, null, null, sentIntent)
                Log.d(TAG, "MmsSender: sendMultimediaMessage called for mmsId=$mmsId with PDU subId=$subscriptionId")
            } else {
                Log.e(TAG, "MmsSender: pduUri is null, cannot send")
            }
        } catch (e: Exception) {
            Log.e(TAG, "MmsSender: sendMultimediaMessage failed for subId=$subscriptionId", e)
        }

        Log.d(TAG, "MmsSender: done for mmsId=$mmsId")
    }

    private fun sendTextOnlyGroupMms(
        context: Context,
        recipients: List<String>,
        body: String,
        subscriptionId: Int
    ) {
        val settings = KlinkerSettings().apply {
            setUseSystemSending(true)
            setGroup(true)
            setDeliveryReports(Prefs.get().deliveryReports)
            if (subscriptionId >= 0) {
                setSubscriptionId(subscriptionId)
            }
        }

        val transaction = KlinkerTransaction(context, settings)
        val sentIntent = Intent(ACTION_MMS_SENT, null, context, MmsSentReceiver::class.java).apply {
            putExtra(EXTRA_THREAD_ID, resolveThreadId(context, recipients))
            putExtra("extra_has_image", false)
            putExtra("extra_library_sender", true)
        }
        transaction.setExplicitBroadcastForSentMms(sentIntent)

        val message = KlinkerMessage(body, recipients.toTypedArray())
        Log.d(TAG, "MmsSender: sending text-only group MMS through mmslib recipients=$recipients subId=$subscriptionId")
        transaction.sendNewMessage(message)
    }

    private fun resolveThreadId(context: Context, recipients: List<String>): Long {
        return try {
            if (recipients.size == 1) {
                Telephony.Threads.getOrCreateThreadId(context, recipients.first())
            } else {
                Telephony.Threads.getOrCreateThreadId(context, recipients.toSet())
            }
        } catch (e: Exception) {
            Log.w(TAG, "MmsSender: resolveThreadId failed for $recipients", e)
            -1L
        }
    }

    // ── MMS provider helpers ──────────────────────────────────────────────────

private fun insertMmsRow(context: Context, threadId: Long, subject: String, subscriptionId: Int, hasImage: Boolean): Long {
        val contentType = if (hasImage) {
            "application/vnd.wap.multipart.related"
        } else {
            "application/vnd.wap.multipart.mixed"
        }
        val cv = ContentValues().apply {
            put("thread_id", threadId)
            put("msg_box",   MSG_BOX_OUTBOX)
            put("m_type",    M_TYPE_SEND_REQ)
            put("v",         MMS_VERSION_12)
            put("date",      System.currentTimeMillis() / 1000L)
            put("read",      1)
            put("seen",      1)
            put("ct_t",      contentType)
            if (subject.isNotBlank()) put("sub", subject)
            // Use the provided subscriptionId if valid (>= 0), otherwise let the provider choose default
            if (subscriptionId >= 0) {
                put("sub_id", subscriptionId)
            }
        }
        return try {
            val uri = context.contentResolver.insert(
                Uri.parse("content://mms"),
                cv
            )
            uri?.lastPathSegment?.toLongOrNull() ?: -1L
        } catch (e: Exception) {
            e.printStackTrace(); -1L
        }
    }

    private fun insertTextPart(context: Context, mmsId: Long, text: String) {
        val cv = ContentValues().apply {
            put("mid",        mmsId)
            put("ct",         "text/plain")
            put("chset",      106)   // UTF-8
            put("text",       text)
            put("content_id", "text_0")  // must match SMIL reference
        }
        try {
            context.contentResolver.insert(
                Uri.parse("content://mms/$mmsId/part"),
                cv
            )
        } catch (e: Exception) { e.printStackTrace() }
    }

    private fun insertImagePart(
        context: Context,
        mmsId: Long,
        mimeType: String,
        data: ByteArray
    ) {
        val cv = ContentValues().apply {
            put("mid",        mmsId)
            put("ct",         mimeType)
            put("content_id", "image_0")  // must match SMIL reference
        }
        try {
            val partUri = context.contentResolver.insert(
                Uri.parse("content://mms/$mmsId/part"),
                cv
            ) ?: return
            context.contentResolver.openOutputStream(partUri)?.use { it.write(data) }
        } catch (e: Exception) { e.printStackTrace() }
    }

    private fun insertAddr(context: Context, mmsId: Long, address: String, type: Int) {
        val cv = ContentValues().apply {
            put("address", address)
            put("type",    type)
            put("charset", 106)
        }
        try {
            context.contentResolver.insert(
                Uri.parse("content://mms/$mmsId/addr"),
                cv
            )
        } catch (e: Exception) { e.printStackTrace() }
    }

    /**
     * Updates the msg_box column on an MMS row (e.g. outbox → sent, or → failed).
     */
    fun updateMmsStatus(context: Context, mmsId: Long, msgBox: Int) {
        if (mmsId <= 0) return
        try {
            context.contentResolver.update(
                Uri.parse("content://mms/$mmsId"),
                ContentValues().apply { put("msg_box", msgBox) },
                null, null
            )
        } catch (e: Exception) { e.printStackTrace() }
    }

    // ── Image compression ─────────────────────────────────────────────────────

    private fun compressImage(context: Context, uri: Uri): ByteArray? {
        return try {
            val inputStream = context.contentResolver.openInputStream(uri) ?: return null
            val original    = BitmapFactory.decodeStream(inputStream)
            inputStream.close()
            if (original == null) return null

            val scaled = if (original.width > MAX_IMAGE_WIDTH) {
                val ratio  = MAX_IMAGE_WIDTH.toFloat() / original.width.toFloat()
                val newH   = (original.height * ratio).toInt()
                val result = Bitmap.createScaledBitmap(original, MAX_IMAGE_WIDTH, newH, true)
                original.recycle()
                result
            } else {
                original
            }

            val out = ByteArrayOutputStream()
            scaled.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
            scaled.recycle()
            out.toByteArray()
        } catch (e: Exception) {
            e.printStackTrace(); null
        }
    }

    // ── PDU cache ─────────────────────────────────────────────────────────────

    private fun writePduToCache(context: Context, pduBytes: ByteArray): File? {
        return try {
            val dir  = File(context.cacheDir, "mms_pdu").apply { mkdirs() }
            val file = File(dir, "send_${System.currentTimeMillis()}.pdu")
            FileOutputStream(file).use { it.write(pduBytes) }
            file
        } catch (e: Exception) {
            e.printStackTrace(); null
        }
    }

    // ── SMIL builder ─────────────────────────────────────────────────────────

    /**
     * Builds a minimal SMIL document referencing the text and/or image parts.
     * Carriers expect a SMIL presentation layer even for text-only MMS.
     * For single-content (text-only or image-only), use 100% height.
     * For dual-content (text+image), split at 50% each.
     */
    private fun buildSmil(hasText: Boolean, hasImage: Boolean): String {
        val sb = StringBuilder()
        sb.append("<smil><head><layout>")
        sb.append("<root-layout width=\"100%\" height=\"100%\"/>")
        
        // Determine region heights: 100% if only one type, 50% if both
        val regionHeight = if (hasText && hasImage) "50%" else "100%"
        
        if (hasImage) sb.append("<region id=\"Image\" width=\"100%\" height=\"$regionHeight\" fit=\"meet\"/>")
        if (hasText)  sb.append("<region id=\"Text\"  width=\"100%\" height=\"$regionHeight\"/>")
        sb.append("</layout></head><body><par dur=\"5000ms\">")
        if (hasImage) sb.append("<img src=\"image_0\" region=\"Image\"/>")
        if (hasText)  sb.append("<text src=\"text_0\" region=\"Text\"/>")
        sb.append("</par></body></smil>")
        return sb.toString()
    }
}
