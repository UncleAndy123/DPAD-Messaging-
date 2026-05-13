package com.dpad.messaging.activities

import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import androidx.appcompat.app.AppCompatActivity
import com.dpad.messaging.helpers.Prefs

/**
 * Base activity that applies the user's chosen UI scale by overriding the
 * system font scale before inflation. All other activities extend this instead
 * of [AppCompatActivity] directly.
 */
abstract class BaseActivity : AppCompatActivity() {

    override fun attachBaseContext(newBase: Context) {
        // Guard: Prefs may not yet be initialised during very early startup (SplashActivity).
        val scale = try { Prefs.get().uiScaleFactor } catch (_: Exception) { 1.0f }
        if (scale == 1.0f) {
            super.attachBaseContext(newBase)
            return
        }
        val systemFontScale = Resources.getSystem().configuration.fontScale
        val config = Configuration(newBase.resources.configuration)
        config.fontScale = systemFontScale * scale
        val scaledContext = newBase.createConfigurationContext(config)
        super.attachBaseContext(scaledContext)
    }
}
