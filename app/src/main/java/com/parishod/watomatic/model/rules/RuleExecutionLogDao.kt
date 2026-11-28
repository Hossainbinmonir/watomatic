package com.parishod.watomatic.model.rules

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface RuleExecutionLogDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertLogSync(log: RuleExecutionLog): Long

    @Query(
        "SELECT MAX(replied_at) FROM rule_execution_logs " +
            "WHERE rule_id = :ruleId AND package_name = :packageName AND LOWER(sender_name) = LOWER(:senderName)"
    )
    fun getLastReplyTimeForRuleAndSender(
        ruleId: Long,
        packageName: String,
        senderName: String
    ): Long?

    @Query(
        "SELECT COUNT(*) FROM rule_execution_logs " +
            "WHERE rule_id = :ruleId AND package_name = :packageName " +
            "AND LOWER(sender_name) = LOWER(:senderName) AND replied_at >= :sinceTime"
    )
    fun getReplyCountForRuleAndSenderSince(
        ruleId: Long,
        packageName: String,
        senderName: String,
        sinceTime: Long
    ): Int

    @Query(
        "SELECT COUNT(*) FROM rule_execution_logs " +
            "WHERE notification_key = :notificationKey AND message_timestamp = :messageTimestamp"
    )
    fun hasRepliedToNotification(notificationKey: String, messageTimestamp: Long): Int

    @Query("DELETE FROM rule_execution_logs WHERE replied_at < :cutoffTime")
    fun purgeOldLogs(cutoffTime: Long)

    @Query("DELETE FROM rule_execution_logs WHERE replied_at <= :cutoffTime")
    fun purgeOldLogsSync(cutoffTime: Long): Int
}
