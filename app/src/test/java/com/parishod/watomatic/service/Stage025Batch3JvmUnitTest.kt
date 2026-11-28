package com.parishod.watomatic.service

import android.app.Notification
import android.app.PendingIntent
import android.service.notification.StatusBarNotification
import androidx.core.app.RemoteInput
import com.parishod.watomatic.NotificationWear
import com.parishod.watomatic.model.logs.NotificationDispatchClaimEntity
import com.parishod.watomatic.model.logs.ReplyAttemptEntity
import com.parishod.watomatic.model.utils.NotificationUtils
import com.parishod.watomatic.service.reply.PreDispatchRetryStateStore
import com.parishod.watomatic.service.reply.ReplyExecutionStatus
import com.parishod.watomatic.service.reply.ReplyRetryPolicy
import com.parishod.watomatic.service.reply.RetryDecision
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito

/**
 * Pure-JVM tests (no Robolectric) for Stage 02.5 Batch 3 policy, store, and heuristic invariants.
 */
class Stage025Batch3JvmUnitTest {

    private fun claim(
        eventKey: String = "evt-1",
        attemptId: String = "att-1",
        status: String = ReplyExecutionStatus.FAILED.name,
        dispatchAttempted: Boolean = false,
        dispatchedNormally: Boolean = false
    ) = NotificationDispatchClaimEntity(
        eventKey = eventKey,
        attemptId = attemptId,
        packageName = "com.whatsapp",
        senderKey = "alice",
        notificationKey = "notif-1",
        claimStatus = status,
        failureCategory = null,
        dispatchAttempted = dispatchAttempted,
        dispatchedNormally = dispatchedNormally,
        createdAtMs = 1_000L,
        updatedAtMs = 1_000L,
        expiresAtMs = 999_999L
    )

    private fun attempt(
        attemptId: String = "att-1",
        eventKey: String = "evt-1",
        status: String = ReplyExecutionStatus.FAILED.name,
        dispatchAttempted: Boolean = false,
        dispatchedNormally: Boolean = false
    ) = ReplyAttemptEntity(
        attemptId = attemptId,
        sourceType = "DEFAULT",
        sourceLabel = null,
        packageName = "com.whatsapp",
        notificationKey = "notif-1",
        senderName = "Alice",
        incomingMessage = "Hi",
        replyText = "Hello",
        status = status,
        dispatchAttempted = dispatchAttempted,
        dispatchedNormally = dispatchedNormally,
        failureCategory = null,
        createdAtMs = 1_000L,
        updatedAtMs = 1_000L,
        eventKey = eventKey
    )

    @Test
    fun `evaluateAuthorizedPreDispatchRetry permits FAILED pre-dispatch within cap and rejects exhaustion`() {
        val c = claim(status = ReplyExecutionStatus.FAILED.name, dispatchAttempted = false)
        val a = attempt(status = ReplyExecutionStatus.FAILED.name, dispatchAttempted = false)

        assertEquals(
            RetryDecision.ALLOW_AUTHORIZED_RETRY,
            ReplyRetryPolicy.evaluateAuthorizedPreDispatchRetry(c, a, listOf(a), retryCountAlreadyUsed = 0)
        )
        assertEquals(
            RetryDecision.ALLOW_AUTHORIZED_RETRY,
            ReplyRetryPolicy.evaluateAuthorizedPreDispatchRetry(c, a, listOf(a), retryCountAlreadyUsed = 1)
        )
        assertEquals(
            RetryDecision.DENY_RETRY_LIMIT_EXCEEDED,
            ReplyRetryPolicy.evaluateAuthorizedPreDispatchRetry(
                c,
                a,
                listOf(a),
                retryCountAlreadyUsed = ReplyRetryPolicy.MAX_PRE_DISPATCH_RETRIES
            )
        )
        // DB failed-attempt count fallback also blocks even if prefs retryCount was cleared
        val a2 = a.copy(attemptId = "att-2")
        val a3 = a.copy(attemptId = "att-3")
        assertEquals(
            RetryDecision.DENY_RETRY_LIMIT_EXCEEDED,
            ReplyRetryPolicy.evaluateAuthorizedPreDispatchRetry(
                c.copy(attemptId = "att-3"),
                a3,
                listOf(a, a2, a3),
                retryCountAlreadyUsed = 0
            )
        )
    }

    @Test
    fun `evaluateAuthorizedPreDispatchRetry rejects UNKNOWN DISPATCHED CANCELLED IN_FLIGHT and mismatched attempts`() {
        for (blockedStatus in listOf(
            ReplyExecutionStatus.UNKNOWN.name,
            ReplyExecutionStatus.DISPATCHED.name,
            ReplyExecutionStatus.CANCELLED.name,
            ReplyExecutionStatus.GENERATED.name,
            ReplyExecutionStatus.DISPATCHING.name,
            NotificationDispatchClaimEntity.STATUS_CLAIMED
        )) {
            val c = claim(status = blockedStatus, dispatchAttempted = false)
            val decision = ReplyRetryPolicy.evaluateAuthorizedPreDispatchRetry(c, null, emptyList(), 0)
            assertFalse("Status $blockedStatus must never be allowed to retry", ReplyRetryPolicy.isDecisionAllowed(decision))
        }

        // FAILED claim with dispatchAttempted = true must NEVER retry
        assertEquals(
            RetryDecision.DENY_DUPLICATE_PRE_DISPATCH_FAILURE,
            ReplyRetryPolicy.evaluateAuthorizedPreDispatchRetry(
                claim(status = ReplyExecutionStatus.FAILED.name, dispatchAttempted = true),
                null,
                emptyList(),
                0
            )
        )

        // Pre-dispatch claim paired with UNKNOWN attempt in reply_attempts must reject conservatively
        assertEquals(
            RetryDecision.DENY_UNKNOWN_OUTCOME,
            ReplyRetryPolicy.evaluateAuthorizedPreDispatchRetry(
                claim(status = ReplyExecutionStatus.FAILED.name, dispatchAttempted = false),
                attempt(status = ReplyExecutionStatus.UNKNOWN.name, dispatchAttempted = true),
                emptyList(),
                0
            )
        )

        // Pre-dispatch claim + earlier DISPATCHED attempt on same eventKey must reject
        val failedClaim = claim(attemptId = "att-2", status = ReplyExecutionStatus.FAILED.name, dispatchAttempted = false)
        val failedAttempt = attempt(attemptId = "att-2", status = ReplyExecutionStatus.FAILED.name, dispatchAttempted = false)
        val dispatchedAttempt = attempt(attemptId = "att-1", status = ReplyExecutionStatus.DISPATCHED.name, dispatchAttempted = true, dispatchedNormally = true)
        assertEquals(
            RetryDecision.DENY_ALREADY_DISPATCHED,
            ReplyRetryPolicy.evaluateAuthorizedPreDispatchRetry(
                failedClaim,
                failedAttempt,
                listOf(dispatchedAttempt, failedAttempt),
                0
            )
        )
    }

    @Test
    fun `evaluateAuthorizedPreDispatchRetry enforces live service freshness and capability`() {
        val c = claim(status = ReplyExecutionStatus.FAILED.name, dispatchAttempted = false)
        val a = attempt(status = ReplyExecutionStatus.FAILED.name, dispatchAttempted = false)

        assertEquals(
            RetryDecision.DENY_SERVICE_SHUTDOWN,
            ReplyRetryPolicy.evaluateAuthorizedPreDispatchRetry(c, a, listOf(a), 0, isServiceShutdown = true)
        )
        assertEquals(
            RetryDecision.DENY_CANCELLED_OR_STALE,
            ReplyRetryPolicy.evaluateAuthorizedPreDispatchRetry(c, a, listOf(a), 0, isStaleNotification = true)
        )
        assertEquals(
            RetryDecision.DENY_MISSING_CAPABILITY,
            ReplyRetryPolicy.evaluateAuthorizedPreDispatchRetry(c, a, listOf(a), 0, hasValidReplyCapability = false)
        )

        assertFalse(ReplyRetryPolicy.hasValidWearReplyCapability(null))
        val pi = Mockito.mock(PendingIntent::class.java)
        val nonFreeFormRi = RemoteInput.Builder("k").setAllowFreeFormInput(false).build()
        val freeFormRi = RemoteInput.Builder("k").setAllowFreeFormInput(true).build()
        assertFalse(ReplyRetryPolicy.hasValidWearReplyCapability(NotificationWear("pkg", null, listOf(freeFormRi), null, "t", "1")))
        assertFalse(ReplyRetryPolicy.hasValidWearReplyCapability(NotificationWear("pkg", pi, listOf(nonFreeFormRi), null, "t", "1")))
        assertTrue(ReplyRetryPolicy.hasValidWearReplyCapability(NotificationWear("pkg", pi, listOf(freeFormRi), null, "t", "1")))

        assertFalse(ReplyRetryPolicy.hasValidReplyCapability(null, NotificationWear("pkg", pi, listOf(freeFormRi), null, "t", "1")))
        val sbnNoNotif = Mockito.mock(StatusBarNotification::class.java)
        assertFalse(ReplyRetryPolicy.hasValidReplyCapability(sbnNoNotif, NotificationWear("pkg", pi, listOf(freeFormRi), null, "t", "1")))
    }

    @Test
    fun `PreDispatchRetryStateStore InMemoryBackend tracks increments clears and expiration`() {
        val store = PreDispatchRetryStateStore.InMemoryBackend()
        assertEquals(0, store.getRetryCount("evt-a"))
        assertEquals(1, store.incrementRetryCount("evt-a", 1_000L))
        assertEquals(2, store.incrementRetryCount("evt-a", 2_000L))
        assertEquals(1, store.incrementRetryCount("evt-b", 10_000L))

        val removed = store.pruneExpired(nowMs = 11_000L, maxAgeMs = 5_000L)
        assertEquals(1, removed)
        assertEquals(0, store.getRetryCount("evt-a"))
        assertEquals(1, store.getRetryCount("evt-b"))

        store.clear("evt-b")
        assertEquals(0, store.getRetryCount("evt-b"))
    }

    @Test
    fun `NotificationUtils isPossiblyImageGroupMessage handles all edge cases deterministically`() {
        assertTrue(NotificationUtils.isPossiblyImageGroupMessage("Alice: Family", "\uD83D\uDCF7 Photo"))
        assertTrue(NotificationUtils.isPossiblyImageGroupMessage("Alice @ Team", "New \uD83D\uDCF7"))
        assertFalse(NotificationUtils.isPossiblyImageGroupMessage("Alice", "\uD83D\uDCF7 Photo"))
        assertFalse(NotificationUtils.isPossiblyImageGroupMessage("alice@example.com", "\uD83D\uDCF7 Photo"))
        assertFalse(NotificationUtils.isPossiblyImageGroupMessage(null, "\uD83D\uDCF7"))
        assertFalse(NotificationUtils.isPossiblyImageGroupMessage("", "\uD83D\uDCF7"))
        assertFalse(NotificationUtils.isPossiblyImageGroupMessage("   ", "\uD83D\uDCF7"))
        assertFalse(NotificationUtils.isPossiblyImageGroupMessage(": ", "\uD83D\uDCF7"))
        assertFalse(NotificationUtils.isPossiblyImageGroupMessage("@ ", "\uD83D\uDCF7"))
        assertFalse(NotificationUtils.isPossiblyImageGroupMessage("Alice: ", "\uD83D\uDCF7"))
        assertFalse(NotificationUtils.isPossiblyImageGroupMessage(": Group", "\uD83D\uDCF7"))
        assertFalse(NotificationUtils.isPossiblyImageGroupMessage("Alice: Group", "No camera"))
    }
}
