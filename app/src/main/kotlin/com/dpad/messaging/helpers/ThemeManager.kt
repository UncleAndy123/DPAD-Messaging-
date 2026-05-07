package com.dpad.messaging.helpers

import android.content.Context
import androidx.annotation.ColorInt
import androidx.appcompat.app.AppCompatDelegate
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

    @ColorInt
    fun accentColor(context: Context): Int {
        val colorRes = when (Prefs.get().appAccent) {
            Prefs.ACCENT_GREEN -> R.color.accent_green
            Prefs.ACCENT_ORANGE -> R.color.accent_orange
            Prefs.ACCENT_ROSE -> R.color.accent_rose
            else -> R.color.accent_blue
        }
        return context.getColor(colorRes)
    }
}
