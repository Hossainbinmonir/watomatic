package com.parishod.watomatic.model.logs

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.parishod.watomatic.service.reply.ReplyExecutionStatus

@Entity(
    tableName = "notification_dispatch_claims",
    indices = [
        Index(value = ["attempt_id"]),
        Index(value = ["claim_status"]),
        Index(value = ["updated_at_ms"]),
        Index(value = ["expires_at_ms"])
    ]
)
data class NotificationDispatchClaimEntity(
    @PrimaryKey
    @ColumnInfo(name = "event_key")
    val eventKey: String,
    @ColumnInfo(name = "attempt_id")
    val attemptId: String,
    @ColumnInfo(name = "package_name")
    val packageName: String,
    @ColumnInfo(name = "sender_key")
    val senderKey: String,
    @ColumnInfo(name = "notification_key")
    val notificationKey: String?,
    @ColumnInfo(name = "claim_status")
    val claimStatus: String,
    @ColumnInfo(name = "failure_category")
    val failureCategory: String?,
    @ColumnInfo(name = "dispatch_attempted")
    val dispatchAttempted: Boolean,
    @ColumnInfo(name = "dispatched_normally")
    val dispatchedNormally: Boolean,
    @ColumnInfo(name = "created_at_ms")
    val createdAtMs: Long,
    @ColumnInfo(name = "updated_at_ms")
    val updatedAtMs: Long,
    @ColumnInfo(name = "expires_at_ms")
    val expiresAtMs: Long
) {
    companion object {
        const val STATUS_CLAIMED = "CLAIMED"
        const val DEFAULT_RESOLVED_TTL_MS = 7L * 24L * 60L * 60L * 1000L
        const val DEFAULT_UNRESOLVED_TTL_MS = 30L * 24L * 60L * 60L * 1000L
        const val MAX_RESOLVED_CLAIM_ROWS = 2000

        @JvmStatic
        fun isTerminalResolvedForPurge(status: String): Boolean =
            status == ReplyExecutionStatus.DISPATCHED.name ||
                status == ReplyExecutionStatus.FAILED.name ||
                status == ReplyExecutionStatus.CANCELLED.name

        @JvmStatic
        fun isActiveOrUncertain(status: String): Boolean =
            status == STATUS_CLAIMED ||
                status == ReplyExecutionStatus.GENERATED.name ||
                status == ReplyExecutionStatus.DISPATCHING.name ||
                status == ReplyExecutionStatus.UNKNOWN.name
    }
}
