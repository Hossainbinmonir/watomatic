package com.parishod.watomatic.model.logs

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.parishod.watomatic.service.reply.ReplyAttempt
import com.parishod.watomatic.service.reply.ReplyExecutionResult
import com.parishod.watomatic.service.reply.ReplyExecutionStatus
import com.parishod.watomatic.service.reply.ReplySourceType

@Entity(
    tableName = "reply_attempts",
    indices = [
        Index(value = ["updated_at_ms"]),
        Index(value = ["package_name", "sender_name"]),
        Index(value = ["event_key"])
    ]
)
data class ReplyAttemptEntity(
    @PrimaryKey
    @ColumnInfo(name = "attempt_id")
    val attemptId: String,
    @ColumnInfo(name = "package_name")
    val packageName: String,
    @ColumnInfo(name = "sender_name")
    val senderName: String?,
    @ColumnInfo(name = "incoming_message")
    val incomingMessage: String?,
    @ColumnInfo(name = "notification_key")
    val notificationKey: String?,
    @ColumnInfo(name = "reply_text")
    val replyText: String?,
    @ColumnInfo(name = "source_type")
    val sourceType: String,
    @ColumnInfo(name = "source_label")
    val sourceLabel: String?,
    @ColumnInfo(name = "status")
    val status: String,
    @ColumnInfo(name = "dispatch_attempted")
    val dispatchAttempted: Boolean,
    @ColumnInfo(name = "dispatched_normally")
    val dispatchedNormally: Boolean,
    @ColumnInfo(name = "failure_category")
    val failureCategory: String?,
    @ColumnInfo(name = "created_at_ms")
    val createdAtMs: Long,
    @ColumnInfo(name = "updated_at_ms")
    val updatedAtMs: Long,
    @ColumnInfo(name = "event_key")
    val eventKey: String? = null
) {
    fun toDomainAttempt(): ReplyAttempt {
        val parsedSource = runCatching { ReplySourceType.valueOf(sourceType) }
            .getOrDefault(ReplySourceType.DEFAULT)
        val parsedStatus = runCatching { ReplyExecutionStatus.valueOf(status) }
            .getOrDefault(ReplyExecutionStatus.UNKNOWN)
        return ReplyAttempt(
            attemptId = attemptId,
            sourceType = parsedSource,
            sourceLabel = sourceLabel,
            packageName = packageName,
            notificationKey = notificationKey,
            replyText = replyText,
            status = parsedStatus,
            createdAtMs = createdAtMs,
            updatedAtMs = updatedAtMs,
            eventKey = eventKey
        )
    }

    companion object {
        private val ALLOWED_FAILURE_CATEGORIES = setOf(
            "SERVICE_SHUTDOWN",
            "INVALID_REPLY_TEXT",
            "MISSING_NOTIFICATION_WEAR",
            "MISSING_REMOTE_INPUT",
            "MISSING_PENDING_INTENT",
            "CONCURRENT_IN_FLIGHT",
            "PENDING_INTENT_CANCELED",
            "SECURITY_EXCEPTION",
            "AMBIGUOUS_SECURITY_EXCEPTION",
            "AMBIGUOUS_SEND_EXCEPTION",
            "DUPLICATE_EVENT",
            "UNKNOWN_OUTCOME_LOCKED",
            "GENERATED_INTERRUPTED",
            "DISPATCHING_INTERRUPTED",
            "CLAIM_PERSISTENCE_ERROR"
        )

        @JvmStatic
        fun sanitizeFailureCategory(category: String?): String? {
            if (category.isNullOrBlank()) return null
            val trimmed = category.trim()
            return if (trimmed in ALLOWED_FAILURE_CATEGORIES) trimmed else "UNCLASSIFIED_FAILURE"
        }

        @JvmStatic
        fun fromTransition(
            attempt: ReplyAttempt,
            senderName: String?,
            incomingMessage: String?
        ): ReplyAttemptEntity = ReplyAttemptEntity(
            attemptId = attempt.attemptId,
            packageName = attempt.packageName,
            senderName = senderName,
            incomingMessage = incomingMessage,
            notificationKey = attempt.notificationKey,
            replyText = attempt.replyText,
            sourceType = attempt.sourceType.name,
            sourceLabel = attempt.sourceLabel,
            status = attempt.status.name,
            dispatchAttempted = false,
            dispatchedNormally = false,
            failureCategory = null,
            createdAtMs = attempt.createdAtMs,
            updatedAtMs = attempt.updatedAtMs,
            eventKey = attempt.eventKey
        )

        @JvmStatic
        fun fromResult(
            attempt: ReplyAttempt,
            result: ReplyExecutionResult,
            senderName: String?,
            incomingMessage: String?
        ): ReplyAttemptEntity = ReplyAttemptEntity(
            attemptId = attempt.attemptId,
            packageName = attempt.packageName,
            senderName = senderName,
            incomingMessage = incomingMessage,
            notificationKey = attempt.notificationKey,
            replyText = attempt.replyText,
            sourceType = attempt.sourceType.name,
            sourceLabel = attempt.sourceLabel,
            status = result.status.name,
            dispatchAttempted = result.dispatchAttempted,
            dispatchedNormally = result.dispatchedNormally,
            failureCategory = sanitizeFailureCategory(result.failureCategory),
            createdAtMs = attempt.createdAtMs,
            updatedAtMs = result.timestampMs,
            eventKey = attempt.eventKey
        )
    }
}
