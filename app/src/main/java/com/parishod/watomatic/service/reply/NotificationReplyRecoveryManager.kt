package com.parishod.watomatic.service.reply

import android.content.Context
import android.util.Log
import com.parishod.watomatic.model.logs.MessageLogsDB
import com.parishod.watomatic.model.logs.NotificationDispatchClaimEntity
import com.parishod.watomatic.model.logs.ReplyAttemptEntity

data class RecoverySummary(
    val recoveredGeneratedCount: Int = 0,
    val recoveredDispatchingCount: Int = 0,
    val recoveredOrphanClaimsCount: Int = 0
) {
    val totalRecovered: Int
        get() = recoveredGeneratedCount + recoveredDispatchingCount + recoveredOrphanClaimsCount
}

/**
 * Safely and idempotently recovers unresolved reply attempts and dispatch claims
 * left behind by service termination or process death.
 *
 * Guarantees:
 * - Never auto-resends any interrupted notification reply.
 * - `DISPATCHING` -> `UNKNOWN` (`DISPATCHING_INTERRUPTED`), because binder IPC dispatch may have occurred.
 * - `GENERATED` and unstarted `CLAIMED` -> `CANCELLED` (`GENERATED_INTERRUPTED`).
 * - Never alters terminal `DISPATCHED`, `FAILED`, `CANCELLED`, or `UNKNOWN` rows.
 * - Never writes `message_logs` or AI assistant memory/notes.
 */
object NotificationReplyRecoveryManager {
    private const val TAG = "ReplyRecoveryMgr"

    @JvmStatic
    @JvmOverloads
    fun recoverOnStartup(
        context: Context,
        nowMs: Long = System.currentTimeMillis()
    ): RecoverySummary {
        return try {
            val db = MessageLogsDB.getInstance(context.applicationContext)
            recoverDatabase(db, nowMs)
        } catch (e: Exception) {
            Log.e(TAG, "Recovery skipped due to storage error (${e.javaClass.simpleName})")
            RecoverySummary()
        }
    }

    @JvmStatic
    @JvmOverloads
    fun tryRecoverOnStartup(
        context: Context,
        nowMs: Long = System.currentTimeMillis()
    ): Boolean {
        return try {
            val db = MessageLogsDB.getInstance(context.applicationContext)
            recoverDatabase(db, nowMs)
            true
        } catch (e: Exception) {
            Log.e(TAG, "Recovery attempt failed due to storage error (${e.javaClass.simpleName})")
            false
        }
    }

    @JvmStatic
    @JvmOverloads
    fun recoverDatabase(
        db: MessageLogsDB,
        nowMs: Long = System.currentTimeMillis()
    ): RecoverySummary {
        val attemptDao = db.replyAttemptDao()
        val claimDao = db.notificationDispatchClaimDao()
        val logsDao = db.logsDao()

        var generatedRecovered = 0
        var dispatchingRecovered = 0
        var orphanClaimsRecovered = 0

        db.runInTransaction {
            val unresolvedAttempts = attemptDao.getUnresolvedAttemptsSync()
            for (attempt in unresolvedAttempts) {
                val safeTimestamp = maxOf(attempt.updatedAtMs, nowMs)
                val linkedClaim = resolveClaimForAttempt(attempt, claimDao)
                val hasVerifiedDispatchedProof = linkedClaim != null &&
                    linkedClaim.attemptId == attempt.attemptId &&
                    linkedClaim.claimStatus == ReplyExecutionStatus.DISPATCHED.name &&
                    logsDao.countByAttemptIdSync(attempt.attemptId) > 0

                when (attempt.status) {
                    ReplyExecutionStatus.DISPATCHING.name -> {
                        if (hasVerifiedDispatchedProof) {
                            val reconciled = attempt.copy(
                                status = ReplyExecutionStatus.DISPATCHED.name,
                                dispatchAttempted = true,
                                dispatchedNormally = true,
                                failureCategory = null,
                                updatedAtMs = safeTimestamp
                            )
                            attemptDao.upsertAttemptSafely(reconciled)
                            dispatchingRecovered++
                        } else {
                            val updatedAttempt = attempt.copy(
                                status = ReplyExecutionStatus.UNKNOWN.name,
                                dispatchAttempted = true,
                                dispatchedNormally = false,
                                failureCategory = ReplyAttemptEntity.sanitizeFailureCategory(
                                    ReplyExecutionManager.FAILURE_DISPATCHING_INTERRUPTED
                                ),
                                updatedAtMs = safeTimestamp
                            )
                            attemptDao.upsertAttemptSafely(updatedAttempt)
                            dispatchingRecovered++

                            if (linkedClaim != null &&
                                (linkedClaim.claimStatus == NotificationDispatchClaimEntity.STATUS_CLAIMED ||
                                    linkedClaim.claimStatus == ReplyExecutionStatus.GENERATED.name ||
                                    linkedClaim.claimStatus == ReplyExecutionStatus.DISPATCHING.name)
                            ) {
                                claimDao.recoverUnresolvedClaimSync(
                                    eventKey = linkedClaim.eventKey,
                                    newStatus = ReplyExecutionStatus.UNKNOWN.name,
                                    failureCategory = updatedAttempt.failureCategory,
                                    dispatchAttempted = true,
                                    dispatchedNormally = false,
                                    updatedAtMs = safeTimestamp,
                                    expiresAtMs = safeTimestamp + NotificationDispatchClaimEntity.DEFAULT_UNRESOLVED_TTL_MS
                                )
                            }
                        }
                    }

                    ReplyExecutionStatus.GENERATED.name -> {
                        if (hasVerifiedDispatchedProof) {
                            val reconciled = attempt.copy(
                                status = ReplyExecutionStatus.DISPATCHED.name,
                                dispatchAttempted = true,
                                dispatchedNormally = true,
                                failureCategory = null,
                                updatedAtMs = safeTimestamp
                            )
                            attemptDao.upsertAttemptSafely(reconciled)
                            generatedRecovered++
                        } else if (linkedClaim != null &&
                            linkedClaim.attemptId == attempt.attemptId &&
                            (linkedClaim.claimStatus == ReplyExecutionStatus.DISPATCHING.name ||
                                linkedClaim.claimStatus == ReplyExecutionStatus.UNKNOWN.name)
                        ) {
                            val updatedAttempt = attempt.copy(
                                status = ReplyExecutionStatus.UNKNOWN.name,
                                dispatchAttempted = true,
                                dispatchedNormally = false,
                                failureCategory = ReplyAttemptEntity.sanitizeFailureCategory(
                                    ReplyExecutionManager.FAILURE_DISPATCHING_INTERRUPTED
                                ),
                                updatedAtMs = safeTimestamp
                            )
                            attemptDao.upsertAttemptSafely(updatedAttempt)
                            dispatchingRecovered++

                            if (linkedClaim.claimStatus == ReplyExecutionStatus.DISPATCHING.name) {
                                claimDao.recoverUnresolvedClaimSync(
                                    eventKey = linkedClaim.eventKey,
                                    newStatus = ReplyExecutionStatus.UNKNOWN.name,
                                    failureCategory = updatedAttempt.failureCategory,
                                    dispatchAttempted = true,
                                    dispatchedNormally = false,
                                    updatedAtMs = safeTimestamp,
                                    expiresAtMs = safeTimestamp + NotificationDispatchClaimEntity.DEFAULT_UNRESOLVED_TTL_MS
                                )
                            }
                        } else {
                            val updatedAttempt = attempt.copy(
                                status = ReplyExecutionStatus.CANCELLED.name,
                                dispatchAttempted = false,
                                dispatchedNormally = false,
                                failureCategory = ReplyAttemptEntity.sanitizeFailureCategory(
                                    ReplyExecutionManager.FAILURE_GENERATED_INTERRUPTED
                                ),
                                updatedAtMs = safeTimestamp
                            )
                            attemptDao.upsertAttemptSafely(updatedAttempt)
                            generatedRecovered++

                            if (linkedClaim != null &&
                                (linkedClaim.claimStatus == NotificationDispatchClaimEntity.STATUS_CLAIMED ||
                                    linkedClaim.claimStatus == ReplyExecutionStatus.GENERATED.name)
                            ) {
                                claimDao.recoverUnresolvedClaimSync(
                                    eventKey = linkedClaim.eventKey,
                                    newStatus = ReplyExecutionStatus.CANCELLED.name,
                                    failureCategory = updatedAttempt.failureCategory,
                                    dispatchAttempted = false,
                                    dispatchedNormally = false,
                                    updatedAtMs = safeTimestamp,
                                    expiresAtMs = safeTimestamp + NotificationDispatchClaimEntity.DEFAULT_RESOLVED_TTL_MS
                                )
                            }
                        }
                    }
                }
            }

            val remainingUnresolvedClaims = claimDao.getUnresolvedClaimsSync()
            for (claim in remainingUnresolvedClaims) {
                val safeTimestamp = maxOf(claim.updatedAtMs, nowMs)
                if (claim.claimStatus == ReplyExecutionStatus.DISPATCHING.name) {
                    val count = claimDao.recoverUnresolvedClaimSync(
                        eventKey = claim.eventKey,
                        newStatus = ReplyExecutionStatus.UNKNOWN.name,
                        failureCategory = ReplyAttemptEntity.sanitizeFailureCategory(
                            ReplyExecutionManager.FAILURE_DISPATCHING_INTERRUPTED
                        ),
                        dispatchAttempted = true,
                        dispatchedNormally = false,
                        updatedAtMs = safeTimestamp,
                        expiresAtMs = safeTimestamp + NotificationDispatchClaimEntity.DEFAULT_UNRESOLVED_TTL_MS
                    )
                    orphanClaimsRecovered += count
                } else {
                    val count = claimDao.recoverUnresolvedClaimSync(
                        eventKey = claim.eventKey,
                        newStatus = ReplyExecutionStatus.CANCELLED.name,
                        failureCategory = ReplyAttemptEntity.sanitizeFailureCategory(
                            ReplyExecutionManager.FAILURE_GENERATED_INTERRUPTED
                        ),
                        dispatchAttempted = false,
                        dispatchedNormally = false,
                        updatedAtMs = safeTimestamp,
                        expiresAtMs = safeTimestamp + NotificationDispatchClaimEntity.DEFAULT_RESOLVED_TTL_MS
                    )
                    orphanClaimsRecovered += count
                }
            }
        }

        val summary = RecoverySummary(
            recoveredGeneratedCount = generatedRecovered,
            recoveredDispatchingCount = dispatchingRecovered,
            recoveredOrphanClaimsCount = orphanClaimsRecovered
        )
        if (summary.totalRecovered > 0) {
            Log.i(
                TAG,
                "Recovered interrupted reply state: generated=${summary.recoveredGeneratedCount}, " +
                    "dispatchingToUnknown=${summary.recoveredDispatchingCount}, orphanClaims=${summary.recoveredOrphanClaimsCount}"
            )
        }
        return summary
    }

    private fun resolveClaimForAttempt(
        attempt: ReplyAttemptEntity,
        claimDao: com.parishod.watomatic.model.logs.NotificationDispatchClaimDao
    ): NotificationDispatchClaimEntity? {
        val byEventKey = attempt.eventKey?.takeIf { it.isNotBlank() }?.let {
            claimDao.getClaimByEventKeySync(it)
        }
        if (byEventKey != null) return byEventKey
        return claimDao.getClaimByAttemptIdSync(attempt.attemptId)
    }
}
