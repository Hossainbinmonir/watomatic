package com.parishod.watomatic.service

import android.app.Notification
import com.parishod.watomatic.model.logs.AttemptClaimPersistOutcome
import com.parishod.watomatic.model.rules.AutoReplyRule
import com.parishod.watomatic.model.rules.MatchType
import com.parishod.watomatic.service.reply.NotificationEventKeyBuilder
import com.parishod.watomatic.service.reply.PendingClaimHandoff
import com.parishod.watomatic.service.reply.ReplyExecutionManager
import com.parishod.watomatic.service.reply.ReplyExecutionStatus
import com.parishod.watomatic.service.rules.IncomingMessageContext
import com.parishod.watomatic.service.rules.RuleEvaluator
import com.parishod.watomatic.service.rules.RuleExecutionLogStore
import com.parishod.watomatic.service.rules.RuleExecutionReservationCoordinator
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.TimeZone
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class Stage025Batch2JvmUnitTest {

    private class InMemoryRuleLogStore : RuleExecutionLogStore {
        private data class Key(val ruleId: Long, val pkg: String, val sender: String)
        private val logs = mutableMapOf<Key, MutableList<Long>>()
        private val repliedKeys = mutableSetOf<Pair<String, Long>>()

        override fun getLastReplyTimeForRuleAndSender(
            ruleId: Long,
            packageName: String,
            senderName: String
        ): Long? = logs[Key(ruleId, packageName, senderName)]?.maxOrNull()

        override fun getReplyCountForRuleAndSenderSince(
            ruleId: Long,
            packageName: String,
            senderName: String,
            sinceTime: Long
        ): Int = logs[Key(ruleId, packageName, senderName)]?.count { it >= sinceTime } ?: 0

        override fun hasRepliedToNotification(notificationKey: String, messageTimestamp: Long): Boolean =
            (notificationKey to messageTimestamp) in repliedKeys

        override fun recordExecution(
            ruleId: Long,
            packageName: String,
            senderName: String,
            notificationKey: String,
            messageTimestamp: Long,
            repliedAt: Long
        ) {
            logs.getOrPut(Key(ruleId, packageName, senderName)) { mutableListOf() }.add(repliedAt)
            if (notificationKey.isNotBlank() && messageTimestamp > 0L) {
                repliedKeys.add(notificationKey to messageTimestamp)
            }
        }
    }

    @Before
    fun setUp() {
        PendingClaimHandoff.clear()
    }

    @After
    fun tearDown() {
        PendingClaimHandoff.clear()
    }

    @Test
    fun `reservation coordinator enforces atomic cooldown and maxRepliesPerSender across threads`() {
        val coordinator = RuleExecutionReservationCoordinator(TimeZone.getTimeZone("UTC"))
        val store = InMemoryRuleLogStore()
        val rule = AutoReplyRule(
            id = 7L,
            name = "Atomic Cooldown",
            isEnabled = true,
            selectedPackages = listOf("com.whatsapp"),
            matchType = MatchType.CONTAINS,
            keywords = listOf("hi"),
            replyMessage = "Hello!",
            cooldownSeconds = 60,
            maxRepliesPerSender = 1,
            frequencyWindowMinutes = 60
        )
        val pool = Executors.newFixedThreadPool(4)
        val gate = CountDownLatch(1)
        val results = java.util.Collections.synchronizedList(mutableListOf<RuleExecutionReservationCoordinator.ReservedRuleEvaluation?>())
        try {
            val futures = (1..4).map { idx ->
                pool.submit {
                    gate.await(5, TimeUnit.SECONDS)
                    val ctx = IncomingMessageContext(
                        packageName = "com.whatsapp",
                        senderName = "Alice",
                        messageText = "hi $idx",
                        isGroup = false,
                        notificationKey = "k-$idx",
                        messageTimestamp = 1_000_000L + idx
                    )
                    results.add(coordinator.evaluateAndReserve(listOf(rule), ctx, store, 1_000_000L + idx))
                }
            }
            gate.countDown()
            futures.forEach { it.get(5, TimeUnit.SECONDS) }
            val reserved = results.filterNotNull()
            assertEquals(1, reserved.size)
            assertEquals(1, coordinator.activeReservedCount())
            assertEquals(1, coordinator.totalTrackedCount())
            assertEquals(
                RuleExecutionReservationCoordinator.ReservationStatus.RESERVED,
                coordinator.getStatus(reserved.single().reservation.reservationId)
            )
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun `reservation lifecycle supports confirm, markPersistedToDb, markUnknown, release, and group isolation`() {
        val coordinator = RuleExecutionReservationCoordinator(TimeZone.getTimeZone("UTC"))
        val store = InMemoryRuleLogStore()
        val rule = AutoReplyRule(
            id = 11L,
            name = "Freq Rule",
            isEnabled = true,
            selectedPackages = listOf("com.whatsapp"),
            matchType = MatchType.CONTAINS,
            keywords = listOf("ping"),
            replyMessage = "pong",
            cooldownSeconds = 0,
            maxRepliesPerSender = 1,
            frequencyWindowMinutes = 60
        )

        val dmCtx = IncomingMessageContext("com.whatsapp", "Bob", "ping", false, "k1", 10_000L)
        val groupCtx = IncomingMessageContext("com.whatsapp", "Bob", "ping", true, "k2", 10_000L)
        val selfCtx = IncomingMessageContext("com.whatsapp", "Me", "ping", false, "k3", 10_000L, selfDisplayName = "Me")
        val blankPkgCtx = IncomingMessageContext("", "Bob", "ping", false)

        assertNull(coordinator.evaluateAndReserve(listOf(rule), blankPkgCtx, store, 10_000L))
        assertNull(coordinator.evaluateAndReserve(listOf(rule), selfCtx, store, 10_000L))

        val dmReserved = coordinator.evaluateAndReserve(listOf(rule), dmCtx, store, 10_000L)!!
        val groupReserved = coordinator.evaluateAndReserve(listOf(rule), groupCtx, store, 10_000L)!!
        assertEquals("Bob", dmReserved.reservation.senderScopeKey)
        assertEquals("group:Bob", groupReserved.reservation.senderScopeKey)

        // Confirm DM dispatch & persist DB log -> in-memory confirmed entry cleaned cleanly
        coordinator.confirmDispatched(dmReserved.reservation.reservationId, 10_100L)
        assertEquals(
            RuleExecutionReservationCoordinator.ReservationStatus.CONFIRMED,
            coordinator.getStatus(dmReserved.reservation.reservationId)
        )
        store.recordExecution(rule.id, "com.whatsapp", "Bob", "k1", 10_000L, 10_100L)
        coordinator.markPersistedToDb(dmReserved.reservation.reservationId)
        assertNull(coordinator.getStatus(dmReserved.reservation.reservationId))
        assertNull(coordinator.evaluateAndReserve(listOf(rule), dmCtx.copy(notificationKey = "k1b"), store, 10_200L))
        assertNull(coordinator.evaluateAndReserve(listOf(rule), dmCtx, store, 10_200L))

        // Group UNKNOWN locks conservatively and cannot be released
        coordinator.markUnknown(groupReserved.reservation.reservationId, 10_150L)
        assertEquals(
            RuleExecutionReservationCoordinator.ReservationStatus.LOCKED_UNKNOWN,
            coordinator.getStatus(groupReserved.reservation.reservationId)
        )
        coordinator.release(groupReserved.reservation.reservationId)
        assertEquals(
            RuleExecutionReservationCoordinator.ReservationStatus.LOCKED_UNKNOWN,
            coordinator.getStatus(groupReserved.reservation.reservationId)
        )
        assertEquals(0, coordinator.releaseAllReserved())
        assertNull(coordinator.evaluateAndReserve(listOf(rule), groupCtx.copy(notificationKey = "k2b"), store, 10_300L))

        // After retention window (>24h), old UNKNOWN entry is pruned
        val nextDayCtx = groupCtx.copy(notificationKey = "k2c", messageTimestamp = 10_000L + 25L * 3600L * 1000L)
        val afterPrune = coordinator.evaluateAndReserve(
            listOf(rule),
            nextDayCtx,
            store,
            10_000L + 25L * 3600L * 1000L
        )
        assertNotNull(afterPrune)
        assertEquals(1, coordinator.releaseAllReserved())
        assertEquals(0, coordinator.activeReservedCount())
        coordinator.clearForTesting()
        assertEquals(0, coordinator.totalTrackedCount())
    }

    @Test
    fun `RuleEvaluator buildSenderScopeKey and PendingClaimHandoff handle edge cases cleanly`() {
        assertEquals("Alice", RuleEvaluator.buildSenderScopeKey(" Alice ", false))
        assertEquals("group:Alice", RuleEvaluator.buildSenderScopeKey(" Alice ", true))
        assertEquals(RuleEvaluator.UNKNOWN_SENDER, RuleEvaluator.buildSenderScopeKey("  ", false))
        assertEquals("group:${RuleEvaluator.UNKNOWN_SENDER}", RuleEvaluator.buildSenderScopeKey(null, true))

        // Active handoffs > 512 are preserved; TTL-expired entries are purged cleanly
        val t0 = 5_000_000L
        for (i in 1..650) {
            PendingClaimHandoff.register("evt-$i", "tok-$i", nowMs = t0, ttlMs = 60_000L)
        }
        assertEquals(650, PendingClaimHandoff.activeSize())
        assertEquals("tok-1", PendingClaimHandoff.peek("evt-1", nowMs = t0 + 1_000L))

        // Token-guarded complete/cancel
        assertFalse(PendingClaimHandoff.complete("evt-1", "wrong-tok"))
        assertFalse(PendingClaimHandoff.cancel("evt-1", "wrong-tok"))
        assertTrue(PendingClaimHandoff.complete("evt-1", "tok-1"))
        assertNull(PendingClaimHandoff.peek("evt-1", nowMs = t0 + 1_000L))
        assertEquals("tok-2", PendingClaimHandoff.consume("evt-2", nowMs = t0 + 1_000L))

        // Expiry cleanup
        assertEquals(648, PendingClaimHandoff.purgeExpired(nowMs = t0 + 60_001L))
        assertEquals(0, PendingClaimHandoff.activeSize())

        PendingClaimHandoff.register("", "tok", nowMs = t0)
        PendingClaimHandoff.register("evt-x", "", nowMs = t0)
        PendingClaimHandoff.register("evt-exp", "tok-exp", nowMs = t0, ttlMs = 500L)
        assertNull(PendingClaimHandoff.consume("evt-exp", nowMs = t0 + 600L))
        PendingClaimHandoff.register("evt-exp2", "tok-exp2", nowMs = t0, ttlMs = 500L)
        assertNull(PendingClaimHandoff.peek("evt-exp2", nowMs = t0 + 600L))
    }

    @Test
    fun `AttemptClaimPersistOutcome and NotificationEventKeyBuilder pure helpers behave deterministically`() {
        val outcome = AttemptClaimPersistOutcome(
            effectiveAttemptStatus = ReplyExecutionStatus.DISPATCHED,
            effectiveClaimStatus = ReplyExecutionStatus.DISPATCHED.name,
            dispatchedPersisted = true,
            claimUpdated = true,
            claimMissing = false,
            reconciledFromAuthoritativeAttempt = false,
            failureCategory = null
        )
        assertEquals(ReplyExecutionStatus.DISPATCHED, outcome.effectiveAttemptStatus)
        assertTrue(outcome.dispatchedPersisted)
        assertTrue(outcome.claimUpdated)
        assertFalse(outcome.claimMissing)
        assertFalse(outcome.reconciledFromAuthoritativeAttempt)

        val unknownOutcome = AttemptClaimPersistOutcome(
            effectiveAttemptStatus = ReplyExecutionStatus.UNKNOWN,
            effectiveClaimStatus = ReplyExecutionStatus.UNKNOWN.name,
            dispatchedPersisted = false,
            claimUpdated = true,
            claimMissing = false,
            reconciledFromAuthoritativeAttempt = true,
            failureCategory = ReplyExecutionManager.FAILURE_CLAIM_PERSISTENCE_ERROR
        )
        assertEquals(ReplyExecutionManager.FAILURE_CLAIM_PERSISTENCE_ERROR, unknownOutcome.failureCategory)

        assertNull(NotificationEventKeyBuilder.buildEventKey(null))
        assertEquals("alice", NotificationEventKeyBuilder.normalizeSenderKey(" Alice "))
        assertEquals("group:alice", NotificationEventKeyBuilder.normalizeSenderKey(" Alice ", true))
        assertEquals(NotificationEventKeyBuilder.UNKNOWN_SENDER, NotificationEventKeyBuilder.normalizeSenderKey("  ", false))
        assertTrue(NotificationEventKeyBuilder.isUnknownSender(null))
        assertTrue(NotificationEventKeyBuilder.isUnknownSender(NotificationEventKeyBuilder.UNKNOWN_SENDER))
        assertFalse(NotificationEventKeyBuilder.isUnknownSender("alice"))
        assertEquals("hello world", NotificationEventKeyBuilder.normalizeMessage("  hello   world \n "))
        assertEquals("", NotificationEventKeyBuilder.normalizeMessage("  "))

        val notifWithWhen = org.mockito.Mockito.mock(Notification::class.java).apply {
            `when` = 1_234_567L
        }
        assertEquals(
            1_234_567L,
            NotificationEventKeyBuilder.resolveStableTimestampMs(notifWithWhen, null, 9_999_999L, "hi")
        )
        assertEquals(
            9_999_999L,
            NotificationEventKeyBuilder.resolveStableTimestampMs(null, null, 9_999_999L, "hi")
        )
        assertEquals(
            0L,
            NotificationEventKeyBuilder.resolveStableTimestampMs(null, null, 0L, "hi")
        )
    }
}
