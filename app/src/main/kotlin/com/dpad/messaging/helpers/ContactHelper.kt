package com.dpad.messaging.helpers

import android.content.Context
import android.net.Uri
import android.provider.ContactsContract
import android.util.LruCache

/**
 * Resolves phone numbers to contact display names and photo URIs.
 * Uses an LruCache to avoid repeated ContentProvider queries.
 */
class ContactHelper(private val context: Context) {

    data class ContactInfo(
        val displayName: String,
        val photoUri: String = ""
    )

    private val cache = LruCache<String, ContactInfo>(256)

    /** Returns the best display name for a phone number, or the number itself if no contact found. */
    fun getDisplayName(phoneNumber: String): String {
        return resolve(phoneNumber)?.displayName ?: phoneNumber
    }

    /** Returns ContactInfo (name + photo URI) for a phone number, or null if unknown. */
    fun resolve(phoneNumber: String): ContactInfo? {
        if (phoneNumber.isBlank()) return null

        val cached = cache.get(phoneNumber)
        if (cached != null) return cached

        val uri = Uri.withAppendedPath(
            ContactsContract.PhoneLookup.CONTENT_FILTER_URI,
            Uri.encode(phoneNumber)
        )
        val projection = arrayOf(
            ContactsContract.PhoneLookup.DISPLAY_NAME,
            ContactsContract.PhoneLookup.PHOTO_THUMBNAIL_URI
        )

        return try {
            context.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val name = cursor.getString(0) ?: return@use null
                    val photo = cursor.getString(1) ?: ""
                    ContactInfo(name, photo).also { cache.put(phoneNumber, it) }
                } else null
            }
        } catch (e: Exception) {
            null
        }
    }

    fun clearCache() {
        cache.evictAll()
    }
}
