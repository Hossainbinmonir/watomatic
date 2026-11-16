package com.parishod.watomatic.model.rules

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [AutoReplyRule::class, RuleExecutionLog::class],
    version = 2,
    exportSchema = false
)
@TypeConverters(RuleTypeConverters::class)
abstract class AutoReplyRulesDatabase : RoomDatabase() {
    abstract fun ruleDao(): AutoReplyRuleDao
    abstract fun executionLogDao(): RuleExecutionLogDao

    companion object {
        const val DB_NAME = "auto_reply_rules_db"

        @Volatile
        private var instance: AutoReplyRulesDatabase? = null

        val MIGRATION_1_2: Migration = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE auto_reply_rules ADD COLUMN category TEXT NOT NULL DEFAULT ''")
            }
        }

        @JvmStatic
        fun getInstance(context: Context): AutoReplyRulesDatabase {
            return instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AutoReplyRulesDatabase::class.java,
                    DB_NAME
                )
                    .addMigrations(MIGRATION_1_2)
                    .build()
                    .also { instance = it }
            }
        }

        @JvmStatic
        @androidx.annotation.VisibleForTesting
        fun resetInstance() {
            synchronized(this) {
                instance?.close()
                instance = null
            }
        }
    }
}
