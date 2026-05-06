package com.dpad.messaging.debug

import android.app.Activity
import android.content.ContentUris
import android.content.ContentValues
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Telephony
import android.util.Log
import androidx.activity.result.contract.ActivityResultContracts

/**
 * Temporary debug-only activity that deletes all SMS/MMS conversations from
 * the system provider. It is guarded by BuildConfig.DEBUG and requires a
 * secret token extra to run so it isn't accidentally triggered.
 */
class DebugDeleterActivity : Activity() {
    companion object {
        private const val TAG = "DPAD_MSG"
        const val ACTION_CLEAR_ALL = "com.dpad.messaging.debug.CLEAR_ALL_MESSAGES"
        const val EXTRA_TOKEN = "extra_debug_token"
        // A short token to require in the Intent to avoid accidental wipe.
        const val SECRET_TOKEN = "dpad-clear-2026"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!com.dpad.messaging.BuildConfig.DEBUG) {
            finish(); return
        }
        val action = intent?.action
        val token = intent?.getStringExtra(EXTRA_TOKEN)
        if (action != ACTION_CLEAR_ALL || token != SECRET_TOKEN) {
            Log.w(TAG, "DebugDeleter: refused invalid intent")
            finish(); return
        }

        Thread {
            try {
                // Delete sms, mms and conversations
                contentResolver.delete(Uri.parse("content://sms"), null, null)
                contentResolver.delete(Uri.parse("content://mms"), null, null)
                contentResolver.delete(Uri.parse("content://mms-sms/conversations"), null, null)
                Log.i(TAG, "DebugDeleter: deleted sms/mms/conversations")
            } catch (e: Exception) {
                Log.e(TAG, "DebugDeleter: delete failed", e)
            } finally {
                runOnUiThread { finish() }
            }
        }.start()
    }
}
