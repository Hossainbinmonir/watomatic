package com.parishod.watomatic.service

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.MediumTest
import androidx.test.platform.app.InstrumentationRegistry
import com.parishod.watomatic.model.ai.AiMemoryDatabase
import com.parishod.watomatic.model.ai.AiMemoryRepository
import com.parishod.watomatic.model.ai.PendingPostDispatchMemoryStore
import com.parishod.watomatic.model.logs.MessageLogsDB
import com.parishod.watomatic.model.utils.Constants
import com.parishod.watomatic.model.utils.DbUtils
import com.parishod.watomatic.service.reply.ReplyAttempt
import com.parishod.watomatic.service.reply.ReplyExecutionManager
import com.parishod.watomatic.service.reply.ReplyExecutionResult
import com.parishod.watomatic.service.reply.ReplyExecutionStatus
import com.parishod.watomatic.service.reply.ReplySourceType
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Instrumentation tests for [PendingPostDispatchMemoryStore] and [NotificationService]
 * startup recovery using real Android SharedPreferences and real Room SQLite databases
 * inside a strictly isolated per-test storage namespace.
 */
@RunWith(AndroidJUnit4::class)
@MediumTest
class PendingPostDispatchRecoveryInstrumentedTest {

    private class IsolatedTestContext(
        base: Context,
        private val prefix: String
    ) : ContextWrapper(base) {

        override fun getApplicationContext(): Context = this

        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
            return super.getSharedPreferences("${prefix}_$name", mode)
        }

        override fun deleteSharedPreferences(name: String): Boolean {
            return super.deleteSharedPreferences("${prefix}_$name")
        }

        override fun getDatabasePath(name: String): File {
            return super.getDatabasePath("${prefix}_$name")
        }

        override fun openOrCreateDatabase(
            name: String,
            mode: Int,
            factory: SQLiteDatabase.CursorFactory?
        ): SQLiteDatabase {
            return super.openOrCreateDatabase("${prefix}_$name", mode, factory)
        }

        override fun openOrCreateDatabase(
            name: String,
            mode: Int,
            factory: SQLiteDatabase.CursorFactory?,
            errorHandler: DatabaseErrorHandler?
        ): SQLiteDatabase {
            return super.openOrCreateDatabase("${prefix}_$name", mode, factory, errorHandler)
        }

        override fun deleteDatabase(name: String): Boolean {
            return super.deleteDatabase("${prefix}_$name")
        }
    }

    private class TestableNotificationService : NotificationService() {
        fun attachIsolatedContext(base: Context) {
            attachBaseContext(base)
        }
    }

    private lateinit var targetContext: Context
    private lateinit var isolatedContext: IsolatedTestContext
    private lateinit var dbExecutor: java.util.concurrent.ExecutorService

    @Before
    fun setUp() {
        targetContext = InstrumentationRegistry.getInstrumentation().targetContext
        val prefix = "test_pdr_${UUID.randomUUID().toString().replace("-", "").take(12)}"
        isolatedContext = IsolatedTestContext(targetContext, prefix)
        dbExecutor = Executors.newSingleThreadExecutor()

        // Reset in-memory singletons BEFORE seeding/staging so getInstance(isolatedContext)
        // binds to this test's isolated database files without touching real user data.
        PendingPostDispatchMemoryStore.clearForTesting(isolatedContext)
        AiMemoryDatabase.resetInstance()
        MessageLogsDB.resetInstance()
    }

    @After
    fun tearDown() {
        runCatching {
            runOnDbThread {
                PendingPostDispatchMemoryStore.clearForTesting(isolatedContext)
                AiMemoryDatabase.resetInstance()
                MessageLogsDB.resetInstance()
                isolatedContext.deleteDatabase(AiMemoryDatabase.DB_NAME)
                isolatedContext.deleteDatabase(Constants.LOGS_DB_NAME)
                isolatedContext.deleteSharedPreferences("watomatic_pending_ai_memory")
            }
        }
        dbExecutor.shutdownNow()
    }

    private fun <T> runOnDbThread(block: () -> T): T {
        return dbExecutor.submit(Callable { block() }).get(10, TimeUnit.SECONDS)
    }

    private fun seedDispatchedAttempt(
        attemptId: String,
        senderName: String,
        incomingMessage: String,
        replyText: String,
        eventKey: String,
        timestampMs: Long
    ): DbUtils {
        val dbUtils = DbUtils(isolatedContext)
        val attempt = ReplyAttempt(
            attemptId = attemptId,
            sourceType = ReplySourceType.GLOBAL_AI,
            sourceLabel = "AI",
            packageName = "com.whatsapp",
            notificationKey = "notif-$attemptId",
            replyText = replyText,
            status = ReplyExecutionStatus.DISPATCHED,
            createdAtMs = timestampMs,
            updatedAtMs = timestampMs,
            eventKey = eventKey
        )
        val result = ReplyExecutionResult(
            executionId = attemptId,
            status = ReplyExecutionStatus.DISPATCHED,
            dispatchAttempted = true,
            dispatchedNormally = true,
            failureCategory = null,
            diagnosticMessage = "Dispatched",
            timestampMs = timestampMs
        )
        dbUtils.recordAttemptResult(attempt, result, senderName, incomingMessage, null)
        return dbUtils
    }

    private fun seedUnknownAttempt(
        attemptId: String,
        senderName: String,
        incomingMessage: String,
        replyText: String,
        eventKey: String,
        timestampMs: Long
    ): DbUtils {
        val dbUtils = DbUtils(isolatedContext)
        val attempt = ReplyAttempt(
            attemptId = attemptId,
            sourceType = ReplySourceType.GLOBAL_AI,
            sourceLabel = "AI",
            packageName = "com.whatsapp",
            notificationKey = "notif-$attemptId",
            replyText = replyText,
            status = ReplyExecutionStatus.UNKNOWN,
            createdAtMs = timestampMs,
            updatedAtMs = timestampMs,
            eventKey = eventKey
        )
        val result = ReplyExecutionResult(
            executionId = attemptId,
            status = ReplyExecutionStatus.UNKNOWN,
            dispatchAttempted = true,
            dispatchedNormally = false,
            failureCategory = ReplyExecutionManager.FAILURE_CLAIM_PERSISTENCE_ERROR,
            diagnosticMessage = "Simulated ambiguous outcome",
            timestampMs = timestampMs
        )
        dbUtils.recordFallbackAttemptOutcome(attempt, result, senderName, incomingMessage)
        return dbUtils
    }

    private fun buildPendingEntry(
        attemptId: String,
        senderKey: String = "alice",
        senderDisplay: String = "Alice",
        incomingMessage: String = "Please remind me at 5 PM",
        incomingEventKey: String = "evt-$attemptId",
        assistantReply: String = "Got it, reminder saved.",
        extractedNotes: List<String> = listOf("Call Alice at 5 PM"),
        timestampMs: Long
    ): PendingPostDispatchMemoryStore.PendingPostDispatchEntry {
        return PendingPostDispatchMemoryStore.PendingPostDispatchEntry(
            attemptId = attemptId,
            statusName = ReplyExecutionStatus.DISPATCHED.name,
            dispatchAttempted = true,
            dispatchedNormally = true,
            failureCategory = null,
            packageName = "com.whatsapp",
            senderKey = senderKey,
            senderDisplay = senderDisplay,
            incomingMessage = incomingMessage,
            incomingEventKey = incomingEventKey,
            legacyIncomingEventKey = null,
            assistantReply = assistantReply,
            extractedNotes = extractedNotes,
            memoryEnabled = true,
            notesEnabled = true,
            recordReplyLog = true,
            keepCount = 20,
            timestampMs = timestampMs,
            retryCount = 0,
            createdAtMs = timestampMs
        )
    }

    @Test
    fun replay_commitsDispatchedStagedEntryExactlyOnce_onRealRoomAndPrefs() {
        val nowMs = System.currentTimeMillis()
        val attemptId = "att-inst-dispatched-${UUID.randomUUID()}"
        val eventKey = "evt-inst-dispatched-${UUID.randomUUID()}"
        val entry = buildPendingEntry(
            attemptId = attemptId,
            incomingEventKey = eventKey,
            timestampMs = nowMs
        )

        val (firstReplayCount, secondReplayCount, thirdReplayCount) = runOnDbThread {
            val dbUtils = seedDispatchedAttempt(
                attemptId = attemptId,
                senderName = entry.senderDisplay,
                incomingMessage = entry.incomingMessage,
                replyText = entry.assistantReply,
                eventKey = eventKey,
                timestampMs = nowMs
            )
            assertTrue(dbUtils.hasConsistentDispatchedPersistence(attemptId))

            PendingPostDispatchMemoryStore.stage(isolatedContext, entry)
            assertEquals(1, PendingPostDispatchMemoryStore.getPendingEntries(isolatedContext).size)

            val aiDao = AiMemoryDatabase.getInstance(isolatedContext).aiMemoryDao()
            val aiRepo = AiMemoryRepository(aiDao)

            val firstCount = PendingPostDispatchMemoryStore.replayPendingEntries(
                isolatedContext,
                aiRepo,
                dbUtils
            )
            assertTrue(PendingPostDispatchMemoryStore.getPendingEntries(isolatedContext).isEmpty())

            // Second replay on empty queue must be a no-op
            val secondCount = PendingPostDispatchMemoryStore.replayPendingEntries(
                isolatedContext,
                aiRepo,
                dbUtils
            )

            // Re-stage identical payload (simulating crash after DB commit before queue removal)
            // and replay again to verify DB unique indexes/guards prevent duplicates.
            PendingPostDispatchMemoryStore.stage(isolatedContext, entry)
            val thirdCount = PendingPostDispatchMemoryStore.replayPendingEntries(
                isolatedContext,
                aiRepo,
                dbUtils
            )
            assertTrue(PendingPostDispatchMemoryStore.getPendingEntries(isolatedContext).isEmpty())

            Triple(firstCount, secondCount, thirdCount)
        }

        assertEquals(1, firstReplayCount)
        assertEquals(0, secondReplayCount)
        assertEquals(1, thirdReplayCount)

        runOnDbThread {
            val aiDao = AiMemoryDatabase.getInstance(isolatedContext).aiMemoryDao()
            val history = aiDao.getRecentMessagesDescSync("com.whatsapp", "alice", 10).asReversed()
            assertEquals(2, history.size)
            assertEquals(1, history.count { it.role == "user" && it.content == entry.incomingMessage })
            assertEquals(1, history.count { it.role == "assistant" && it.content == entry.assistantReply })
            assertEquals(1, aiDao.countMessagesByIncomingEventKeySync(eventKey))
            assertEquals(1, aiDao.countAssistantMessagesByAttemptIdSync(attemptId))
            assertEquals(1, aiDao.countNotesByAttemptIdSync(attemptId))
            assertEquals(1, aiDao.countReplyLogsByAttemptIdSync(attemptId))
        }
    }

    @Test
    fun replay_refusesToPromoteUnknownAttempt_onRealRoomAndPrefs() {
        val nowMs = System.currentTimeMillis()
        val attemptId = "att-inst-unknown-${UUID.randomUUID()}"
        val eventKey = "evt-inst-unknown-${UUID.randomUUID()}"
        val entry = buildPendingEntry(
            attemptId = attemptId,
            incomingMessage = "Did my reply go through?",
            incomingEventKey = eventKey,
            assistantReply = "Should never be persisted as assistant turn",
            extractedNotes = listOf("Should never be persisted as note"),
            timestampMs = nowMs
        )

        val replayedCount = runOnDbThread {
            val dbUtils = seedUnknownAttempt(
                attemptId = attemptId,
                senderName = entry.senderDisplay,
                incomingMessage = entry.incomingMessage,
                replyText = entry.assistantReply,
                eventKey = eventKey,
                timestampMs = nowMs
            )
            assertEquals("UNKNOWN", dbUtils.getAttemptById(attemptId)?.status)
            assertFalse(dbUtils.hasConsistentDispatchedPersistence(attemptId))

            PendingPostDispatchMemoryStore.stage(isolatedContext, entry)
            assertEquals(1, PendingPostDispatchMemoryStore.getPendingEntries(isolatedContext).size)

            val aiDao = AiMemoryDatabase.getInstance(isolatedContext).aiMemoryDao()
            val aiRepo = AiMemoryRepository(aiDao)
            PendingPostDispatchMemoryStore.replayPendingEntries(isolatedContext, aiRepo, dbUtils)
        }

        assertEquals(1, replayedCount)

        runOnDbThread {
            assertTrue(PendingPostDispatchMemoryStore.getPendingEntries(isolatedContext).isEmpty())
            val aiDao = AiMemoryDatabase.getInstance(isolatedContext).aiMemoryDao()
            assertEquals(0, aiDao.countAssistantMessagesByAttemptIdSync(attemptId))
            assertEquals(0, aiDao.countNotesByAttemptIdSync(attemptId))
            assertEquals(0, aiDao.countReplyLogsByAttemptIdSync(attemptId))

            val messages = aiDao.getRecentMessagesDescSync("com.whatsapp", "alice", 10)
            assertEquals(1, messages.size)
            assertEquals("user", messages[0].role)
            assertEquals(entry.incomingMessage, messages[0].content)
        }
    }

    @Test
    fun notificationService_onCreate_triggersStartupReplay_onAndroid() {
        val nowMs = System.currentTimeMillis()
        val attemptId = "att-inst-service-${UUID.randomUUID()}"
        val eventKey = "evt-inst-service-${UUID.randomUUID()}"
        val entry = buildPendingEntry(
            attemptId = attemptId,
            incomingMessage = "Service startup replay check",
            incomingEventKey = eventKey,
            assistantReply = "Recovered via NotificationService onCreate",
            extractedNotes = listOf("Startup recovered note"),
            timestampMs = nowMs
        )

        runOnDbThread {
            val dbUtils = seedDispatchedAttempt(
                attemptId = attemptId,
                senderName = entry.senderDisplay,
                incomingMessage = entry.incomingMessage,
                replyText = entry.assistantReply,
                eventKey = eventKey,
                timestampMs = nowMs
            )
            assertTrue(dbUtils.hasConsistentDispatchedPersistence(attemptId))

            // Do NOT call AiMemoryDatabase.resetInstance() after staging.
            PendingPostDispatchMemoryStore.stage(isolatedContext, entry)
            assertEquals(1, PendingPostDispatchMemoryStore.getPendingEntries(isolatedContext).size)
        }

        var service: TestableNotificationService? = null
        try {
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                val svc = TestableNotificationService()
                svc.attachIsolatedContext(isolatedContext)
                svc.onCreate()
                service = svc
            }

            service!!.awaitAiMemoryIdleForTesting()

            runOnDbThread {
                assertTrue(PendingPostDispatchMemoryStore.getPendingEntries(isolatedContext).isEmpty())
                val aiDao = AiMemoryDatabase.getInstance(isolatedContext).aiMemoryDao()
                val history = aiDao.getRecentMessagesDescSync("com.whatsapp", "alice", 10).asReversed()
                assertEquals(2, history.size)
                assertEquals("user", history[0].role)
                assertEquals(entry.incomingMessage, history[0].content)
                assertEquals("assistant", history[1].role)
                assertEquals(entry.assistantReply, history[1].content)
                assertEquals(1, aiDao.countAssistantMessagesByAttemptIdSync(attemptId))
                assertEquals(1, aiDao.countNotesByAttemptIdSync(attemptId))
                assertEquals(1, aiDao.countReplyLogsByAttemptIdSync(attemptId))
            }
        } finally {
            service?.let { svc ->
                runCatching {
                    InstrumentationRegistry.getInstrumentation().runOnMainSync {
                        svc.onDestroy()
                    }
                }
            }
        }
    }
}
