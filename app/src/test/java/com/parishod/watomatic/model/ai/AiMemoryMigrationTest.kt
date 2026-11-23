package com.parishod.watomatic.model.ai

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import com.parishod.watomatic.service.ai.AiPromptAndPolicyHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
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
class AiMemoryMigrationTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        AiMemoryDatabase.resetInstance()
        context.deleteDatabase(AiMemoryDatabase.DB_NAME)
    }

    @After
    fun tearDown() {
        AiMemoryDatabase.resetInstance()
        context.deleteDatabase(AiMemoryDatabase.DB_NAME)
    }

    @Test
    fun `migration from v1 to v2 preserves existing messages, notes, and reply logs as LEGACY`() = runBlocking {
        val dbFile = context.getDatabasePath(AiMemoryDatabase.DB_NAME)
        dbFile.parentFile?.mkdirs()
        val sqliteDb = SQLiteDatabase.openOrCreateDatabase(dbFile, null)
        try {
            sqliteDb.execSQL(
                "CREATE TABLE IF NOT EXISTS `ai_conversation_messages` (" +
                    "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                    "`package_name` TEXT NOT NULL, " +
                    "`sender_key` TEXT NOT NULL, " +
                    "`sender_display_name` TEXT NOT NULL, " +
                    "`role` TEXT NOT NULL, " +
                    "`content` TEXT NOT NULL, " +
                    "`timestamp` INTEGER NOT NULL)"
            )
            sqliteDb.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_ai_conversation_messages_package_name_sender_key_timestamp` " +
                    "ON `ai_conversation_messages` (`package_name`, `sender_key`, `timestamp`)"
            )
            sqliteDb.execSQL(
                "CREATE TABLE IF NOT EXISTS `ai_notes` (" +
                    "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                    "`sender_name` TEXT NOT NULL, " +
                    "`package_name` TEXT NOT NULL, " +
                    "`note_text` TEXT NOT NULL, " +
                    "`source_message` TEXT NOT NULL, " +
                    "`created_at` INTEGER NOT NULL, " +
                    "`is_completed` INTEGER NOT NULL)"
            )
            sqliteDb.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_ai_notes_is_completed_created_at` " +
                    "ON `ai_notes` (`is_completed`, `created_at`)"
            )
            sqliteDb.execSQL(
                "CREATE TABLE IF NOT EXISTS `ai_reply_logs` (" +
                    "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                    "`package_name` TEXT NOT NULL, " +
                    "`sender_key` TEXT NOT NULL, " +
                    "`replied_at` INTEGER NOT NULL)"
            )
            sqliteDb.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_ai_reply_logs_package_name_sender_key_replied_at` " +
                    "ON `ai_reply_logs` (`package_name`, `sender_key`, `replied_at`)"
            )

            sqliteDb.execSQL(
                "INSERT INTO `ai_conversation_messages` (`id`, `package_name`, `sender_key`, `sender_display_name`, `role`, `content`, `timestamp`) " +
                    "VALUES (1, 'com.whatsapp', 'alice', 'Alice', 'user', 'Legacy hello', 1700000000000)"
            )
            sqliteDb.execSQL(
                "INSERT INTO `ai_conversation_messages` (`id`, `package_name`, `sender_key`, `sender_display_name`, `role`, `content`, `timestamp`) " +
                    "VALUES (2, 'com.whatsapp', 'alice', 'Alice', 'assistant', 'Legacy reply', 1700000001000)"
            )
            sqliteDb.execSQL(
                "INSERT INTO `ai_notes` (`id`, `sender_name`, `package_name`, `note_text`, `source_message`, `created_at`, `is_completed`) " +
                    "VALUES (10, 'Alice', 'com.whatsapp', 'Legacy note', 'Call me', 1700000002000, 0)"
            )
            sqliteDb.execSQL(
                "INSERT INTO `ai_reply_logs` (`id`, `package_name`, `sender_key`, `replied_at`) " +
                    "VALUES (20, 'com.whatsapp', 'alice', 1700000001000)"
            )
            sqliteDb.version = 1
        } finally {
            sqliteDb.close()
        }

        val migratedDb = AiMemoryDatabase.getInstance(context)
        withContext(Dispatchers.IO) {
            assertEquals(3, migratedDb.openHelper.readableDatabase.version)
            val dao = migratedDb.aiMemoryDao()

            val historyAsc = dao.getRecentMessagesDescSync("com.whatsapp", "alice", 10).asReversed()
            assertEquals(2, historyAsc.size)
            assertEquals("Legacy hello", historyAsc[0].content)
            assertEquals(AiConversationMessage.STATUS_LEGACY, historyAsc[0].executionStatus)
            assertNull(historyAsc[0].attemptId)
            assertEquals("Legacy reply", historyAsc[1].content)
            assertEquals(AiConversationMessage.STATUS_LEGACY, historyAsc[1].executionStatus)
            assertNull(historyAsc[1].attemptId)

            val notes = dao.observeAllNotes().first()
            assertEquals(1, notes.size)
            assertEquals("Legacy note", notes[0].noteText)
            assertEquals("alice", notes[0].senderKey)
            assertNull(notes[0].attemptId)

            assertEquals(1700000001000L, dao.getLastAiReplyTimeSync("com.whatsapp", "alice"))
            assertEquals(2, dao.getConversationMessageCountSync())

            // Verify new unique index permits multiple legacy NULL attempt_ids while enforcing uniqueness on non-null attempt_ids
            val repo = AiMemoryRepository(dao)
            val inserted = repo.recordIncomingUserMessage(
                packageName = "com.whatsapp",
                senderKey = "alice",
                senderDisplay = "Alice",
                incomingMessage = "New message after upgrade",
                incomingEventKey = AiPromptAndPolicyHelper.buildIncomingEventKey(
                    "k1", 1700000005000L, 1700000005000L, "com.whatsapp", "alice", "New message after upgrade"
                ),
                keepCount = 20,
                timestampMs = 1700000005000L
            )
            assertTrue(inserted)
            assertEquals(3, dao.getConversationMessageCountSync())
        }
    }

    @Test
    fun `migration from v2 to v3 backfills sender_key isolates unknown and supports group vs direct notes`() = runBlocking {
        val dbFile = context.getDatabasePath(AiMemoryDatabase.DB_NAME)
        dbFile.parentFile?.mkdirs()
        val sqliteDb = SQLiteDatabase.openOrCreateDatabase(dbFile, null)
        try {
            sqliteDb.execSQL(
                "CREATE TABLE IF NOT EXISTS `ai_conversation_messages` (" +
                    "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                    "`package_name` TEXT NOT NULL, " +
                    "`sender_key` TEXT NOT NULL, " +
                    "`sender_display_name` TEXT NOT NULL, " +
                    "`role` TEXT NOT NULL, " +
                    "`content` TEXT NOT NULL, " +
                    "`timestamp` INTEGER NOT NULL, " +
                    "`attempt_id` TEXT, " +
                    "`incoming_event_key` TEXT, " +
                    "`execution_status` TEXT NOT NULL DEFAULT 'LEGACY')"
            )
            sqliteDb.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_ai_conversation_messages_package_name_sender_key_timestamp` " +
                    "ON `ai_conversation_messages` (`package_name`, `sender_key`, `timestamp`)"
            )
            sqliteDb.execSQL(
                "CREATE UNIQUE INDEX IF NOT EXISTS `index_ai_conversation_messages_attempt_id_role` " +
                    "ON `ai_conversation_messages` (`attempt_id`, `role`)"
            )
            sqliteDb.execSQL(
                "CREATE UNIQUE INDEX IF NOT EXISTS `index_ai_conversation_messages_incoming_event_key` " +
                    "ON `ai_conversation_messages` (`incoming_event_key`)"
            )
            sqliteDb.execSQL(
                "CREATE TABLE IF NOT EXISTS `ai_notes` (" +
                    "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                    "`sender_name` TEXT NOT NULL, " +
                    "`package_name` TEXT NOT NULL, " +
                    "`note_text` TEXT NOT NULL, " +
                    "`source_message` TEXT NOT NULL, " +
                    "`created_at` INTEGER NOT NULL, " +
                    "`is_completed` INTEGER NOT NULL, " +
                    "`attempt_id` TEXT, " +
                    "`note_dedup_key` TEXT)"
            )
            sqliteDb.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_ai_notes_is_completed_created_at` " +
                    "ON `ai_notes` (`is_completed`, `created_at`)"
            )
            sqliteDb.execSQL(
                "CREATE UNIQUE INDEX IF NOT EXISTS `index_ai_notes_attempt_id_note_dedup_key` " +
                    "ON `ai_notes` (`attempt_id`, `note_dedup_key`)"
            )
            sqliteDb.execSQL(
                "CREATE TABLE IF NOT EXISTS `ai_reply_logs` (" +
                    "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                    "`package_name` TEXT NOT NULL, " +
                    "`sender_key` TEXT NOT NULL, " +
                    "`replied_at` INTEGER NOT NULL, " +
                    "`attempt_id` TEXT)"
            )
            sqliteDb.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_ai_reply_logs_package_name_sender_key_replied_at` " +
                    "ON `ai_reply_logs` (`package_name`, `sender_key`, `replied_at`)"
            )
            sqliteDb.execSQL(
                "CREATE UNIQUE INDEX IF NOT EXISTS `index_ai_reply_logs_attempt_id` " +
                    "ON `ai_reply_logs` (`attempt_id`)"
            )

            sqliteDb.execSQL(
                "INSERT INTO `ai_notes` (`id`, `sender_name`, `package_name`, `note_text`, `source_message`, `created_at`, `is_completed`, `attempt_id`, `note_dedup_key`) " +
                    "VALUES (1, '  Sam ', 'com.whatsapp', 'Direct Sam v2 note', 'src1', 1700000001000, 0, 'att-v2-sam', 'k1')"
            )
            sqliteDb.execSQL(
                "INSERT INTO `ai_notes` (`id`, `sender_name`, `package_name`, `note_text`, `source_message`, `created_at`, `is_completed`, `attempt_id`, `note_dedup_key`) " +
                    "VALUES (2, 'Unknown', 'com.whatsapp', 'Unknown display note', 'src2', 1700000002000, 0, 'att-v2-unk1', 'k2')"
            )
            sqliteDb.execSQL(
                "INSERT INTO `ai_notes` (`id`, `sender_name`, `package_name`, `note_text`, `source_message`, `created_at`, `is_completed`, `attempt_id`, `note_dedup_key`) " +
                    "VALUES (3, '   ', 'com.whatsapp', 'Blank sender note', 'src3', 1700000003000, 0, NULL, NULL)"
            )
            sqliteDb.version = 2
        } finally {
            sqliteDb.close()
        }

        val migratedDb = AiMemoryDatabase.getInstance(context)
        withContext(Dispatchers.IO) {
            assertEquals(3, migratedDb.openHelper.readableDatabase.version)
            val dao = migratedDb.aiMemoryDao()
            val allNotes = dao.observeAllNotes().first().associateBy { it.id }
            assertEquals(3, allNotes.size)
            assertEquals("sam", allNotes[1L]?.senderKey)
            assertEquals(AiPromptAndPolicyHelper.UNKNOWN_SENDER, allNotes[2L]?.senderKey)
            assertEquals(AiPromptAndPolicyHelper.UNKNOWN_SENDER, allNotes[3L]?.senderKey)

            // Prompt lookup isolates unknown senders completely
            assertTrue(dao.getPendingNotesForSenderKeySync("com.whatsapp", AiPromptAndPolicyHelper.UNKNOWN_SENDER, 5).isEmpty())
            assertEquals(listOf("Direct Sam v2 note"), dao.getPendingNotesForSenderKeySync("com.whatsapp", "sam", 5).map { it.noteText })

            // Group note for "Sam" uses group:sam and does not collide with direct "sam"
            dao.insertNoteSync(
                AiNote(
                    senderName = "Sam",
                    packageName = "com.whatsapp",
                    noteText = "Group Sam v3 note",
                    sourceMessage = "src4",
                    createdAt = 1700000004000L,
                    isCompleted = false,
                    attemptId = "att-v3-group-sam",
                    noteDedupKey = "k4",
                    senderKey = AiPromptAndPolicyHelper.normalizeSenderKey("Sam", true)
                )
            )
            assertEquals(listOf("Direct Sam v2 note"), dao.getPendingNotesForSenderKeySync("com.whatsapp", "sam", 5).map { it.noteText })
            assertEquals(listOf("Group Sam v3 note"), dao.getPendingNotesForSenderKeySync("com.whatsapp", "group:sam", 5).map { it.noteText })
        }
    }

    @Test
    fun `fresh v3 database initializes cleanly and enforces non-destructive version mismatch`() = runBlocking {
        val db = AiMemoryDatabase.getInstance(context)
        withContext(Dispatchers.IO) {
            assertEquals(3, db.openHelper.writableDatabase.version)
            assertEquals(0, db.aiMemoryDao().getConversationMessageCountSync())
            assertEquals(0, db.aiMemoryDao().getTotalNoteCountSync())
        }

        AiMemoryDatabase.resetInstance()
        context.deleteDatabase(AiMemoryDatabase.DB_NAME)

        val dbFile = context.getDatabasePath(AiMemoryDatabase.DB_NAME)
        dbFile.parentFile?.mkdirs()
        val incompatible = SQLiteDatabase.openOrCreateDatabase(dbFile, null)
        try {
            incompatible.execSQL("CREATE TABLE IF NOT EXISTS user_marker (id INTEGER PRIMARY KEY)")
            incompatible.version = 99
        } finally {
            incompatible.close()
        }

        val brokenDb = AiMemoryDatabase.getInstance(context)
        withContext(Dispatchers.IO) {
            try {
                brokenDb.aiMemoryDao().getConversationMessageCountSync()
                fail("Expected IllegalStateException because destructive migration fallback must not be enabled")
            } catch (expected: IllegalStateException) {
                assertTrue(expected.message?.contains("Migration") == true)
            }
        }
    }
}
