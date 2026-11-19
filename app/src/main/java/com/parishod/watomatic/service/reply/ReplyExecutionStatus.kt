package com.parishod.watomatic.service.reply

/**
 * Lifecycle statuses for a single reply execution attempt.
 *
 * Note: [DISPATCHED] indicates that Android's `PendingIntent.send()` returned normally.
 * It does NOT guarantee that the destination messaging app delivered the message to the recipient.
 */
enum class ReplyExecutionStatus {
    /** Reply content has been generated or resolved; dispatch has not started. */
    GENERATED,

    /** Validation passed and `PendingIntent.send()` is about to be attempted. */
    DISPATCHING,

    /**
     * `PendingIntent.send()` returned normally. Android accepted the dispatch handoff.
     * Does NOT confirm remote delivery or read receipt.
     */
    DISPATCHED,

    /**
     * A deterministic failure occurred before dispatch (for example, missing
     * `RemoteInput`, missing `PendingIntent`, or a deterministic pre-dispatch `SecurityException`).
     */
    FAILED,

    /**
     * Execution was safely cancelled before dispatch (e.g., service shutdown or duplicate in-flight lock),
     * or Android reported `PendingIntent.CanceledException`.
     */
    CANCELLED,

    /**
     * Dispatch outcome cannot be determined reliably (for example, an unexpected exception
     * after invoking `PendingIntent.send()`). Never treated as success and never automatically retried.
     */
    UNKNOWN
}
