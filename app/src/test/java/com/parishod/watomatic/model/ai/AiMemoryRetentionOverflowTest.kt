package com.parishod.watomatic.model.ai

import android.content.Context
import android.database.sqlite.SQLiteException
import androidx.preference.PreferenceManager
import androidx.test.core.app.ApplicationProvider
import com.parishod.watomatic.model.logs.MessageLogsDB
import com.parishod.watomatic.model.logs.ReplyAttemptEntity
import com.parishod.watomatic.model.preferences.PreferencesManager
import com.parishod.watomatic.model.rules.AutoReplyRulesDatabase
import com.parishod.watomatic.model.utils.Constants
import com.parishod.watomatic.model.utils.DbUtils
import com.parishod.watomatic.service.reply.ReplyExecutionManager
import com.parishod.watomatic.service.reply.ReplyExecutionStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class AiMemoryRetentionOverflowTest {

    private lateinit var context: Context
    private lateinit var prefs: PreferencesManager
    private lateinit var logsDb: MessageLogsDB
    private lateinit var memoryDb: AiMemoryDatabase
    private lateinit var rulesDb: AutoReplyRulesDatabase
    private lateinit var dbUtils: DbUtils

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        PreferencesManager.resetInstance()
        PreferenceManager.getDefaultSharedPreferences(context).edit().clear().commit()
        val enc = context.getSharedPreferences("test_ai_retention_overflow_enc", Context.MODE_PRIVATE)
        enc.edit().clear().commit()
        PreferencesManager.setEncryptedPrefsProviderForTesting { enc }
        prefs = PreferencesManager.getPreferencesInstance(context)

        PendingPostDispatchMemoryStore.clearForTesting(context)
        MessageLogsDB.resetInstance()
        context.deleteDatabase(Constants.LOGS_DB_NAME)
        AiMemoryDatabase.resetInstance()
        context.deleteDatabase(AiMemoryDatabase.DB_NAME)
        AutoReplyRulesDatabase.resetInstance()
        context.deleteDatabase(AutoReplyRulesDatabase.DB_NAME)

        logsDb = MessageLogsDB.getInstance(context)
        memoryDb = AiMemoryDatabase.getInstance(context)
        rulesDb = AutoReplyRulesDatabase.getInstance(context)
        dbUtils = DbUtils(context)
    }

    @After
    fun tearDown() {
        val cleanupExec = Executors.newSingleThreadExecutor()
        try {
            cleanupExec.submit {
                PendingPostDispatchMemoryStore.clearForTesting(context)
                MessageLogsDB.resetInstance()
                context.deleteDatabase(Constants.LOGS_DB_NAME)
                AiMemoryDatabase.resetInstance()
                context.deleteDatabase(AiMemoryDatabase.DB_NAME)
                AutoReplyRulesDatabase.resetInstance()
                context.deleteDatabase(AutoReplyRulesDatabase.DB_NAME)
            }.get(15, TimeUnit.SECONDS)
        } finally {
            cleanupExec.shutdownNow()
        }
        PreferenceManager.getDefaultSharedPreferences(context).edit().clear().commit()
        context.getSharedPreferences("test_ai_retention_overflow_enc", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
        PreferencesManager.resetInstance()
    }

    private fun unknownAttempt(attemptId: String, timestampMs: Long): ReplyAttemptEntity {
        return ReplyAttemptEntity(
            attemptId = attemptId,
            sourceType = "GLOBAL_AI",
            sourceLabel = "AI",
            packageName = "com.whatsapp",
            notificationKey = "nk-$attemptId",
            senderName = "Alice",
            incomingMessage = "Hello",
            replyText = "Reply",
            status = ReplyExecutionStatus.UNKNOWN.name,
            dispatchAttempted = true,
            dispatchedNormally = false,
            failureCategory = ReplyExecutionManager.FAILURE_AMBIGUOUS_SEND_EXCEPTION,
            createdAtMs = timestampMs,
            updatedAtMs = timestampMs,
            eventKey = "evt-$attemptId"
        )
    }

    private fun pendingEntry(attemptId: String, timestampMs: Long): PendingPostDispatchMemoryStore.PendingPostDispatchEntry {
        return PendingPostDispatchMemoryStore.PendingPostDispatchEntry(
            attemptId = attemptId,
            statusName = ReplyExecutionStatus.DISPATCHED.name,
            dispatchAttempted = true,
            dispatchedNormally = true,
            failureCategory = null,
            packageName = "com.whatsapp",
            senderKey = "alice",
            senderDisplay = "Alice",
            incomingMessage = "Hello",
            incomingEventKey = "in-$attemptId",
            legacyIncomingEventKey = null,
            assistantReply = "Protected pending reply $attemptId",
            extractedNotes = emptyList(),
            memoryEnabled = true,
            notesEnabled = true,
            recordReplyLog = false,
            keepCount = 20,
            timestampMs = timestampMs,
            retryCount = 0,
            createdAtMs = timestampMs
        )
    }

    @Test
    fun `normal sized protected ID set preserves fast path semantics and advances retention timestamp`() = runBlocking {
        val nowMs = 1_800_000_000_000L
        val expiredMs = nowMs - DbUtils.HISTORY_RETENTION_MS - 10_000L
        val freshMs = nowMs - (3L * 24L * 60L * 60L * 1000L)

        withContext(Dispatchers.IO) {
            val attemptDao = logsDb.replyAttemptDao()
            val aiDao = memoryDb.aiMemoryDao()

            attemptDao.upsertRawSync(unknownAttempt("unk-fast-1", expiredMs))
            PendingPostDispatchMemoryStore.stage(context, pendingEntry("pending-fast-1", nowMs - 1_000L))

            // Protected expired messages & notes
            aiDao.insertConversationMessageSync(
                AiConversationMessage(
                    packageName = "com.whatsapp",
                    senderKey = "alice",
                    senderDisplayName = "Alice",
                    role = "assistant",
                    content = "protected unknown msg",
                    timestamp = expiredMs,
                    attemptId = "unk-fast-1",
                    executionStatus = AiConversationMessage.STATUS_DISPATCHED
                )
            )
            aiDao.insertConversationMessageSync(
                AiConversationMessage(
                    packageName = "com.whatsapp",
                    senderKey = "alice",
                    senderDisplayName = "Alice",
                    role = "assistant",
                    content = "protected pending msg",
                    timestamp = expiredMs,
                    attemptId = "pending-fast-1",
                    executionStatus = AiConversationMessage.STATUS_DISPATCHED
                )
            )
            aiDao.insertNoteSync(
                AiNote(
                    senderName = "Alice",
                    packageName = "com.whatsapp",
                    noteText = "protected unknown note",
                    createdAt = expiredMs,
                    attemptId = "unk-fast-1",
                    noteDedupKey = "fast-unk-note",
                    senderKey = "alice"
                )
            )

            // Expired unprotected messages & notes (both null attemptId and DISPATCHED attemptId)
            aiDao.insertConversationMessageSync(
                AiConversationMessage(
                    packageName = "com.whatsapp",
                    senderKey = "alice",
                    senderDisplayName = "Alice",
                    role = "user",
                    content = "expired user null attempt",
                    timestamp = expiredMs,
                    attemptId = null,
                    executionStatus = AiConversationMessage.STATUS_USER_RECEIVED
                )
            )
            aiDao.insertConversationMessageSync(
                AiConversationMessage(
                    packageName = "com.whatsapp",
                    senderKey = "alice",
                    senderDisplayName = "Alice",
                    role = "assistant",
                    content = "expired dispatched assistant",
                    timestamp = expiredMs,
                    attemptId = "disp-fast-1",
                    executionStatus = AiConversationMessage.STATUS_DISPATCHED
                )
            )
            aiDao.insertNoteSync(
                AiNote(
                    senderName = "Alice",
                    packageName = "com.whatsapp",
                    noteText = "expired manual note",
                    createdAt = expiredMs,
                    attemptId = null
                )
            )
            aiDao.insertNoteSync(
                AiNote(
                    senderName = "Alice",
                    packageName = "com.whatsapp",
                    noteText = "expired ai note",
                    createdAt = expiredMs,
                    attemptId = "disp-fast-1",
                    noteDedupKey = "fast-disp-note",
                    senderKey = "alice"
                )
            )

            // Fresh unprotected messages & notes
            aiDao.insertConversationMessageSync(
                AiConversationMessage(
                    packageName = "com.whatsapp",
                    senderKey = "alice",
                    senderDisplayName = "Alice",
                    role = "assistant",
                    content = "fresh msg",
                    timestamp = freshMs,
                    attemptId = "disp-fresh-1",
                    executionStatus = AiConversationMessage.STATUS_DISPATCHED
                )
            )
            aiDao.insertNoteSync(
                AiNote(
                    senderName = "Alice",
                    packageName = "com.whatsapp",
                    noteText = "fresh note",
                    createdAt = freshMs,
                    attemptId = "disp-fresh-1",
                    noteDedupKey = "fast-fresh-note",
                    senderKey = "alice"
                )
            )

            val summary = dbUtils.runPeriodicRetentionCleanupIfDue(nowMs, true)
            assertTrue(summary.isHistorySucceeded)
            assertEquals(nowMs, prefs.getLastPurgedTime())

            val remainingContents = aiDao.getAllConversationMessagesSync().map { it.content }
            assertEquals(
                listOf("protected unknown msg", "protected pending msg", "fresh msg"),
                remainingContents
            )
            assertEquals(2, aiDao.getTotalNoteCountSync())
            assertEquals(1, aiDao.getNotesByAttemptIdSync("unk-fast-1").size)
            assertEquals(1, aiDao.getNotesByAttemptIdSync("disp-fresh-1").size)
        }
    }

    @Test
    fun `stress test with over 1500 protected IDs avoids SQLite bind overflow and preserves all protected and fresh records`() = runBlocking {
        val nowMs = 1_850_000_000_000L
        val expiredMs = nowMs - DbUtils.HISTORY_RETENTION_MS - 60_000L
        val freshMs = nowMs - (2L * 24L * 60L * 60L * 1000L)

        val unknownCount = 1_200
        val pendingCount = 350 // Total protected IDs = 1,550 (> 1,500)
        val expiredUnprotectedNullMsgCount = 550
        val expiredUnprotectedDispatchedMsgCount = 600 // Total 1,150 (> 500 chunk size)
        val expiredUnprotectedNoteCount = 650 // > 500 chunk size

        withContext(Dispatchers.IO) {
            val attemptDao = logsDb.replyAttemptDao()
            val aiDao = memoryDb.aiMemoryDao()

            // Seed 1,200 UNKNOWN attempts
            logsDb.runInTransaction {
                for (i in 1..unknownCount) {
                    attemptDao.upsertRawSync(unknownAttempt("unk-stress-$i", expiredMs - i))
                }
            }

            // Seed 350 staged pending post-dispatch IDs
            for (i in 1..pendingCount) {
                assertTrue(PendingPostDispatchMemoryStore.stage(context, pendingEntry("pend-stress-$i", nowMs - i)))
            }

            memoryDb.runInTransaction {
                // Seed expired protected rows referencing UNKNOWN attempts (sample across entire range)
                for (i in listOf(1, 250, 500, 900, 901, 1000, 1200)) {
                    aiDao.insertConversationMessageSync(
                        AiConversationMessage(
                            packageName = "com.whatsapp",
                            senderKey = "alice",
                            senderDisplayName = "Alice",
                            role = "assistant",
                            content = "protected-unknown-msg-$i",
                            timestamp = expiredMs,
                            attemptId = "unk-stress-$i",
                            executionStatus = AiConversationMessage.STATUS_DISPATCHED
                        )
                    )
                    aiDao.insertNoteSync(
                        AiNote(
                            senderName = "Alice",
                            packageName = "com.whatsapp",
                            noteText = "protected-unknown-note-$i",
                            createdAt = expiredMs,
                            attemptId = "unk-stress-$i",
                            noteDedupKey = "nk-unk-$i",
                            senderKey = "alice"
                        )
                    )
                }

                // Seed expired protected rows referencing PendingPostDispatchMemoryStore attempts
                for (i in listOf(1, 175, 350)) {
                    aiDao.insertConversationMessageSync(
                        AiConversationMessage(
                            packageName = "com.whatsapp",
                            senderKey = "alice",
                            senderDisplayName = "Alice",
                            role = "assistant",
                            content = "protected-pending-msg-$i",
                            timestamp = expiredMs,
                            attemptId = "pend-stress-$i",
                            executionStatus = AiConversationMessage.STATUS_DISPATCHED
                        )
                    )
                    aiDao.insertNoteSync(
                        AiNote(
                            senderName = "Alice",
                            packageName = "com.whatsapp",
                            noteText = "protected-pending-note-$i",
                            createdAt = expiredMs,
                            attemptId = "pend-stress-$i",
                            noteDedupKey = "nk-pend-$i",
                            senderKey = "alice"
                        )
                    )
                }

                // Seed 1,150 expired unprotected conversation messages
                for (i in 1..expiredUnprotectedNullMsgCount) {
                    aiDao.insertConversationMessageSync(
                        AiConversationMessage(
                            packageName = "com.whatsapp",
                            senderKey = "bob",
                            senderDisplayName = "Bob",
                            role = "user",
                            content = "expired-null-msg-$i",
                            timestamp = expiredMs,
                            attemptId = null,
                            executionStatus = AiConversationMessage.STATUS_USER_RECEIVED
                        )
                    )
                }
                for (i in 1..expiredUnprotectedDispatchedMsgCount) {
                    aiDao.insertConversationMessageSync(
                        AiConversationMessage(
                            packageName = "com.whatsapp",
                            senderKey = "bob",
                            senderDisplayName = "Bob",
                            role = "assistant",
                            content = "expired-disp-msg-$i",
                            timestamp = expiredMs,
                            attemptId = "disp-expired-$i",
                            executionStatus = AiConversationMessage.STATUS_DISPATCHED
                        )
                    )
                }

                // Seed 650 expired unprotected AI notes
                for (i in 1..expiredUnprotectedNoteCount) {
                    aiDao.insertNoteSync(
                        AiNote(
                            senderName = "Bob",
                            packageName = "com.whatsapp",
                            noteText = "expired-unprotected-note-$i",
                            createdAt = expiredMs,
                            attemptId = if (i % 2 == 0) null else "disp-note-$i",
                            noteDedupKey = if (i % 2 == 0) null else "nk-exp-$i",
                            senderKey = "bob"
                        )
                    )
                }

                // Seed fresh/non-expired messages and notes (must remain intact)
                aiDao.insertConversationMessageSync(
                    AiConversationMessage(
                        packageName = "com.whatsapp",
                        senderKey = "carol",
                        senderDisplayName = "Carol",
                        role = "user",
                        content = "fresh-unprotected-msg",
                        timestamp = freshMs,
                        attemptId = null,
                        executionStatus = AiConversationMessage.STATUS_USER_RECEIVED
                    )
                )
                aiDao.insertNoteSync(
                    AiNote(
                        senderName = "Carol",
                        packageName = "com.whatsapp",
                        noteText = "fresh-unprotected-note",
                        createdAt = freshMs,
                        attemptId = "disp-fresh-note",
                        noteDedupKey = "nk-fresh",
                        senderKey = "carol"
                    )
                )
            }

            val summary = dbUtils.runPeriodicRetentionCleanupIfDue(nowMs, true)
            assertTrue("Retention cleanup must succeed for >1500 protected IDs", summary.isHistorySucceeded)
            assertEquals(nowMs, prefs.getLastPurgedTime())

            val remainingMsgs = aiDao.getAllConversationMessagesSync()
            // 7 unknown + 3 pending + 1 fresh = 11 messages
            assertEquals(11, remainingMsgs.size)
            val remainingContents = remainingMsgs.map { it.content }.toSet()
            assertTrue(remainingContents.contains("fresh-unprotected-msg"))
            for (i in listOf(1, 250, 500, 900, 901, 1000, 1200)) {
                assertTrue("Protected UNKNOWN message $i must survive", remainingContents.contains("protected-unknown-msg-$i"))
            }
            for (i in listOf(1, 175, 350)) {
                assertTrue("Protected pending message $i must survive", remainingContents.contains("protected-pending-msg-$i"))
            }
            assertFalse(remainingContents.any { it.startsWith("expired-") })

            // 7 unknown + 3 pending + 1 fresh = 11 notes
            assertEquals(11, aiDao.getTotalNoteCountSync())
            for (i in listOf(1, 250, 500, 900, 901, 1000, 1200)) {
                assertEquals(1, aiDao.getNotesByAttemptIdSync("unk-stress-$i").size)
            }
            for (i in listOf(1, 175, 350)) {
                assertEquals(1, aiDao.getNotesByAttemptIdSync("pend-stress-$i").size)
            }
            assertEquals(1, aiDao.getNotesByAttemptIdSync("disp-fresh-note").size)
        }
    }

    @Test
    fun `threshold boundary at 900 vs 901 protected IDs deletes exact same eligible set without overflow`() = runBlocking {
        val cutoffMs = 1_000_000_000L
        withContext(Dispatchers.IO) {
            val aiDao = memoryDb.aiMemoryDao()

            // Exactly 900 protected IDs -> fast path
            val protected900 = (1..900).map { "prot-900-$it" }
            aiDao.insertConversationMessageSync(
                AiConversationMessage(
                    packageName = "com.whatsapp",
                    senderKey = "alice",
                    senderDisplayName = "Alice",
                    role = "assistant",
                    content = "keep-900",
                    timestamp = cutoffMs - 1L,
                    attemptId = "prot-900-900",
                    executionStatus = AiConversationMessage.STATUS_DISPATCHED
                )
            )
            aiDao.insertConversationMessageSync(
                AiConversationMessage(
                    packageName = "com.whatsapp",
                    senderKey = "alice",
                    senderDisplayName = "Alice",
                    role = "assistant",
                    content = "drop-900",
                    timestamp = cutoffMs - 1L,
                    attemptId = "unprot-900",
                    executionStatus = AiConversationMessage.STATUS_DISPATCHED
                )
            )
            val deletedFast = aiDao.purgeExpiredMemorySync(cutoffMs, protected900)
            assertEquals(1, deletedFast)
            assertEquals(listOf("keep-900"), aiDao.getAllConversationMessagesSync().map { it.content })

            // 901 protected IDs -> fallback path
            aiDao.clearAllConversationMessages()
            val protected901 = (1..901).map { "prot-901-$it" }
            aiDao.insertConversationMessageSync(
                AiConversationMessage(
                    packageName = "com.whatsapp",
                    senderKey = "alice",
                    senderDisplayName = "Alice",
                    role = "assistant",
                    content = "keep-901",
                    timestamp = cutoffMs - 1L,
                    attemptId = "prot-901-901",
                    executionStatus = AiConversationMessage.STATUS_DISPATCHED
                )
            )
            aiDao.insertConversationMessageSync(
                AiConversationMessage(
                    packageName = "com.whatsapp",
                    senderKey = "alice",
                    senderDisplayName = "Alice",
                    role = "assistant",
                    content = "drop-901",
                    timestamp = cutoffMs - 1L,
                    attemptId = "unprot-901",
                    executionStatus = AiConversationMessage.STATUS_DISPATCHED
                )
            )
            val deletedFallback = aiDao.purgeExpiredMemorySync(cutoffMs, protected901)
            assertEquals(1, deletedFallback)
            assertEquals(listOf("keep-901"), aiDao.getAllConversationMessagesSync().map { it.content })
        }
    }

    @Test
    fun `cleanup failure rolls back transaction atomically and does not advance retention timestamp`() = runBlocking {
        val priorPurgeMs = 1_700_000_000_000L
        val nowMs = 1_850_000_000_000L
        val expiredMs = nowMs - DbUtils.HISTORY_RETENTION_MS - 5_000L

        withContext(Dispatchers.IO) {
            prefs.setLastClaimsPurgedTime(priorPurgeMs)
            prefs.setPurgeMessageTime(priorPurgeMs)

            val attemptDao = logsDb.replyAttemptDao()
            val aiDao = memoryDb.aiMemoryDao()
            val repo = AiMemoryRepository(aiDao)

            // Populate >900 protected IDs so both fallback selection and multi-table @Transaction run
            logsDb.runInTransaction {
                for (i in 1..1_050) {
                    attemptDao.upsertRawSync(unknownAttempt("unk-fail-$i", expiredMs))
                }
            }
            aiDao.insertConversationMessageSync(
                AiConversationMessage(
                    packageName = "com.whatsapp",
                    senderKey = "alice",
                    senderDisplayName = "Alice",
                    role = "user",
                    content = "expired-msg-before-failure",
                    timestamp = expiredMs,
                    attemptId = null,
                    executionStatus = AiConversationMessage.STATUS_USER_RECEIVED
                )
            )
            aiDao.insertNoteSync(
                AiNote(
                    senderName = "Alice",
                    packageName = "com.whatsapp",
                    noteText = "expired-note-before-failure",
                    createdAt = expiredMs,
                    attemptId = "disp-fail-note",
                    noteDedupKey = "nk-fail-note",
                    senderKey = "alice"
                )
            )

            // Inject a real SQLite failure during note deletion inside the transaction
            val sqlDb = memoryDb.openHelper.writableDatabase
            sqlDb.execSQL(
                "CREATE TRIGGER fail_note_delete BEFORE DELETE ON ai_notes " +
                    "BEGIN SELECT RAISE(ABORT, 'Simulated cleanup failure'); END;"
            )

            try {
                // Direct repository call propagates SQLiteException rather than swallowing it
                assertThrows(SQLiteException::class.java) {
                    repo.purgeExpiredMemory(context, nowMs)
                }

                // Room @Transaction rolled back earlier ai_conversation_messages deletion too
                assertEquals(1, aiDao.getAllConversationMessagesSync().size)
                assertEquals(1, aiDao.getTotalNoteCountSync())

                // DbUtils periodic cleanup must report failure and NOT advance lastPurgedTime
                val failedSummary = dbUtils.runPeriodicRetentionCleanupIfDue(nowMs, true)
                assertTrue(failedSummary.isHistoryRan)
                assertFalse(failedSummary.isHistorySucceeded)
                assertEquals(priorPurgeMs, prefs.getLastPurgedTime())
                assertEquals(1, aiDao.getAllConversationMessagesSync().size)
                assertEquals(1, aiDao.getTotalNoteCountSync())
            } finally {
                sqlDb.execSQL("DROP TRIGGER IF EXISTS fail_note_delete")
            }

            // Once database recovers, retry succeeds cleanly and advances lastPurgedTime
            val recoveredSummary = dbUtils.runPeriodicRetentionCleanupIfDue(nowMs, true)
            assertTrue(recoveredSummary.isHistorySucceeded)
            assertEquals(nowMs, prefs.getLastPurgedTime())
            assertEquals(0, aiDao.getAllConversationMessagesSync().size)
            assertEquals(0, aiDao.getTotalNoteCountSync())
            assertNotNull(attemptDao.getAttemptByIdSync("unk-fail-1"))
        }
    }
}
