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
        private const val KEY_MUTED_THREADS       = "muted_threads"
        private const val KEY_APP_THEME_MODE      = "app_theme_mode"
        private const val KEY_APP_ACCENT          = "app_accent"

        const val PRIVACY_FULL        = "full"
        const val PRIVACY_SENDER_ONLY = "sender_only"
        const val THEME_SYSTEM        = "system"
        const val THEME_LIGHT         = "light"
        const val THEME_DARK          = "dark"
        const val ACCENT_BLUE         = "blue"
        const val ACCENT_GREEN        = "green"
        const val ACCENT_ORANGE       = "orange"
        const val ACCENT_ROSE         = "rose"

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

    /** App-wide theme mode: system/light/dark. Default: system. */
    var appThemeMode: String
        get() = prefs.getString(KEY_APP_THEME_MODE, THEME_SYSTEM) ?: THEME_SYSTEM
        set(v) = prefs.edit().putString(KEY_APP_THEME_MODE, v).apply()

    /** App-wide accent color choice. Default: blue. */
    var appAccent: String
        get() = prefs.getString(KEY_APP_ACCENT, ACCENT_BLUE) ?: ACCENT_BLUE
        set(v) = prefs.edit().putString(KEY_APP_ACCENT, v).apply()

    /** Returns true when notifications are muted for [threadId]. */
    fun isThreadMuted(threadId: Long): Boolean {
        val muted = prefs.getStringSet(KEY_MUTED_THREADS, emptySet()) ?: emptySet()
        return muted.contains(threadId.toString())
    }

    /** Enables or disables notifications for [threadId]. */
    fun setThreadMuted(threadId: Long, muted: Boolean) {
        val current = prefs.getStringSet(KEY_MUTED_THREADS, emptySet())?.toMutableSet()
            ?: mutableSetOf()
        val key = threadId.toString()
        if (muted) current.add(key) else current.remove(key)
        prefs.edit().putStringSet(KEY_MUTED_THREADS, current).apply()
    }
}
