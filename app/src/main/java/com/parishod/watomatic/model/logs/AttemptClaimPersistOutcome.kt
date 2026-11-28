package com.parishod.watomatic.model.logs

import com.parishod.watomatic.service.reply.ReplyExecutionStatus

data class AttemptClaimPersistOutcome(
    val effectiveAttemptStatus: ReplyExecutionStatus,
    val effectiveClaimStatus: String?,
    val dispatchedPersisted: Boolean,
    val claimUpdated: Boolean,
    val claimMissing: Boolean,
    val reconciledFromAuthoritativeAttempt: Boolean,
    val failureCategory: String?
)
