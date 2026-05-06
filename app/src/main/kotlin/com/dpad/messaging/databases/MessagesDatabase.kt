package com.dpad.messaging.databases

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.dpad.messaging.databases.daos.*
import com.dpad.messaging.models.*

@Database(
    entities = [
        Conversation::class,
        Message::class,
        Attachment::class,
        Draft::class,
        RecycleBinMessage::class,
        BlockedKeyword::class
    ],
    version = 1,
    exportSchema = false
)
abstract class MessagesDatabase : RoomDatabase() {

    abstract fun conversationsDao(): ConversationsDao
    abstract fun messagesDao(): MessagesDao
    abstract fun attachmentsDao(): AttachmentsDao
    abstract fun draftsDao(): DraftsDao
    abstract fun blockedKeywordsDao(): BlockedKeywordsDao

    companion object {
        private const val DB_NAME = "dpad_messages.db"

        @Volatile
        private var instance: MessagesDatabase? = null

        fun getInstance(context: Context): MessagesDatabase {
            return instance ?: synchronized(this) {
                instance ?: buildDatabase(context).also { instance = it }
            }
        }

        private fun buildDatabase(context: Context): MessagesDatabase {
            return Room.databaseBuilder(
                context.applicationContext,
                MessagesDatabase::class.java,
                DB_NAME
            )
                .fallbackToDestructiveMigration()
                .build()
        }
    }
}
