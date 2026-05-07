package com.dpad.messaging.helpers

import android.app.Activity
import android.content.Context
import android.os.Build
import android.view.View
import android.widget.TextView
import androidx.annotation.ColorInt
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat
import com.dpad.messaging.R

object ThemeManager {

    fun applyThemeMode(mode: String) {
        val nightMode = when (mode) {
            Prefs.THEME_LIGHT -> AppCompatDelegate.MODE_NIGHT_NO
            Prefs.THEME_DARK -> AppCompatDelegate.MODE_NIGHT_YES
            else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
        }
        AppCompatDelegate.setDefaultNightMode(nightMode)
    }

    fun applyAccentColor(activity: Activity) {
        val accentColor = accentColor(activity)
        val colorOnPrimary = ContextCompat.getColor(activity, R.color.colorOnPrimary)

        activity.window.apply {
            statusBarColor = accentColor
            navigationBarColor = accentColor
        }

        activity.findViewById<View>(android.R.id.content)?.apply {
            setBackgroundColor(ContextCompat.getColor(context, R.color.colorBackground))
        }
    }

    @ColorInt
    fun accentColor(context: Context): Int {
        val colorRes = when (Prefs.get().appAccent) {
            Prefs.ACCENT_GREEN -> R.color.accent_green
            Prefs.ACCENT_ORANGE -> R.color.accent_orange
            Prefs.ACCENT_ROSE -> R.color.accent_rose
            else -> R.color.accent_blue
        }
        return ContextCompat.getColor(context, colorRes)
    }
}
