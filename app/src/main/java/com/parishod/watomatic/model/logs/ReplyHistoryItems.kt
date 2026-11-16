package com.parishod.watomatic.model.logs

data class ReplyHistorySummaryItem(
    val incomingMessage: String,
    val repliedMessage: String,
    val packageName: String,
    val matchedRuleName: String?,
    val lastSender: String?,
    val replyCount: Int,
    val lastReplyTime: Long
)

data class ReplyHistoryDetailItem(
    val id: Int,
    val packageName: String,
    val senderName: String?,
    val incomingMessage: String?,
    val repliedMessage: String,
    val matchedRuleName: String?,
    val replyTime: Long
)
