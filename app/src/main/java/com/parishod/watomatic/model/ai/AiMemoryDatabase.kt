package com.parishod.watomatic.model.ai

import android.content.Context
import androidx.annotation.VisibleForTesting
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        AiConversationMessage::class,
        AiNote::class,
        AiReplyLog::class
    ],
    version = 2,
    exportSchema = false
)
abstract class AiMemoryDatabase : RoomDatabase() {
    abstract fun aiMemoryDao(): AiMemoryDao

    companion object {
        const val DB_NAME = "ai_memory_db"

        @JvmField
        val MIGRATION_1_2: Migration = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `ai_conversation_messages` ADD COLUMN `attempt_id` TEXT")
                db.execSQL("ALTER TABLE `ai_conversation_messages` ADD COLUMN `incoming_event_key` TEXT")
                db.execSQL("ALTER TABLE `ai_conversation_messages` ADD COLUMN `execution_status` TEXT NOT NULL DEFAULT 'LEGACY'")
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_ai_conversation_messages_attempt_id_role` " +
                        "ON `ai_conversation_messages` (`attempt_id`, `role`)"
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_ai_conversation_messages_incoming_event_key` " +
                        "ON `ai_conversation_messages` (`incoming_event_key`)"
                )

                db.execSQL("ALTER TABLE `ai_notes` ADD COLUMN `attempt_id` TEXT")
                db.execSQL("ALTER TABLE `ai_notes` ADD COLUMN `note_dedup_key` TEXT")
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_ai_notes_attempt_id_note_dedup_key` " +
                        "ON `ai_notes` (`attempt_id`, `note_dedup_key`)"
                )

                db.execSQL("ALTER TABLE `ai_reply_logs` ADD COLUMN `attempt_id` TEXT")
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_ai_reply_logs_attempt_id` " +
                        "ON `ai_reply_logs` (`attempt_id`)"
                )
            }
        }

        @Volatile
        private var instance: AiMemoryDatabase? = null

        @JvmStatic
        fun getInstance(context: Context): AiMemoryDatabase {
            return instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AiMemoryDatabase::class.java,
                    DB_NAME
                )
                    .addMigrations(MIGRATION_1_2)
                    .build()
                    .also { instance = it }
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
