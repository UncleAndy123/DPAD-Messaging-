package com.dpad.messaging.activities

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity

/**
 * Entry point. Redirects immediately to MainActivity.
 * Exists separately so we can add onboarding / permission checks later
 * without touching MainActivity's launch flow.
 */
class SplashActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        startActivity(Intent(this, MainActivity::class.java).apply {
            // Forward any intent extras (e.g. shortcut launch with thread_id)
            if (intent.extras != null) putExtras(intent.extras!!)
        })
        finish()
    }
}
