package com.dpad.messaging.data.repository

import android.content.Context
import android.net.Uri
import android.telephony.SmsManager
import android.os.Build
import com.klinker.android.send_message.Message
import com.klinker.android.send_message.Settings
import com.klinker.android.send_message.Transaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class SmsSender(private val context: Context) {

    suspend fun sendSms(address: String, body: String) = withContext(Dispatchers.IO) {
        val manager = getSmsManager()
        val parts = manager.divideMessage(body)
        if (parts.size == 1) {
            manager.sendTextMessage(address, null, body, null, null)
        } else {
            manager.sendMultipartTextMessage(address, null, parts, null, null)
        }
    }

    suspend fun sendMms(addresses: List<String>, body: String, imageBytes: ByteArray? = null, mimeType: String? = null) = withContext(Dispatchers.IO) {
        val settings = Settings()
        settings.useSystemSending = true
        settings.group = addresses.size > 1
        settings.sendLongAsMms = true
        settings.sendLongAsMmsAfter = 3

        val transaction = Transaction(context, settings)
        val message = Message(body, addresses.toTypedArray())
        
        if (imageBytes != null && mimeType != null) {
            message.addMedia(imageBytes, mimeType)
        }

        transaction.sendNewMessage(message, Transaction.NO_THREAD_ID)
    }

    @Suppress("DEPRECATION")
    private fun getSmsManager(): SmsManager =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            context.getSystemService(SmsManager::class.java)
        else
            SmsManager.getDefault()
}

