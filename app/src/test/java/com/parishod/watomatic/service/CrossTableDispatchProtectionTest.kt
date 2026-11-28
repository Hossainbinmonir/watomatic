package com.parishod.watomatic.service

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.database.sqlite.SQLiteException
import android.os.Process
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationCompat
import androidx.core.app.RemoteInput
import androidx.preference.PreferenceManager
import androidx.test.core.app.ApplicationProvider
import com.parishod.watomatic.NotificationWear
import com.parishod.watomatic.model.ai.AiMemoryDatabase
import com.parishod.watomatic.model.logs.MessageLogsDB
import com.parishod.watomatic.model.logs.NotificationDispatchClaimEntity
import com.parishod.watomatic.model.logs.ReplyAttemptEntity
import com.parishod.watomatic.model.preferences.PreferencesManager
import com.parishod.watomatic.model.rules.AutoReplyRule
import com.parishod.watomatic.model.utils.Constants
import com.parishod.watomatic.model.utils.DbUtils
import com.parishod.watomatic.service.reply.NotificationEventKeyBuilder
import com.parishod.watomatic.service.reply.NotificationReplyRecoveryManager
import com.parishod.watomatic.service.reply.ReplyExecutionManager
import com.parishod.watomatic.service.reply.ReplyExecutionResult
import com.parishod.watomatic.service.reply.ReplyExecutionStatus
import com.parishod.watomatic.service.reply.ReplySourceType
import com.parishod.watomatic.service.reply.RetryDecision
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class CrossTableDispatchProtectionTest {

    private lateinit var context: Context
    private lateinit var db: MessageLogsDB
    private lateinit var dbUtils: DbUtils
    private lateinit var service: NotificationService
    private lateinit var prefs: PreferencesManager

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        PreferencesManager.resetInstance()
        PreferenceManager.getDefaultSharedPreferences(context).edit().clear().commit()
        val encryptedStore = context.getSharedPreferences("test_cross_table_enc", Context.MODE_PRIVATE)
        encryptedStore.edit().clear().commit()
        PreferencesManager.setEncryptedPrefsProviderForTesting { encryptedStore }
        prefs = PreferencesManager.getPreferencesInstance(context)

        MessageLogsDB.resetInstance()
        context.deleteDatabase(Constants.LOGS_DB_NAME)
        AiMemoryDatabase.resetInstance()
        context.deleteDatabase(AiMemoryDatabase.DB_NAME)

        db = MessageLogsDB.getInstance(context)
        dbUtils = DbUtils(context)
        service = Robolectric.buildService(NotificationService::class.java).create().get()
        service.setDbUtilsForTesting(dbUtils)
        service.awaitAiMemoryIdleForTesting()
    }

    @After
    fun tearDown() {
        try {
            service.onDestroy()
        } catch (_: Exception) {
        }
        MessageLogsDB.resetInstance()
        context.deleteDatabase(Constants.LOGS_DB_NAME)
        AiMemoryDatabase.resetInstance()
        context.deleteDatabase(AiMemoryDatabase.DB_NAME)
        PreferenceManager.getDefaultSharedPreferences(context).edit().clear().commit()
        context.getSharedPreferences("test_cross_table_enc", Context.MODE_PRIVATE).edit().clear().commit()
        PreferencesManager.resetInstance()
    }

    private fun buildSbn(
        sender: String = "Alice",
        message: String = "Hello cross table",
        id: Int = 500,
        whenMs: Long = System.currentTimeMillis(),
        withPendingIntent: Boolean = true
    ): StatusBarNotification {
        val replyPi = if (withPendingIntent) {
            PendingIntent.getBroadcast(
                context,
                id,
                Intent("com.parishod.watomatic.CROSS_TABLE_TEST").setPackage(context.packageName),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
            )
        } else {
            null
        }
        val remoteInput = RemoteInput.Builder("reply_key")
            .setLabel("Reply")
            .setAllowFreeFormInput(true)
            .build()
        val action = NotificationCompat.Action.Builder(0, "Reply", replyPi)
            .addRemoteInput(remoteInput)
            .build()
        val notification = NotificationCompat.Builder(context, "test_ch")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(sender)
            .setContentText(message)
            .setWhen(whenMs)
            .addAction(action)
            .build()
        return StatusBarNotification(
            "com.whatsapp",
            "com.whatsapp",
            id,
            "tag-$id",
            0,
            0,
            0,
            notification,
            Process.myUserHandle(),
            whenMs + 10L
        )
    }

    private fun wearFromSbn(
        sbn: StatusBarNotification,
        withPendingIntent: Boolean = true
    ): NotificationWear {
        val remoteInput = RemoteInput.Builder("reply_key")
            .setLabel("Reply")
            .setAllowFreeFormInput(true)
            .build()
        val pi = if (withPendingIntent) {
            PendingIntent.getBroadcast(
                context,
                sbn.id + 5000,
                Intent("com.parishod.watomatic.CROSS_TABLE_WEAR").setPackage(context.packageName),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
            )
        } else {
            null
        }
        return NotificationWear(
            sbn.packageName,
            pi,
            listOf(remoteInput),
            sbn.notification.extras,
            sbn.tag,
            sbn.id.toString()
        )
    }

    private fun insertAttempt(
        attemptId: String,
        eventKey: String,
        status: String,
        dispatchAttempted: Boolean,
        dispatchedNormally: Boolean,
        createdAtMs: Long = System.currentTimeMillis()
    ) {
        db.replyAttemptDao().upsertAttemptSafely(
            ReplyAttemptEntity(
                attemptId = attemptId,
                eventKey = eventKey,
                packageName = "com.whatsapp",
                senderName = "Alice",
                incomingMessage = "Hello cross table",
                notificationKey = "nk-$attemptId",
                replyText = "Reply text",
                sourceType = ReplySourceType.DEFAULT.name,
                sourceLabel = "Default",
                status = status,
                dispatchAttempted = dispatchAttempted,
                dispatchedNormally = dispatchedNormally,
                failureCategory = null,
                createdAtMs = createdAtMs,
                updatedAtMs = createdAtMs
            )
        )
    }

    // -----------------------------------------------------------------
    // A. Existing attempt + existing claim -> new non-retry blocked
    // -----------------------------------------------------------------
    @Test
    fun `A_existing attempt and existing claim block new non-retry dispatch including split-brain claim`() {
        val sendCount = AtomicInteger(0)
        service.setReplyExecutionManagerForTesting(
            ReplyExecutionManager(context, pendingIntentSender = ReplyExecutionManager.PendingIntentSender { _, _, _ ->
                sendCount.incrementAndGet()
            })
        )

        val sbn = buildSbn(id = 501)
        val eventKey = NotificationEventKeyBuilder.buildEventKey(sbn)!!

        // 1. Both attempt and claim show DISPATCHED -> blocked
        insertAttempt("att-a-1", eventKey, ReplyExecutionStatus.DISPATCHED.name, true, true)
        db.notificationDispatchClaimDao().insertClaimIgnoreSync(
            NotificationDispatchClaimEntity(
                eventKey = eventKey,
                attemptId = "att-a-1",
                packageName = "com.whatsapp",
                senderKey = "alice",
                notificationKey = sbn.key,
                claimStatus = ReplyExecutionStatus.DISPATCHED.name,
                failureCategory = null,
                dispatchAttempted = true,
                dispatchedNormally = true,
                createdAtMs = 1000L,
                updatedAtMs = 1000L,
                expiresAtMs = 9_999_999_999_999L
            )
        )
        val res1 = service.sendActualReply(sbn, wearFromSbn(sbn), "Reply", null, ReplySourceType.DEFAULT)
        assertEquals(ReplyExecutionStatus.CANCELLED, res1.status)
        assertEquals(ReplyExecutionManager.FAILURE_DUPLICATE_EVENT, res1.failureCategory)
        assertEquals(0, sendCount.get())

        // 2. Split-brain: claim is stale FAILED(dispatchAttempted=false), reply_attempts has UNKNOWN -> blocked as UNKNOWN
        val sbnSplit = buildSbn(id = 502, sender = "Bob")
        val splitKey = NotificationEventKeyBuilder.buildEventKey(sbnSplit)!!
        insertAttempt("att-a-split", splitKey, ReplyExecutionStatus.UNKNOWN.name, true, false)
        db.notificationDispatchClaimDao().insertClaimIgnoreSync(
            NotificationDispatchClaimEntity(
                eventKey = splitKey,
                attemptId = "att-a-split",
                packageName = "com.whatsapp",
                senderKey = "bob",
                notificationKey = sbnSplit.key,
                claimStatus = ReplyExecutionStatus.FAILED.name,
                failureCategory = ReplyExecutionManager.FAILURE_MISSING_PENDING_INTENT,
                dispatchAttempted = false,
                dispatchedNormally = false,
                createdAtMs = 1000L,
                updatedAtMs = 1000L,
                expiresAtMs = 9_999_999_999_999L
            )
        )
        val outcomeSplit = dbUtils.tryAcquireNotificationClaim(
            splitKey, "att-new", "com.whatsapp", "bob", sbnSplit.key, 2000L, false
        )
        assertFalse(outcomeSplit.acquired)
        assertEquals(RetryDecision.DENY_UNKNOWN_OUTCOME, outcomeSplit.decision)

        val resSplit = service.sendActualReply(sbnSplit, wearFromSbn(sbnSplit), "Reply", null, ReplySourceType.DEFAULT)
        assertEquals(ReplyExecutionStatus.CANCELLED, resSplit.status)
        assertEquals(ReplyExecutionManager.FAILURE_UNKNOWN_OUTCOME_LOCKED, resSplit.failureCategory)
        assertEquals(0, sendCount.get())
    }

    // -----------------------------------------------------------------
    // B. Existing attempt + missing claim -> new non-retry blocked
    // -----------------------------------------------------------------
    @Test
    fun `B_existing attempt with missing claim blocks both normal and pre-claimed dispatch paths`() {
        val sendCount = AtomicInteger(0)
        service.setReplyExecutionManagerForTesting(
            ReplyExecutionManager(context, pendingIntentSender = ReplyExecutionManager.PendingIntentSender { _, _, _ ->
                sendCount.incrementAndGet()
            })
        )

        // Normal sendActualReply path (preClaimedToken == null)
        val sbn1 = buildSbn(id = 510, sender = "Cara")
        val eventKey1 = NotificationEventKeyBuilder.buildEventKey(sbn1)!!
        insertAttempt("att-b-1", eventKey1, ReplyExecutionStatus.DISPATCHED.name, true, true)
        assertNull(dbUtils.getNotificationClaim(eventKey1))

        val res1 = service.sendActualReply(sbn1, wearFromSbn(sbn1), "Dup reply", null, ReplySourceType.DEFAULT)
        assertEquals(ReplyExecutionStatus.CANCELLED, res1.status)
        assertEquals(ReplyExecutionManager.FAILURE_DUPLICATE_EVENT, res1.failureCategory)
        assertNull(dbUtils.getNotificationClaim(eventKey1))
        assertEquals(0, sendCount.get())

        // Pre-claimed sendActualReply path (preClaimedToken != null) with missing claim row
        val sbn2 = buildSbn(id = 511, sender = "Dan")
        val eventKey2 = NotificationEventKeyBuilder.buildEventKey(sbn2)!!
        insertAttempt("att-b-2", eventKey2, ReplyExecutionStatus.UNKNOWN.name, true, false)
        assertNull(dbUtils.getNotificationClaim(eventKey2))

        val res2 = service.sendActualReply(
            sbn2, wearFromSbn(sbn2), "Dup preclaimed", null, ReplySourceType.GLOBAL_AI, null, "pre-tok-b2"
        )
        assertEquals(ReplyExecutionStatus.CANCELLED, res2.status)
        assertEquals(ReplyExecutionManager.FAILURE_UNKNOWN_OUTCOME_LOCKED, res2.failureCategory)
        assertNull(dbUtils.getNotificationClaim(eventKey2))
        assertEquals(0, sendCount.get())

        // Pre-claimed sendActualReply path with open CLAIMED row + blocking DISPATCHED attempt
        val sbn3 = buildSbn(id = 512, sender = "Eve")
        val eventKey3 = NotificationEventKeyBuilder.buildEventKey(sbn3)!!
        assertTrue(
            dbUtils.tryAcquireNotificationClaim(
                eventKey3, "pre-tok-b3", "com.whatsapp", "eve", sbn3.key, 1000L, false
            ).acquired
        )
        insertAttempt("att-b-3-prior", eventKey3, ReplyExecutionStatus.DISPATCHED.name, true, true)

        val res3 = service.sendActualReply(
            sbn3, wearFromSbn(sbn3), "Open claim dup", null, ReplySourceType.GLOBAL_AI, null, "pre-tok-b3"
        )
        assertEquals(ReplyExecutionStatus.CANCELLED, res3.status)
        assertEquals(ReplyExecutionManager.FAILURE_DUPLICATE_EVENT, res3.failureCategory)
        assertNull(dbUtils.getNotificationClaim(eventKey3))
        assertEquals(0, sendCount.get())
    }

    // -----------------------------------------------------------------
    // C. Existing attempt + evicted/deleted claim -> blocked
    // -----------------------------------------------------------------
    @Test
    fun `C_existing DISPATCHED attempt after bounded or TTL claim eviction blocks replay`() {
        val sendCount = AtomicInteger(0)
        service.setReplyExecutionManagerForTesting(
            ReplyExecutionManager(context, pendingIntentSender = ReplyExecutionManager.PendingIntentSender { _, _, _ ->
                sendCount.incrementAndGet()
            })
        )

        // 1. Bounded row eviction (maxResolvedRows = 0)
        val sbnBounded = buildSbn(id = 520, sender = "Fay")
        val boundedKey = NotificationEventKeyBuilder.buildEventKey(sbnBounded)!!
        val firstRes = service.sendActualReply(sbnBounded, wearFromSbn(sbnBounded), "First reply", null, ReplySourceType.DEFAULT)
        assertEquals(ReplyExecutionStatus.DISPATCHED, firstRes.status)
        assertEquals(1, sendCount.get())
        assertNotNull(dbUtils.getNotificationClaim(boundedKey))

        val deletedBounded = db.notificationDispatchClaimDao()
            .purgeExpiredAndBoundedClaimsSync(nowMs = 1L, maxResolvedRows = 0)
        assertEquals(1, deletedBounded)
        assertNull(dbUtils.getNotificationClaim(boundedKey))
        assertEquals(1, db.replyAttemptDao().getAttemptsByEventKeySync(boundedKey).size)

        val replayAfterBoundedEviction = service.sendActualReply(
            sbnBounded, wearFromSbn(sbnBounded), "Duplicate after eviction", null, ReplySourceType.DEFAULT
        )
        assertEquals(ReplyExecutionStatus.CANCELLED, replayAfterBoundedEviction.status)
        assertEquals(ReplyExecutionManager.FAILURE_DUPLICATE_EVENT, replayAfterBoundedEviction.failureCategory)
        assertNull(dbUtils.getNotificationClaim(boundedKey))
        assertEquals(1, sendCount.get())
        assertEquals(1L, db.logsDao().getNumReplies())

        // 2. TTL claim expiry eviction
        val sbnTtl = buildSbn(id = 521, sender = "Gus")
        val ttlKey = NotificationEventKeyBuilder.buildEventKey(sbnTtl)!!
        val ttlFirst = service.sendActualReply(sbnTtl, wearFromSbn(sbnTtl), "TTL first", null, ReplySourceType.DEFAULT)
        assertEquals(ReplyExecutionStatus.DISPATCHED, ttlFirst.status)
        assertEquals(2, sendCount.get())

        val ttlClaim = dbUtils.getNotificationClaim(ttlKey)!!
        val deletedTtl = db.notificationDispatchClaimDao()
            .purgeExpiredAndBoundedClaimsSync(
                nowMs = ttlClaim.expiresAtMs + 1000L,
                maxResolvedRows = NotificationDispatchClaimEntity.MAX_RESOLVED_CLAIM_ROWS
            )
        assertEquals(1, deletedTtl)
        assertNull(dbUtils.getNotificationClaim(ttlKey))

        val replayAfterTtl = service.sendActualReply(
            sbnTtl, wearFromSbn(sbnTtl), "Duplicate after TTL purge", null, ReplySourceType.DEFAULT
        )
        assertEquals(ReplyExecutionStatus.CANCELLED, replayAfterTtl.status)
        assertEquals(ReplyExecutionManager.FAILURE_DUPLICATE_EVENT, replayAfterTtl.failureCategory)
        assertNull(dbUtils.getNotificationClaim(ttlKey))
        assertEquals(2, sendCount.get())
        assertEquals(2L, db.logsDao().getNumReplies())
    }

    // -----------------------------------------------------------------
    // D. All prior-dispatch attempt statuses covered
    // -----------------------------------------------------------------
    @Test
    fun `D_every blocking or unrecognized status in reply_attempts denies acquisition when claim is missing`() {
        data class Case(
            val label: String,
            val status: String,
            val dispatchAttempted: Boolean,
            val dispatchedNormally: Boolean,
            val allowRetry: Boolean,
            val expectedDecision: RetryDecision
        )

        val cases = listOf(
            Case("dispatched_non_retry", ReplyExecutionStatus.DISPATCHED.name, true, true, false, RetryDecision.DENY_ALREADY_DISPATCHED),
            Case("dispatched_retry", ReplyExecutionStatus.DISPATCHED.name, true, true, true, RetryDecision.DENY_ALREADY_DISPATCHED),
            Case("unknown_non_retry", ReplyExecutionStatus.UNKNOWN.name, true, false, false, RetryDecision.DENY_UNKNOWN_OUTCOME),
            Case("unknown_retry", ReplyExecutionStatus.UNKNOWN.name, true, false, true, RetryDecision.DENY_UNKNOWN_OUTCOME),
            Case("generated_non_retry", ReplyExecutionStatus.GENERATED.name, false, false, false, RetryDecision.DENY_IN_FLIGHT),
            Case("generated_retry", ReplyExecutionStatus.GENERATED.name, false, false, true, RetryDecision.DENY_IN_FLIGHT),
            Case("dispatching_non_retry", ReplyExecutionStatus.DISPATCHING.name, true, false, false, RetryDecision.DENY_IN_FLIGHT),
            Case("dispatching_retry", ReplyExecutionStatus.DISPATCHING.name, true, false, true, RetryDecision.DENY_IN_FLIGHT),
            Case("cancelled_non_retry", ReplyExecutionStatus.CANCELLED.name, false, false, false, RetryDecision.DENY_CANCELLED_OR_STALE),
            Case("cancelled_retry", ReplyExecutionStatus.CANCELLED.name, true, false, true, RetryDecision.DENY_CANCELLED_OR_STALE),
            Case("failed_dispatched_attempted_non_retry", ReplyExecutionStatus.FAILED.name, true, false, false, RetryDecision.DENY_DUPLICATE_PRE_DISPATCH_FAILURE),
            Case("failed_dispatched_attempted_retry", ReplyExecutionStatus.FAILED.name, true, false, true, RetryDecision.DENY_DUPLICATE_PRE_DISPATCH_FAILURE),
            Case("failed_dispatched_normally_retry", ReplyExecutionStatus.FAILED.name, false, true, true, RetryDecision.DENY_DUPLICATE_PRE_DISPATCH_FAILURE),
            Case("failed_pre_dispatch_non_retry", ReplyExecutionStatus.FAILED.name, false, false, false, RetryDecision.DENY_DUPLICATE_PRE_DISPATCH_FAILURE),
            Case("unrecognized_status_non_retry", "CORRUPTED_STATE", false, false, false, RetryDecision.DENY_UNKNOWN_OUTCOME),
            Case("unrecognized_status_retry", "CORRUPTED_STATE", false, false, true, RetryDecision.DENY_UNKNOWN_OUTCOME)
        )

        for ((idx, testCase) in cases.withIndex()) {
            val eventKey = "evt_d_${idx}_${testCase.label}"
            insertAttempt(
                attemptId = "att-d-$idx",
                eventKey = eventKey,
                status = testCase.status,
                dispatchAttempted = testCase.dispatchAttempted,
                dispatchedNormally = testCase.dispatchedNormally
            )
            val outcome = dbUtils.tryAcquireNotificationClaim(
                eventKey,
                "new-tok-$idx",
                "com.whatsapp",
                "alice",
                "nk-$idx",
                2000L + idx,
                testCase.allowRetry
            )
            assertFalse("${testCase.label} must not acquire", outcome.acquired)
            assertEquals("${testCase.label} decision mismatch", testCase.expectedDecision, outcome.decision)
            assertNull("${testCase.label} must not leave tentative claim", dbUtils.getNotificationClaim(eventKey))
        }
    }

    // -----------------------------------------------------------------
    // E. Authorized retry continues working; unauthorized retry blocked
    // -----------------------------------------------------------------
    @Test
    fun `E_authorized pre-dispatch retry succeeds while unauthorized cross-table retry is blocked`() {
        val sendCount = AtomicInteger(0)
        service.setReplyExecutionManagerForTesting(
            ReplyExecutionManager(context, pendingIntentSender = ReplyExecutionManager.PendingIntentSender { _, _, _ ->
                sendCount.incrementAndGet()
            })
        )

        // 1. Authorized retry after legitimate pre-dispatch FAILED
        val sbnNoPi = buildSbn(id = 530, sender = "Hank", withPendingIntent = false)
        val retryKey = NotificationEventKeyBuilder.buildEventKey(sbnNoPi)!!
        val initialFail = service.sendActualReply(
            sbnNoPi, wearFromSbn(sbnNoPi, withPendingIntent = false), "Will retry", null, ReplySourceType.DEFAULT
        )
        assertEquals(ReplyExecutionStatus.FAILED, initialFail.status)
        assertFalse(initialFail.dispatchAttempted)
        assertEquals(0, sendCount.get())

        val sbnValidPi = buildSbn(id = 530, sender = "Hank", whenMs = sbnNoPi.notification.`when`, withPendingIntent = true)
        assertEquals(retryKey, NotificationEventKeyBuilder.buildEventKey(sbnValidPi))

        val retryRes = service.retryPreDispatchFailure(
            sbnValidPi, wearFromSbn(sbnValidPi), "Will retry", null, ReplySourceType.DEFAULT, null
        )
        assertEquals(ReplyExecutionStatus.DISPATCHED, retryRes.status)
        assertTrue(retryRes.dispatchedNormally)
        assertEquals(1, sendCount.get())
        assertEquals("DISPATCHED", dbUtils.getNotificationClaim(retryKey)?.claimStatus)

        // 2. Unauthorized retry: pre-dispatch FAILED claim paired with DISPATCHED attempt row -> blocked!
        val badKey = "evt_e_unauthorized_retry"
        insertAttempt("att-e-prev-fail", badKey, ReplyExecutionStatus.FAILED.name, false, false, createdAtMs = 1000L)
        insertAttempt("att-e-dispatched", badKey, ReplyExecutionStatus.DISPATCHED.name, true, true, createdAtMs = 2000L)
        db.notificationDispatchClaimDao().insertClaimIgnoreSync(
            NotificationDispatchClaimEntity(
                eventKey = badKey,
                attemptId = "att-e-prev-fail",
                packageName = "com.whatsapp",
                senderKey = "hank",
                notificationKey = "nk-bad",
                claimStatus = ReplyExecutionStatus.FAILED.name,
                failureCategory = ReplyExecutionManager.FAILURE_MISSING_PENDING_INTENT,
                dispatchAttempted = false,
                dispatchedNormally = false,
                createdAtMs = 1000L,
                updatedAtMs = 1000L,
                expiresAtMs = 9_999_999_999_999L
            )
        )
        val unauthorizedOutcome = dbUtils.tryAcquireNotificationClaim(
            badKey, "att-e-reclaim", "com.whatsapp", "hank", "nk-bad", 3000L, true
        )
        assertFalse(unauthorizedOutcome.acquired)
        assertEquals(RetryDecision.DENY_ALREADY_DISPATCHED, unauthorizedOutcome.decision)

        // 3. Direct tryAcquireNotificationClaim with allowRetryOnPreDispatchFailure=true & clean pre-dispatch FAILED
        val cleanFailKey = "evt_e_clean_fail_missing_claim"
        insertAttempt("att-e-clean", cleanFailKey, ReplyExecutionStatus.FAILED.name, false, false)
        val allowedOutcome = dbUtils.tryAcquireNotificationClaim(
            cleanFailKey, "att-e-retry-tok", "com.whatsapp", "hank", "nk-clean", 4000L, true
        )
        assertTrue(allowedOutcome.acquired)
        assertEquals(RetryDecision.ALLOW_AUTHORIZED_RETRY, allowedOutcome.decision)
        assertEquals("att-e-retry-tok", dbUtils.getNotificationClaim(cleanFailKey)?.attemptId)
    }

    // -----------------------------------------------------------------
    // F. Concurrent DB-backed callers for identical eventKey
    // -----------------------------------------------------------------
    @Test
    fun `F_concurrent callers allow strictly at most 1 winner and 0 winners when reply_attempts already blocks`() {
        val pool = Executors.newFixedThreadPool(8)
        try {
            // 1. No prior attempts: strictly 1 winner
            val gate1 = CountDownLatch(1)
            val futures1 = (1..16).map { idx ->
                pool.submit<ClaimOutcomeSummary> {
                    gate1.await(5, TimeUnit.SECONDS)
                    val out = dbUtils.tryAcquireNotificationClaim(
                        "evt_f_fresh",
                        "tok-f-$idx",
                        "com.whatsapp",
                        "alice",
                        "nk-f",
                        1000L + idx,
                        false
                    )
                    ClaimOutcomeSummary(out.acquired, out.decision)
                }
            }
            gate1.countDown()
            val results1 = futures1.map { it.get(10, TimeUnit.SECONDS) }
            assertEquals(1, results1.count { it.acquired })
            assertEquals(15, results1.count { !it.acquired })
            assertEquals(1, db.notificationDispatchClaimDao().getAllClaimsSync().count { it.eventKey == "evt_f_fresh" })

            // 2. Prior DISPATCHED attempt + missing claim: strictly 0 winners and 0 lingering claims
            val blockedKey = "evt_f_blocked_by_attempt"
            insertAttempt("att-f-dispatched", blockedKey, ReplyExecutionStatus.DISPATCHED.name, true, true)
            val gate2 = CountDownLatch(1)
            val futures2 = (1..16).map { idx ->
                pool.submit<ClaimOutcomeSummary> {
                    gate2.await(5, TimeUnit.SECONDS)
                    val out = dbUtils.tryAcquireNotificationClaim(
                        blockedKey,
                        "tok-fb-$idx",
                        "com.whatsapp",
                        "alice",
                        "nk-fb",
                        2000L + idx,
                        false
                    )
                    ClaimOutcomeSummary(out.acquired, out.decision)
                }
            }
            gate2.countDown()
            val results2 = futures2.map { it.get(10, TimeUnit.SECONDS) }
            assertEquals(0, results2.count { it.acquired })
            assertTrue(results2.all { it.decision == RetryDecision.DENY_ALREADY_DISPATCHED })
            assertNull(dbUtils.getNotificationClaim(blockedKey))
        } finally {
            pool.shutdownNow()
        }
    }

    private data class ClaimOutcomeSummary(val acquired: Boolean, val decision: RetryDecision)

    // -----------------------------------------------------------------
    // G. Existing GENERATED/DISPATCHING recovery scenarios remain consistent
    // -----------------------------------------------------------------
    @Test
    fun `G_interrupted GENERATED and DISPATCHING recovery reconciles claims and keeps replays blocked`() {
        assertTrue(
            dbUtils.tryAcquireNotificationClaim(
                "evt_g_gen", "att-g-gen", "com.whatsapp", "alice", "nk-g1", 1000L, false
            ).acquired
        )
        insertAttempt("att-g-gen", "evt_g_gen", ReplyExecutionStatus.GENERATED.name, false, false)
        assertEquals(
            1,
            dbUtils.updateNotificationClaimTransition(
                "evt_g_gen", "att-g-gen", "att-g-gen", ReplyExecutionStatus.GENERATED, 1005L
            )
        )

        assertTrue(
            dbUtils.tryAcquireNotificationClaim(
                "evt_g_disp", "att-g-disp", "com.whatsapp", "alice", "nk-g2", 1000L, false
            ).acquired
        )
        insertAttempt("att-g-disp", "evt_g_disp", ReplyExecutionStatus.DISPATCHING.name, true, false)
        assertEquals(
            1,
            dbUtils.updateNotificationClaimTransition(
                "evt_g_disp", "att-g-disp", "att-g-disp", ReplyExecutionStatus.DISPATCHING, 1005L
            )
        )

        val summary = NotificationReplyRecoveryManager.recoverOnStartup(context, 50_000L)
        assertEquals(1, summary.recoveredGeneratedCount)
        assertEquals(1, summary.recoveredDispatchingCount)

        assertEquals("CANCELLED", db.replyAttemptDao().getAttemptByIdSync("att-g-gen")?.status)
        assertEquals("CANCELLED", dbUtils.getNotificationClaim("evt_g_gen")?.claimStatus)
        assertEquals("UNKNOWN", db.replyAttemptDao().getAttemptByIdSync("att-g-disp")?.status)
        assertEquals("UNKNOWN", dbUtils.getNotificationClaim("evt_g_disp")?.claimStatus)

        val retryGen = dbUtils.tryAcquireNotificationClaim(
            "evt_g_gen", "att-g-gen-2", "com.whatsapp", "alice", "nk-g1", 60_000L, true
        )
        assertFalse(retryGen.acquired)
        assertEquals(RetryDecision.DENY_CANCELLED_OR_STALE, retryGen.decision)

        val retryDisp = dbUtils.tryAcquireNotificationClaim(
            "evt_g_disp", "att-g-disp-2", "com.whatsapp", "alice", "nk-g2", 60_000L, true
        )
        assertFalse(retryDisp.acquired)
        assertEquals(RetryDecision.DENY_UNKNOWN_OUTCOME, retryDisp.decision)
    }

    // -----------------------------------------------------------------
    // H. Database / guard failures fail closed and never silently send
    // -----------------------------------------------------------------
    @Test
    fun `H_database or cross-table guard exceptions fail closed without dispatching`() {
        val sendCount = AtomicInteger(0)
        service.setReplyExecutionManagerForTesting(
            ReplyExecutionManager(context, pendingIntentSender = ReplyExecutionManager.PendingIntentSender { _, _, _ ->
                sendCount.incrementAndGet()
            })
        )

        // 1. Pre-claim missing-token path fails during tryAcquireNotificationClaim
        val failingAcquireDb = object : DbUtils(context) {
            override fun tryAcquireNotificationClaim(
                eventKey: String,
                claimToken: String,
                packageName: String,
                senderKey: String,
                notificationKey: String?,
                nowMs: Long,
                allowRetryOnPreDispatchFailure: Boolean
            ): com.parishod.watomatic.model.logs.ClaimAcquireOutcome {
                throw SQLiteException("Simulated SQLite read failure on reply_attempts")
            }
        }
        service.setDbUtilsForTesting(failingAcquireDb)
        val sbn1 = buildSbn(id = 540, sender = "Iris")
        val res1 = service.sendActualReply(sbn1, wearFromSbn(sbn1), "Should fail closed", null, ReplySourceType.DEFAULT)
        assertEquals(ReplyExecutionStatus.FAILED, res1.status)
        assertFalse(res1.dispatchAttempted)
        assertEquals(ReplyExecutionManager.FAILURE_CLAIM_PERSISTENCE_ERROR, res1.failureCategory)
        assertEquals(0, sendCount.get())

        // 2. Pre-claimed open-claim path fails during getBlockingAttemptDecisionForEvent
        val sbn2 = buildSbn(id = 541, sender = "Jake")
        val eventKey2 = NotificationEventKeyBuilder.buildEventKey(sbn2)!!
        assertTrue(
            dbUtils.tryAcquireNotificationClaim(
                eventKey2, "tok-h2", "com.whatsapp", "jake", sbn2.key, 1000L, false
            ).acquired
        )
        val failingAttemptCheckDb = object : DbUtils(context) {
            override fun getBlockingAttemptDecisionForEvent(eventKey: String): RetryDecision? {
                throw SQLiteException("Simulated reply_attempts query failure")
            }
        }
        service.setDbUtilsForTesting(failingAttemptCheckDb)
        val res2 = service.sendActualReply(
            sbn2, wearFromSbn(sbn2), "Should also fail closed", null, ReplySourceType.GLOBAL_AI, null, "tok-h2"
        )
        assertEquals(ReplyExecutionStatus.FAILED, res2.status)
        assertFalse(res2.dispatchAttempted)
        assertEquals(ReplyExecutionManager.FAILURE_CLAIM_PERSISTENCE_ERROR, res2.failureCategory)
        assertEquals(0, sendCount.get())
    }
}
