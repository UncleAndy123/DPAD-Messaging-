// app/src/main/kotlin/com/dpad/messaging/helpers/SmsWhitelistManager.kt
package com.dpad.messaging.helpers

import android.content.Context
import android.content.RestrictionsManager
import android.util.Log

/**
 * Reads SMS/MMS whitelist and blocklist from MDM-pushed Application Restrictions.
 *
 * The launcher pushes these via DevicePolicyManager.setApplicationRestrictions().
 * Keys:
 *   "sms_allowed_numbers"  - CSV of allowed sender numbers (whitelist mode)
 *   "sms_blocked_numbers"  - CSV of blocked sender numbers (blocklist mode)
 *   "sms_filter_mode"      - "whitelist" | "blocklist" | "off" (default: "off")
 *
 * Whitelist mode: ONLY numbers in the list can send messages through.
 * Blocklist mode: numbers in the list are silently dropped.
 * Off: no filtering (default).
 */
object SmsWhitelistManager {
    private const val TAG = "DPAD_MSG"
    const val KEY_ALLOWED = "sms_allowed_numbers"
    const val KEY_BLOCKED = "sms_blocked_numbers"
    const val KEY_MODE    = "sms_filter_mode"

    enum class FilterMode { OFF, WHITELIST, BLOCKLIST }

    data class FilterResult(val allowed: Boolean, val reason: String)

    fun check(context: Context, address: String): FilterResult {
        val rm = context.getSystemService(Context.RESTRICTIONS_SERVICE) as RestrictionsManager
        val bundle = rm.applicationRestrictions

        // Temporary diagnostic — remove once filtering is confirmed working.
        Log.d("DPAD_MSG", "SmsWhitelistManager.check() address=$address mode=${bundle.getString(KEY_MODE)} allowed=${bundle.getString(KEY_ALLOWED)} blocked=${bundle.getString(KEY_BLOCKED)}")

        val mode = when (bundle.getString(KEY_MODE, "off")?.lowercase()) {
            "whitelist" -> FilterMode.WHITELIST
            "blocklist" -> FilterMode.BLOCKLIST
            else        -> FilterMode.OFF
        }

        if (mode == FilterMode.OFF) return FilterResult(true, "filtering off")

        val normalized = normalize(address)

        return when (mode) {
            FilterMode.WHITELIST -> {
                val allowed = parseNumbers(bundle.getString(KEY_ALLOWED, ""))
                if (allowed.contains("*") || allowed.contains(normalized))
                    FilterResult(true, "whitelist pass")
                else
                    FilterResult(false, "not in whitelist")
            }
            FilterMode.BLOCKLIST -> {
                val blocked = parseNumbers(bundle.getString(KEY_BLOCKED, ""))
                if (blocked.contains(normalized))
                    FilterResult(false, "blocklist hit")
                else
                    FilterResult(true, "not in blocklist")
            }
            FilterMode.OFF -> FilterResult(true, "filtering off")
        }
    }

    private fun parseNumbers(csv: String?): Set<String> =
        csv?.split(",")?.map { normalize(it.trim()) }?.filter { it.isNotEmpty() }?.toSet()
            ?: emptySet()

    private fun normalize(number: String): String =
        number.filter { it.isDigit() || it == '+' || it == '*' }
            .trimStart('+').trimStart('1').takeIf { it.length >= 10 }
            ?: number.filter { it.isDigit() || it == '*' }
}