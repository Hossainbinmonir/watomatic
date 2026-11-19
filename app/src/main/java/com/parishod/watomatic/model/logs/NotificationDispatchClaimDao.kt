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

    @Transaction
    fun tryAcquireClaimSync(
        newClaim: NotificationDispatchClaimEntity,
        allowRetryOnPreDispatchFailure: Boolean = false
    ): ClaimAcquireOutcome {
        val insertedRowId = insertClaimIgnoreSync(newClaim)
        if (insertedRowId > 0L) {
            return ClaimAcquireOutcome(
                acquired = true,
                claim = newClaim,
                decision = RetryDecision.ALLOW_NEW_EVENT
            )
        }

        val existing = getClaimByEventKeySync(newClaim.eventKey)
        val decision = ReplyRetryPolicy.evaluateClaimConflict(
            existingClaim = existing,
            allowRetryOnPreDispatchFailure = allowRetryOnPreDispatchFailure
        )
        if (decision == RetryDecision.ALLOW_AUTHORIZED_RETRY && existing != null) {
            val updated = reclaimFailedPreDispatchSync(
                eventKey = existing.eventKey,
                newAttemptId = newClaim.attemptId,
                newStatus = newClaim.claimStatus,
                nowMs = newClaim.updatedAtMs,
                expiresAtMs = newClaim.expiresAtMs
            )
            if (updated == 1) {
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
            decision = decision
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
    fun releaseUnusedClaimSync(eventKey: String, claimToken: String): Int

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
}
