package com.dpad.messaging.helpers

import android.content.Context
import android.content.SharedPreferences

/**
 * Thin SharedPreferences wrapper for user-configurable settings.
 *
 * Initialised once via [init] in App.onCreate(); accessed anywhere via [get].
 * Thread-safe: double-checked locking via @Volatile + synchronized.
 */
class Prefs private constructor(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("dpad_prefs", Context.MODE_PRIVATE)

    // ── Keys ──────────────────────────────────────────────────────────────────

    companion object {
        private const val KEY_DELIVERY_REPORTS    = "delivery_reports"
        private const val KEY_SEND_ON_ENTER       = "send_on_enter"
        private const val KEY_CHARACTER_COUNTER   = "character_counter"
        private const val KEY_SEND_GROUP_MESSAGE_MMS = "send_group_message_mms"
        private const val KEY_LOCK_SCREEN_PRIVACY = "lock_screen_privacy"
        private const val KEY_RECYCLE_BIN_ENABLED = "recycle_bin_enabled"

        const val PRIVACY_FULL        = "full"
        const val PRIVACY_SENDER_ONLY = "sender_only"

        @Volatile private var instance: Prefs? = null

        fun init(context: Context): Prefs =
            instance ?: synchronized(this) {
                instance ?: Prefs(context.applicationContext).also { instance = it }
            }

        fun get(): Prefs = checkNotNull(instance) { "Prefs.init() must be called before Prefs.get()" }
    }

    // ── Properties ────────────────────────────────────────────────────────────

    /** Show a "Delivered" status tick after the system confirms delivery. Default: false. */
    var deliveryReports: Boolean
        get() = prefs.getBoolean(KEY_DELIVERY_REPORTS, false)
        set(v) = prefs.edit().putBoolean(KEY_DELIVERY_REPORTS, v).apply()

    /** Press Enter (not Shift+Enter) to send the message. Default: true. */
    var sendOnEnter: Boolean
        get() = prefs.getBoolean(KEY_SEND_ON_ENTER, true)
        set(v) = prefs.edit().putBoolean(KEY_SEND_ON_ENTER, v).apply()

    /** Show remaining characters and segment count in the compose bar. Default: false. */
    var characterCounter: Boolean
        get() = prefs.getBoolean(KEY_CHARACTER_COUNTER, false)
        set(v) = prefs.edit().putBoolean(KEY_CHARACTER_COUNTER, v).apply()

    /**
     * Keep text-only group replies in a single thread by sending them as group MMS.
     * When disabled, text-only group replies are sent as individual SMS for compatibility.
     * Default: false.
     */
    var sendGroupMessageMms: Boolean
        get() = prefs.getBoolean(KEY_SEND_GROUP_MESSAGE_MMS, false)
        set(v) = prefs.edit().putBoolean(KEY_SEND_GROUP_MESSAGE_MMS, v).apply()

    /**
     * Controls what is shown on the lock screen notification.
     * [PRIVACY_FULL] = show sender and message body.
     * [PRIVACY_SENDER_ONLY] = show sender only; body is hidden.
     * Default: [PRIVACY_FULL].
     */
    var lockScreenPrivacy: String
        get() = prefs.getString(KEY_LOCK_SCREEN_PRIVACY, PRIVACY_FULL) ?: PRIVACY_FULL
        set(v) = prefs.edit().putString(KEY_LOCK_SCREEN_PRIVACY, v).apply()

    /** Move deleted messages to the recycle bin instead of hard-deleting. Default: false. */
    var recycleBinEnabled: Boolean
        get() = prefs.getBoolean(KEY_RECYCLE_BIN_ENABLED, false)
        set(v) = prefs.edit().putBoolean(KEY_RECYCLE_BIN_ENABLED, v).apply()
}
