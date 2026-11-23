package com.parishod.watomatic.model.utils

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.parishod.watomatic.model.CustomRepliesData
import com.parishod.watomatic.model.logs.AppPackage
import com.parishod.watomatic.model.logs.MessageLog
import com.parishod.watomatic.model.logs.MessageLogsDB
import com.parishod.watomatic.model.logs.ReplyHistoryStatus
import com.parishod.watomatic.model.preferences.PreferencesManager
import com.parishod.watomatic.service.reply.ReplyAttempt
import com.parishod.watomatic.service.reply.ReplyExecutionResult
import com.parishod.watomatic.service.reply.ReplyExecutionStatus
import com.parishod.watomatic.service.reply.ReplySourceType
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class DbUtilsTest {

    private lateinit var context: Context
    private lateinit var dbUtils: DbUtils

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        PreferencesManager.resetInstance()
        CustomRepliesData.resetInstance()
        dbUtils = DbUtils(context)
    }

    @After
    fun tearDown() {
        PreferencesManager.resetInstance()
        CustomRepliesData.resetInstance()
        // Reset the database singleton via reflection
        try {
            val field = MessageLogsDB::class.java.getDeclaredField("_instance")
            field.isAccessible = true
            val db = field.get(null) as? MessageLogsDB
            db?.close()
            field.set(null, null)
        } catch (_: Exception) {
            // Ignore if reflection fails
        }
    }

    // --- getNumReplies ---

    @Test
    fun `getNumReplies returns 0 when no logs exist`() {
        val count = dbUtils.getNumReplies()
        assertEquals(0L, count)
    }

    // --- getLastRepliedTime ---

    @Test
    fun `getLastRepliedTime returns 0 for null title`() {
        val result = dbUtils.getLastRepliedTime("com.whatsapp", null)
        assertEquals(0L, result)
    }

    @Test
    fun `getLastRepliedTime returns 0 when no logs exist`() {
        val result = dbUtils.getLastRepliedTime("com.whatsapp", "John")
        assertEquals(0L, result)
    }

    // --- getFirstRepliedTime ---

    @Test
    fun `getFirstRepliedTime returns 0 when no logs exist`() {
        val result = dbUtils.getFirstRepliedTime()
        assertEquals(0L, result)
    }

    // --- purgeMessageLogs ---

    @Test
    fun `purgeMessageLogs does not throw when no logs exist`() {
        // Should not throw
        dbUtils.purgeMessageLogs()
        assertEquals(0L, dbUtils.getNumReplies())
    }

    // --- Constructor ---

    @Test
    fun `DbUtils can be constructed with context`() {
        val utils = DbUtils(context)
        // Basic smoke test — doesn't crash
        assertTrue(utils.getNumReplies() >= 0)
    }

    @Test
    fun `purgeMessageLogs deletes logs older than 30 days in milliseconds and retains recent logs`() {
        val db = MessageLogsDB.getInstance(context)
        db.appPackageDao().insertAppPackage(AppPackage("com.whatsapp"))
        val pkgIdx = db.appPackageDao().getPackageIndex("com.whatsapp")
        val nowMs = System.currentTimeMillis()
        val olderThan30DaysMs = nowMs - (31L * 24L * 60L * 60L * 1000L)
        val within30DaysMs = nowMs - (5L * 24L * 60L * 60L * 1000L)

        db.logsDao().logReply(
            MessageLog(pkgIdx, "OldSender", "OldMsg", olderThan30DaysMs, "OldReply", null, olderThan30DaysMs, ReplyHistoryStatus.DISPATCHED, "att-old")
        )
        db.logsDao().logReply(
            MessageLog(pkgIdx, "RecentSender", "NewMsg", within30DaysMs, "NewReply", null, within30DaysMs, ReplyHistoryStatus.DISPATCHED, "att-new")
        )
        assertEquals(2L, dbUtils.getNumReplies())

        dbUtils.purgeMessageLogs()

        assertEquals(1L, dbUtils.getNumReplies())
        assertEquals(0, db.logsDao().countByAttemptIdSync("att-old"))
        assertEquals(1, db.logsDao().countByAttemptIdSync("att-new"))
    }

    @Test
    fun `recordAttemptAndClaimResult atomically persists attempt, log row, and claim state`() {
        val eventKey = "evt_atomic_1"
        val attempt = ReplyAttempt.create(
            ReplySourceType.DEFAULT,
            "Default",
            "com.whatsapp",
            "nk-atomic-1",
            "Atomic reply",
            eventKey
        )
        assertTrue(
            dbUtils.tryAcquireNotificationClaim(
                eventKey,
                attempt.attemptId,
                "com.whatsapp",
                "alice",
                "nk-atomic-1",
                1000L,
                false
            ).acquired
        )

        val result = ReplyExecutionResult(
            executionId = attempt.attemptId,
            status = ReplyExecutionStatus.DISPATCHED,
            dispatchAttempted = true,
            dispatchedNormally = true,
            failureCategory = null,
            diagnosticMessage = "Dispatched",
            timestampMs = 2000L
        )
        dbUtils.recordAttemptAndClaimResult(eventKey, attempt, result, "Alice", "Hello", null)

        val persistedAttempt = dbUtils.getAttemptById(attempt.attemptId)
        assertNotNull(persistedAttempt)
        assertEquals("DISPATCHED", persistedAttempt!!.status)
        assertEquals(1L, dbUtils.getNumReplies())
        assertEquals("DISPATCHED", dbUtils.getNotificationClaim(eventKey)?.claimStatus)
    }

    @Test
    fun `concurrent logReply inserts create single package row cleanly`() {
        val pool = Executors.newFixedThreadPool(6)
        val gate = CountDownLatch(1)
        try {
            val futures = (1..12).map { i ->
                pool.submit {
                    gate.await(5, TimeUnit.SECONDS)
                    val attempt = ReplyAttempt.create(
                        ReplySourceType.DEFAULT,
                        "Default",
                        "com.example.concurrent",
                        "nk-$i",
                        "Reply $i",
                        "evt-conc-$i"
                    )
                    val res = ReplyExecutionResult(
                        attempt.attemptId,
                        ReplyExecutionStatus.DISPATCHED,
                        true,
                        true,
                        null,
                        "ok",
                        1000L + i
                    )
                    dbUtils.recordAttemptResult(attempt, res, "Sender $i", "Hi $i", null)
                }
            }
            gate.countDown()
            futures.forEach { it.get(10, TimeUnit.SECONDS) }

            assertEquals(12L, dbUtils.getNumReplies())
            val cursor = MessageLogsDB.getInstance(context)
                .openHelper
                .readableDatabase
                .query("SELECT COUNT(*) FROM app_packages WHERE package_name = 'com.example.concurrent'")
            cursor.use {
                assertTrue(it.moveToFirst())
                assertEquals(1, it.getInt(0))
            }
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun `purgeExpiredHistory removes 30-day expired resolved attempts and logs while protecting active and UNKNOWN states`() {
        val db = MessageLogsDB.getInstance(context)
        val attemptDao = db.replyAttemptDao()
        val nowMs = 1_800_000_000_000L
        val cutoffMs = nowMs - DbUtils.HISTORY_RETENTION_MS

        fun insertAttempt(id: String, status: String, updatedAtMs: Long) {
            attemptDao.upsertAttemptSafely(
                com.parishod.watomatic.model.logs.ReplyAttemptEntity(
                    attemptId = id,
                    packageName = "com.whatsapp",
                    senderName = "Sender-$id",
                    incomingMessage = "Msg-$id",
                    notificationKey = "nk-$id",
                    replyText = "Reply-$id",
                    sourceType = ReplySourceType.DEFAULT.name,
                    sourceLabel = "Default",
                    status = status,
                    dispatchAttempted = status == "DISPATCHED" || status == "DISPATCHING" || status == "UNKNOWN",
                    dispatchedNormally = status == "DISPATCHED",
                    failureCategory = null,
                    createdAtMs = updatedAtMs - 100L,
                    updatedAtMs = updatedAtMs,
                    eventKey = "evt-$id"
                )
            )
        }

        db.appPackageDao().insertAppPackage(AppPackage("com.whatsapp"))
        val pkgIdx = db.appPackageDao().getPackageIndex("com.whatsapp")

        // Exact boundary & expired resolved attempts (MUST be purged along with matching message_logs)
        insertAttempt("att-disp-boundary", "DISPATCHED", cutoffMs)
        db.logsDao().logReply(
            MessageLog(pkgIdx, "S1", "M1", cutoffMs, "R1", null, cutoffMs, ReplyHistoryStatus.DISPATCHED, "att-disp-boundary")
        )
        insertAttempt("att-fail-old", "FAILED", cutoffMs - 5_000L)
        insertAttempt("att-canc-old", "CANCELLED", cutoffMs - 10_000L)

        // Just inside retention window (cutoffMs + 1) -> MUST be retained across both tables
        insertAttempt("att-disp-recent", "DISPATCHED", cutoffMs + 1L)
        db.logsDao().logReply(
            MessageLog(pkgIdx, "S2", "M2", cutoffMs + 1L, "R2", null, cutoffMs + 1L, ReplyHistoryStatus.DISPATCHED, "att-disp-recent")
        )
        insertAttempt("att-fail-recent", "FAILED", cutoffMs + 1L)

        // Old unresolved/active/UNKNOWN states -> MUST NEVER be deleted merely due to age
        insertAttempt("att-claimed-old", "CLAIMED", cutoffMs - 100_000L)
        insertAttempt("att-gen-old", "GENERATED", cutoffMs - 100_000L)
        insertAttempt("att-dispatching-old", "DISPATCHING", cutoffMs - 100_000L)
        insertAttempt("att-unknown-old", "UNKNOWN", cutoffMs - 100_000L)

        // Active claim must remain untouched by history retention purge
        assertTrue(
            dbUtils.tryAcquireNotificationClaim(
                "evt-keep-claim", "att-disp-boundary", "com.whatsapp", "s1", "nk-1", cutoffMs, false
            ).acquired
        )

        dbUtils.purgeExpiredHistory(nowMs)

        // Purged expired resolved
        assertEquals(null, dbUtils.getAttemptById("att-disp-boundary"))
        assertEquals(null, dbUtils.getAttemptById("att-fail-old"))
        assertEquals(null, dbUtils.getAttemptById("att-canc-old"))
        assertEquals(0, db.logsDao().countByAttemptIdSync("att-disp-boundary"))

        // Retained recent resolved (Timeline & Grouped stay consistent)
        assertNotNull(dbUtils.getAttemptById("att-disp-recent"))
        assertEquals(1, db.logsDao().countByAttemptIdSync("att-disp-recent"))
        assertEquals(1L, dbUtils.getNumReplies())
        assertNotNull(dbUtils.getAttemptById("att-fail-recent"))

        // Protected unresolved / active / UNKNOWN states regardless of age
        assertNotNull(dbUtils.getAttemptById("att-claimed-old"))
        assertNotNull(dbUtils.getAttemptById("att-gen-old"))
        assertNotNull(dbUtils.getAttemptById("att-dispatching-old"))
        assertNotNull(dbUtils.getAttemptById("att-unknown-old"))

        // Claims preserved
        assertNotNull(dbUtils.getNotificationClaim("evt-keep-claim"))
    }
}
