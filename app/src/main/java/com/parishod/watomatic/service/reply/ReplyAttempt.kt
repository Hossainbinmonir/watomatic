package com.parishod.watomatic.service.reply

import java.util.UUID

/** Origin category of a reply attempt. */
enum class ReplySourceType {
    DEFAULT,
    RULE_STATIC,
    RULE_AI,
    GLOBAL_AI,
    AI_FALLBACK
}

/**
 * Transient domain model representing an individual reply execution attempt in Phase 02.2.
 *
 * Persistence via Room belongs to Development 02.3; this class intentionally has no database annotations.
 */
data class ReplyAttempt(
    val attemptId: String = UUID.randomUUID().toString(),
    val sourceType: ReplySourceType = ReplySourceType.DEFAULT,
    val sourceLabel: String? = null,
    val packageName: String = "",
    val notificationKey: String? = null,
    val replyText: String? = null,
    val status: ReplyExecutionStatus = ReplyExecutionStatus.GENERATED,
    val createdAtMs: Long = System.currentTimeMillis(),
    val updatedAtMs: Long = createdAtMs
) {
    fun transitionTo(
        newStatus: ReplyExecutionStatus,
        timestampMs: Long = System.currentTimeMillis()
    ): ReplyAttempt = copy(status = newStatus, updatedAtMs = timestampMs)

    companion object {
        @JvmStatic
        @JvmOverloads
        fun create(
            sourceType: ReplySourceType = ReplySourceType.DEFAULT,
            sourceLabel: String? = null,
            packageName: String = "",
            notificationKey: String? = null,
            replyText: String? = null
        ): ReplyAttempt = ReplyAttempt(
            sourceType = sourceType,
            sourceLabel = sourceLabel,
            packageName = packageName,
            notificationKey = notificationKey,
            replyText = replyText
        )
    }
}
