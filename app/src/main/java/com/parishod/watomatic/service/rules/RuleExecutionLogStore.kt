package com.parishod.watomatic.service.rules

import com.parishod.watomatic.model.rules.RuleExecutionLog
import com.parishod.watomatic.model.rules.RuleExecutionLogDao

interface RuleExecutionLogStore {
    fun getLastReplyTimeForRuleAndSender(ruleId: Long, packageName: String, senderName: String): Long?
    fun getReplyCountForRuleAndSenderSince(
        ruleId: Long,
        packageName: String,
        senderName: String,
        sinceTime: Long
    ): Int
    fun hasRepliedToNotification(notificationKey: String, messageTimestamp: Long): Boolean
    fun recordExecution(
        ruleId: Long,
        packageName: String,
        senderName: String,
        notificationKey: String,
        messageTimestamp: Long,
        repliedAt: Long
    )
}

class RoomRuleExecutionLogStore(
    private val dao: RuleExecutionLogDao
) : RuleExecutionLogStore {
    override fun getLastReplyTimeForRuleAndSender(
        ruleId: Long,
        packageName: String,
        senderName: String
    ): Long? = dao.getLastReplyTimeForRuleAndSender(ruleId, packageName, senderName)

    override fun getReplyCountForRuleAndSenderSince(
        ruleId: Long,
        packageName: String,
        senderName: String,
        sinceTime: Long
    ): Int = dao.getReplyCountForRuleAndSenderSince(ruleId, packageName, senderName, sinceTime)

    override fun hasRepliedToNotification(notificationKey: String, messageTimestamp: Long): Boolean {
        if (notificationKey.isBlank() || messageTimestamp <= 0L) return false
        return dao.hasRepliedToNotification(notificationKey, messageTimestamp) > 0
    }

    override fun recordExecution(
        ruleId: Long,
        packageName: String,
        senderName: String,
        notificationKey: String,
        messageTimestamp: Long,
        repliedAt: Long
    ) {
        dao.insertLogSync(
            RuleExecutionLog(
                ruleId = ruleId,
                packageName = packageName,
                senderName = senderName,
                notificationKey = notificationKey,
                messageTimestamp = messageTimestamp,
                repliedAt = repliedAt
            )
        )
    }
}
