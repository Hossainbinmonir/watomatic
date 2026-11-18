package com.parishod.watomatic.service.reply

/**
 * Immutable result of a single reply execution attempt.
 *
 * Intentionally excludes sensitive reply text, incoming message content, sender names,
 * URLs, and credentials from diagnostic fields.
 */
data class ReplyExecutionResult(
    val executionId: String,
    val status: ReplyExecutionStatus,
    val dispatchAttempted: Boolean,
    val dispatchedNormally: Boolean,
    val failureCategory: String? = null,
    val diagnosticMessage: String? = null,
    val timestampMs: Long = System.currentTimeMillis()
) {
    /**
     * True only when `PendingIntent.send()` returned normally ([ReplyExecutionStatus.DISPATCHED]).
     * Never treats [ReplyExecutionStatus.UNKNOWN] as success.
     */
    fun isDispatched(): Boolean =
        status == ReplyExecutionStatus.DISPATCHED && dispatchedNormally

    /** True when the outcome could not be determined reliably. Must never be auto-retried. */
    fun isUnknown(): Boolean = status == ReplyExecutionStatus.UNKNOWN

    /** True when dispatch definitively failed or was cancelled. */
    fun isFailedOrCancelled(): Boolean =
        status == ReplyExecutionStatus.FAILED || status == ReplyExecutionStatus.CANCELLED
}
