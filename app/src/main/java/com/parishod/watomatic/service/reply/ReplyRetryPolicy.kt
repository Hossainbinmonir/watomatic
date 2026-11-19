package com.parishod.watomatic.service.reply

import com.parishod.watomatic.model.logs.NotificationDispatchClaimEntity
import com.parishod.watomatic.model.logs.ReplyAttemptEntity

enum class RetryDecision {
    ALLOW_NEW_EVENT,
    ALLOW_AUTHORIZED_RETRY,
    DENY_ALREADY_DISPATCHED,
    DENY_UNKNOWN_OUTCOME,
    DENY_CANCELLED_OR_STALE,
    DENY_IN_FLIGHT,
    DENY_DUPLICATE_PRE_DISPATCH_FAILURE,
    DENY_DUPLICATE_CALLBACK,
    DENY_SERVICE_SHUTDOWN,
    DENY_MISSING_CAPABILITY,
    DENY_DB_ERROR
}

/**
 * Explicit retry and duplicate-prevention policy covering every [ReplyExecutionStatus].
 */
object ReplyRetryPolicy {

    @JvmStatic
    fun isDecisionAllowed(decision: RetryDecision): Boolean =
        decision == RetryDecision.ALLOW_NEW_EVENT || decision == RetryDecision.ALLOW_AUTHORIZED_RETRY

    @JvmStatic
    @JvmOverloads
    fun evaluateClaimConflict(
        existingClaim: NotificationDispatchClaimEntity?,
        allowRetryOnPreDispatchFailure: Boolean = false,
        isServiceShutdown: Boolean = false,
        isDuplicateCallback: Boolean = false,
        isStaleNotification: Boolean = false,
        hasValidReplyCapability: Boolean = true
    ): RetryDecision {
        if (isServiceShutdown) return RetryDecision.DENY_SERVICE_SHUTDOWN
        if (isDuplicateCallback) return RetryDecision.DENY_DUPLICATE_CALLBACK
        if (isStaleNotification) return RetryDecision.DENY_CANCELLED_OR_STALE
        if (!hasValidReplyCapability) return RetryDecision.DENY_MISSING_CAPABILITY
        if (existingClaim == null) return RetryDecision.ALLOW_NEW_EVENT

        return when (existingClaim.claimStatus) {
            ReplyExecutionStatus.DISPATCHED.name -> RetryDecision.DENY_ALREADY_DISPATCHED
            ReplyExecutionStatus.UNKNOWN.name -> RetryDecision.DENY_UNKNOWN_OUTCOME
            ReplyExecutionStatus.CANCELLED.name -> RetryDecision.DENY_CANCELLED_OR_STALE
            NotificationDispatchClaimEntity.STATUS_CLAIMED,
            ReplyExecutionStatus.GENERATED.name,
            ReplyExecutionStatus.DISPATCHING.name -> RetryDecision.DENY_IN_FLIGHT
            ReplyExecutionStatus.FAILED.name -> {
                if (!existingClaim.dispatchAttempted && allowRetryOnPreDispatchFailure) {
                    RetryDecision.ALLOW_AUTHORIZED_RETRY
                } else {
                    RetryDecision.DENY_DUPLICATE_PRE_DISPATCH_FAILURE
                }
            }
            else -> RetryDecision.DENY_UNKNOWN_OUTCOME
        }
    }

    @JvmStatic
    @JvmOverloads
    fun evaluateAttemptStatus(
        priorAttempt: ReplyAttemptEntity?,
        allowRetryOnPreDispatchFailure: Boolean = false,
        isServiceShutdown: Boolean = false,
        isDuplicateCallback: Boolean = false,
        isStaleNotification: Boolean = false,
        hasValidReplyCapability: Boolean = true
    ): RetryDecision {
        if (isServiceShutdown) return RetryDecision.DENY_SERVICE_SHUTDOWN
        if (isDuplicateCallback) return RetryDecision.DENY_DUPLICATE_CALLBACK
        if (isStaleNotification) return RetryDecision.DENY_CANCELLED_OR_STALE
        if (!hasValidReplyCapability) return RetryDecision.DENY_MISSING_CAPABILITY
        if (priorAttempt == null) return RetryDecision.ALLOW_NEW_EVENT

        return when (priorAttempt.status) {
            ReplyExecutionStatus.DISPATCHED.name -> RetryDecision.DENY_ALREADY_DISPATCHED
            ReplyExecutionStatus.UNKNOWN.name -> RetryDecision.DENY_UNKNOWN_OUTCOME
            ReplyExecutionStatus.CANCELLED.name -> RetryDecision.DENY_CANCELLED_OR_STALE
            ReplyExecutionStatus.GENERATED.name,
            ReplyExecutionStatus.DISPATCHING.name -> RetryDecision.DENY_IN_FLIGHT
            ReplyExecutionStatus.FAILED.name -> {
                if (!priorAttempt.dispatchAttempted && allowRetryOnPreDispatchFailure) {
                    RetryDecision.ALLOW_AUTHORIZED_RETRY
                } else {
                    RetryDecision.DENY_DUPLICATE_PRE_DISPATCH_FAILURE
                }
            }
            else -> RetryDecision.DENY_UNKNOWN_OUTCOME
        }
    }

    @JvmStatic
    fun failureCategoryForDenial(decision: RetryDecision): String = when (decision) {
        RetryDecision.DENY_UNKNOWN_OUTCOME -> ReplyExecutionManager.FAILURE_UNKNOWN_OUTCOME_LOCKED
        RetryDecision.DENY_SERVICE_SHUTDOWN -> ReplyExecutionManager.FAILURE_SERVICE_SHUTDOWN
        RetryDecision.DENY_DB_ERROR -> ReplyExecutionManager.FAILURE_CLAIM_PERSISTENCE_ERROR
        RetryDecision.DENY_MISSING_CAPABILITY -> ReplyExecutionManager.FAILURE_MISSING_PENDING_INTENT
        else -> ReplyExecutionManager.FAILURE_DUPLICATE_EVENT
    }
}
