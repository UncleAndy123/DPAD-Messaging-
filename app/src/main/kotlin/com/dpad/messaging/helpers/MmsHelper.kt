package com.dpad.messaging.helpers

import android.content.Context
import android.net.Uri

/**
 * Utilities for reading MMS message parts from the system Telephony provider.
 *
 * All functions must be called from a background thread.
 */
object MmsHelper {

    private val IMAGE_MIME_TYPES = setOf(
        "image/jpeg", "image/png", "image/gif", "image/webp", "image/bmp"
    )

    // MIME types to skip when looking for "other attachment" labels
    private val SKIP_MIME_TYPES = IMAGE_MIME_TYPES + setOf("text/plain", "application/smil")
    private fun String?.isImageMimeType(): Boolean {
        val mimeType = this?.lowercase() ?: return false
        return mimeType.startsWith("image/") || mimeType in IMAGE_MIME_TYPES
    }

    /**
     * Returns the text/plain body of an MMS message, or an empty string if none.
     */
    fun getMmsTextBody(context: Context, msgId: Long): String {
        val partsUri = Uri.parse("content://mms/$msgId/part")
        try {
            context.contentResolver.query(
                partsUri,
                arrayOf("_id", "ct", "text"),
                null, null, null
            )?.use { cursor ->
                val idxCt   = cursor.getColumnIndex("ct")
                val idxText = cursor.getColumnIndex("text")
                while (cursor.moveToNext()) {
                    val ct = cursor.getString(idxCt) ?: continue
                    if (ct == "text/plain") {
                        return cursor.getString(idxText) ?: ""
                    }
                }
            }
        } catch (_: Exception) {}
        return ""
    }

    /**
     * Returns the content URI string of the first image part found in the MMS message,
     * e.g. "content://mms/part/42", or null if there is no image part.
     */
    fun getMmsImagePartUri(context: Context, msgId: Long): String? {
        val partsUri = Uri.parse("content://mms/$msgId/part")
        try {
            context.contentResolver.query(
                partsUri,
                arrayOf("_id", "ct"),
                null, null, null
            )?.use { cursor ->
                val idxId = cursor.getColumnIndex("_id")
                val idxCt = cursor.getColumnIndex("ct")
                while (cursor.moveToNext()) {
                    val ct = cursor.getString(idxCt) ?: continue
                    if (ct.isImageMimeType()) {
                        val partId = cursor.getLong(idxId)
                        return "content://mms/part/$partId"
                    }
                }
            }
        } catch (_: Exception) {}
        return null
    }

    /**
     * Returns the MIME type of the first non-text, non-image, non-SMIL part, or an
     * empty string if all parts are accounted for by text/images.
     * Useful for showing e.g. "audio/mpeg" or "video/mp4" as a fallback label.
     */
    fun getMmsAttachmentLabel(context: Context, msgId: Long): String {
        val partsUri = Uri.parse("content://mms/$msgId/part")
        try {
            context.contentResolver.query(
                partsUri,
                arrayOf("ct"),
                null, null, null
            )?.use { cursor ->
                val idxCt = cursor.getColumnIndex("ct")
                while (cursor.moveToNext()) {
                    val ct = cursor.getString(idxCt) ?: continue
                    if (!ct.isImageMimeType() && ct !in SKIP_MIME_TYPES) return ct
                }
            }
        } catch (_: Exception) {}
        return ""
    }

    /**
     * Returns the best available display body for an MMS message:
     *  1. The text/plain part body, if present and non-blank.
     *  2. The subject field, if non-blank.
     *  3. The MIME type of any non-text/non-image attachment (e.g. "audio/mpeg").
     *  4. "MMS" as a last resort.
     */
    fun getMmsDisplayBody(context: Context, msgId: Long, subject: String): String {
        val textBody = getMmsTextBody(context, msgId)
        if (textBody.isNotBlank()) return textBody
        if (subject.isNotBlank()) return subject
        if (getMmsImagePartUri(context, msgId) != null) return ""
        val attachLabel = getMmsAttachmentLabel(context, msgId)
        if (attachLabel.isNotBlank()) return attachLabel
        return "MMS"
    }
}
