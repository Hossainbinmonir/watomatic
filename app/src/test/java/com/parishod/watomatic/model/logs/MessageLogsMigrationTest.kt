package com.parishod.watomatic.model.logs

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import com.parishod.watomatic.model.utils.Constants
import com.parishod.watomatic.model.utils.DbUtils
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class MessageLogsMigrationTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        MessageLogsDB.resetInstance()
        context.deleteDatabase(Constants.LOGS_DB_NAME)
    }

    @After
    fun tearDown() {
        MessageLogsDB.resetInstance()
        context.deleteDatabase(Constants.LOGS_DB_NAME)
    }

    @Test
    fun `migration from v2 to v5 preserves existing logs and labels them LEGACY without destructive reset`() = runBlocking {
        // Build a real v2 SQLite database matching schemas/.../2.json
        val dbFile = context.getDatabasePath(Constants.LOGS_DB_NAME)
        dbFile.parentFile?.mkdirs()
        val sqliteDb = SQLiteDatabase.openOrCreateDatabase(dbFile, null)
        try {
            sqliteDb.execSQL("CREATE TABLE IF NOT EXISTS `app_packages` (`index` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `package_name` TEXT)")
            sqliteDb.execSQL(
                "CREATE TABLE IF NOT EXISTS `message_logs` (" +
                    "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                    "`index` INTEGER NOT NULL, " +
                    "`notif_id` TEXT, " +
                    "`notif_title` TEXT, " +
                    "`notif_arrived_time` INTEGER NOT NULL, " +
                    "`notif_is_replied` INTEGER NOT NULL, " +
                    "`notif_replied_msg` TEXT, " +
                    "`notif_reply_time` INTEGER NOT NULL, " +
                    "FOREIGN KEY(`index`) REFERENCES `app_packages`(`index`) ON UPDATE NO ACTION ON DELETE CASCADE )"
            )
            sqliteDb.execSQL("CREATE INDEX IF NOT EXISTS `index_message_logs_index` ON `message_logs` (`index`)")
            sqliteDb.execSQL("INSERT INTO `app_packages` (`index`, `package_name`) VALUES (1, 'com.whatsapp')")
            sqliteDb.execSQL(
                "INSERT INTO `message_logs` (`id`, `index`, `notif_id`, `notif_title`, `notif_arrived_time`, `notif_is_replied`, `notif_replied_msg`, `notif_reply_time`) " +
                    "VALUES (10, 1, 'legacy-uuid-1', 'Alice', 1700000000000, 1, 'Legacy auto reply', 1700000005000)"
            )
            sqliteDb.version = 2
        } finally {
            sqliteDb.close()
        }

        val migratedDb = MessageLogsDB.getInstance(context)
        assertEquals(5, migratedDb.openHelper.readableDatabase.version)

        val dbUtils = DbUtils(context)
        assertEquals(1L, dbUtils.numReplies)
        assertEquals(1700000005000L, dbUtils.getLastRepliedTime("com.whatsapp", "Alice"))

        val timeline = migratedDb.logsDao().observeAllReplyHistory().first()
        assertEquals(1, timeline.size)
        val entry = timeline.first()
        assertEquals("Alice", entry.senderName)
        assertEquals("Legacy auto reply", entry.repliedMessage)
        assertEquals(ReplyHistoryStatus.LEGACY, entry.executionStatus)
        assertNull(entry.attemptId)

        val grouped = migratedDb.logsDao().observeGroupedReplyHistory().first()
        assertEquals(1, grouped.size)
        assertEquals(ReplyHistoryStatus.LEGACY, grouped.first().executionStatus)

        // reply_attempts and notification_dispatch_claims tables are also present and functional after v2 -> v5 migration
        assertEquals(0, migratedDb.replyAttemptDao().getTotalAttemptCountSync())
        assertEquals(0, migratedDb.notificationDispatchClaimDao().getClaimCountSync())
    }

    @Test
    fun `migration from v3 to v5 preserves rule and incoming message fields and labels records LEGACY`() = runBlocking {
        val dbFile = context.getDatabasePath(Constants.LOGS_DB_NAME)
        dbFile.parentFile?.mkdirs()
        val sqliteDb = SQLiteDatabase.openOrCreateDatabase(dbFile, null)
        try {
            sqliteDb.execSQL("CREATE TABLE IF NOT EXISTS `app_packages` (`index` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `package_name` TEXT)")
            sqliteDb.execSQL(
                "CREATE TABLE IF NOT EXISTS `message_logs` (" +
                    "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                    "`index` INTEGER NOT NULL, " +
                    "`notif_id` TEXT, " +
                    "`notif_title` TEXT, " +
                    "`notif_incoming_msg` TEXT, " +
                    "`notif_arrived_time` INTEGER NOT NULL, " +
                    "`notif_is_replied` INTEGER NOT NULL, " +
                    "`notif_replied_msg` TEXT, " +
                    "`matched_rule_name` TEXT, " +
                    "`notif_reply_time` INTEGER NOT NULL, " +
                    "FOREIGN KEY(`index`) REFERENCES `app_packages`(`index`) ON UPDATE NO ACTION ON DELETE CASCADE )"
            )
            sqliteDb.execSQL("CREATE INDEX IF NOT EXISTS `index_message_logs_index` ON `message_logs` (`index`)")
            sqliteDb.execSQL("INSERT INTO `app_packages` (`index`, `package_name`) VALUES (2, 'org.telegram.messenger')")
            sqliteDb.execSQL(
                "INSERT INTO `message_logs` (`id`, `index`, `notif_id`, `notif_title`, `notif_incoming_msg`, `notif_arrived_time`, `notif_is_replied`, `notif_replied_msg`, `matched_rule_name`, `notif_reply_time`) " +
                    "VALUES (20, 2, 'v3-uuid', 'Bob', 'Hours?', 1700001000000, 1, '9 AM - 5 PM', 'Hours Rule', 1700001002000)"
            )
            sqliteDb.version = 3
        } finally {
            sqliteDb.close()
        }

        val migratedDb = MessageLogsDB.getInstance(context)
        assertEquals(5, migratedDb.openHelper.readableDatabase.version)

        val timeline = migratedDb.logsDao().observeAllReplyHistory().first()
        assertEquals(1, timeline.size)
        val entry = timeline.first()
        assertEquals("Bob", entry.senderName)
        assertEquals("Hours?", entry.incomingMessage)
        assertEquals("9 AM - 5 PM", entry.repliedMessage)
        assertEquals("Hours Rule", entry.matchedRuleName)
        assertEquals(ReplyHistoryStatus.LEGACY, entry.executionStatus)
        assertNull(entry.attemptId)
        assertEquals(0, migratedDb.notificationDispatchClaimDao().getClaimCountSync())
    }

    @Test
    fun `migration from v4 to v5 preserves reply_attempts and creates notification_dispatch_claims cleanly`() = runBlocking {
        val dbFile = context.getDatabasePath(Constants.LOGS_DB_NAME)
        dbFile.parentFile?.mkdirs()
        val sqliteDb = SQLiteDatabase.openOrCreateDatabase(dbFile, null)
        try {
            sqliteDb.execSQL("CREATE TABLE IF NOT EXISTS `app_packages` (`index` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `package_name` TEXT)")
            sqliteDb.execSQL(
                "CREATE TABLE IF NOT EXISTS `message_logs` (" +
                    "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                    "`index` INTEGER NOT NULL, " +
                    "`notif_id` TEXT, " +
                    "`notif_title` TEXT, " +
                    "`notif_incoming_msg` TEXT, " +
                    "`notif_arrived_time` INTEGER NOT NULL, " +
                    "`notif_is_replied` INTEGER NOT NULL, " +
                    "`notif_replied_msg` TEXT, " +
                    "`matched_rule_name` TEXT, " +
                    "`notif_reply_time` INTEGER NOT NULL, " +
                    "`execution_status` TEXT NOT NULL DEFAULT 'LEGACY', " +
                    "`attempt_id` TEXT, " +
                    "FOREIGN KEY(`index`) REFERENCES `app_packages`(`index`) ON UPDATE NO ACTION ON DELETE CASCADE )"
            )
            sqliteDb.execSQL("CREATE INDEX IF NOT EXISTS `index_message_logs_index` ON `message_logs` (`index`)")
            sqliteDb.execSQL("CREATE INDEX IF NOT EXISTS `index_message_logs_attempt_id` ON `message_logs` (`attempt_id`)")
            sqliteDb.execSQL(
                "CREATE TABLE IF NOT EXISTS `reply_attempts` (" +
                    "`attempt_id` TEXT NOT NULL, " +
                    "`package_name` TEXT NOT NULL, " +
                    "`sender_name` TEXT, " +
                    "`incoming_message` TEXT, " +
                    "`notification_key` TEXT, " +
                    "`reply_text` TEXT, " +
                    "`source_type` TEXT NOT NULL, " +
                    "`source_label` TEXT, " +
                    "`status` TEXT NOT NULL, " +
                    "`dispatch_attempted` INTEGER NOT NULL, " +
                    "`dispatched_normally` INTEGER NOT NULL, " +
                    "`failure_category` TEXT, " +
                    "`created_at_ms` INTEGER NOT NULL, " +
                    "`updated_at_ms` INTEGER NOT NULL, " +
                    "PRIMARY KEY(`attempt_id`)" +
                    ")"
            )
            sqliteDb.execSQL("CREATE INDEX IF NOT EXISTS `index_reply_attempts_updated_at_ms` ON `reply_attempts` (`updated_at_ms`)")
            sqliteDb.execSQL("CREATE INDEX IF NOT EXISTS `index_reply_attempts_package_name_sender_name` ON `reply_attempts` (`package_name`, `sender_name`)")

            sqliteDb.execSQL("INSERT INTO `app_packages` (`index`, `package_name`) VALUES (1, 'com.whatsapp')")
            sqliteDb.execSQL(
                "INSERT INTO `message_logs` (`id`, `index`, `notif_id`, `notif_title`, `notif_incoming_msg`, `notif_arrived_time`, `notif_is_replied`, `notif_replied_msg`, `matched_rule_name`, `notif_reply_time`, `execution_status`, `attempt_id`) " +
                    "VALUES (30, 1, 'att-v4-1', 'Carol', 'Ping', 1700002000000, 1, 'Pong', 'Default Reply', 1700002001000, 'DISPATCHED', 'att-v4-1')"
            )
            sqliteDb.execSQL(
                "INSERT INTO `reply_attempts` (`attempt_id`, `package_name`, `sender_name`, `incoming_message`, `notification_key`, `reply_text`, `source_type`, `source_label`, `status`, `dispatch_attempted`, `dispatched_normally`, `failure_category`, `created_at_ms`, `updated_at_ms`) " +
                    "VALUES ('att-v4-1', 'com.whatsapp', 'Carol', 'Ping', 'key-30', 'Pong', 'DEFAULT', 'Default Reply', 'DISPATCHED', 1, 1, NULL, 1700002000000, 1700002001000)"
            )
            sqliteDb.version = 4
        } finally {
            sqliteDb.close()
        }

        val migratedDb = MessageLogsDB.getInstance(context)
        assertEquals(5, migratedDb.openHelper.readableDatabase.version)

        val preservedAttempt = migratedDb.replyAttemptDao().getAttemptByIdSync("att-v4-1")
        assertEquals("DISPATCHED", preservedAttempt?.status)
        assertEquals("Pong", preservedAttempt?.replyText)
        assertNull(preservedAttempt?.eventKey)

        val timeline = migratedDb.logsDao().observeAllReplyHistory().first()
        assertEquals(1, timeline.size)
        assertEquals("att-v4-1", timeline.first().attemptId)
        assertEquals(ReplyHistoryStatus.DISPATCHED, timeline.first().executionStatus)

        val dbUtils = DbUtils(context)
        val claimOutcome = dbUtils.tryAcquireNotificationClaim(
            "evt_v5_check",
            "att-v5-check",
            "com.whatsapp",
            "carol",
            "key-31",
            1700003000000L,
            false
        )
        assertTrue(claimOutcome.acquired)
        assertEquals(1, migratedDb.notificationDispatchClaimDao().getClaimCountSync())
    }

    @Test
    fun `fresh v5 database initializes schema cleanly and supports attempts, claims, and logs`() = runBlocking {
        val db = MessageLogsDB.getInstance(context)
        assertEquals(5, db.openHelper.writableDatabase.version)
        assertEquals(0, db.replyAttemptDao().getTotalAttemptCountSync())
        assertEquals(0, db.notificationDispatchClaimDao().getClaimCountSync())
        assertEquals(0L, db.logsDao().getNumReplies())

        db.appPackageDao().insertAppPackage(AppPackage("com.whatsapp"))
        val pkgIdx = db.appPackageDao().getPackageIndex("com.whatsapp")
        assertTrue(pkgIdx > 0)
    }

    @Test
    fun `destructive migration fallback is disabled when unexpected version mismatch occurs`() {
        val dbFile = context.getDatabasePath(Constants.LOGS_DB_NAME)
        dbFile.parentFile?.mkdirs()
        val sqliteDb = SQLiteDatabase.openOrCreateDatabase(dbFile, null)
        try {
            sqliteDb.execSQL("CREATE TABLE IF NOT EXISTS user_marker (id INTEGER PRIMARY KEY)")
            sqliteDb.execSQL("INSERT INTO user_marker (id) VALUES (999)")
            sqliteDb.version = 99
        } finally {
            sqliteDb.close()
        }

        val db = MessageLogsDB.getInstance(context)
        try {
            db.logsDao().getNumReplies()
            fail("Expected IllegalStateException because destructive migration fallback must be disabled")
        } catch (expected: IllegalStateException) {
            assertTrue(expected.message?.contains("Migration") == true)
        }
    }
}
