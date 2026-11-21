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
}
