package com.dpad.messaging.helpers

import android.content.Context
import com.dpad.messaging.App
import com.dpad.messaging.models.BackupData
import com.dpad.messaging.models.BackupPreferences
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

object BackupManager {

    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = false
        explicitNulls = false
    }

    suspend fun backup(context: Context): String {
        val db = App.get().database
        val prefs = Prefs.get()

        val data = BackupData(
            timestamp = System.currentTimeMillis(),
            conversations = db.conversationsDao().getConversations(),
            messages = db.messagesDao().getAllMessages(),
            attachments = db.attachmentsDao().getAllAttachments(),
            drafts = db.draftsDao().getAllDrafts(),
            recycleBinMessages = db.messagesDao().getRecycleBinMessages(),
            blockedKeywords = db.blockedKeywordsDao().getAll(),
            blockedNumbers = db.blockedNumbersDao().getAll(),
            preferences = BackupPreferences(
                deliveryReports = prefs.deliveryReports,
                sendOnEnter = prefs.sendOnEnter,
                characterCounter = prefs.characterCounter,
                sendGroupMessageMms = prefs.sendGroupMessageMms,
                useLibrarySmsSending = prefs.useLibrarySmsSending,
                recycleBinEnabled = prefs.recycleBinEnabled,
                lockScreenPrivacy = prefs.lockScreenPrivacy,
                appThemeMode = prefs.appThemeMode,
                appAccent = prefs.appAccent,
                dateFormat = prefs.dateFormat,
                timeFormat = prefs.timeFormat,
                uiScale = prefs.uiScale,
                mmsProxyHost = prefs.mmsProxyHost,
                mmsProxyPort = prefs.mmsProxyPort,
                mutedThreads = prefs.getMutedThreadIds().map { it.toString() }.toSet(),
                pinnedThreads = prefs.getPinnedThreadIds().map { it.toString() }.toSet(),
                archivedThreads = prefs.getArchivedThreadIds().map { it.toString() }.toSet()
            )
        )

        return json.encodeToString(data)
    }

    suspend fun restore(context: Context, backupJson: String): BackupResult {
        val db = App.get().database
        val prefs = Prefs.get()

        val data = try {
            json.decodeFromString<BackupData>(backupJson)
        } catch (e: Exception) {
            return BackupResult(false, "Invalid backup file: ${e.message}")
        }

        if (data.version != 1) {
            return BackupResult(false, "Unsupported backup version: ${data.version}")
        }

        try {
            // Clear existing data before restore
            db.conversationsDao().deleteAllConversations()
            db.messagesDao().deleteAllMessages()
            db.attachmentsDao().deleteAllAttachments()
            db.draftsDao().deleteAllDrafts()
            db.messagesDao().emptyRecycleBin()
            db.blockedKeywordsDao().deleteAll()
            db.blockedNumbersDao().deleteAll()

            // Insert restored data in batches
            if (data.conversations.isNotEmpty()) {
                db.conversationsDao().insertConversations(data.conversations)
            }
            if (data.messages.isNotEmpty()) {
                db.messagesDao().insertMessages(data.messages)
            }
            if (data.attachments.isNotEmpty()) {
                db.attachmentsDao().insertAttachments(data.attachments)
            }
            for (draft in data.drafts) {
                db.draftsDao().insertDraft(draft)
            }
            for (msg in data.recycleBinMessages) {
                db.messagesDao().insertRecycleBinMessage(msg)
            }
            for (kw in data.blockedKeywords) {
                db.blockedKeywordsDao().insert(kw)
            }
            for (num in data.blockedNumbers) {
                db.blockedNumbersDao().insert(num)
            }

            // Restore preferences
            val p = data.preferences
            prefs.deliveryReports = p.deliveryReports
            prefs.sendOnEnter = p.sendOnEnter
            prefs.characterCounter = p.characterCounter
            prefs.sendGroupMessageMms = p.sendGroupMessageMms
            prefs.useLibrarySmsSending = p.useLibrarySmsSending
            prefs.recycleBinEnabled = p.recycleBinEnabled
            prefs.lockScreenPrivacy = p.lockScreenPrivacy
            prefs.appThemeMode = p.appThemeMode
            prefs.appAccent = p.appAccent
            prefs.dateFormat = p.dateFormat
            prefs.timeFormat = p.timeFormat
            prefs.uiScale = p.uiScale
            prefs.mmsProxyHost = p.mmsProxyHost
            prefs.mmsProxyPort = p.mmsProxyPort
            for (id in p.mutedThreads) {
                id.toLongOrNull()?.let { prefs.setThreadMuted(it, true) }
            }
            for (id in p.pinnedThreads) {
                id.toLongOrNull()?.let { prefs.setThreadPinned(it, true) }
            }
            for (id in p.archivedThreads) {
                id.toLongOrNull()?.let { prefs.setThreadArchived(it, true) }
            }

            return BackupResult(true, "Restored ${data.messages.size} messages, ${data.conversations.size} conversations")
        } catch (e: Exception) {
            return BackupResult(false, "Restore failed: ${e.message}")
        }
    }
}

data class BackupResult(
    val success: Boolean,
    val message: String
)
