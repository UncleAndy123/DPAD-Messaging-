package com.dpad.messaging.util

import android.content.Context
import android.os.Build
import android.telephony.SmsManager
import android.telephony.SubscriptionManager
import android.util.Log

object SmsHelper {
    private const val TAG = "SmsHelper"

    fun getDefaultSmsSubId(context: Context): Int {
        return try {
            SubscriptionManager.getDefaultSmsSubscriptionId()
                .takeIf { it != SubscriptionManager.INVALID_SUBSCRIPTION_ID }
                ?: SubscriptionManager.getDefaultSubscriptionId()
        } catch (e: Exception) {
            Log.w(TAG, "getDefaultSmsSubId failed", e)
            SubscriptionManager.INVALID_SUBSCRIPTION_ID
        }
    }

    fun getSmsManager(context: Context): SmsManager {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val base = context.getSystemService(SmsManager::class.java)
            val subId = getDefaultSmsSubId(context)
            if (subId != SubscriptionManager.INVALID_SUBSCRIPTION_ID) {
                try {
                    base.createForSubscriptionId(subId)
                } catch (e: Exception) {
                    Log.w(TAG, "createForSubscriptionId failed for subId=$subId, falling back", e)
                    base
                }
            } else {
                base
            }
        } else {
            SmsManager.getDefault()
        }
    }
}
