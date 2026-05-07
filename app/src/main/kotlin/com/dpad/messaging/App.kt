package com.dpad.messaging

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import com.dpad.messaging.databases.MessagesDatabase
import com.dpad.messaging.helpers.ContactHelper
import com.dpad.messaging.helpers.Prefs
import com.dpad.messaging.helpers.ThemeManager

class App : Application() {

    lateinit var database: MessagesDatabase
        private set

    lateinit var contactHelper: ContactHelper
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        database = MessagesDatabase.getInstance(this)
        contactHelper = ContactHelper(this)
        Prefs.init(this)
        ThemeManager.applyThemeMode(Prefs.get().appThemeMode)
        createNotificationChannels()
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)

            // Default incoming message channel
            val incomingChannel = NotificationChannel(
                CHANNEL_MESSAGES,
                "Messages",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Incoming SMS and MMS messages"
                enableVibration(true)
                enableLights(true)
            }
            manager.createNotificationChannel(incomingChannel)

            // Send failure channel
            val failureChannel = NotificationChannel(
                CHANNEL_SEND_FAILURE,
                "Send Failures",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Notifications when a message fails to send"
            }
            manager.createNotificationChannel(failureChannel)
        }
    }

    companion object {
        const val CHANNEL_MESSAGES = "dpad_messages_incoming"
        const val CHANNEL_SEND_FAILURE = "dpad_messages_send_failure"

        @Volatile
        private var instance: App? = null

        fun get(): App = instance ?: throw IllegalStateException("App not initialized")
    }
}
