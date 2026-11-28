package com.parishod.watomatic.service

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Process
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationCompat
import androidx.core.app.RemoteInput
import androidx.preference.PreferenceManager
import androidx.test.core.app.ApplicationProvider
import com.parishod.watomatic.NotificationWear
import com.parishod.watomatic.model.ai.AiMemoryDatabase
import com.parishod.watomatic.model.ai.PendingPostDispatchMemoryStore
import com.parishod.watomatic.model.logs.ClaimAcquireOutcome
import com.parishod.watomatic.model.logs.MessageLogsDB
import com.parishod.watomatic.model.logs.NotificationDispatchClaimDao
import com.parishod.watomatic.model.logs.NotificationDispatchClaimEntity
import com.parishod.watomatic.model.logs.ReplyAttemptEntity
import com.parishod.watomatic.model.preferences.PreferencesManager
import com.parishod.watomatic.model.rules.AutoReplyRule
import com.parishod.watomatic.model.rules.AutoReplyRulesDatabase
import com.parishod.watomatic.model.rules.MatchType
import com.parishod.watomatic.model.utils.Constants
import com.parishod.watomatic.model.utils.DbUtils
import com.parishod.watomatic.service.reply.NotificationEventKeyBuilder
import com.parishod.watomatic.service.reply.NotificationReplyRecoveryManager
import com.parishod.watomatic.service.reply.PendingClaimHandoff
import com.parishod.watomatic.service.reply.PreDispatchRetryCoordinator
import com.parishod.watomatic.service.reply.PreDispatchRetryStateStore
import com.parishod.watomatic.service.reply.ReplyExecutionManager
import com.parishod.watomatic.service.reply.ReplyExecutionResult
import com.parishod.watomatic.service.reply.ReplyExecutionStatus
import com.parishod.watomatic.service.reply.ReplyRetryPolicy
import com.parishod.watomatic.service.reply.ReplySourceType
import com.parishod.watomatic.service.reply.RetryDecision
import com.parishod.watomatic.service.rules.RuleExecutionReservationCoordinator
import com.parishod.watomatic.service.rules.RuleNotificationProcessor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.AbstractExecutorService
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class Stage0253Batch3CAuditConf01And03Test {

    private lateinit var context: Context
    private lateinit var prefs: PreferencesManager
    private lateinit var logsDb: MessageLogsDB
    private lateinit var rulesDb: AutoReplyRulesDatabase
    private lateinit var dbUtils: DbUtils
    private val createdServices = mutableListOf<NotificationService>()

    private class CapturingScheduledExecutor(
        private val scheduledTasks: MutableList<Runnable>
    ) : AbstractExecutorService(), ScheduledExecutorService {
        private var shutdown = false
        override fun shutdown() { shutdown = true }
        override fun shutdownNow(): MutableList<Runnable> {
            shutdown = true
            return mutableListOf()
        }
        override fun isShutdown(): Boolean = shutdown
        override fun isTerminated(): Boolean = shutdown
        override fun awaitTermination(timeout: Long, unit: TimeUnit): Boolean = true
        override fun execute(command: Runnable) { command.run() }
        override fun schedule(command: Runnable, delay: Long, unit: TimeUnit): ScheduledFuture<*> {
            scheduledTasks.add(command)
            return object : ScheduledFuture<Any?> {
                override fun getDelay(u: TimeUnit): Long = u.convert(delay, unit)
                override fun compareTo(other: java.util.concurrent.Delayed): Int = 0
                override fun cancel(mayInterruptIfRunning: Boolean): Boolean = false
                override fun isCancelled(): Boolean = false
                override fun isDone(): Boolean = false
                override fun get(): Any? = null
                override fun get(timeout: Long, u: TimeUnit): Any? = null
            }
        }
        override fun <V : Any?> schedule(callable: java.util.concurrent.Callable<V>, delay: Long, unit: TimeUnit) =
            error("Unused")
        override fun scheduleAtFixedRate(command: Runnable, initialDelay: Long, period: Long, unit: TimeUnit) =
            error("Unused")
        override fun scheduleWithFixedDelay(command: Runnable, initialDelay: Long, delay: Long, unit: TimeUnit) =
            error("Unused")
    }

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        PreferencesManager.resetInstance()
        PreferenceManager.getDefaultSharedPreferences(context).edit().clear().commit()
        context.getSharedPreferences("watomatic_pre_dispatch_retry_state", Context.MODE_PRIVATE)
            .edit().clear().commit()
        val enc = context.getSharedPreferences("test_batch3c_enc", Context.MODE_PRIVATE)
        enc.edit().clear().commit()
        PreferencesManager.setEncryptedPrefsProviderForTesting { enc }

        prefs = PreferencesManager.getPreferencesInstance(context)
        prefs.setServicePref(true)
        prefs.saveEnabledApps("com.whatsapp", true)
        prefs.setEnableByokReplies(false)
        prefs.setAppendWatomaticAttribution(false)
        prefs.setRuleFallbackToGlobalEnabled(false)

        PendingClaimHandoff.clear()
        NotificationDispatchClaimDao.clearAllPriorFailedSnapshotsForTesting()
        PendingPostDispatchMemoryStore.clearForTesting(context)
        MessageLogsDB.resetInstance()
        context.deleteDatabase(Constants.LOGS_DB_NAME)
        AutoReplyRulesDatabase.resetInstance()
        context.deleteDatabase(AutoReplyRulesDatabase.DB_NAME)
        AiMemoryDatabase.resetInstance()
        context.deleteDatabase(AiMemoryDatabase.DB_NAME)

        logsDb = MessageLogsDB.getInstance(context)
        rulesDb = AutoReplyRulesDatabase.getInstance(context)
        dbUtils = DbUtils(context)
    }

    @After
    fun tearDown() {
        for (svc in createdServices) {
            runCatching { svc.onDestroy() }
            runCatching { svc.awaitAiMemoryIdleForTesting() }
        }
        createdServices.clear()
        PendingClaimHandoff.clear()
        NotificationDispatchClaimDao.clearAllPriorFailedSnapshotsForTesting()
        val cleanupExec = Executors.newSingleThreadExecutor()
        try {
            cleanupExec.submit {
                PendingPostDispatchMemoryStore.clearForTesting(context)
                MessageLogsDB.resetInstance()
                context.deleteDatabase(Constants.LOGS_DB_NAME)
                AutoReplyRulesDatabase.resetInstance()
                context.deleteDatabase(AutoReplyRulesDatabase.DB_NAME)
                AiMemoryDatabase.resetInstance()
                context.deleteDatabase(AiMemoryDatabase.DB_NAME)
            }.get(15, TimeUnit.SECONDS)
        } finally {
            cleanupExec.shutdownNow()
        }
        context.getSharedPreferences("watomatic_pre_dispatch_retry_state", Context.MODE_PRIVATE)
            .edit().clear().commit()
        PreferenceManager.getDefaultSharedPreferences(context).edit().clear().commit()
        context.getSharedPreferences("test_batch3c_enc", Context.MODE_PRIVATE).edit().clear().commit()
        PreferencesManager.resetInstance()
    }

    private fun createManagedService(sendCounter: AtomicInteger = AtomicInteger(0)): NotificationService {
        val controller = Robolectric.buildService(NotificationService::class.java)
        val svc = controller.get()
        controller.create()
        svc.awaitAiMemoryIdleForTesting()
        svc.setReplyExecutionManagerForTesting(
            ReplyExecutionManager(
                context,
                pendingIntentSender = ReplyExecutionManager.PendingIntentSender { _, _, _ ->
                    sendCounter.incrementAndGet()
                }
            )
        )
        createdServices.add(svc)
        return svc
    }

    private fun buildSbn(
        pkg: String = "com.whatsapp",
        id: Int = 600,
        sender: String? = "Alice",
        message: String? = "Hello Batch 3C",
        whenMs: Long = System.currentTimeMillis(),
        isGroup: Boolean = false
    ): StatusBarNotification {
        val replyPi = PendingIntent.getBroadcast(
            context,
            id,
            Intent("com.parishod.watomatic.BATCH3C_REPLY").setPackage(context.packageName),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        )
        val ri = RemoteInput.Builder("reply_key").setLabel("Reply").setAllowFreeFormInput(true).build()
        val action = NotificationCompat.Action.Builder(0, "Reply", replyPi).addRemoteInput(ri).build()
        val builder = NotificationCompat.Builder(context, "ch")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setWhen(whenMs)
            .addAction(action)
        if (sender != null) builder.setContentTitle(sender)
        if (message != null) builder.setContentText(message)
        val notification = builder.build().apply {
            extras.putBoolean("android.isGroupConversation", isGroup)
            if (isGroup && sender != null) {
                extras.putString("android.hiddenConversationTitle", sender)
            }
        }
        return StatusBarNotification(
            pkg,
            pkg,
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
        includePendingIntent: Boolean = true
    ): NotificationWear {
        val ri = RemoteInput.Builder("reply_key").setLabel("Reply").setAllowFreeFormInput(true).build()
        val pi = if (includePendingIntent) {
            PendingIntent.getBroadcast(
                context,
                sbn.id + 8000,
                Intent("com.parishod.watomatic.BATCH3C_WEAR").setPackage(context.packageName),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
            )
        } else null
        return NotificationWear(sbn.packageName, pi, listOf(ri), sbn.notification.extras, sbn.tag, sbn.id.toString())
    }

    private fun insertAttempt(
        attemptId: String,
        eventKey: String,
        status: String,
        dispatchAttempted: Boolean = false,
        dispatchedNormally: Boolean = false,
        failureCategory: String? = null,
        tsMs: Long = System.currentTimeMillis()
    ) {
        logsDb.replyAttemptDao().upsertAttemptSafely(
            ReplyAttemptEntity(
                attemptId = attemptId,
                sourceType = ReplySourceType.DEFAULT.name,
                sourceLabel = "Default",
                packageName = "com.whatsapp",
                notificationKey = "nk-$attemptId",
                senderName = "Alice",
                incomingMessage = "Hello",
                replyText = "Reply",
                status = status,
                dispatchAttempted = dispatchAttempted,
                dispatchedNormally = dispatchedNormally,
                failureCategory = failureCategory,
                createdAtMs = tsMs,
                updatedAtMs = tsMs,
                eventKey = eventKey
            )
        )
    }

    // =========================================================================
    // AUDIT-CONF-01 (A–H): Retry-Budget Integrity & Claim Evidence Preservation
    // =========================================================================

    @Test
    fun `A_missing claim with prior pre-dispatch FAILED returns ALLOW_AUTHORIZED_RETRY and increments retry budget`() {
        val now = System.currentTimeMillis()
        val sbn = buildSbn(id = 601, sender = "Alice", message = "Missing claim retry A", whenMs = now)
        val wear = wearFromSbn(sbn)
        val eventKey = NotificationEventKeyBuilder.buildEventKey(sbn)!!

        insertAttempt(
            attemptId = "att-a-1",
            eventKey = eventKey,
            status = ReplyExecutionStatus.FAILED.name,
            dispatchAttempted = false,
            dispatchedNormally = false,
            failureCategory = ReplyExecutionManager.FAILURE_MISSING_PENDING_INTENT,
            tsMs = now - 500L
        )
        assertNull(dbUtils.getNotificationClaim(eventKey))

        val decision = ReplyRetryPolicy.evaluateAuthorizedPreDispatchRetry(
            existingClaim = null,
            linkedAttempt = null,
            attemptsForEventKey = logsDb.replyAttemptDao().getAttemptsByEventKeySync(eventKey),
            retryCountAlreadyUsed = 0
        )
        assertEquals(RetryDecision.ALLOW_AUTHORIZED_RETRY, decision)

        val backend = PreDispatchRetryStateStore.InMemoryBackend()
        val coordinator = PreDispatchRetryCoordinator(backend)
        val outcome = coordinator.tryAcquireForFreshNotification(
            dbUtils, sbn, wear, eventKey, "tok-a-2", "com.whatsapp", "alice", sbn.key, now, false
        )
        assertTrue(outcome.acquired)
        assertEquals(RetryDecision.ALLOW_AUTHORIZED_RETRY, outcome.decision)
        assertEquals(1, coordinator.getRetryCount(eventKey))
    }

    @Test
    fun `B_missing or evicted claim after maxRetries pre-dispatch failures in reply_attempts enforces DENY_RETRY_LIMIT_EXCEEDED`() {
        val now = System.currentTimeMillis()
        val sbn = buildSbn(id = 602, sender = "Bob", message = "Exhausted missing claim B", whenMs = now)
        val wear = wearFromSbn(sbn)
        val eventKey = NotificationEventKeyBuilder.buildEventKey(sbn)!!

        // Initial attempt + MAX_PRE_DISPATCH_RETRIES (2) failed pre-dispatch attempts = 3 total FAILED rows
        for (i in 0..ReplyRetryPolicy.MAX_PRE_DISPATCH_RETRIES) {
            insertAttempt(
                attemptId = "att-b-$i",
                eventKey = eventKey,
                status = ReplyExecutionStatus.FAILED.name,
                dispatchAttempted = false,
                dispatchedNormally = false,
                failureCategory = ReplyExecutionManager.FAILURE_MISSING_PENDING_INTENT,
                tsMs = now - 1000L + i
            )
        }
        // Claim is missing/evicted AND prefs/backend has count 0 (simulating prefs eviction/restart)
        assertNull(dbUtils.getNotificationClaim(eventKey))

        val coordinator = PreDispatchRetryCoordinator(PreDispatchRetryStateStore.InMemoryBackend())
        val freshOutcome = coordinator.tryAcquireForFreshNotification(
            dbUtils, sbn, wear, eventKey, "tok-b-bypass", "com.whatsapp", "bob", sbn.key, now, false
        )
        assertFalse(freshOutcome.acquired)
        assertEquals(RetryDecision.DENY_RETRY_LIMIT_EXCEEDED, freshOutcome.decision)
        assertNull("Denied retry must not leave tentative claim", dbUtils.getNotificationClaim(eventKey))

        val explicitOutcome = coordinator.tryAcquireExplicitPreDispatchRetry(
            dbUtils, sbn, wear, eventKey, "tok-b-explicit", "com.whatsapp", "bob", sbn.key, now, false
        )
        assertFalse(explicitOutcome.acquired)
        assertEquals(RetryDecision.DENY_RETRY_LIMIT_EXCEEDED, explicitOutcome.decision)
        assertNull(dbUtils.getNotificationClaim(eventKey))
    }

    @Test
    fun `C_missing claim with exhausted stateBackend retryCount blocks even when reply_attempts is empty`() {
        val now = System.currentTimeMillis()
        val sbn = buildSbn(id = 603, sender = "Cara", message = "Backend exhausted C", whenMs = now)
        val wear = wearFromSbn(sbn)
        val eventKey = NotificationEventKeyBuilder.buildEventKey(sbn)!!

        val backend = PreDispatchRetryStateStore.InMemoryBackend()
        repeat(ReplyRetryPolicy.MAX_PRE_DISPATCH_RETRIES) {
            backend.incrementRetryCount(eventKey, now)
        }
        val coordinator = PreDispatchRetryCoordinator(backend)

        val freshOutcome = coordinator.tryAcquireForFreshNotification(
            dbUtils, sbn, wear, eventKey, "tok-c-1", "com.whatsapp", "cara", sbn.key, now, false
        )
        assertFalse(freshOutcome.acquired)
        assertEquals(RetryDecision.DENY_RETRY_LIMIT_EXCEEDED, freshOutcome.decision)
        assertNull(dbUtils.getNotificationClaim(eventKey))

        val explicitOutcome = coordinator.tryAcquireExplicitPreDispatchRetry(
            dbUtils, sbn, wear, eventKey, "tok-c-2", "com.whatsapp", "cara", sbn.key, now, false
        )
        assertFalse(explicitOutcome.acquired)
        assertEquals(RetryDecision.DENY_RETRY_LIMIT_EXCEEDED, explicitOutcome.decision)
        assertNull(dbUtils.getNotificationClaim(eventKey))
    }

    @Test
    fun `D_missing claim still blocks DISPATCHED UNKNOWN CANCELLED IN_FLIGHT and post-dispatch FAILED attempts`() {
        val now = System.currentTimeMillis()
        val cases = listOf(
            Triple(ReplyExecutionStatus.DISPATCHED.name, true, RetryDecision.DENY_ALREADY_DISPATCHED),
            Triple(ReplyExecutionStatus.UNKNOWN.name, true, RetryDecision.DENY_UNKNOWN_OUTCOME),
            Triple(ReplyExecutionStatus.CANCELLED.name, false, RetryDecision.DENY_CANCELLED_OR_STALE),
            Triple(ReplyExecutionStatus.GENERATED.name, false, RetryDecision.DENY_IN_FLIGHT),
            Triple(ReplyExecutionStatus.DISPATCHING.name, true, RetryDecision.DENY_IN_FLIGHT),
            Triple(ReplyExecutionStatus.FAILED.name, true, RetryDecision.DENY_DUPLICATE_PRE_DISPATCH_FAILURE)
        )
        val coordinator = PreDispatchRetryCoordinator(PreDispatchRetryStateStore.InMemoryBackend())
        for ((idx, case) in cases.withIndex()) {
            val sbn = buildSbn(id = 610 + idx, sender = "SenderD$idx", message = "Msg $idx", whenMs = now)
            val key = NotificationEventKeyBuilder.buildEventKey(sbn)!!
            insertAttempt("att-d-$idx", key, case.first, dispatchAttempted = case.second, tsMs = now)
            val out = coordinator.tryAcquireExplicitPreDispatchRetry(
                dbUtils, sbn, wearFromSbn(sbn), key, "tok-d-$idx", "com.whatsapp", "senderd$idx", sbn.key, now, false
            )
            assertFalse(out.acquired)
            assertEquals("Case ${case.first}", case.third, out.decision)
            assertNull(dbUtils.getNotificationClaim(key))
        }
    }

    @Test
    fun `E_unused claim release on reclaimed retry preserves prior FAILED claim evidence across cycles`() {
        val now = System.currentTimeMillis()
        val sbn = buildSbn(id = 620, sender = "Dan", message = "Evidence preserve E", whenMs = now)
        val wear = wearFromSbn(sbn)
        val eventKey = NotificationEventKeyBuilder.buildEventKey(sbn)!!

        // Initial FAILED pre-dispatch attempt + claim
        assertTrue(
            dbUtils.tryAcquireNotificationClaim(eventKey, "orig-fail-att", "com.whatsapp", "dan", sbn.key, now - 100L, false).acquired
        )
        insertAttempt(
            attemptId = "orig-fail-att",
            eventKey = eventKey,
            status = ReplyExecutionStatus.FAILED.name,
            dispatchAttempted = false,
            dispatchedNormally = false,
            failureCategory = ReplyExecutionManager.FAILURE_MISSING_PENDING_INTENT,
            tsMs = now - 100L
        )
        dbUtils.updateNotificationClaimResult(
            eventKey,
            "orig-fail-att",
            ReplyExecutionResult(
                "orig-fail-att",
                ReplyExecutionStatus.FAILED,
                false,
                false,
                ReplyExecutionManager.FAILURE_MISSING_PENDING_INTENT,
                "Missing PI",
                now - 100L
            )
        )

        // Reclaim for retry #1 -> claim becomes CLAIMED under retry-tok-1
        val coordinator = PreDispatchRetryCoordinator(PreDispatchRetryStateStore.InMemoryBackend())
        val reclaim1 = coordinator.tryAcquireExplicitPreDispatchRetry(
            dbUtils, sbn, wear, eventKey, "retry-tok-1", "com.whatsapp", "dan", sbn.key, now, false
        )
        assertTrue(reclaim1.acquired)
        assertEquals(NotificationDispatchClaimEntity.STATUS_CLAIMED, dbUtils.getNotificationClaim(eventKey)?.claimStatus)

        // Release unused retry-tok-1 -> MUST restore prior FAILED claim evidence instead of deleting!
        assertEquals(1, dbUtils.releaseUnusedNotificationClaim(eventKey, "retry-tok-1"))
        val restored1 = dbUtils.getNotificationClaim(eventKey)
        assertNotNull("Prior FAILED claim evidence must NOT be deleted on unused retry release", restored1)
        assertEquals("orig-fail-att", restored1!!.attemptId)
        assertEquals(ReplyExecutionStatus.FAILED.name, restored1.claimStatus)
        assertEquals(ReplyExecutionManager.FAILURE_MISSING_PENDING_INTENT, restored1.failureCategory)
        assertFalse(restored1.dispatchAttempted)
        assertFalse(restored1.dispatchedNormally)

        // Reclaim & release unused a second time (without reply_attempts row needed if snapshot/attempt exists)
        val reclaim2 = coordinator.tryAcquireExplicitPreDispatchRetry(
            dbUtils, sbn, wear, eventKey, "retry-tok-2", "com.whatsapp", "dan", sbn.key, now + 5L, false
        )
        assertTrue(reclaim2.acquired)
        assertEquals(1, dbUtils.releaseUnusedNotificationClaim(eventKey, "retry-tok-2"))
        val restored2 = dbUtils.getNotificationClaim(eventKey)!!
        assertEquals("orig-fail-att", restored2.attemptId)
        assertEquals(ReplyExecutionStatus.FAILED.name, restored2.claimStatus)
        assertEquals(ReplyExecutionManager.FAILURE_MISSING_PENDING_INTENT, restored2.failureCategory)
    }

    @Test
    fun `F_unused claim release on genuinely new claim deletes row cleanly`() {
        val now = System.currentTimeMillis()
        val eventKey = "evt_fresh_release_f"
        assertTrue(
            dbUtils.tryAcquireNotificationClaim(eventKey, "fresh-tok", "com.whatsapp", "eva", "nk-f", now, false).acquired
        )
        assertNotNull(dbUtils.getNotificationClaim(eventKey))

        assertEquals(1, dbUtils.releaseUnusedNotificationClaim(eventKey, "fresh-tok"))
        assertNull(dbUtils.getNotificationClaim(eventKey))
    }

    @Test
    fun `G_unused claim release ignores mismatched token or already transitioned claims`() {
        val now = System.currentTimeMillis()
        val eventKey = "evt_guarded_release_g"
        assertTrue(
            dbUtils.tryAcquireNotificationClaim(eventKey, "owner-tok", "com.whatsapp", "fay", "nk-g", now, false).acquired
        )
        // Wrong token
        assertEquals(0, dbUtils.releaseUnusedNotificationClaim(eventKey, "wrong-tok"))
        assertNotNull(dbUtils.getNotificationClaim(eventKey))

        // Transitioned to DISPATCHING
        assertEquals(
            1,
            dbUtils.updateNotificationClaimTransition(
                eventKey, "owner-tok", "owner-tok", ReplyExecutionStatus.DISPATCHING, now + 10L
            )
        )
        assertEquals(0, dbUtils.releaseUnusedNotificationClaim(eventKey, "owner-tok"))
        assertEquals(ReplyExecutionStatus.DISPATCHING.name, dbUtils.getNotificationClaim(eventKey)?.claimStatus)
    }

    @Test
    fun `H_end_to_end NotificationService missing claim after pre-dispatch failures still respects maxRetries`() {
        val sendCount = AtomicInteger(0)
        val svc = createManagedService(sendCount)
        val now = System.currentTimeMillis()
        val sbn = buildSbn(id = 630, sender = "Gus", message = "E2E budget check H", whenMs = now)
        val eventKey = NotificationEventKeyBuilder.buildEventKey(sbn)!!

        // Initial pre-dispatch failure via NotificationService
        val initialRes = svc.sendActualReply(
            sbn, wearFromSbn(sbn, includePendingIntent = false), "Reply", null, ReplySourceType.DEFAULT
        )
        assertEquals(ReplyExecutionStatus.FAILED, initialRes.status)
        assertFalse(initialRes.dispatchAttempted)

        // Add 2 more failed pre-dispatch attempts to hit MAX_PRE_DISPATCH_RETRIES (2) and evict claim row
        insertAttempt("extra-fail-1", eventKey, ReplyExecutionStatus.FAILED.name, dispatchAttempted = false, tsMs = now + 1L)
        insertAttempt("extra-fail-2", eventKey, ReplyExecutionStatus.FAILED.name, dispatchAttempted = false, tsMs = now + 2L)
        logsDb.notificationDispatchClaimDao().purgeExpiredAndBoundedClaimsSync(now + 10L, maxResolvedRows = 0)
        assertNull(dbUtils.getNotificationClaim(eventKey))

        // Retry via NotificationService must be DENIED even though claim row is missing
        val retryRes = svc.retryPreDispatchFailure(
            sbn, wearFromSbn(sbn, includePendingIntent = true), "Reply", null, ReplySourceType.DEFAULT, null
        )
        assertEquals(ReplyExecutionStatus.CANCELLED, retryRes.status)
        assertEquals(0, sendCount.get())
        assertNull(dbUtils.getNotificationClaim(eventKey))
    }

    // =========================================================================
    // AUDIT-CONF-03 (I–U): Deferred Claim Acquisition + 128 Fixed Lock Stripes
    // =========================================================================

    @Test
    fun `I_disabled rules with fallback disabled acquire zero persistent DB claims`() = runBlocking {
        withContext(Dispatchers.IO) {
            rulesDb.ruleDao().insertRule(
                AutoReplyRule(
                    name = "Disabled Rule",
                    isEnabled = false,
                    selectedPackages = listOf("com.whatsapp"),
                    matchType = MatchType.CONTAINS,
                    keywords = listOf("hello"),
                    replyMessage = "Hi"
                )
            )
        }
        val claimWrites = AtomicInteger(0)
        val trackingDb = object : DbUtils(context) {
            override fun tryAcquireAuthorizedPreDispatchClaim(
                eventKey: String,
                claimToken: String,
                packageName: String,
                senderKey: String,
                notificationKey: String?,
                nowMs: Long,
                priorRetryCount: Int,
                maxRetries: Int
            ): ClaimAcquireOutcome {
                claimWrites.incrementAndGet()
                return super.tryAcquireAuthorizedPreDispatchClaim(
                    eventKey, claimToken, packageName, senderKey, notificationKey, nowMs, priorRetryCount, maxRetries
                )
            }
        }
        val exec = Executors.newSingleThreadScheduledExecutor()
        val processor = RuleNotificationProcessor(context, exec, trackingDb)
        try {
            val sbn = buildSbn(id = 640, sender = "Hank", message = "hello")
            val cb = object : RuleNotificationProcessor.ReplyDispatchCallback {
                override fun onRuleMatchedSendReply(sbn: StatusBarNotification, notificationWear: NotificationWear, replyText: String, matchedRule: AutoReplyRule) {}
                override fun onFallbackToLegacy(sbn: StatusBarNotification) {}
            }
            processor.processNotification(sbn, cb)
            processor.awaitIdleForTesting()

            assertEquals(0, claimWrites.get())
            assertEquals(0, logsDb.notificationDispatchClaimDao().getClaimCountSync())
            assertEquals(0, processor.activeInFlightEventKeyCountForTesting())
        } finally {
            processor.shutdown()
            exec.shutdownNow()
        }
    }

    @Test
    fun `J_unmatched enabled rules with fallback disabled acquire zero persistent DB claims`() = runBlocking {
        withContext(Dispatchers.IO) {
            rulesDb.ruleDao().insertRule(
                AutoReplyRule(
                    name = "Only Order",
                    isEnabled = true,
                    selectedPackages = listOf("com.whatsapp"),
                    matchType = MatchType.EXACT,
                    keywords = listOf("order"),
                    replyMessage = "Order reply"
                )
            )
        }
        val claimWrites = AtomicInteger(0)
        val trackingDb = object : DbUtils(context) {
            override fun tryAcquireAuthorizedPreDispatchClaim(
                eventKey: String,
                claimToken: String,
                packageName: String,
                senderKey: String,
                notificationKey: String?,
                nowMs: Long,
                priorRetryCount: Int,
                maxRetries: Int
            ): ClaimAcquireOutcome {
                claimWrites.incrementAndGet()
                return super.tryAcquireAuthorizedPreDispatchClaim(
                    eventKey, claimToken, packageName, senderKey, notificationKey, nowMs, priorRetryCount, maxRetries
                )
            }
        }
        val exec = Executors.newSingleThreadScheduledExecutor()
        val processor = RuleNotificationProcessor(context, exec, trackingDb)
        try {
            val sbn = buildSbn(id = 641, sender = "Ivy", message = "unrelated chatter")
            val cb = object : RuleNotificationProcessor.ReplyDispatchCallback {
                override fun onRuleMatchedSendReply(sbn: StatusBarNotification, notificationWear: NotificationWear, replyText: String, matchedRule: AutoReplyRule) {}
                override fun onFallbackToLegacy(sbn: StatusBarNotification) {}
            }
            processor.processNotification(sbn, cb)
            processor.awaitIdleForTesting()

            assertEquals(0, claimWrites.get())
            assertEquals(0, logsDb.notificationDispatchClaimDao().getClaimCountSync())
            assertEquals(0, processor.activeInFlightEventKeyCountForTesting())
        } finally {
            processor.shutdown()
            exec.shutdownNow()
        }
    }

    @Test
    fun `K_non_actionable legacy fallback rejected by decider acquires zero DB claims`() {
        // Unsupported package (Telegram not enabled) + 0 rules -> fallback rejected by decider
        val sendCount = AtomicInteger(0)
        val svc = createManagedService(sendCount)
        val claimAttempts = AtomicInteger(0)
        svc.setDbUtilsForTesting(object : DbUtils(context) {
            override fun tryAcquireAuthorizedPreDispatchClaim(
                eventKey: String,
                claimToken: String,
                packageName: String,
                senderKey: String,
                notificationKey: String?,
                nowMs: Long,
                priorRetryCount: Int,
                maxRetries: Int
            ): ClaimAcquireOutcome {
                claimAttempts.incrementAndGet()
                return super.tryAcquireAuthorizedPreDispatchClaim(
                    eventKey, claimToken, packageName, senderKey, notificationKey, nowMs, priorRetryCount, maxRetries
                )
            }

            override fun tryAcquireNotificationClaim(
                eventKey: String,
                claimToken: String,
                packageName: String,
                senderKey: String,
                notificationKey: String?,
                nowMs: Long,
                allowRetryOnPreDispatchFailure: Boolean
            ): ClaimAcquireOutcome {
                claimAttempts.incrementAndGet()
                return super.tryAcquireNotificationClaim(
                    eventKey, claimToken, packageName, senderKey, notificationKey, nowMs, allowRetryOnPreDispatchFailure
                )
            }
        })

        val unsupportedSbn = buildSbn(pkg = "org.telegram.messenger", id = 642, sender = "Jack", message = "hi")
        svc.onNotificationPosted(unsupportedSbn)
        svc.awaitRuleProcessorIdleForTesting()

        assertEquals(0, sendCount.get())
        assertEquals(0, claimAttempts.get())
        assertEquals(0, logsDb.notificationDispatchClaimDao().getClaimCountSync())
    }

    @Test
    fun `L_matched immediate rule acquires claim only after reservation and dispatches once`() = runBlocking {
        withContext(Dispatchers.IO) {
            rulesDb.ruleDao().insertRule(
                AutoReplyRule(
                    name = "Immediate Rule",
                    isEnabled = true,
                    selectedPackages = listOf("com.whatsapp"),
                    matchType = MatchType.CONTAINS,
                    keywords = listOf("status"),
                    replyMessage = "Status OK"
                )
            )
        }
        val sendCount = AtomicInteger(0)
        val svc = createManagedService(sendCount)
        val claimCountAtCallback = AtomicInteger(0)
        val exec = Executors.newSingleThreadScheduledExecutor()
        val processor = RuleNotificationProcessor(context, exec, dbUtils, svc.getRuleReservationCoordinatorForTesting())
        try {
            val sbn = buildSbn(id = 643, sender = "Kate", message = "status?")
            val eventKey = NotificationEventKeyBuilder.buildEventKey(sbn)!!
            val cb = object : RuleNotificationProcessor.ClaimAwareReplyDispatchCallback() {
                override fun onRuleMatchedSendReply(
                    sbn: StatusBarNotification,
                    notificationWear: NotificationWear,
                    replyText: String,
                    matchedRule: AutoReplyRule,
                    claimToken: String?,
                    eventKey: String?,
                    reservation: RuleExecutionReservationCoordinator.RuleReservation?
                ) {
                    assertNotNull(claimToken)
                    assertNotNull(reservation)
                    claimCountAtCallback.set(logsDb.notificationDispatchClaimDao().getClaimCountSync())
                    svc.sendActualReply(sbn, notificationWear, replyText, matchedRule.name, ReplySourceType.RULE_STATIC, matchedRule, claimToken, reservation)
                }
                override fun onFallbackToLegacy(sbn: StatusBarNotification, claimToken: String?, eventKey: String?) {}
            }
            processor.processNotification(sbn, cb)
            processor.awaitIdleForTesting()

            assertEquals(1, claimCountAtCallback.get())
            assertEquals(1, sendCount.get())
            assertEquals(ReplyExecutionStatus.DISPATCHED.name, dbUtils.getNotificationClaim(eventKey)?.claimStatus)
            assertEquals(0, processor.activeInFlightEventKeyCountForTesting())
        } finally {
            processor.shutdown()
            exec.shutdownNow()
        }
    }

    @Test
    fun `M_matched delayed rule acquires persistent claim before scheduled delay and blocks duplicates during delay`() = runBlocking {
        withContext(Dispatchers.IO) {
            rulesDb.ruleDao().insertRule(
                AutoReplyRule(
                    name = "Delayed Rule",
                    isEnabled = true,
                    selectedPackages = listOf("com.whatsapp"),
                    matchType = MatchType.CONTAINS,
                    keywords = listOf("later"),
                    replyMessage = "Delayed response",
                    replyDelaySeconds = 15
                )
            )
        }
        val scheduledTasks = mutableListOf<Runnable>()
        val svc = createManagedService(AtomicInteger(0))
        val sendCount = AtomicInteger(0)
        svc.setReplyExecutionManagerForTesting(
            ReplyExecutionManager(context, pendingIntentSender = ReplyExecutionManager.PendingIntentSender { _, _, _ ->
                sendCount.incrementAndGet()
            })
        )
        val processor = RuleNotificationProcessor(
            context,
            CapturingScheduledExecutor(scheduledTasks),
            dbUtils,
            svc.getRuleReservationCoordinatorForTesting()
        )
        try {
            val sbn = buildSbn(id = 644, sender = "Leo", message = "talk later")
            val eventKey = NotificationEventKeyBuilder.buildEventKey(sbn)!!
            val cb = object : RuleNotificationProcessor.ClaimAwareReplyDispatchCallback() {
                override fun onRuleMatchedSendReply(
                    sbn: StatusBarNotification,
                    notificationWear: NotificationWear,
                    replyText: String,
                    matchedRule: AutoReplyRule,
                    claimToken: String?,
                    eventKey: String?,
                    reservation: RuleExecutionReservationCoordinator.RuleReservation?
                ) {
                    svc.sendActualReply(sbn, notificationWear, replyText, matchedRule.name, ReplySourceType.RULE_STATIC, matchedRule, claimToken, reservation)
                }
                override fun onFallbackToLegacy(sbn: StatusBarNotification, claimToken: String?, eventKey: String?) {}
            }

            withContext(Dispatchers.IO) {
                processor.processNotification(sbn, cb)
            }
            assertEquals(1, scheduledTasks.size)
            assertEquals(NotificationDispatchClaimEntity.STATUS_CLAIMED, dbUtils.getNotificationClaim(eventKey)?.claimStatus)

            // Duplicate delivery while delayed MUST be suppressed
            withContext(Dispatchers.IO) {
                processor.processNotification(sbn, cb)
            }
            assertEquals(1, scheduledTasks.size)

            withContext(Dispatchers.IO) {
                scheduledTasks.single().run()
            }
            assertEquals(1, sendCount.get())
            assertEquals(ReplyExecutionStatus.DISPATCHED.name, dbUtils.getNotificationClaim(eventKey)?.claimStatus)
        } finally {
            processor.shutdown()
        }
    }

    @Test
    fun `N_shutdown cancels delayed rule claim and releases reservation and inFlight keys`() = runBlocking {
        withContext(Dispatchers.IO) {
            rulesDb.ruleDao().insertRule(
                AutoReplyRule(
                    name = "Shutdown Rule",
                    isEnabled = true,
                    selectedPackages = listOf("com.whatsapp"),
                    matchType = MatchType.CONTAINS,
                    keywords = listOf("wait"),
                    replyMessage = "Waiting",
                    replyDelaySeconds = 20
                )
            )
        }
        val scheduledTasks = mutableListOf<Runnable>()
        val processor = RuleNotificationProcessor(context, CapturingScheduledExecutor(scheduledTasks), dbUtils)
        val sbn = buildSbn(id = 645, sender = "Mia", message = "please wait")
        val eventKey = NotificationEventKeyBuilder.buildEventKey(sbn)!!
        val cb = object : RuleNotificationProcessor.ClaimAwareReplyDispatchCallback() {
            override fun onRuleMatchedSendReply(
                sbn: StatusBarNotification,
                notificationWear: NotificationWear,
                replyText: String,
                matchedRule: AutoReplyRule,
                claimToken: String?,
                eventKey: String?,
                reservation: RuleExecutionReservationCoordinator.RuleReservation?
            ) {}
            override fun onFallbackToLegacy(sbn: StatusBarNotification, claimToken: String?, eventKey: String?) {}
        }
        withContext(Dispatchers.IO) {
            processor.processNotification(sbn, cb)
        }
        assertEquals(1, scheduledTasks.size)
        assertEquals(NotificationDispatchClaimEntity.STATUS_CLAIMED, dbUtils.getNotificationClaim(eventKey)?.claimStatus)

        withContext(Dispatchers.IO) {
            processor.shutdown()
        }
        val cancelledClaim = dbUtils.getNotificationClaim(eventKey)!!
        assertEquals(ReplyExecutionStatus.CANCELLED.name, cancelledClaim.claimStatus)
        assertEquals(ReplyExecutionManager.FAILURE_SERVICE_SHUTDOWN, cancelledClaim.failureCategory)
        assertEquals(0, processor.reservationCoordinator.activeReservedCount())
        assertEquals(0, processor.activeInFlightEventKeyCountForTesting())
    }

    @Test
    fun `O_concurrent duplicate processNotification burst allows strictly one dispatch`() = runBlocking {
        withContext(Dispatchers.IO) {
            rulesDb.ruleDao().insertRule(
                AutoReplyRule(
                    name = "Burst Rule",
                    isEnabled = true,
                    selectedPackages = listOf("com.whatsapp"),
                    matchType = MatchType.CONTAINS,
                    keywords = listOf("burst"),
                    replyMessage = "Handled"
                )
            )
        }
        val sendCount = AtomicInteger(0)
        val svc = createManagedService(sendCount)
        val ruleExec = Executors.newSingleThreadScheduledExecutor()
        val callerPool = Executors.newFixedThreadPool(8)
        val processor = RuleNotificationProcessor(context, ruleExec, dbUtils, svc.getRuleReservationCoordinatorForTesting())
        val gate = CountDownLatch(1)
        try {
            val sbn = buildSbn(id = 646, sender = "Noah", message = "burst check")
            val cb = object : RuleNotificationProcessor.ClaimAwareReplyDispatchCallback() {
                override fun onRuleMatchedSendReply(
                    sbn: StatusBarNotification,
                    notificationWear: NotificationWear,
                    replyText: String,
                    matchedRule: AutoReplyRule,
                    claimToken: String?,
                    eventKey: String?,
                    reservation: RuleExecutionReservationCoordinator.RuleReservation?
                ) {
                    svc.sendActualReply(sbn, notificationWear, replyText, matchedRule.name, ReplySourceType.RULE_STATIC, matchedRule, claimToken, reservation)
                }
                override fun onFallbackToLegacy(sbn: StatusBarNotification, claimToken: String?, eventKey: String?) {}
            }
            val futures = (1..12).map {
                callerPool.submit {
                    gate.await(5, TimeUnit.SECONDS)
                    processor.processNotification(sbn, cb)
                }
            }
            gate.countDown()
            futures.forEach { it.get(5, TimeUnit.SECONDS) }
            processor.awaitIdleForTesting()

            assertEquals(1, sendCount.get())
            assertEquals(1L, dbUtils.numReplies)
            assertEquals(0, processor.activeInFlightEventKeyCountForTesting())
        } finally {
            processor.shutdown()
            ruleExec.shutdownNow()
            callerPool.shutdownNow()
        }
    }

    @Test
    fun `P_suppressed matched rule releases RuleReservation cleanly without leaking cooldown slot`() = runBlocking {
        withContext(Dispatchers.IO) {
            rulesDb.ruleDao().insertRule(
                AutoReplyRule(
                    name = "Reserved Cleanup Rule",
                    isEnabled = true,
                    selectedPackages = listOf("com.whatsapp"),
                    matchType = MatchType.CONTAINS,
                    keywords = listOf("hello"),
                    replyMessage = "Hi",
                    cooldownSeconds = 60
                )
            )
        }
        val exec = Executors.newSingleThreadScheduledExecutor()
        val reservationCoordinator = RuleExecutionReservationCoordinator()
        val processor = RuleNotificationProcessor(context, exec, dbUtils, reservationCoordinator)
        try {
            val sbn = buildSbn(id = 647, sender = "Olivia", message = "hello")
            val eventKey = NotificationEventKeyBuilder.buildEventKey(sbn)!!
            // Pre-lock eventKey as DISPATCHED
            assertTrue(dbUtils.tryAcquireNotificationClaim(eventKey, "prior-tok", "com.whatsapp", "olivia", sbn.key, System.currentTimeMillis(), false).acquired)
            dbUtils.updateNotificationClaimResult(
                eventKey, "prior-tok", ReplyExecutionResult("prior-tok", ReplyExecutionStatus.DISPATCHED, true, true, null, "done", System.currentTimeMillis())
            )

            val callbacksInvoked = AtomicInteger(0)
            val cb = object : RuleNotificationProcessor.ReplyDispatchCallback {
                override fun onRuleMatchedSendReply(sbn: StatusBarNotification, notificationWear: NotificationWear, replyText: String, matchedRule: AutoReplyRule) {
                    callbacksInvoked.incrementAndGet()
                }
                override fun onFallbackToLegacy(sbn: StatusBarNotification) {
                    callbacksInvoked.incrementAndGet()
                }
            }
            processor.processNotification(sbn, cb)
            processor.awaitIdleForTesting()

            assertEquals(0, callbacksInvoked.get())
            assertEquals("RuleReservation must be released when deferred claim denies", 0, reservationCoordinator.activeReservedCount())
            assertEquals(0, reservationCoordinator.totalTrackedCount())
        } finally {
            processor.shutdown()
            exec.shutdownNow()
        }
    }

    @Test
    fun `Q_R_S_coordinator uses exactly 128 fixed deterministic bounded lock stripes`() {
        assertEquals(128, PreDispatchRetryCoordinator.LOCK_STRIPE_COUNT)
        val coordinator = PreDispatchRetryCoordinator(PreDispatchRetryStateStore.InMemoryBackend())
        assertEquals(128, coordinator.lockStripeCount())

        val edgeKeys = listOf("", "k-1", "evt_abc", "polygenelubricants", "GydZG_") // includes Int.MIN_VALUE hashCode string "polygenelubricants"
        for (key in edgeKeys) {
            val idx = PreDispatchRetryCoordinator.stripeIndexFor(key)
            assertTrue("Stripe index must be in 0..127 for '$key'", idx in 0 until 128)
            assertSame(coordinator.lockStripeFor(key), coordinator.lockStripeFor(String(key.toCharArray())))
        }

        // Load 12,000 unique event keys and verify stripe count remains strictly bounded at 128
        val distinctStripes = HashSet<Any>()
        for (i in 0 until 12_000) {
            val key = "evt_heavy_load_$i"
            val stripe = coordinator.lockStripeFor(key)
            distinctStripes.add(stripe)
        }
        assertEquals(128, coordinator.lockStripeCount())
        assertEquals(128, distinctStripes.size)
    }

    @Test
    fun `T_concurrent coordinator acquisitions across shared and distinct stripes remain deadlock-free with single winner`() {
        val now = System.currentTimeMillis()
        val coordinator = PreDispatchRetryCoordinator(PreDispatchRetryStateStore.InMemoryBackend())
        val pool = Executors.newFixedThreadPool(8)
        val gate = CountDownLatch(1)
        try {
            val sbnA = buildSbn(id = 650, sender = "Paul", message = "Stripe contention A", whenMs = now)
            val keyA = NotificationEventKeyBuilder.buildEventKey(sbnA)!!
            val sbnB = buildSbn(id = 651, sender = "Quinn", message = "Stripe contention B", whenMs = now)
            val keyB = NotificationEventKeyBuilder.buildEventKey(sbnB)!!

            val futuresA = (1..8).map { i ->
                pool.submit<Boolean> {
                    gate.await(5, TimeUnit.SECONDS)
                    coordinator.tryAcquireForFreshNotification(
                        dbUtils, sbnA, wearFromSbn(sbnA), keyA, "tok-a-$i", "com.whatsapp", "paul", sbnA.key, now, false
                    ).acquired
                }
            }
            val futuresB = (1..8).map { i ->
                pool.submit<Boolean> {
                    gate.await(5, TimeUnit.SECONDS)
                    coordinator.tryAcquireForFreshNotification(
                        dbUtils, sbnB, wearFromSbn(sbnB), keyB, "tok-b-$i", "com.whatsapp", "quinn", sbnB.key, now, false
                    ).acquired
                }
            }
            gate.countDown()
            assertEquals(1, futuresA.count { it.get(10, TimeUnit.SECONDS) })
            assertEquals(1, futuresB.count { it.get(10, TimeUnit.SECONDS) })
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun `U_BYOK AI group filter block in sendReply avoids acquiring persistent DB claim`() {
        prefs.setEnableByokReplies(true)
        prefs.saveOpenAIApiKeySecurely("sk-fake-key-for-policy-test")
        prefs.setGroupReplyPref(true)
        prefs.setAiGroupFilterMode("DIRECT_ONLY")

        val sendCount = AtomicInteger(0)
        val svc = createManagedService(sendCount)
        val claimAttempts = AtomicInteger(0)
        svc.setDbUtilsForTesting(object : DbUtils(context) {
            override fun tryAcquireAuthorizedPreDispatchClaim(
                eventKey: String,
                claimToken: String,
                packageName: String,
                senderKey: String,
                notificationKey: String?,
                nowMs: Long,
                priorRetryCount: Int,
                maxRetries: Int
            ): ClaimAcquireOutcome {
                claimAttempts.incrementAndGet()
                return super.tryAcquireAuthorizedPreDispatchClaim(
                    eventKey, claimToken, packageName, senderKey, notificationKey, nowMs, priorRetryCount, maxRetries
                )
            }
        })

        val groupSbn = buildSbn(id = 652, sender = "TeamChat", message = "Anyone there?", isGroup = true)
        svc.onNotificationPosted(groupSbn)
        svc.awaitRuleProcessorIdleForTesting()
        svc.awaitAiMemoryIdleForTesting()

        assertEquals(0, sendCount.get())
        assertEquals(0, claimAttempts.get())
        assertEquals(0, logsDb.notificationDispatchClaimDao().getClaimCountSync())
    }

    // =========================================================================
    // Cross-Batch Integration Checks (V–Y)
    // =========================================================================

    @Test
    fun `V_cross_batch missing claim with DISPATCHED attempt still blocks RuleNotificationProcessor deferred acquisition`() = runBlocking {
        withContext(Dispatchers.IO) {
            rulesDb.ruleDao().insertRule(
                AutoReplyRule(
                    name = "Cross Batch Rule",
                    isEnabled = true,
                    selectedPackages = listOf("com.whatsapp"),
                    matchType = MatchType.CONTAINS,
                    keywords = listOf("ping"),
                    replyMessage = "pong"
                )
            )
        }
        val sendCount = AtomicInteger(0)
        val svc = createManagedService(sendCount)
        val exec = Executors.newSingleThreadScheduledExecutor()
        val processor = RuleNotificationProcessor(context, exec, dbUtils, svc.getRuleReservationCoordinatorForTesting())
        try {
            val sbn = buildSbn(id = 660, sender = "Rita", message = "ping")
            val eventKey = NotificationEventKeyBuilder.buildEventKey(sbn)!!
            // Prior DISPATCHED attempt in reply_attempts, NO claim row in notification_dispatch_claims
            insertAttempt("att-v-disp", eventKey, ReplyExecutionStatus.DISPATCHED.name, true, true)
            assertNull(dbUtils.getNotificationClaim(eventKey))

            val callbackCount = AtomicInteger(0)
            processor.processNotification(sbn, object : RuleNotificationProcessor.ClaimAwareReplyDispatchCallback() {
                override fun onRuleMatchedSendReply(
                    sbn: StatusBarNotification,
                    notificationWear: NotificationWear,
                    replyText: String,
                    matchedRule: AutoReplyRule,
                    claimToken: String?,
                    eventKey: String?,
                    reservation: RuleExecutionReservationCoordinator.RuleReservation?
                ) {
                    callbackCount.incrementAndGet()
                }
                override fun onFallbackToLegacy(sbn: StatusBarNotification, claimToken: String?, eventKey: String?) {}
            })
            processor.awaitIdleForTesting()

            assertEquals(0, callbackCount.get())
            assertEquals(0, sendCount.get())
            assertNull("Blocked cross-table event must leave 0 lingering claims", dbUtils.getNotificationClaim(eventKey))
        } finally {
            processor.shutdown()
            exec.shutdownNow()
        }
    }

    @Test
    fun `W_legacy ReplyDispatchCallback receives handed-off claimToken after delayed matched rule`() = runBlocking {
        withContext(Dispatchers.IO) {
            rulesDb.ruleDao().insertRule(
                AutoReplyRule(
                    name = "Legacy CB Rule",
                    isEnabled = true,
                    selectedPackages = listOf("com.whatsapp"),
                    matchType = MatchType.CONTAINS,
                    keywords = listOf("legacy"),
                    replyMessage = "Legacy OK",
                    replyDelaySeconds = 5
                )
            )
        }
        val scheduledTasks = mutableListOf<Runnable>()
        val sendCount = AtomicInteger(0)
        val svc = createManagedService(sendCount)
        val processor = RuleNotificationProcessor(context, CapturingScheduledExecutor(scheduledTasks), dbUtils)
        val handedOffToken = AtomicReference<String?>(null)
        try {
            val sbn = buildSbn(id = 661, sender = "Sam", message = "legacy callback test")
            val eventKey = NotificationEventKeyBuilder.buildEventKey(sbn)!!
            withContext(Dispatchers.IO) {
                processor.processNotification(sbn, object : RuleNotificationProcessor.ReplyDispatchCallback {
                    override fun onRuleMatchedSendReply(
                        sbn: StatusBarNotification,
                        notificationWear: NotificationWear,
                        replyText: String,
                        matchedRule: AutoReplyRule
                    ) {
                        val tok = PendingClaimHandoff.consume(NotificationEventKeyBuilder.buildEventKey(sbn))
                        handedOffToken.set(tok)
                        svc.sendActualReply(sbn, notificationWear, replyText, matchedRule.name, ReplySourceType.RULE_STATIC, matchedRule, tok)
                    }
                    override fun onFallbackToLegacy(sbn: StatusBarNotification) {}
                })
            }
            assertEquals(1, scheduledTasks.size)
            val claimBeforeFire = dbUtils.getNotificationClaim(eventKey)!!

            withContext(Dispatchers.IO) {
                scheduledTasks.single().run()
            }
            assertEquals(claimBeforeFire.attemptId, handedOffToken.get())
            assertEquals(1, sendCount.get())
            assertEquals(ReplyExecutionStatus.DISPATCHED.name, dbUtils.getNotificationClaim(eventKey)?.claimStatus)
        } finally {
            processor.shutdown()
        }
    }

    @Test
    fun `X_startup recovery transitions interrupted delayed rule CLAIMED row to CANCELLED and blocks retry`() = runBlocking {
        withContext(Dispatchers.IO) {
            rulesDb.ruleDao().insertRule(
                AutoReplyRule(
                    name = "Crash During Delay",
                    isEnabled = true,
                    selectedPackages = listOf("com.whatsapp"),
                    matchType = MatchType.CONTAINS,
                    keywords = listOf("crash"),
                    replyMessage = "Recover me",
                    replyDelaySeconds = 30
                )
            )
        }
        val scheduledTasks = mutableListOf<Runnable>()
        val processor = RuleNotificationProcessor(context, CapturingScheduledExecutor(scheduledTasks), dbUtils)
        val sbn = buildSbn(id = 662, sender = "Tina", message = "crash test")
        val eventKey = NotificationEventKeyBuilder.buildEventKey(sbn)!!
        withContext(Dispatchers.IO) {
            processor.processNotification(sbn, object : RuleNotificationProcessor.ClaimAwareReplyDispatchCallback() {
                override fun onRuleMatchedSendReply(
                    sbn: StatusBarNotification,
                    notificationWear: NotificationWear,
                    replyText: String,
                    matchedRule: AutoReplyRule,
                    claimToken: String?,
                    eventKey: String?,
                    reservation: RuleExecutionReservationCoordinator.RuleReservation?
                ) {}
                override fun onFallbackToLegacy(sbn: StatusBarNotification, claimToken: String?, eventKey: String?) {}
            })
        }
        assertEquals(NotificationDispatchClaimEntity.STATUS_CLAIMED, dbUtils.getNotificationClaim(eventKey)?.claimStatus)

        // Simulate process death recovery without calling processor.shutdown()
        val summary = NotificationReplyRecoveryManager.recoverOnStartup(context, System.currentTimeMillis() + 5_000L)
        assertEquals(1, summary.recoveredOrphanClaimsCount)
        assertEquals(ReplyExecutionStatus.CANCELLED.name, dbUtils.getNotificationClaim(eventKey)?.claimStatus)

        val retryAfterCrash = PreDispatchRetryCoordinator(context).tryAcquireExplicitPreDispatchRetry(
            dbUtils, sbn, wearFromSbn(sbn), eventKey, "post-crash-tok", "com.whatsapp", "tina", sbn.key, System.currentTimeMillis(), false
        )
        assertFalse(retryAfterCrash.acquired)
        assertEquals(RetryDecision.DENY_CANCELLED_OR_STALE, retryAfterCrash.decision)
    }

    @Test
    fun `Y_exception during delayed or immediate callback cleans up claim reservation and inFlightEventKeys`() = runBlocking {
        withContext(Dispatchers.IO) {
            rulesDb.ruleDao().insertRule(
                AutoReplyRule(
                    name = "Throw Rule",
                    isEnabled = true,
                    selectedPackages = listOf("com.whatsapp"),
                    matchType = MatchType.CONTAINS,
                    keywords = listOf("boom"),
                    replyMessage = "Boom"
                )
            )
        }
        val exec = Executors.newSingleThreadScheduledExecutor()
        val reservationCoordinator = RuleExecutionReservationCoordinator()
        val processor = RuleNotificationProcessor(context, exec, dbUtils, reservationCoordinator)
        try {
            val sbn = buildSbn(id = 663, sender = "Uma", message = "boom")
            val eventKey = NotificationEventKeyBuilder.buildEventKey(sbn)!!
            processor.processNotification(sbn, object : RuleNotificationProcessor.ClaimAwareReplyDispatchCallback() {
                override fun onRuleMatchedSendReply(
                    sbn: StatusBarNotification,
                    notificationWear: NotificationWear,
                    replyText: String,
                    matchedRule: AutoReplyRule,
                    claimToken: String?,
                    eventKey: String?,
                    reservation: RuleExecutionReservationCoordinator.RuleReservation?
                ) {
                    throw IllegalStateException("Simulated callback crash")
                }
                override fun onFallbackToLegacy(sbn: StatusBarNotification, claimToken: String?, eventKey: String?) {}
            })
            processor.awaitIdleForTesting()

            assertEquals(0, processor.activeInFlightEventKeyCountForTesting())
            assertEquals(0, reservationCoordinator.activeReservedCount())
            assertEquals(ReplyExecutionStatus.CANCELLED.name, dbUtils.getNotificationClaim(eventKey)?.claimStatus)
        } finally {
            processor.shutdown()
            exec.shutdownNow()
        }
    }
}
