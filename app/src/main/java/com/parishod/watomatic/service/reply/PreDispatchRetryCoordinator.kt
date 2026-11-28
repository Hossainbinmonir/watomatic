package com.parishod.watomatic.service.reply

import android.content.Context
import android.service.notification.StatusBarNotification
import com.parishod.watomatic.NotificationWear
import com.parishod.watomatic.model.logs.ClaimAcquireOutcome
import com.parishod.watomatic.model.logs.MessageLogsDB
import com.parishod.watomatic.model.logs.NotificationDispatchClaimDao
import com.parishod.watomatic.model.logs.NotificationDispatchClaimEntity
import com.parishod.watomatic.model.logs.ReplyAttemptEntity
import com.parishod.watomatic.model.utils.DbUtils
import com.parishod.watomatic.model.utils.NotificationUtils

/**
 * Coordinates safe, bounded, capability-verified claim acquisition and retry
 * authorization for pre-dispatch failures (`status == FAILED && !dispatchAttempted`).
 *
 * Guarantees:
 * - Never retries `UNKNOWN`, `DISPATCHED`, `CANCELLED`, or any attempt with `dispatch_attempted == 1`.
 * - Never fabricates or reuses a stale `PendingIntent`: requires a live service, a fresh
 *   [StatusBarNotification], and a valid [NotificationWear] with free-form `RemoteInput`.
 * - Enforces a strict bounded retry budget ([maxRetries]).
 */
class PreDispatchRetryCoordinator @JvmOverloads constructor(
    private val stateBackend: PreDispatchRetryStateStore.StoreBackend,
    private val maxRetries: Int = ReplyRetryPolicy.MAX_PRE_DISPATCH_RETRIES
) {
    @JvmOverloads
    constructor(
        context: Context,
        maxRetries: Int = ReplyRetryPolicy.MAX_PRE_DISPATCH_RETRIES
    ) : this(
        stateBackend = PreDispatchRetryStateStore.SharedPrefsBackend(context.applicationContext),
        maxRetries = maxRetries
    )

    private val lockStripes = Array(LOCK_STRIPE_COUNT) { Any() }

    internal fun lockStripeFor(eventKey: String): Any = lockStripes[stripeIndexFor(eventKey)]

    internal fun lockStripeCount(): Int = lockStripes.size

    fun tryAcquireForFreshNotification(
        dbUtils: DbUtils,
        sbn: StatusBarNotification?,
        notificationWear: NotificationWear?,
        eventKey: String?,
        claimToken: String,
        packageName: String,
        senderKey: String,
        notificationKey: String?,
        nowMs: Long,
        isServiceDestroyed: Boolean
    ): ClaimAcquireOutcome {
        if (isServiceDestroyed) {
            return ClaimAcquireOutcome(
                acquired = false,
                claim = null,
                decision = RetryDecision.DENY_SERVICE_SHUTDOWN
            )
        }
        if (sbn == null || eventKey.isNullOrBlank()) {
            return ClaimAcquireOutcome(
                acquired = false,
                claim = null,
                decision = RetryDecision.DENY_MISSING_CAPABILITY
            )
        }
        if (!NotificationUtils.isNewNotification(sbn)) {
            return ClaimAcquireOutcome(
                acquired = false,
                claim = null,
                decision = RetryDecision.DENY_CANCELLED_OR_STALE
            )
        }
        if (!ReplyRetryPolicy.hasValidWearReplyCapability(notificationWear)) {
            return ClaimAcquireOutcome(
                acquired = false,
                claim = null,
                decision = RetryDecision.DENY_MISSING_CAPABILITY
            )
        }

        val lock = lockStripeFor(eventKey)
        synchronized(lock) {
            val priorRetryCount = stateBackend.getRetryCount(eventKey)
            val outcome = dbUtils.tryAcquireAuthorizedPreDispatchClaim(
                eventKey,
                claimToken,
                packageName,
                senderKey,
                notificationKey,
                nowMs,
                priorRetryCount,
                maxRetries
            )
            if (outcome.acquired && outcome.decision == RetryDecision.ALLOW_AUTHORIZED_RETRY) {
                stateBackend.incrementRetryCount(eventKey, nowMs)
            }
            return outcome
        }
    }

    /**
     * Explicit retry boundary for an event that previously failed pre-dispatch (`FAILED`,
     * `dispatch_attempted == 0`). Rejects new/unclaimed events, `UNKNOWN`, `DISPATCHED`,
     * `CANCELLED`, in-flight claims, missing capabilities, stale notifications, destroyed
     * services, or exhausted retry budgets.
     */
    fun tryAcquireExplicitPreDispatchRetry(
        dbUtils: DbUtils,
        sbn: StatusBarNotification?,
        notificationWear: NotificationWear?,
        eventKey: String?,
        claimToken: String,
        packageName: String,
        senderKey: String,
        notificationKey: String?,
        nowMs: Long,
        isServiceDestroyed: Boolean
    ): ClaimAcquireOutcome {
        if (isServiceDestroyed) {
            return ClaimAcquireOutcome(false, null, RetryDecision.DENY_SERVICE_SHUTDOWN)
        }
        if (sbn == null || eventKey.isNullOrBlank()) {
            return ClaimAcquireOutcome(false, null, RetryDecision.DENY_MISSING_CAPABILITY)
        }
        if (!NotificationUtils.isNewNotification(sbn)) {
            return ClaimAcquireOutcome(false, null, RetryDecision.DENY_CANCELLED_OR_STALE)
        }
        if (!ReplyRetryPolicy.hasValidWearReplyCapability(notificationWear)) {
            return ClaimAcquireOutcome(false, null, RetryDecision.DENY_MISSING_CAPABILITY)
        }

        val lock = lockStripeFor(eventKey)
        synchronized(lock) {
            val priorRetryCount = stateBackend.getRetryCount(eventKey)
            val existingClaim = dbUtils.getNotificationClaim(eventKey)
            if (existingClaim == null) {
                val priorAttempts = dbUtils.getAttemptsForEventKey(eventKey)
                if (priorAttempts.isEmpty() && priorRetryCount <= 0) {
                    return ClaimAcquireOutcome(false, null, RetryDecision.DENY_CANCELLED_OR_STALE)
                }
            }
            val outcome = dbUtils.tryAcquireAuthorizedPreDispatchClaim(
                eventKey,
                claimToken,
                packageName,
                senderKey,
                notificationKey,
                nowMs,
                priorRetryCount,
                maxRetries
            )
            if (outcome.acquired && outcome.decision == RetryDecision.ALLOW_AUTHORIZED_RETRY) {
                stateBackend.incrementRetryCount(eventKey, nowMs)
            }
            return outcome
        }
    }

    fun onTerminalOutcome(eventKey: String?, status: ReplyExecutionStatus) {
        if (eventKey.isNullOrBlank()) return
        if (status == ReplyExecutionStatus.DISPATCHED ||
            status == ReplyExecutionStatus.UNKNOWN ||
            status == ReplyExecutionStatus.CANCELLED
        ) {
            stateBackend.clear(eventKey)
        }
    }

    fun getRetryCount(eventKey: String): Int = stateBackend.getRetryCount(eventKey)

    companion object {
        const val LOCK_STRIPE_COUNT = 128

        @JvmStatic
        fun stripeIndexFor(eventKey: String): Int =
            (eventKey.hashCode() and 0x7fffffff) % LOCK_STRIPE_COUNT

        /**
         * Pure transactional helper usable with any [MessageLogsDB] instance in tests or DB utils.
         */
        @JvmStatic
        fun tryAcquireAuthorizedPreDispatchClaimInDb(
            db: MessageLogsDB,
            newClaim: NotificationDispatchClaimEntity,
            priorRetryCount: Int,
            maxRetries: Int = ReplyRetryPolicy.MAX_PRE_DISPATCH_RETRIES
        ): ClaimAcquireOutcome {
            val claimDao = db.notificationDispatchClaimDao()
            val attemptDao = db.replyAttemptDao()
            lateinit var outcome: ClaimAcquireOutcome
            db.runInTransaction {
                val insertedRowId = claimDao.insertClaimIgnoreSync(newClaim)
                if (insertedRowId > 0L) {
                    val attemptsForEvent = attemptDao.getAttemptsByEventKeySync(newClaim.eventKey)
                    val decision = ReplyRetryPolicy.evaluateAuthorizedPreDispatchRetry(
                        existingClaim = null,
                        linkedAttempt = null,
                        attemptsForEventKey = attemptsForEvent,
                        retryCountAlreadyUsed = priorRetryCount,
                        maxRetries = maxRetries
                    )
                    if (!ReplyRetryPolicy.isDecisionAllowed(decision)) {
                        claimDao.deleteUnusedClaimByTokenSync(newClaim.eventKey, newClaim.attemptId)
                        outcome = ClaimAcquireOutcome(false, null, decision)
                        return@runInTransaction
                    }
                    outcome = ClaimAcquireOutcome(true, newClaim, decision)
                    return@runInTransaction
                }

                val existing = claimDao.getClaimByEventKeySync(newClaim.eventKey)
                val linkedAttempt: ReplyAttemptEntity? = existing?.attemptId?.let {
                    attemptDao.getAttemptByIdSync(it)
                }
                val attemptsForEvent = attemptDao.getAttemptsByEventKeySync(newClaim.eventKey)
                val decision = ReplyRetryPolicy.evaluateAuthorizedPreDispatchRetry(
                    existingClaim = existing,
                    linkedAttempt = linkedAttempt,
                    attemptsForEventKey = attemptsForEvent,
                    retryCountAlreadyUsed = priorRetryCount,
                    maxRetries = maxRetries
                )
                if (decision == RetryDecision.ALLOW_AUTHORIZED_RETRY && existing != null) {
                    val updated = claimDao.reclaimFailedPreDispatchSync(
                        eventKey = existing.eventKey,
                        newAttemptId = newClaim.attemptId,
                        newStatus = newClaim.claimStatus,
                        nowMs = newClaim.updatedAtMs,
                        expiresAtMs = newClaim.expiresAtMs
                    )
                    outcome = if (updated == 1) {
                        NotificationDispatchClaimDao.recordPriorFailedSnapshot(existing, newClaim.attemptId)
                        ClaimAcquireOutcome(
                            acquired = true,
                            claim = claimDao.getClaimByEventKeySync(existing.eventKey),
                            decision = RetryDecision.ALLOW_AUTHORIZED_RETRY
                        )
                    } else {
                        ClaimAcquireOutcome(
                            acquired = false,
                            claim = claimDao.getClaimByEventKeySync(existing.eventKey),
                            decision = RetryDecision.DENY_IN_FLIGHT
                        )
                    }
                } else {
                    outcome = ClaimAcquireOutcome(
                        acquired = false,
                        claim = existing,
                        decision = decision
                    )
                }
            }
            return outcome
        }
    }
}
