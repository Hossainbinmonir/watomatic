package com.parishod.watomatic.service.reply

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.parishod.watomatic.model.ai.AiMemoryDatabase
import com.parishod.watomatic.model.logs.MessageLogsDB
import com.parishod.watomatic.model.logs.NotificationDispatchClaimEntity
import com.parishod.watomatic.model.logs.ReplyAttemptEntity
import com.parishod.watomatic.model.utils.Constants
import com.parishod.watomatic.model.utils.DbUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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
class NotificationReplyRecoveryAndClaimTest {

    private lateinit var context: Context
    private lateinit var db: MessageLogsDB
    private lateinit var dbUtils: DbUtils

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        MessageLogsDB.resetInstance()
        context.deleteDatabase(Constants.LOGS_DB_NAME)
        AiMemoryDatabase.resetInstance()
        context.deleteDatabase(AiMemoryDatabase.DB_NAME)
        db = MessageLogsDB.getInstance(context)
        dbUtils = DbUtils(context)
    }

    @After
    fun tearDown() {
        MessageLogsDB.resetInstance()
        context.deleteDatabase(Constants.LOGS_DB_NAME)
        AiMemoryDatabase.resetInstance()
        context.deleteDatabase(AiMemoryDatabase.DB_NAME)
    }

    @Test
    fun `concurrent claim acquisition allows strictly one winner for identical event key`() {
        val pool = Executors.newFixedThreadPool(8)
        val startGate = CountDownLatch(1)
        try {
            val futures = (1..16).map { idx ->
                pool.submit<Boolean> {
                    startGate.await(5, TimeUnit.SECONDS)
                    dbUtils.tryAcquireNotificationClaim(
                        "evt_concurrent_1",
                        "token-$idx",
                        "com.whatsapp",
                        "alice",
                        "k-1",
                        1000L + idx,
                        false
                    ).acquired
                }
            }
            startGate.countDown()
            val results = futures.map { it.get(10, TimeUnit.SECONDS) }
            assertEquals(1, results.count { it })
            assertEquals(15, results.count { !it })
            assertEquals(1, db.notificationDispatchClaimDao().getClaimCountSync())
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun `owner-guarded claim transitions reject mismatched owners and terminal overrides`() {
        val acquire = dbUtils.tryAcquireNotificationClaim(
            "evt_owner_guard",
            "owner-1",
            "com.whatsapp",
            "bob",
            "k-2",
            2000L,
            false
        )
        assertTrue(acquire.acquired)

        // Wrong owner cannot overwrite active claim
        val rogueTransition = dbUtils.updateNotificationClaimTransition(
            "evt_owner_guard",
            "wrong-owner",
            "wrong-owner",
            ReplyExecutionStatus.DISPATCHING,
            2100L
        )
        assertEquals(0, rogueTransition)

        // Real owner transitions to DISPATCHED
        val okResult = ReplyExecutionResult(
            executionId = "owner-1",
            status = ReplyExecutionStatus.DISPATCHED,
            dispatchAttempted = true,
            dispatchedNormally = true,
            failureCategory = null,
            diagnosticMessage = "ok",
            timestampMs = 2200L
        )
        assertEquals(1, dbUtils.updateNotificationClaimResult("evt_owner_guard", "owner-1", okResult))

        // Terminal state cannot be overwritten even by owner
        val lateOverwrite = dbUtils.updateNotificationClaimTransition(
            "evt_owner_guard",
            "owner-1",
            "owner-1",
            ReplyExecutionStatus.FAILED,
            2300L
        )
        assertEquals(0, lateOverwrite)
        assertEquals("DISPATCHED", dbUtils.getNotificationClaim("evt_owner_guard")?.claimStatus)
    }

    @Test
    fun `startup recovery converts interrupted states safely, is idempotent, and touches zero logs or AI memory`() {
        val attemptDao = db.replyAttemptDao()
        val claimDao = db.notificationDispatchClaimDao()

        fun insertAttempt(
            id: String,
            eventKey: String,
            status: ReplyExecutionStatus,
            dispatchAttempted: Boolean,
            dispatchedNormally: Boolean
        ) {
            attemptDao.upsertAttemptSafely(
                ReplyAttemptEntity(
                    attemptId = id,
                    eventKey = eventKey,
                    packageName = "com.whatsapp",
                    senderName = "Sender",
                    incomingMessage = "Msg",
                    notificationKey = "nk-$id",
                    replyText = "Reply",
                    sourceType = "DEFAULT",
                    sourceLabel = "Default Reply",
                    status = status.name,
                    dispatchAttempted = dispatchAttempted,
                    dispatchedNormally = dispatchedNormally,
                    failureCategory = null,
                    createdAtMs = 1000L,
                    updatedAtMs = 1000L
                )
            )
        }

        // Unresolved rows from simulated crash
        insertAttempt("att-gen", "evt_gen", ReplyExecutionStatus.GENERATED, false, false)
        assertTrue(dbUtils.tryAcquireNotificationClaim("evt_gen", "att-gen", "com.whatsapp", "s", "nk-gen", 1000L, false).acquired)
        assertEquals(1, dbUtils.updateNotificationClaimTransition("evt_gen", "att-gen", "att-gen", ReplyExecutionStatus.GENERATED, 1005L))

        insertAttempt("att-disp", "evt_disp", ReplyExecutionStatus.DISPATCHING, true, false)
        assertTrue(dbUtils.tryAcquireNotificationClaim("evt_disp", "att-disp", "com.whatsapp", "s", "nk-disp", 1000L, false).acquired)
        assertEquals(1, dbUtils.updateNotificationClaimTransition("evt_disp", "att-disp", "att-disp", ReplyExecutionStatus.DISPATCHING, 1005L))

        // Orphan CLAIMED row with no ReplyAttemptEntity yet
        assertTrue(dbUtils.tryAcquireNotificationClaim("evt_orphan_claimed", "tok-orphan", "com.whatsapp", "s", "nk-o", 1000L, false).acquired)

        // Terminal rows that MUST remain completely unaltered
        insertAttempt("att-done", "evt_done", ReplyExecutionStatus.DISPATCHED, true, true)
        insertAttempt("att-unk", "evt_unk", ReplyExecutionStatus.UNKNOWN, true, false)
        insertAttempt("att-fail", "evt_fail", ReplyExecutionStatus.FAILED, false, false)
        insertAttempt("att-canc", "evt_canc", ReplyExecutionStatus.CANCELLED, false, false)

        val recoveryNow = 50_000L
        val summary1 = NotificationReplyRecoveryManager.recoverOnStartup(context, recoveryNow)
        assertEquals(1, summary1.recoveredGeneratedCount)
        assertEquals(1, summary1.recoveredDispatchingCount)
        assertEquals(1, summary1.recoveredOrphanClaimsCount)
        assertEquals(3, summary1.totalRecovered)

        // Verify transitions
        val genAfter = attemptDao.getAttemptByIdSync("att-gen")!!
        assertEquals("CANCELLED", genAfter.status)
        assertEquals(ReplyExecutionManager.FAILURE_GENERATED_INTERRUPTED, genAfter.failureCategory)
        assertFalse(genAfter.dispatchAttempted)
        assertEquals("CANCELLED", claimDao.getClaimByEventKeySync("evt_gen")?.claimStatus)

        val dispAfter = attemptDao.getAttemptByIdSync("att-disp")!!
        assertEquals("UNKNOWN", dispAfter.status)
        assertEquals(ReplyExecutionManager.FAILURE_DISPATCHING_INTERRUPTED, dispAfter.failureCategory)
        assertTrue(dispAfter.dispatchAttempted)
        assertFalse(dispAfter.dispatchedNormally)
        assertEquals("UNKNOWN", claimDao.getClaimByEventKeySync("evt_disp")?.claimStatus)

        val orphanClaim = claimDao.getClaimByEventKeySync("evt_orphan_claimed")!!
        assertEquals("CANCELLED", orphanClaim.claimStatus)
        assertEquals(ReplyExecutionManager.FAILURE_GENERATED_INTERRUPTED, orphanClaim.failureCategory)

        // Terminal states preserved untouched (including updatedAtMs == 1000L)
        assertEquals("DISPATCHED", attemptDao.getAttemptByIdSync("att-done")?.status)
        assertEquals(1000L, attemptDao.getAttemptByIdSync("att-done")?.updatedAtMs)
        assertEquals("UNKNOWN", attemptDao.getAttemptByIdSync("att-unk")?.status)
        assertEquals(1000L, attemptDao.getAttemptByIdSync("att-unk")?.updatedAtMs)
        assertEquals("FAILED", attemptDao.getAttemptByIdSync("att-fail")?.status)
        assertEquals("CANCELLED", attemptDao.getAttemptByIdSync("att-canc")?.status)

        // UNKNOWN locked after restart (never converted to DISPATCHED, never retried)
        val retryAfterRecovery = dbUtils.tryAcquireNotificationClaim(
            "evt_disp", "att-retry", "com.whatsapp", "s", "nk-disp", 60_000L, true
        )
        assertFalse(retryAfterRecovery.acquired)
        assertEquals(RetryDecision.DENY_UNKNOWN_OUTCOME, retryAfterRecovery.decision)

        // Second run is strictly idempotent
        val summary2 = NotificationReplyRecoveryManager.recoverOnStartup(context, 70_000L)
        assertEquals(0, summary2.totalRecovered)

        // Zero phantom reply history logs or AI memory written
        assertEquals(0L, db.logsDao().getNumReplies())
        runBlocking {
            withContext(Dispatchers.IO) {
                val memDao = AiMemoryDatabase.getInstance(context).aiMemoryDao()
                assertEquals(0, memDao.getConversationMessageCountSync())
                assertEquals(0, memDao.getPendingNoteCountSync())
            }
        }
    }

    @Test
    fun `bounded claim cleanup deletes expired or excess resolved rows but never deletes active or UNKNOWN claims`() {
        val claimDao = db.notificationDispatchClaimDao()

        fun insertRawClaim(
            key: String,
            status: String,
            updatedAtMs: Long,
            expiresAtMs: Long
        ) {
            claimDao.insertClaimIgnoreSync(
                NotificationDispatchClaimEntity(
                    eventKey = key,
                    attemptId = "att-$key",
                    packageName = "com.whatsapp",
                    senderKey = "alice",
                    notificationKey = "nk-$key",
                    claimStatus = status,
                    failureCategory = null,
                    dispatchAttempted = status == "DISPATCHED" || status == "UNKNOWN" || status == "DISPATCHING",
                    dispatchedNormally = status == "DISPATCHED",
                    createdAtMs = updatedAtMs,
                    updatedAtMs = updatedAtMs,
                    expiresAtMs = expiresAtMs
                )
            )
        }

        // Expired resolved rows (must be purged)
        insertRawClaim("expired_disp", "DISPATCHED", 100L, 500L)
        insertRawClaim("expired_canc", "CANCELLED", 100L, 500L)

        // Unexpired resolved rows beyond maxResolvedRows=2 cap
        insertRawClaim("resolved_1", "DISPATCHED", 1000L, 999999L)
        insertRawClaim("resolved_2", "FAILED", 2000L, 999999L)
        insertRawClaim("resolved_3", "DISPATCHED", 3000L, 999999L)

        // Active and UNKNOWN rows even with past expiresAtMs (MUST be preserved!)
        insertRawClaim("active_claimed", NotificationDispatchClaimEntity.STATUS_CLAIMED, 50L, 100L)
        insertRawClaim("active_dispatching", "DISPATCHING", 60L, 100L)
        insertRawClaim("unresolved_unknown", "UNKNOWN", 70L, 100L)

        val deleted = claimDao.purgeExpiredAndBoundedClaimsSync(nowMs = 5000L, maxResolvedRows = 2)
        assertEquals(3, deleted) // expired_disp, expired_canc, resolved_1

        assertNull(claimDao.getClaimByEventKeySync("expired_disp"))
        assertNull(claimDao.getClaimByEventKeySync("expired_canc"))
        assertNull(claimDao.getClaimByEventKeySync("resolved_1"))

        assertNotNull(claimDao.getClaimByEventKeySync("resolved_2"))
        assertNotNull(claimDao.getClaimByEventKeySync("resolved_3"))
        assertNotNull(claimDao.getClaimByEventKeySync("active_claimed"))
        assertNotNull(claimDao.getClaimByEventKeySync("active_dispatching"))
        assertNotNull(claimDao.getClaimByEventKeySync("unresolved_unknown"))
    }

    @Test
    fun `recovery reconciles GENERATED attempt paired with DISPATCHING claim conservatively to UNKNOWN`() {
        val attemptDao = db.replyAttemptDao()
        val claimDao = db.notificationDispatchClaimDao()

        attemptDao.upsertAttemptSafely(
            ReplyAttemptEntity(
                attemptId = "att-mismatch-gen",
                eventKey = "evt_mismatch_gen",
                packageName = "com.whatsapp",
                senderName = "Bob",
                incomingMessage = "Hello",
                notificationKey = "nk-mismatch-gen",
                replyText = "Hi",
                sourceType = "DEFAULT",
                sourceLabel = "Default",
                status = ReplyExecutionStatus.GENERATED.name,
                dispatchAttempted = false,
                dispatchedNormally = false,
                failureCategory = null,
                createdAtMs = 1000L,
                updatedAtMs = 1000L
            )
        )
        assertTrue(dbUtils.tryAcquireNotificationClaim("evt_mismatch_gen", "att-mismatch-gen", "com.whatsapp", "bob", "nk-mismatch-gen", 1000L, false).acquired)
        assertEquals(1, dbUtils.updateNotificationClaimTransition("evt_mismatch_gen", "att-mismatch-gen", "att-mismatch-gen", ReplyExecutionStatus.DISPATCHING, 1010L))

        val summary = NotificationReplyRecoveryManager.recoverOnStartup(context, 50_000L)
        assertEquals(1, summary.recoveredDispatchingCount)
        assertEquals(0, summary.recoveredOrphanClaimsCount)

        val recoveredAttempt = attemptDao.getAttemptByIdSync("att-mismatch-gen")!!
        val recoveredClaim = claimDao.getClaimByEventKeySync("evt_mismatch_gen")!!
        assertEquals("UNKNOWN", recoveredAttempt.status)
        assertEquals(ReplyExecutionManager.FAILURE_DISPATCHING_INTERRUPTED, recoveredAttempt.failureCategory)
        assertTrue(recoveredAttempt.dispatchAttempted)
        assertEquals("UNKNOWN", recoveredClaim.claimStatus)
    }

    @Test
    fun `recovery reconciles DISPATCHING attempt to DISPATCHED when claim is DISPATCHED and message log exists`() {
        val attemptDao = db.replyAttemptDao()
        val claimDao = db.notificationDispatchClaimDao()

        db.appPackageDao().insertAppPackage(com.parishod.watomatic.model.logs.AppPackage("com.whatsapp"))
        val pkgIdx = db.appPackageDao().getPackageIndex("com.whatsapp")
        db.logsDao().logReply(
            com.parishod.watomatic.model.logs.MessageLog(
                pkgIdx, "Bob", "Hey", 1000L, "On it", null, 1020L,
                com.parishod.watomatic.model.logs.ReplyHistoryStatus.DISPATCHED, "att-reconcile-disp"
            )
        )
        attemptDao.upsertAttemptSafely(
            ReplyAttemptEntity(
                attemptId = "att-reconcile-disp",
                eventKey = "evt_reconcile_disp",
                packageName = "com.whatsapp",
                senderName = "Bob",
                incomingMessage = "Hey",
                notificationKey = "nk-reconcile-disp",
                replyText = "On it",
                sourceType = "DEFAULT",
                sourceLabel = "Default",
                status = ReplyExecutionStatus.DISPATCHING.name,
                dispatchAttempted = true,
                dispatchedNormally = false,
                failureCategory = null,
                createdAtMs = 1000L,
                updatedAtMs = 1010L
            )
        )
        claimDao.insertClaimIgnoreSync(
            NotificationDispatchClaimEntity(
                eventKey = "evt_reconcile_disp",
                attemptId = "att-reconcile-disp",
                packageName = "com.whatsapp",
                senderKey = "bob",
                notificationKey = "nk-reconcile-disp",
                claimStatus = ReplyExecutionStatus.DISPATCHED.name,
                failureCategory = null,
                dispatchAttempted = true,
                dispatchedNormally = true,
                createdAtMs = 1000L,
                updatedAtMs = 1020L,
                expiresAtMs = 999_999L
            )
        )

        NotificationReplyRecoveryManager.recoverOnStartup(context, 50_000L)

        val reconciled = attemptDao.getAttemptByIdSync("att-reconcile-disp")!!
        assertEquals("DISPATCHED", reconciled.status)
        assertTrue(reconciled.dispatchAttempted)
        assertTrue(reconciled.dispatchedNormally)
    }
}
