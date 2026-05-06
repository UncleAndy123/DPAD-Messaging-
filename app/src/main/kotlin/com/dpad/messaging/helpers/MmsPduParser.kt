package com.dpad.messaging.helpers

import android.util.Log

/**
 * Parses a binary M-Retrieve-Conf PDU (the MMS content downloaded from the MMSC).
 *
 * Implements just enough of OMA MMS 1.2 / WSP binary encoding to extract:
 *   - FROM address (sender)
 *   - Subject (optional)
 *   - text/plain body parts
 *   - image/jpeg, image/png, image/gif body parts
 *
 * No external dependencies — pure ByteArray parsing.
 */
object MmsPduParser {

    private const val TAG = "DPAD_MSG"

    // ── MMS header field codes (OMA MMS 1.2 §7.3) ────────────────────────────
    private const val FIELD_MESSAGE_TYPE   = 0x8C
    private const val FIELD_TRANSACTION_ID = 0x98
    private const val FIELD_MMS_VERSION    = 0x8D
    private const val FIELD_DATE           = 0x85
    private const val FIELD_FROM           = 0x89
    private const val FIELD_TO             = 0x97
    private const val FIELD_CC             = 0x92
    private const val FIELD_SUBJECT        = 0x96
    private const val FIELD_CONTENT_TYPE   = 0x84   // marks end of headers

    // ── WSP Well-Known-Media tokens (token = byte & 0x7F) ────────────────────
    private const val TOKEN_TEXT_PLAIN         = 0x03   // 0x83 as short-int
    private const val TOKEN_IMAGE_GIF          = 0x1D   // 0x9D
    private const val TOKEN_IMAGE_JPEG         = 0x1E   // 0x9E
    private const val TOKEN_IMAGE_PNG          = 0x1F   // 0x9F
    private const val TOKEN_MULTIPART_MIXED    = 0x23   // 0xA3
    private const val TOKEN_MULTIPART_RELATED  = 0x33   // 0xB3

    // ── Public data model ─────────────────────────────────────────────────────

    data class ParsedMms(
        val from: String,
        val toAddresses: List<String>,   // all TO + CC recipients from the PDU headers
        val subject: String,
        val textBody: String,
        val imageParts: List<ImagePart>
    )

    data class ImagePart(
        val mimeType: String,
        val data: ByteArray
    )

    // ── Entry point ───────────────────────────────────────────────────────────

    fun parse(pdu: ByteArray): ParsedMms? {
        if (pdu.isEmpty()) return null
        return try {
            parseInternal(PduReader(pdu))
        } catch (e: Exception) {
            Log.e(TAG, "MmsPduParser: parse failed", e)
            null
        }
    }

    // ── Internal header + body parsing ───────────────────────────────────────

    private fun parseInternal(r: PduReader): ParsedMms {
        var from    = ""
        var subject = ""
        val toAddresses = mutableListOf<String>()

        // Parse headers until Content-Type (0x84) which marks the body start.
        while (r.hasMore()) {
            val fieldCode = r.readByte()
            when (fieldCode) {
                FIELD_MESSAGE_TYPE   -> r.skipShortInteger()
                FIELD_MMS_VERSION    -> r.skipShortInteger()
                FIELD_TRANSACTION_ID -> r.skipTextString()
                FIELD_DATE           -> r.skipLongInteger()
                FIELD_TO, FIELD_CC   -> {
                    // Collect all TO/CC addresses; strip "/TYPE=PLMN" etc.
                    val raw = r.readEncodedStringValue()
                    raw.split(",").forEach { part ->
                        val addr = part.trim().substringBefore("/").trim()
                        if (addr.isNotBlank()) toAddresses.add(addr)
                    }
                }
                FIELD_FROM           -> { from = r.readFromValue() }
                FIELD_SUBJECT        -> { subject = r.readEncodedStringValue() }
                FIELD_CONTENT_TYPE   -> {
                    r.skipContentTypeValue()
                    break   // body starts at r.pos
                }
                else -> {
                    // Unknown / unhandled header — skip value defensively
                    r.skipUnknownFieldValue()
                }
            }
        }

        val (textBody, imageParts) = parseMultipartBody(r)
        Log.d(TAG, "MmsPduParser: from='$from' to=${toAddresses.size} subject='$subject' text=${textBody.take(30)} images=${imageParts.size}")
        return ParsedMms(from, toAddresses, subject, textBody, imageParts)
    }

    private fun parseMultipartBody(r: PduReader): Pair<String, List<ImagePart>> {
        var textBody = ""
        val imageParts = mutableListOf<ImagePart>()

        if (!r.hasMore()) return Pair(textBody, imageParts)

        val nParts = r.readUintVar()
        Log.d(TAG, "MmsPduParser: multipart nParts=$nParts pos=${r.pos}")

        repeat(nParts) { index ->
            if (!r.hasMore()) return@repeat

            val headerLen = r.readUintVar()
            val dataLen   = r.readUintVar()
            val headerStart = r.pos
            val dataStart   = headerStart + headerLen
            val partEnd     = dataStart + dataLen

            if (partEnd > r.pdu.size) {
                Log.w(TAG, "MmsPduParser: part[$index] out of bounds (partEnd=$partEnd pduSize=${r.pdu.size}), stopping")
                return Pair(textBody, imageParts)
            }

            // First field in the part header is always Content-Type.
            val rawCt = r.readPartContentType()
            // Correct wrong/non-standard MIME types using magic bytes from the actual data.
            val ct = sniffContentType(rawCt, r.pdu, dataStart, dataLen)
            if (ct != rawCt) Log.d(TAG, "MmsPduParser: part[$index] ct overridden '$rawCt' -> '$ct'")
            Log.d(TAG, "MmsPduParser: part[$index] ct='$ct' dataLen=$dataLen")

            // Jump to data (skip remaining part headers like Content-ID, Content-Location).
            r.pos = dataStart
            val data = r.pdu.copyOfRange(dataStart, dataStart + dataLen)
            r.pos = partEnd

            when {
                ct == "text/plain"   -> textBody = data.toString(Charsets.UTF_8)
                ct.startsWith("image/") -> imageParts.add(ImagePart(ct, data))
                // skip application/smil and other parts
            }
        }

        return Pair(textBody, imageParts)
    }

    /**
     * Detects the true image format from magic bytes in [pdu] starting at [offset],
     * returning a corrected MIME type when [declared] is a wrong/unknown type.
     *
     * This handles T-Mobile MMSCs that label JPEG images with token 0x20 (image/tiff)
     * or as application/octet-stream.
     */
    private fun sniffContentType(declared: String, pdu: ByteArray, offset: Int, len: Int): String {
        if (len < 4 || offset + 4 > pdu.size) return declared
        // Only attempt to correct if the declared type is suspect
        val suspect = declared == "application/octet-stream"
                   || declared == "image/tiff"
                   || declared == "image/vnd.wap.wbmp"
        if (!suspect) return declared

        val b0 = pdu[offset].toInt() and 0xFF
        val b1 = pdu[offset + 1].toInt() and 0xFF
        val b2 = pdu[offset + 2].toInt() and 0xFF
        val b3 = pdu[offset + 3].toInt() and 0xFF

        return when {
            // JPEG: FF D8 FF
            b0 == 0xFF && b1 == 0xD8 && b2 == 0xFF -> "image/jpeg"
            // PNG: 89 50 4E 47
            b0 == 0x89 && b1 == 0x50 && b2 == 0x4E && b3 == 0x47 -> "image/png"
            // GIF: 47 49 46
            b0 == 0x47 && b1 == 0x49 && b2 == 0x46 -> "image/gif"
            // WebP: 52 49 46 46 (RIFF) — check bytes 8-11 for WEBP if needed
            b0 == 0x52 && b1 == 0x49 && b2 == 0x46 && b3 == 0x46 -> "image/webp"
            else -> declared
        }
    }

    // ── PDU reader ────────────────────────────────────────────────────────────

    /**
     * Stateful cursor over a PDU byte array.
     * All read/skip methods advance [pos].
     */
    class PduReader(val pdu: ByteArray) {

        var pos = 0

        fun hasMore() = pos < pdu.size

        // ── Primitives ────────────────────────────────────────────────────────

        /** Read one unsigned byte and advance pos. */
        fun readByte(): Int = pdu[pos++].toInt() and 0xFF

        /** Peek at the current byte without advancing. */
        fun peekByte(): Int = if (pos < pdu.size) pdu[pos].toInt() and 0xFF else -1

        /**
         * Read a uintvar (variable-length unsigned int, WSP §8.1.2).
         * Each byte contributes 7 bits; MSB=1 means more bytes follow.
         */
        fun readUintVar(): Int {
            var result = 0
            var b: Int
            do {
                b = readByte()
                result = (result shl 7) or (b and 0x7F)
            } while (b and 0x80 != 0 && pos < pdu.size)
            return result
        }

        /**
         * Read a value-length:
         *   0x00–0x1E → length value directly
         *   0x1F      → length-quote; actual length is the following uintvar
         */
        fun readValueLength(): Int {
            val b = readByte()
            return if (b == 0x1F) readUintVar() else b
        }

        // ── Header value skippers ─────────────────────────────────────────────

        /** Skip a short-integer (1 byte, MSB always set). */
        fun skipShortInteger() { pos++ }

        /** Skip a null-terminated text string (with optional leading quote byte 0x22). */
        fun skipTextString() {
            if (pos >= pdu.size) return
            if ((pdu[pos].toInt() and 0xFF) == 0x22) pos++ // skip leading quote
            while (pos < pdu.size && pdu[pos] != 0.toByte()) pos++
            if (pos < pdu.size) pos++ // consume null terminator
        }

        /** Skip a WSP long-integer: first byte = N, then N value bytes. */
        fun skipLongInteger() {
            if (pos >= pdu.size) return
            val n = pdu[pos++].toInt() and 0xFF
            pos += n
        }

        /** Skip an encoded-string-value (discarding the result). */
        fun skipEncodedStringValue() { readEncodedStringValue() }

        // ── Header value readers ──────────────────────────────────────────────

        /**
         * Read an encoded-string-value (WSP §8.4.2.3):
         *   Value-length Char-set Text-string  |  Text-string
         *
         * Returns the string contents (charset is consumed but not applied —
         * addresses are ASCII; for Subject we fall back to Latin-1 if needed).
         */
        fun readEncodedStringValue(): String {
            if (pos >= pdu.size) return ""
            val first = pdu[pos].toInt() and 0xFF

            return if (first <= 0x1F) {
                // Value-length form: read length, skip charset, read text
                val len = readValueLength()
                val end = pos + len
                skipCharset()
                val sb = StringBuilder()
                while (pos < end && pos < pdu.size && pdu[pos] != 0.toByte()) {
                    sb.append(pdu[pos++].toChar())
                }
                pos = end     // always land at the right offset regardless of null
                sb.toString()
            } else {
                // Text-string (no charset prefix)
                if (first == 0x22) pos++ // skip optional leading quote
                val sb = StringBuilder()
                while (pos < pdu.size && pdu[pos] != 0.toByte()) {
                    sb.append(pdu[pos++].toChar())
                }
                if (pos < pdu.size) pos++ // consume null
                sb.toString()
            }
        }

        /**
         * Read the From header value (OMA MMS 1.2 §7.3.21):
         *   Value-length ( address-present-token EncodedString | insert-address-token )
         *
         * Returns the sender address, stripping any "/TYPE=PLMN" suffix.
         */
        fun readFromValue(): String {
            if (pos >= pdu.size) return ""
            val len = readValueLength()
            val end = pos + len
            if (pos >= pdu.size || len == 0) { pos = end; return "" }

            val token = readByte()
            if (token == 0x81) { pos = end; return "" }   // insert-address-token → own address

            // token == 0x80: address-present-token; address follows as EncodedString
            skipCharset()   // optional charset prefix
            val sb = StringBuilder()
            while (pos < end && pos < pdu.size && pdu[pos] != 0.toByte()) {
                val c = pdu[pos++].toChar()
                if (c.code >= 32) sb.append(c)
            }
            pos = end
            return sb.toString().substringBefore("/")  // strip "/TYPE=PLMN" etc.
        }

        /**
         * Skip the top-level Content-Type header value that ends the MMS headers.
         * Content-type-value = Constrained-media | Content-general-form
         */
        fun skipContentTypeValue() {
            if (pos >= pdu.size) return
            val first = pdu[pos].toInt() and 0xFF
            when {
                first and 0x80 != 0 -> pos++           // short-integer well-known type
                first <= 0x1F -> {                      // value-length form
                    val len = readValueLength()
                    pos += len
                }
                else -> skipTextString()                // extension-media text string
            }
        }

        /**
         * Read and return the MIME content-type string from a multipart part header.
         * Advances [pos] past the type + parameters (sets pos to the next header field,
         * but the caller overrides pos to [dataStart] anyway).
         */
        fun readPartContentType(): String {
            if (pos >= pdu.size) return ""
            val first = pdu[pos].toInt() and 0xFF
            Log.d(TAG, "MmsPduParser.readPartContentType(): first=0x${first.toString(16).uppercase()} pos=$pos")
            return when {
                // Short-integer: well-known content type token
                first and 0x80 != 0 -> {
                    pos++
                    wellKnownContentType(first and 0x7F)
                }
                // Value-length form: type token/string + optional params
                first <= 0x1F -> {
                    val len = readValueLength()
                    val ctEnd = pos + len
                    val ct = readTypeToken(ctEnd)
                    pos = ctEnd   // skip params
                    ct
                }
                // Text MIME type (e.g. "text/plain\0")
                else -> {
                    val sb = StringBuilder()
                    while (pos < pdu.size && pdu[pos] != 0.toByte()) sb.append(pdu[pos++].toChar())
                    if (pos < pdu.size) pos++
                    sb.toString()
                }
            }
        }

        /**
         * Try to skip an unknown header field value.
         * Uses heuristics: short-integer (MSB set), value-length form (0..0x1F), or text-string.
         */
        fun skipUnknownFieldValue() {
            if (pos >= pdu.size) return
            val first = pdu[pos].toInt() and 0xFF
            when {
                first and 0x80 != 0 -> pos++           // short-integer
                first <= 0x1F -> {                      // value-length form
                    val len = readValueLength()
                    pos += len
                }
                else -> skipTextString()                // text string
            }
        }

        // ── Private helpers ───────────────────────────────────────────────────

        /**
         * Skip an optional WSP charset indicator:
         *   short-integer (byte with MSB set) → 1 byte
         *   long-integer  (byte N in 1..30, then N bytes)
         *   otherwise     → no charset present, do not advance
         */
        private fun skipCharset() {
            if (pos >= pdu.size) return
            val b = pdu[pos].toInt() and 0xFF
            when {
                b and 0x80 != 0          -> pos++           // short-integer charset
                b in 1..30               -> { pos++; pos += b } // long-integer charset
                // else: no charset prefix; don't move
            }
        }

        /**
         * Read the type portion of a value-length-encoded content-type
         * (which may be a well-known short-int token or a text string),
         * bounded by [ctEnd].
         */
        private fun readTypeToken(ctEnd: Int): String {
            if (pos >= pdu.size) return ""
            val b = pdu[pos].toInt() and 0xFF
            return when {
                b and 0x80 != 0 -> {
                    pos++
                    wellKnownContentType(b and 0x7F)
                }
                b in 1..30 -> {
                    // long-integer type (unusual) — skip it
                    pos += 1 + b; ""
                }
                else -> {
                    val sb = StringBuilder()
                    while (pos < ctEnd && pos < pdu.size && pdu[pos] != 0.toByte()) {
                        sb.append(pdu[pos++].toChar())
                    }
                    if (pos < pdu.size && pdu[pos] == 0.toByte()) pos++
                    sb.toString()
                }
            }
        }

        /**
         * Maps a WSP Well-Known-Media token (the short-integer value with MSB stripped)
         * to a MIME type string.
         * Source: OMA WAP-230-WSP-20010705-a §8.4.2.24 Table 40.
         */
        private fun wellKnownContentType(token: Int): String = when (token) {
            TOKEN_TEXT_PLAIN        -> "text/plain"
            TOKEN_IMAGE_GIF         -> "image/gif"
            TOKEN_IMAGE_JPEG        -> "image/jpeg"
            TOKEN_IMAGE_PNG         -> "image/png"
            TOKEN_MULTIPART_MIXED   -> "multipart/mixed"
            TOKEN_MULTIPART_RELATED -> "multipart/related"
            0x02                    -> "text/html"
            0x04                    -> "text/xml"
            0x07                    -> "text/vnd.wap.wml"
            0x1C                    -> "image/bmp"
            0x20                    -> "image/tiff"   // WAP-230 Table 40; T-Mobile may send JPEG with this token
            0x21                    -> "image/vnd.wap.wbmp"
            0x22                    -> "application/vnd.wap.multipart.form-data"
            0x24                    -> "application/vnd.wap.multipart.byteranges"
            0x25                    -> "application/vnd.wap.multipart.alternative"
            0x28                    -> "application/vnd.wap.wmlscriptc"
            0x2E                    -> "audio/x-wav"
            0x30                    -> "audio/mpeg"
            0x36                    -> "video/mpeg"
            0x37                    -> "video/mp4"
            else                    -> {
                Log.w(TAG, "MmsPduParser: unknown content-type token 0x${token.toString(16).uppercase()}")
                "application/octet-stream"
            }
        }
    }
}
