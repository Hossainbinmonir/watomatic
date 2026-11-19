package com.parishod.watomatic.model.logs

object ReplyHistoryStatus {
    const val GENERATED = "GENERATED"
    const val DISPATCHING = "DISPATCHING"
    const val DISPATCHED = "DISPATCHED"
    const val FAILED = "FAILED"
    const val CANCELLED = "CANCELLED"
    const val UNKNOWN = "UNKNOWN"
    const val LEGACY = "LEGACY"
    const val MIXED = "MIXED"
}

data class ReplyHistorySummaryItem(
    val incomingMessage: String,
    val repliedMessage: String,
    val packageName: String,
    val matchedRuleName: String?,
    val lastSender: String?,
    val replyCount: Int,
    val lastReplyTime: Long,
    val executionStatus: String = ReplyHistoryStatus.LEGACY
)

data class ReplyHistoryDetailItem(
    val id: Int,
    val attemptId: String? = null,
    val packageName: String,
    val senderName: String?,
    val incomingMessage: String?,
    val repliedMessage: String,
    val matchedRuleName: String?,
    val replyTime: Long,
    val createdAtMs: Long = replyTime,
    val executionStatus: String = ReplyHistoryStatus.LEGACY,
    val sourceType: String? = null,
    val failureCategory: String? = null
)
