package com.dpad.messaging.helpers

import java.io.ByteArrayOutputStream

/**
 * Composes a WAP binary MMS M-Send-Req PDU for a message containing one text
 * part and an optional image part.
 *
 * Encoding references:
 *  • WAP-230-WSP-20010705 (WSP spec) — header field encoding, uintvar, multipart
 *  • OMA-MMS-ENC-V1_3 (MMS Encapsulation spec) — M-Send-Req header fields
 *
 * Only the codes documented in the project's Critical Context section are used;
 * these have been verified against the AOSP MmsService implementation.
 */
object MmsPduComposer {

    // ── MMS header field codes (0x80 | field-id) ─────────────────────────────
    private const val FIELD_MESSAGE_TYPE    = 0x8C
    private const val FIELD_TRANSACTION_ID  = 0x98
    private const val FIELD_MMS_VERSION     = 0x8D
    private const val FIELD_DATE            = 0x85
    private const val FIELD_FROM            = 0x89
    private const val FIELD_TO              = 0x97
    private const val FIELD_MESSAGE_CLASS   = 0x8A
    private const val FIELD_EXPIRY          = 0x88
    private const val FIELD_PRIORITY        = 0x8F
    private const val FIELD_DELIVERY_REPORT = 0x86
    private const val FIELD_READ_REPORT     = 0x90
    private const val FIELD_CONTENT_TYPE    = 0x84

    // ── Message-Type field values ─────────────────────────────────────────────
    private const val MESSAGE_TYPE_M_SEND_REQ = 0x80

    // ── MMS Version ──────────────────────────────────────────────────────────
    private const val MMS_VERSION_1_2 = 0x92

    // ── WSP content-type short-integers ──────────────────────────────────────
    private const val CT_TEXT_PLAIN        = 0x83
    private const val CT_IMAGE_GIF         = 0x9D
    private const val CT_IMAGE_JPEG        = 0x9E
    private const val CT_IMAGE_PNG         = 0x9F
    private const val CT_MULTIPART_RELATED = 0xB3   // multipart/related (0x33 | 0x80)
    private const val CT_MULTIPART_MIXED   = 0xB0   // multipart/mixed (0x30 | 0x80) - for text-only MMS

    // ── MMS field values ──────────────────────────────────────────────────────
    private const val MESSAGE_CLASS_PERSONAL = 0x80
    private const val PRIORITY_NORMAL        = 0x81
    private const val VALUE_NO               = 0x81
    private const val DEFAULT_EXPIRY_SECS    = 7 * 24 * 60 * 60  // 7 days

    // ── Charset / encoding ────────────────────────────────────────────────────
    private const val CHARSET_UTF8  = 0xEA  // 0x80 | 0x6A
    private const val PARAM_CHARSET = 0x01

    // ── From field special value ──────────────────────────────────────────────
    private const val FROM_INSERT_ADDRESS_TOKEN = 0x81

    // ── WSP well-known part headers ───────────────────────────────────────────
    private const val HEADER_CONTENT_ID       = 0xC4
    private const val HEADER_CONTENT_LOCATION = 0x8E

    data class Part(
        val contentType: String,
        val contentId: String,
        val contentLocation: String = "",   // optional; used for smil.xml
        val data: ByteArray
    )

    /**
     * Builds a binary M-Send-Req PDU matching the header order used by
     * android-smsmms (Fossify / QKSMS) so carrier MMSCs accept delivery.
     *
     * Header order: TYPE → TID → VERSION → DATE → FROM → TO(×n) →
     *               MESSAGE-CLASS → EXPIRY → PRIORITY → DR → RR → Content-Type
     *
     * @param recipients One or more recipient phone numbers (group MMS = multiple).
     * @param txnId      Unique transaction ID string (e.g. short UUID).
     * @param parts      MIME parts — SMIL first, then text/image.
     * @param hasImage   Whether the message contains an image. Text-only MMS uses multipart/mixed.
     */
    fun compose(recipients: List<String>, txnId: String, parts: List<Part>, hasImage: Boolean = true): ByteArray {
        val pdu = ByteArrayOutputStream()

        // X-Mms-Message-Type: m-send-req
        pdu.write(FIELD_MESSAGE_TYPE)
        pdu.write(MESSAGE_TYPE_M_SEND_REQ)

        // X-Mms-Transaction-ID
        pdu.write(FIELD_TRANSACTION_ID)
        pdu.writeNullTerminatedString(txnId)

        // X-Mms-MMS-Version: 1.2
        pdu.write(FIELD_MMS_VERSION)
        pdu.write(MMS_VERSION_1_2)

        // Date (seconds since epoch) — encoded as long-integer
        pdu.write(FIELD_DATE)
        pdu.writeLongInteger(System.currentTimeMillis() / 1000L)

        // From: insert-address-token (value-length=1, token=0x81)
        pdu.write(FIELD_FROM)
        pdu.write(0x01)
        pdu.write(FROM_INSERT_ADDRESS_TOKEN)

        // To: one FIELD_TO header per recipient
        for (recipient in recipients) {
            pdu.write(FIELD_TO)
            pdu.writeNullTerminatedString(normalizeAddress(recipient))
        }

        // X-Mms-Message-Class: personal
        pdu.write(FIELD_MESSAGE_CLASS)
        pdu.write(MESSAGE_CLASS_PERSONAL)

        // X-Mms-Expiry: relative, 7 days
        //   WAP long-integer = short-length (count) + multi-octet value bytes
        //   value = token(0x81=relative) + count-byte + value-bytes
        //   total value-length = 1 (token) + 1 (count) + valueBytes.size
        val expiryValueBytes = encodeLongInteger(DEFAULT_EXPIRY_SECS.toLong())
        pdu.write(FIELD_EXPIRY)
        pdu.write(1 + 1 + expiryValueBytes.size)  // value-length
        pdu.write(0x81)                            // relative-token
        pdu.write(expiryValueBytes.size)           // short-length (count byte)
        pdu.write(expiryValueBytes)                // multi-octet value

        // X-Mms-Priority: normal
        pdu.write(FIELD_PRIORITY)
        pdu.write(PRIORITY_NORMAL)

        // X-Mms-Delivery-Report: No
        pdu.write(FIELD_DELIVERY_REPORT)
        pdu.write(VALUE_NO)

        // X-Mms-Read-Report: No
        pdu.write(FIELD_READ_REPORT)
        pdu.write(VALUE_NO)

        // Text-only MMS is packaged as multipart/mixed. Messages with media keep
        // multipart/related so the SMIL presentation can reference attachments.
        pdu.write(FIELD_CONTENT_TYPE)
        pdu.write(if (hasImage) CT_MULTIPART_RELATED else CT_MULTIPART_MIXED)

        // Multipart body: uintvar(nParts) followed by each encoded part
        pdu.writeUintVar(parts.size)
        for (part in parts) {
            pdu.writePart(part)
        }

        return pdu.toByteArray()
    }

    // ── Part encoding ─────────────────────────────────────────────────────────

    private fun ByteArrayOutputStream.writePart(part: Part) {
        val headersOut = ByteArrayOutputStream()

        // Content-Type (encoded as short-int or string with optional params)
        val ctEncoded = encodeContentType(part.contentType)
        headersOut.write(ctEncoded)

        // Content-ID: well-known header 0xC4 + null-terminated quoted string
        if (part.contentId.isNotEmpty()) {
            headersOut.write(HEADER_CONTENT_ID)
            headersOut.writeNullTerminatedString("<${part.contentId}>")
        }

        // Content-Location: well-known header 0x8E + null-terminated string
        if (part.contentLocation.isNotEmpty()) {
            headersOut.write(HEADER_CONTENT_LOCATION)
            headersOut.writeNullTerminatedString(part.contentLocation)
        }

        val headersBytes = headersOut.toByteArray()
        writeUintVar(headersBytes.size)   // headers-len
        writeUintVar(part.data.size)      // data-len
        write(headersBytes)               // headers
        write(part.data)                  // data
    }

    /**
     * Encodes a MIME type as a WSP content-type value.
     * text/plain gets charset param; known image types use short-int; others are string.
     */
    private fun encodeContentType(mimeType: String): ByteArray {
        val out = ByteArrayOutputStream()
        when (mimeType.lowercase()) {
            "text/plain" -> {
                // [value_len=0x03][CT_TEXT_PLAIN][PARAM_CHARSET][CHARSET_UTF8]
                out.write(0x03)
                out.write(CT_TEXT_PLAIN)
                out.write(PARAM_CHARSET)
                out.write(CHARSET_UTF8)
            }
            "image/jpeg" -> out.write(CT_IMAGE_JPEG)
            "image/png"  -> out.write(CT_IMAGE_PNG)
            "image/gif"  -> out.write(CT_IMAGE_GIF)
            else -> {
                // Encode as null-terminated ASCII string
                out.write(mimeType.toByteArray(Charsets.US_ASCII))
                out.write(0x00)
            }
        }
        return out.toByteArray()
    }

    // ── Utilities ─────────────────────────────────────────────────────────────

    private fun ByteArrayOutputStream.writeNullTerminatedString(s: String) {
        write(s.toByteArray(Charsets.US_ASCII))
        write(0x00)
    }

    /**
     * Encodes a Long as a WSP long-integer (1-byte length + big-endian bytes,
     * leading zeros stripped).
     */
    private fun encodeLongInteger(value: Long): ByteArray {
        val bytes = mutableListOf<Byte>()
        var v = value
        while (v != 0L) {
            bytes.add(0, (v and 0xFF).toByte())
            v = v ushr 8
        }
        if (bytes.isEmpty()) bytes.add(0)
        return bytes.toByteArray()
    }

    private fun ByteArrayOutputStream.writeLongInteger(value: Long) {
        val encoded = encodeLongInteger(value)
        write(encoded.size)   // short-length
        write(encoded)
    }

    /**
     * Writes a WSP uintvar (variable-length unsigned integer).
     * Each 7-bit group occupies one byte; MSB is set on all but the last byte.
     */
    fun ByteArrayOutputStream.writeUintVar(value: Int) {
        require(value >= 0) { "uintvar value must be non-negative" }
        if (value == 0) { write(0x00); return }
        val bytes = mutableListOf<Int>()
        var v = value
        while (v > 0) {
            bytes.add(v and 0x7F)
            v = v ushr 7
        }
        bytes.reverse()
        for (i in bytes.indices) {
            val b = bytes[i]
            write(if (i < bytes.size - 1) b or 0x80 else b)
        }
    }

    /**
     * Normalizes a phone number to E.164-ish form and appends "/TYPE=PLMN".
     *
     * Rules (US/Canada NANP):
     *  • Already has '+' prefix → use as-is
     *  • 11 digits starting with '1' → use as-is (e.g. 17204363079)
     *  • 10 digits → prepend '1' (e.g. 7204363079 → 17204363079)
     *  • Anything else → use as-is
     */
    private fun normalizeAddress(address: String): String {
        val digits = address.filter { it.isDigit() }
        val normalized = when {
            address.startsWith("+")       -> address.filter { it.isDigit() || it == '+' }
            digits.length == 10           -> "1$digits"
            else                          -> digits.ifEmpty { address }
        }
        return "$normalized/TYPE=PLMN"
    }
}
