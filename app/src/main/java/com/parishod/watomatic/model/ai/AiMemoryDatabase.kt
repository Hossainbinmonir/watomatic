package com.parishod.watomatic.model.ai

import android.content.Context
import androidx.annotation.VisibleForTesting
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        AiConversationMessage::class,
        AiNote::class,
        AiReplyLog::class
    ],
    version = 1,
    exportSchema = false
)
abstract class AiMemoryDatabase : RoomDatabase() {
    abstract fun aiMemoryDao(): AiMemoryDao

    companion object {
        const val DB_NAME = "ai_memory_db"

        @Volatile
        private var instance: AiMemoryDatabase? = null

        @JvmStatic
        fun getInstance(context: Context): AiMemoryDatabase {
            return instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AiMemoryDatabase::class.java,
                    DB_NAME
                ).build().also { instance = it }
            }
        }

        @JvmStatic
        @VisibleForTesting
        fun resetInstance() {
            synchronized(this) {
                instance?.close()
                instance = null
            }
        }
    }
}
