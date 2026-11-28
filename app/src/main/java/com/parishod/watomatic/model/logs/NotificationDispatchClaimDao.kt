package com.parishod.watomatic.model.logs

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.parishod.watomatic.service.reply.ReplyExecutionStatus
import com.parishod.watomatic.service.reply.ReplyRetryPolicy
import com.parishod.watomatic.service.reply.RetryDecision

data class ClaimAcquireOutcome(
    val acquired: Boolean,
    val claim: NotificationDispatchClaimEntity?,
    val decision: RetryDecision
)

@Dao
interface NotificationDispatchClaimDao {

    @Query("SELECT * FROM notification_dispatch_claims WHERE event_key = :eventKey LIMIT 1")
    fun getClaimByEventKeySync(eventKey: String): NotificationDispatchClaimEntity?

    @Query("SELECT * FROM notification_dispatch_claims WHERE attempt_id = :attemptId LIMIT 1")
    fun getClaimByAttemptIdSync(attemptId: String): NotificationDispatchClaimEntity?

    @Query("SELECT * FROM notification_dispatch_claims ORDER BY updated_at_ms DESC")
    fun getAllClaimsSync(): List<NotificationDispatchClaimEntity>

    @Query("SELECT COUNT(*) FROM notification_dispatch_claims")
    fun getClaimCountSync(): Int

    @Query(
        "SELECT * FROM notification_dispatch_claims " +
            "WHERE claim_status IN ('CLAIMED', 'GENERATED', 'DISPATCHING')"
    )
    fun getUnresolvedClaimsSync(): List<NotificationDispatchClaimEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun insertClaimIgnoreSync(entity: NotificationDispatchClaimEntity): Long

    @Query(
        "UPDATE notification_dispatch_claims SET " +
            "attempt_id = :newAttemptId, " +
            "claim_status = :newStatus, " +
            "failure_category = NULL, " +
            "dispatch_attempted = 0, " +
            "dispatched_normally = 0, " +
            "updated_at_ms = :nowMs, " +
            "expires_at_ms = :expiresAtMs " +
            "WHERE event_key = :eventKey " +
            "AND claim_status = 'FAILED' " +
            "AND dispatch_attempted = 0"
    )
    fun reclaimFailedPreDispatchSync(
        eventKey: String,
        newAttemptId: String,
        newStatus: String,
        nowMs: Long,
        expiresAtMs: Long
    ): Int

    @Query("SELECT * FROM reply_attempts WHERE event_key = :eventKey ORDER BY created_at_ms DESC")
    fun getAttemptsForEventKeySync(eventKey: String): List<ReplyAttemptEntity>

    @Transaction
    fun tryAcquireClaimSync(
        newClaim: NotificationDispatchClaimEntity,
        allowRetryOnPreDispatchFailure: Boolean = false
    ): ClaimAcquireOutcome {
        val insertedRowId = insertClaimIgnoreSync(newClaim)
        val attemptsForEvent = getAttemptsForEventKeySync(newClaim.eventKey)
        if (insertedRowId > 0L) {
            val attemptDecision = ReplyRetryPolicy.evaluateCrossTableAttemptEvidence(
                attempts = attemptsForEvent,
                allowRetryOnPreDispatchFailure = allowRetryOnPreDispatchFailure
            )
            if (attemptDecision == null) {
                return ClaimAcquireOutcome(
                    acquired = true,
                    claim = newClaim,
                    decision = RetryDecision.ALLOW_NEW_EVENT
                )
            }
            if (attemptDecision == RetryDecision.ALLOW_AUTHORIZED_RETRY) {
                return ClaimAcquireOutcome(
                    acquired = true,
                    claim = newClaim,
                    decision = RetryDecision.ALLOW_AUTHORIZED_RETRY
                )
            }
            deleteUnusedClaimByTokenSync(newClaim.eventKey, newClaim.attemptId)
            return ClaimAcquireOutcome(
                acquired = false,
                claim = null,
                decision = attemptDecision
            )
        }

        val existing = getClaimByEventKeySync(newClaim.eventKey)
        val claimDecision = ReplyRetryPolicy.evaluateClaimConflict(
            existingClaim = existing,
            allowRetryOnPreDispatchFailure = allowRetryOnPreDispatchFailure
        )
        if (claimDecision == RetryDecision.DENY_ALREADY_DISPATCHED ||
            claimDecision == RetryDecision.DENY_UNKNOWN_OUTCOME
        ) {
            return ClaimAcquireOutcome(
                acquired = false,
                claim = existing,
                decision = claimDecision
            )
        }

        val attemptBlocker = ReplyRetryPolicy.evaluateAttemptsBlockingStatus(attemptsForEvent)
        if (attemptBlocker != null) {
            return ClaimAcquireOutcome(
                acquired = false,
                claim = existing,
                decision = attemptBlocker
            )
        }

        if (claimDecision == RetryDecision.ALLOW_AUTHORIZED_RETRY && existing != null) {
            val updated = reclaimFailedPreDispatchSync(
                eventKey = existing.eventKey,
                newAttemptId = newClaim.attemptId,
                newStatus = newClaim.claimStatus,
                nowMs = newClaim.updatedAtMs,
                expiresAtMs = newClaim.expiresAtMs
            )
            if (updated == 1) {
                recordPriorFailedSnapshot(existing, newClaim.attemptId)
                return ClaimAcquireOutcome(
                    acquired = true,
                    claim = getClaimByEventKeySync(existing.eventKey),
                    decision = RetryDecision.ALLOW_AUTHORIZED_RETRY
                )
            }
            return ClaimAcquireOutcome(
                acquired = false,
                claim = getClaimByEventKeySync(existing.eventKey),
                decision = RetryDecision.DENY_IN_FLIGHT
            )
        }

        return ClaimAcquireOutcome(
            acquired = false,
            claim = existing,
            decision = claimDecision
        )
    }

    @Query(
        "UPDATE notification_dispatch_claims SET " +
            "attempt_id = :newAttemptId, " +
            "claim_status = :newStatus, " +
            "failure_category = :failureCategory, " +
            "dispatch_attempted = :dispatchAttempted, " +
            "dispatched_normally = :dispatchedNormally, " +
            "updated_at_ms = :updatedAtMs, " +
            "expires_at_ms = :expiresAtMs " +
            "WHERE event_key = :eventKey " +
            "AND attempt_id = :expectedAttemptId " +
            "AND claim_status NOT IN ('DISPATCHED', 'FAILED', 'CANCELLED', 'UNKNOWN')"
    )
    fun updateClaimStateByOwnerSync(
        eventKey: String,
        expectedAttemptId: String,
        newAttemptId: String,
        newStatus: String,
        failureCategory: String?,
        dispatchAttempted: Boolean,
        dispatchedNormally: Boolean,
        updatedAtMs: Long,
        expiresAtMs: Long
    ): Int

    @Query(
        "DELETE FROM notification_dispatch_claims " +
            "WHERE event_key = :eventKey AND attempt_id = :claimToken AND claim_status = 'CLAIMED'"
    )
    fun deleteUnusedClaimByTokenSync(eventKey: String, claimToken: String): Int

    @Query(
        "UPDATE notification_dispatch_claims SET " +
            "attempt_id = :restoredAttemptId, " +
            "claim_status = 'FAILED', " +
            "failure_category = :failureCategory, " +
            "dispatch_attempted = 0, " +
            "dispatched_normally = 0, " +
            "updated_at_ms = :updatedAtMs, " +
            "expires_at_ms = :expiresAtMs " +
            "WHERE event_key = :eventKey " +
            "AND attempt_id = :expectedClaimToken " +
            "AND claim_status = 'CLAIMED'"
    )
    fun restorePriorFailedClaimSync(
        eventKey: String,
        expectedClaimToken: String,
        restoredAttemptId: String,
        failureCategory: String?,
        updatedAtMs: Long,
        expiresAtMs: Long
    ): Int

    @Transaction
    fun releaseUnusedClaimSync(eventKey: String, claimToken: String): Int {
        val snapshot = consumePriorFailedSnapshot(eventKey, claimToken)
        val current = getClaimByEventKeySync(eventKey)
        if (current == null ||
            current.attemptId != claimToken ||
            current.claimStatus != NotificationDispatchClaimEntity.STATUS_CLAIMED
        ) {
            return 0
        }
        val priorAttempt = getAttemptsForEventKeySync(eventKey).firstOrNull { attempt ->
            attempt.attemptId != claimToken &&
                attempt.status == ReplyExecutionStatus.FAILED.name &&
                !attempt.dispatchAttempted &&
                !attempt.dispatchedNormally
        }
        if (snapshot != null) {
            return restorePriorFailedClaimSync(
                eventKey = eventKey,
                expectedClaimToken = claimToken,
                restoredAttemptId = snapshot.attemptId,
                failureCategory = snapshot.failureCategory,
                updatedAtMs = snapshot.updatedAtMs,
                expiresAtMs = snapshot.expiresAtMs
            )
        }
        if (priorAttempt != null) {
            return restorePriorFailedClaimSync(
                eventKey = eventKey,
                expectedClaimToken = claimToken,
                restoredAttemptId = priorAttempt.attemptId,
                failureCategory = priorAttempt.failureCategory,
                updatedAtMs = priorAttempt.updatedAtMs,
                expiresAtMs = priorAttempt.updatedAtMs + NotificationDispatchClaimEntity.DEFAULT_RESOLVED_TTL_MS
            )
        }
        return deleteUnusedClaimByTokenSync(eventKey, claimToken)
    }

    @Query(
        "UPDATE notification_dispatch_claims SET " +
            "claim_status = 'CANCELLED', " +
            "failure_category = :failureCategory, " +
            "dispatch_attempted = 0, " +
            "dispatched_normally = 0, " +
            "updated_at_ms = :updatedAtMs, " +
            "expires_at_ms = :expiresAtMs " +
            "WHERE event_key = :eventKey AND attempt_id = :claimToken AND claim_status = 'CLAIMED'"
    )
    fun cancelPendingClaimSync(
        eventKey: String,
        claimToken: String,
        failureCategory: String?,
        updatedAtMs: Long,
        expiresAtMs: Long
    ): Int

    @Query(
        "UPDATE notification_dispatch_claims SET " +
            "claim_status = :newStatus, " +
            "failure_category = :failureCategory, " +
            "dispatch_attempted = :dispatchAttempted, " +
            "dispatched_normally = :dispatchedNormally, " +
            "updated_at_ms = :updatedAtMs, " +
            "expires_at_ms = :expiresAtMs " +
            "WHERE event_key = :eventKey " +
            "AND claim_status IN ('CLAIMED', 'GENERATED', 'DISPATCHING')"
    )
    fun recoverUnresolvedClaimSync(
        eventKey: String,
        newStatus: String,
        failureCategory: String?,
        dispatchAttempted: Boolean,
        dispatchedNormally: Boolean,
        updatedAtMs: Long,
        expiresAtMs: Long
    ): Int

    @Query(
        "DELETE FROM notification_dispatch_claims " +
            "WHERE expires_at_ms <= :nowMs " +
            "AND claim_status IN ('DISPATCHED', 'FAILED', 'CANCELLED')"
    )
    fun deleteExpiredResolvedClaimsSync(nowMs: Long): Int

    @Query(
        "DELETE FROM notification_dispatch_claims " +
            "WHERE claim_status IN ('DISPATCHED', 'FAILED', 'CANCELLED') " +
            "AND event_key NOT IN (" +
            "  SELECT event_key FROM notification_dispatch_claims " +
            "  WHERE claim_status IN ('DISPATCHED', 'FAILED', 'CANCELLED') " +
            "  ORDER BY updated_at_ms DESC LIMIT :maxResolvedRows" +
            ")"
    )
    fun trimExcessResolvedClaimsSync(maxResolvedRows: Int): Int

    @Transaction
    fun purgeExpiredAndBoundedClaimsSync(
        nowMs: Long,
        maxResolvedRows: Int = NotificationDispatchClaimEntity.MAX_RESOLVED_CLAIM_ROWS
    ): Int {
        var deleted = deleteExpiredResolvedClaimsSync(nowMs)
        if (maxResolvedRows >= 0) {
            deleted += trimExcessResolvedClaimsSync(maxResolvedRows)
        }
        return deleted
    }

    @Query("DELETE FROM notification_dispatch_claims")
    fun clearAllClaimsSync()

    companion object {
        private const val MAX_STASH_ENTRIES = 1024

        private data class PriorFailedSnapshot(
            val attemptId: String,
            val failureCategory: String?,
            val updatedAtMs: Long,
            val expiresAtMs: Long
        )

        private val stashLock = Any()
        private val priorFailedSnapshots = LinkedHashMap<String, PriorFailedSnapshot>(64, 0.75f, false)

        @JvmStatic
        fun recordPriorFailedSnapshot(
            priorClaim: NotificationDispatchClaimEntity,
            newClaimToken: String
        ) {
            if (priorClaim.eventKey.isBlank() || newClaimToken.isBlank()) return
            val key = "${priorClaim.eventKey}|$newClaimToken"
            synchronized(stashLock) {
                if (priorFailedSnapshots.size >= MAX_STASH_ENTRIES && !priorFailedSnapshots.containsKey(key)) {
                    val eldest = priorFailedSnapshots.entries.firstOrNull()?.key
                    if (eldest != null) {
                        priorFailedSnapshots.remove(eldest)
                    }
                }
                priorFailedSnapshots[key] = PriorFailedSnapshot(
                    attemptId = priorClaim.attemptId,
                    failureCategory = priorClaim.failureCategory,
                    updatedAtMs = priorClaim.updatedAtMs,
                    expiresAtMs = priorClaim.expiresAtMs
                )
            }
        }

        @JvmStatic
        fun clearPriorFailedSnapshot(eventKey: String?, claimToken: String?) {
            if (eventKey.isNullOrBlank() || claimToken.isNullOrBlank()) return
            val key = "$eventKey|$claimToken"
            synchronized(stashLock) {
                priorFailedSnapshots.remove(key)
            }
        }

        private fun consumePriorFailedSnapshot(eventKey: String, claimToken: String): PriorFailedSnapshot? {
            if (eventKey.isBlank() || claimToken.isBlank()) return null
            val key = "$eventKey|$claimToken"
            synchronized(stashLock) {
                return priorFailedSnapshots.remove(key)
            }
        }

        @JvmStatic
        fun clearAllPriorFailedSnapshotsForTesting() {
            synchronized(stashLock) {
                priorFailedSnapshots.clear()
            }
        }
    }
}
